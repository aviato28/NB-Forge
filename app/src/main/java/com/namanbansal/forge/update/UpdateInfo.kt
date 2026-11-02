package com.namanbansal.forge.update

/** Update channel. [param] is the value used in `?channel=` on the Forge site endpoint. */
enum class Channel(val param: String) {
    Stable("stable"),
    Beta("beta");

    companion object {
        fun fromParam(value: String?): Channel = entries.firstOrNull { it.param.equals(value, ignoreCase = true) } ?: Stable
    }
}

/**
 * One installable release. Mirrors the Penumbra/Forge site contract
 * `GET /update/latest.json?channel=stable|beta` -> `{version, versionCode, channel, notes, publishedAt, apk, size, sha256}`.
 */
data class UpdateInfo(
    val version: String,
    val versionCode: Int,
    val channel: Channel,
    val notes: String,
    val publishedAt: String,
    val apkUrl: String,
    val size: Long,
    val sha256: String?,
)

/** Where update metadata comes from. Implementations must never embed credentials. */
interface UpdateSource {
    /** The newest release on [channel], or null if none exists. Throws [UpdateException] on failure. */
    suspend fun latest(channel: Channel): UpdateInfo?
}

class UpdateException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { Offline, RateLimited, Http, Parse, Download, Checksum, Install }
}

object Versions {
    private val TAG = Regex("""^v?\d+(?:\.\d+)*$""")

    /** `v0.1.123` -> 123 (the last dot segment). Returns null for anything that isn't a plain numeric tag. */
    fun versionCodeFromTag(tag: String): Int? {
        val t = tag.trim()
        if (!TAG.matches(t)) return null
        return t.substringAfterLast('.').trimStart('v').toIntOrNull()
    }

    /** `v0.1.123` -> `0.1.123`. */
    fun versionNameFromTag(tag: String): String = tag.trim().removePrefix("v")

    fun isNewer(candidate: UpdateInfo?, currentVersionCode: Int): Boolean =
        candidate != null && candidate.versionCode > currentVersionCode
}
