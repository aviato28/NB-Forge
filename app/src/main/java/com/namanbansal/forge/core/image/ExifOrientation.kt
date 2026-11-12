package com.namanbansal.forge.core.image

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface

/** EXIF orientation tag (1..8) maths: how to turn the stored pixels into the upright picture. */
object ExifOrientation {
    fun swapsAxes(o: Int): Boolean = o in 5..8

    /** Size of the upright image for a stored [w] x [h] with orientation [o]. */
    fun uprightSize(w: Int, h: Int, o: Int): Pair<Int, Int> = if (swapsAxes(o)) h to w else w to h

    /** Matrix turning stored pixels into upright pixels (identity for 1 / unknown). */
    fun matrix(o: Int): Matrix = Matrix().apply {
        when (o) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
        }
    }

    fun isIdentity(o: Int): Boolean = o !in 2..8

    fun apply(src: Bitmap, o: Int): Bitmap {
        if (isIdentity(o)) return src
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix(o), true)
        if (out !== src) src.recycle()
        return out
    }
}
