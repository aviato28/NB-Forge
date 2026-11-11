package com.namanbansal.forge.core.device

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Saves bitmaps as PNG/JPEG into `Pictures/Forge/` through MediaStore (no storage permission on API 30+). Always a new file. */
object PictureSaver {
    const val DIR = "Pictures/Forge"

    suspend fun save(context: Context, bitmap: Bitmap, baseName: String, png: Boolean = true): Uri = withContext(Dispatchers.IO) {
        val resolver = context.applicationContext.contentResolver
        val ext = if (png) "png" else "jpg"
        val name = uniqueName(baseName, ext) { n ->
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf(n, "$DIR%"), null,
            )?.use { it.count > 0 } ?: false
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (png) "image/png" else "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$DIR/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Could not create a file in Pictures/Forge")
        try {
            resolver.openOutputStream(uri, "w")?.use { out ->
                if (!bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, out)) throw IOException("Could not encode the picture")
            } ?: throw IOException("Could not open the output file")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    /** `base.ext`, then `base (2).ext`, ... until [exists] is false. */
    fun uniqueName(base: String, ext: String, exists: (String) -> Boolean): String {
        var n = "$base.$ext"
        var i = 2
        while (exists(n) && i < 1000) { n = "$base ($i).$ext"; i++ }
        return n
    }
}
