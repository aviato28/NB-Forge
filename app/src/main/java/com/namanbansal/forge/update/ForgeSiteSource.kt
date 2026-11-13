package com.namanbansal.forge.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient

/**
 * Reads `https://forge.nbansal28.com/update/latest.json?channel=stable|beta` (same contract as Penumbra).
 * Primary source (see [UpdateConfig]); GitHub Releases is the fallback.
 */
class ForgeSiteSource(
    private val client: OkHttpClient,
    private val endpoint: String,
) : UpdateSource {

    override suspend fun latest(channel: Channel): UpdateInfo? =
        client.getText("$endpoint?channel=${channel.param}")?.let { parse(it) }

    @Serializable
    internal data class SiteJson(
        val version: String = "",
        val versionCode: Int = 0,
        val channel: String = "stable",
        val notes: String = "",
        val publishedAt: String = "",
        val apk: String = "",
        val size: Long = 0,
        val sha256: String? = null,
    )

    companion object {
        fun parse(json: String): UpdateInfo? {
            val s = try {
                UpdateJson.decodeFromString<SiteJson>(json)
            } catch (e: SerializationException) {
                throw UpdateException(UpdateException.Kind.Parse, "Unexpected response from update server.", e)
            } catch (e: IllegalArgumentException) {
                throw UpdateException(UpdateException.Kind.Parse, "Unexpected response from update server.", e)
            }
            if (s.versionCode <= 0 || s.apk.isBlank()) return null
            return UpdateInfo(
                version = s.version,
                versionCode = s.versionCode,
                channel = Channel.fromParam(s.channel),
                notes = s.notes,
                publishedAt = s.publishedAt,
                apkUrl = s.apk,
                size = s.size,
                sha256 = s.sha256?.lowercase()?.removePrefix("sha256:")?.takeIf { it.isNotBlank() },
            )
        }
    }
}
