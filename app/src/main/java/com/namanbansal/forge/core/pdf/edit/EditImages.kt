package com.namanbansal.forge.core.pdf.edit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID

/** A picture the user added, stored in the app's private files: [id] is what [EditObject.imageId] refers to. */
class StoredImage(val id: String, val aspect: Float, val hasAlpha: Boolean)

/**
 * Keeps the pictures placed in Edit mode on disk (so they survive process death with the pending changes). Photos are
 * downsampled to at most [MAX_SIDE] pixels and stored as JPEG; pictures with transparency stay PNG so the alpha is kept.
 */
class EditImageStore(private val dir: File) {
    private fun fileOf(id: String): File? = dir.listFiles()?.firstOrNull { it.nameWithoutExtension == id }

    fun bytes(id: String): ByteArray? = runCatching { fileOf(id)?.readBytes() }.getOrNull()

    /** Reads, orients, downsamples and stores a picture; null when it can't be decoded. [open] is called twice (bounds, pixels). */
    fun import(open: () -> InputStream?): StoredImage? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
            val decoded = open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            val turn = open()?.use { orientationDegrees(it) } ?: 0
            if (decoded == null) null else store(if (turn == 0) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(turn.toFloat()) }, true))
        }
    } catch (_: Throwable) { null }

    fun store(bmp: Bitmap): StoredImage {
        dir.mkdirs()
        val id = "img" + UUID.randomUUID().toString().replace("-", "").take(12)
        val alpha = bmp.hasAlpha() && hasTransparentPixel(bmp)
        val out = ByteArrayOutputStream()
        if (alpha) bmp.compress(Bitmap.CompressFormat.PNG, 100, out) else bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
        File(dir, id + if (alpha) ".png" else ".jpg").writeBytes(out.toByteArray())
        return StoredImage(id, bmp.width.toFloat() / bmp.height.coerceAtLeast(1), alpha)
    }

    /** Deletes every stored picture not in [keep] (after a save or a discard). */
    fun retainOnly(keep: Set<String>) { dir.listFiles()?.forEach { if (it.nameWithoutExtension !in keep) runCatching { it.delete() } } }

    private fun hasTransparentPixel(b: Bitmap): Boolean {
        val w = b.width; val h = b.height
        val stepX = maxOf(1, w / 64); val stepY = maxOf(1, h / 64)
        var y = 0
        while (y < h) { var x = 0; while (x < w) { if ((b.getPixel(x, y) ushr 24) < 250) return true; x += stepX }; y += stepY }
        return false
    }

    private fun orientationDegrees(input: InputStream): Int = try {
        when (android.media.ExifInterface(input).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (_: Throwable) { 0 }

    companion object { const val MAX_SIDE = 2400 }
}
