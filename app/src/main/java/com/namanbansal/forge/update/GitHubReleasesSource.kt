package com.namanbansal.forge.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient

/**
 * Reads releases of a public GitHub repo through the unauthenticated REST API (no token, ever).
 * Stable = `/releases/latest` (newest non-prerelease). Beta = newest of `/releases?per_page=10`, prereleases included.
 */
class GitHubReleasesSource(
    private val client: OkHttpClient,
    private val ownerRepo: String,
    private val apiBase: String = "https://api.github.com",
) : UpdateSource {

    override suspend fun latest(channel: Channel): UpdateInfo? {
        val headers = mapOf("Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28")
        return when (channel) {
            Channel.Stable -> client.getText("$apiBase/repos/$ownerRepo/releases/latest", headers)?.let { parseRelease(it) }
            Channel.Beta -> client.getText("$apiBase/repos/$ownerRepo/releases?per_page=10", headers)?.let { parseReleaseList(it) }
        }
    }

    @Serializable
    internal data class GhAsset(
        val name: String = "",
        @SerialName("browser_download_url") val url: String = "",
        val size: Long = 0,
        val digest: String? = null,
    )

    @Serializable
    internal data class GhRelease(
        @SerialName("tag_name") val tag: String = "",
        val name: String? = null,
        val body: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        @SerialName("published_at") val publishedAt: String? = null,
        val assets: List<GhAsset> = emptyList(),
    )

    companion object {
        /** Maps one GitHub release object to [UpdateInfo]; null if it has no usable tag or APK asset. */
        fun parseRelease(json: String): UpdateInfo? = decode<GhRelease>(json).toInfo()

        /** Picks the highest-versioned non-draft release from a `/releases` list (prereleases included). */
        fun parseReleaseList(json: String): UpdateInfo? =
            decode<List<GhRelease>>(json).mapNotNull { it.toInfo() }.maxByOrNull { it.versionCode }

        private inline fun <reified T> decode(json: String): T = try {
            UpdateJson.decodeFromString<T>(json)
        } catch (e: SerializationException) {
            throw UpdateException(UpdateException.Kind.Parse, "Unexpected response from GitHub.", e)
        } catch (e: IllegalArgumentException) {
            throw UpdateException(UpdateException.Kind.Parse, "Unexpected response from GitHub.", e)
        }

        private fun GhRelease.toInfo(): UpdateInfo? {
            if (draft) return null
            val code = Versions.versionCodeFromTag(tag) ?: return null
            val apk = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) } ?: return null
            val sha = apk.digest?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
                ?.substringAfter(':')?.lowercase()?.takeIf { it.length == 64 }
            return UpdateInfo(
                version = Versions.versionNameFromTag(tag),
                versionCode = code,
                channel = if (prerelease) Channel.Beta else Channel.Stable,
                notes = body.orEmpty().trim(),
                publishedAt = publishedAt.orEmpty(),
                apkUrl = apk.url,
                size = apk.size,
                sha256 = sha,
            )
        }
    }
}
