package com.namanbansal.forge.core.image

import com.namanbansal.forge.core.ToolException
import java.util.Base64

/** Image <-> `data:` URI. */
object Base64Image {
    data class Decoded(val bytes: ByteArray, val mime: String)

    fun encode(bytes: ByteArray, mime: String): String = "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)

    /** Accepts a full data URI, or bare Base64 (with line breaks, spaces, URL-safe alphabet). */
    fun decode(text: String): Decoded {
        val t = text.trim()
        if (t.isEmpty()) throw ToolException("Paste some Base64 text first.")
        var mimeFromUri: String? = null
        var payload = t
        if (t.startsWith("data:", ignoreCase = true)) {
            val comma = t.indexOf(',')
            if (comma < 0) throw ToolException("That data URI has no data after the comma.")
            val header = t.substring(5, comma)
            if (!header.contains("base64", ignoreCase = true)) throw ToolException("Only Base64 data URIs are supported.")
            mimeFromUri = header.substringBefore(';').takeIf { it.isNotBlank() }
            payload = t.substring(comma + 1)
        }
        val clean = payload.filterNot { it.isWhitespace() }.replace('-', '+').replace('_', '/').trimEnd('=')
        val bytes = try {
            Base64.getDecoder().decode(clean)
        } catch (_: IllegalArgumentException) {
            throw ToolException("That isn't valid Base64. Check for missing or extra characters.")
        }
        if (bytes.isEmpty()) throw ToolException("Nothing to decode.")
        val sniffed = sniffMime(bytes) ?: throw ToolException("The data decoded fine, but it isn't a picture Forge recognises.")
        return Decoded(bytes, sniffed.takeIf { mimeFromUri == null || !mimeFromUri.startsWith("image/") } ?: mimeFromUri.takeIf { it == sniffed } ?: sniffed)
    }

    fun sniffMime(b: ByteArray): String? {
        fun at(i: Int, vararg v: Int) = b.size > i + v.size - 1 && v.indices.all { (b[i + it].toInt() and 0xFF) == v[it] }
        return when {
            at(0, 0xFF, 0xD8, 0xFF) -> "image/jpeg"
            at(0, 0x89, 0x50, 0x4E, 0x47) -> "image/png"
            at(0, 0x47, 0x49, 0x46, 0x38) -> "image/gif"
            at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50) -> "image/webp"
            at(0, 0x42, 0x4D) -> "image/bmp"
            at(0, 0, 0, 1, 0) -> "image/x-icon"
            at(4, 0x66, 0x74, 0x79, 0x70) -> if (String(b, 8, minOf(4, b.size - 8)).startsWith("avif")) "image/avif" else "image/heic"
            String(b, 0, minOf(b.size, 256), Charsets.UTF_8).contains("<svg", ignoreCase = true) -> "image/svg+xml"
            else -> null
        }
    }

    fun extFor(mime: String): String = when (mime) {
        "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"; "image/bmp" -> "bmp"
        "image/x-icon" -> "ico"; "image/avif" -> "avif"; "image/svg+xml" -> "svg"; else -> "heic"
    }
}
