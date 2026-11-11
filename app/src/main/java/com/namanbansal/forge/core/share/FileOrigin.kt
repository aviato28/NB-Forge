package com.namanbansal.forge.core.share

import android.net.Uri

/**
 * Where an open file lives. [temporary] = it was handed over by another app (Amazon, a bank, email...) and is not stored
 * on the phone in a place the user can find again. [from] names the sender in words ("Gmail"), [where] says where it is.
 */
data class FileOrigin(val temporary: Boolean, val from: String, val where: String)

/** Decides whether a uri is a real file on the phone or a temporary hand-over. Pure: no Android services needed. */
object FileOrigins {
    /** Providers whose files are ordinary, findable files on the phone (Downloads, Files app, MediaStore). */
    private val PERMANENT_AUTHORITIES = setOf(
        "media",
        "com.android.providers.media.documents",
        "com.android.externalstorage.documents",
        "com.android.providers.downloads.documents",
        "downloads",
    )

    private val FRIENDLY = listOf(
        "com.google.android.gm" to "Gmail",
        "com.whatsapp" to "WhatsApp",
        "org.telegram" to "Telegram",
        "com.amazon" to "Amazon",
        "com.google.android.apps.docs" to "Google Drive",
        "com.google.android.apps.photos" to "Google Photos",
        "com.microsoft.office.outlook" to "Outlook",
        "com.android.chrome" to "Chrome",
        "org.mozilla" to "Firefox",
        "com.samsung.android" to "Samsung",
    )

    fun isPermanentAuthority(authority: String?): Boolean = authority != null && authority.lowercase() in PERMANENT_AUTHORITIES

    /**
     * [hasPersistedPermission]: the user picked this document through the system picker and Forge holds a lasting grant for it,
     * which is how a document the user chose to keep somewhere (their own folder or cloud drive) differs from a one-off hand-over.
     * [viaView]: it came in through "Open with" / share from another app.
     */
    fun classify(uri: Uri, hasPersistedPermission: Boolean = false, viaView: Boolean = false): FileOrigin {
        val authority = uri.authority.orEmpty()
        return when (uri.scheme) {
            "file" -> {
                val path = uri.path.orEmpty()
                val onPhone = path.startsWith("/storage/") || path.startsWith("/sdcard/") || path.startsWith("/mnt/")
                if (onPhone) FileOrigin(false, "This phone", "On this phone") else FileOrigin(true, "Forge", "In Forge's private storage")
            }
            "content" -> when {
                isPermanentAuthority(authority) -> FileOrigin(false, "This phone", "On this phone")
                hasPersistedPermission && authority.endsWith(".documents") && !viaView -> FileOrigin(false, friendly(authority), "In ${friendly(authority)}")
                else -> FileOrigin(true, friendly(authority), "Temporary file from ${friendly(authority)}")
            }
            else -> FileOrigin(true, "another app", "Temporary file")
        }
    }

    /** "com.google.android.gm.fileprovider" -> "Gmail"; unknown senders keep their package-ish name. */
    fun friendly(authority: String): String {
        val a = authority.lowercase()
        FRIENDLY.firstOrNull { a.startsWith(it.first) }?.let { return it.second }
        val pkg = a.removeSuffix(".fileprovider").removeSuffix(".provider").removeSuffix(".files").removeSuffix(".documents")
        return pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }.ifBlank { "another app" }
    }
}
