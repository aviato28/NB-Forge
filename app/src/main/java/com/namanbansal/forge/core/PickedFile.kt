package com.namanbansal.forge.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A file the user gave us (picker, share sheet or "Open with"). [size] is -1 when unknown. */
data class PickedFile(val uri: Uri, val name: String, val size: Long, val mime: String?) {
    val kind: FileKind get() = FileKind.fromMime(mime).let { if (it == FileKind.OTHER) FileKind.fromName(name) else it }
}

object FileResolver {
    /** Reads display name, size and type through ContentResolver. Never throws: falls back to the uri's last segment. */
    suspend fun resolve(context: Context, uri: Uri): PickedFile = withContext(Dispatchers.IO) { resolveBlocking(context, uri) }

    fun resolveBlocking(context: Context, uri: Uri): PickedFile {
        val cr = context.contentResolver
        var name: String? = null
        var size = -1L
        try {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (_: Exception) {
            // Provider refused the query; fall through to defaults.
        }
        val mime = try { cr.getType(uri) } catch (_: Exception) { null }
        val display = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"
        return PickedFile(uri, display, size, mime)
    }
}
