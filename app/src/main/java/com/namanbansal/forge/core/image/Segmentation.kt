package com.namanbansal.forge.core.image

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.namanbansal.forge.core.ToolException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.FloatBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Finds the foreground of a picture: one alpha byte (0..255) per pixel, same size as the input. */
interface SubjectSegmenter {
    /** Short name shown to the user ("Subject model", "People model"). */
    val engine: String
    suspend fun segment(bmp: Bitmap): ByteArray
}

/** ML Kit Subject Segmentation (beta, delivered by Google Play services) first, ML Kit Selfie Segmentation (bundled) as the fallback. */
class MlKitSegmenter : SubjectSegmenter {
    override var engine: String = "Subject model"
        private set

    override suspend fun segment(bmp: Bitmap): ByteArray {
        val subject = runCatching { subjectMask(bmp) }
        if (subject.isSuccess) { engine = "Subject model"; return subject.getOrThrow() }
        if (subject.exceptionOrNull() is kotlinx.coroutines.CancellationException) throw subject.exceptionOrNull()!!
        val selfie = runCatching { selfieMask(bmp) }
        if (selfie.isSuccess) { engine = "People model (offline fallback)"; return selfie.getOrThrow() }
        throw ToolException("Couldn't find a subject in this picture. Try another picture.")
    }

    private suspend fun subjectMask(bmp: Bitmap): ByteArray {
        val seg = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build())
        try {
            val buf: FloatBuffer = suspendCancellableCoroutine { cont ->
                seg.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { cont.resume(it.foregroundConfidenceMask ?: FloatBuffer.allocate(0)) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            if (buf.capacity() < bmp.width * bmp.height) throw ToolException("No subject mask")
            return MaskOps.fromConfidence(buf, bmp.width, bmp.height)
        } finally { seg.close() }
    }

    private suspend fun selfieMask(bmp: Bitmap): ByteArray {
        val seg = Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build())
        try {
            val m = suspendCancellableCoroutine { cont ->
                seg.process(InputImage.fromBitmap(bmp, 0)).addOnSuccessListener { cont.resume(it) }.addOnFailureListener { cont.resumeWithException(it) }
            }
            val fb = m.buffer.asFloatBuffer()
            val raw = FloatArray(m.width * m.height).also { fb.get(it) }
            return MaskOps.fromConfidence(FloatBuffer.wrap(raw), m.width, m.height).let { if (m.width == bmp.width && m.height == bmp.height) it else MaskOps.resize(it, m.width, m.height, bmp.width, bmp.height) }
        } finally { seg.close() }
    }
}

/** Swap point so tests can run the screen without ML Kit. */
object SegmenterProvider {
    @Volatile var factory: () -> SubjectSegmenter = { MlKitSegmenter() }
}

enum class BgKind { TRANSPARENT, COLOUR, BLUR }

/** Pure mask maths for the background remover (Alpha-8 style byte masks). */
object MaskOps {
    /** Confidence 0..1 -> 0..255 with a smoothstep around 0.5 so edges are cleaner without being jagged. */
    fun fromConfidence(buf: FloatBuffer, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h)
        for (i in out.indices) {
            val c = buf.get(i).coerceIn(0f, 1f)
            val s = ((c - 0.25f) / 0.5f).coerceIn(0f, 1f)
            out[i] = (s * s * (3 - 2 * s) * 255f + 0.5f).toInt().toByte()
        }
        return out
    }

    fun resize(mask: ByteArray, w: Int, h: Int, nw: Int, nh: Int): ByteArray {
        val src = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        src.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(mask))
        val scaled = Bitmap.createScaledBitmap(src, nw, nh, true)
        val out = ByteArray(nw * nh)
        scaled.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(out))
        if (scaled !== src) scaled.recycle()
        src.recycle()
        return out
    }

    /** Paints a soft round brush into [mask]: restore raises alpha, erase lowers it. Hard core 60 % of the radius, soft edge beyond. */
    fun brush(mask: ByteArray, w: Int, h: Int, cx: Float, cy: Float, radius: Float, restore: Boolean) {
        val x0 = (cx - radius).toInt().coerceAtLeast(0); val x1 = (cx + radius).toInt().coerceAtMost(w - 1)
        val y0 = (cy - radius).toInt().coerceAtLeast(0); val y1 = (cy + radius).toInt().coerceAtMost(h - 1)
        for (y in y0..y1) for (x in x0..x1) {
            val d = Math.hypot((x - cx).toDouble(), (y - cy).toDouble()).toFloat() / radius
            if (d > 1f) continue
            val strength = if (d <= 0.6f) 1f else (1f - d) / 0.4f
            val cur = mask[y * w + x].toInt() and 0xFF
            val v = if (restore) maxOf(cur.toFloat(), 255f * strength) else cur * (1f - strength)
            mask[y * w + x] = v.toInt().coerceIn(0, 255).toByte()
        }
    }

    /**
     * Result picture: [orig] with [mask] as alpha (TRANSPARENT), or the subject laid over a solid [colour] / a blurred copy of the original.
     * [blurred] is only needed for BLUR.
     */
    fun compose(orig: Bitmap, mask: ByteArray, kind: BgKind, colour: Int, blurred: Bitmap?): Bitmap {
        val w = orig.width; val h = orig.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val row = IntArray(w); val bg = IntArray(w)
        for (y in 0 until h) {
            orig.getPixels(row, 0, w, 0, y, w, 1)
            if (kind == BgKind.BLUR && blurred != null) blurred.getPixels(bg, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val a = mask[y * w + x].toInt() and 0xFF
                val p = row[x]
                row[x] = when (kind) {
                    BgKind.TRANSPARENT -> (a shl 24) or (p and 0xFFFFFF)
                    else -> {
                        val b = if (kind == BgKind.COLOUR) colour else bg[x]
                        val ia = 255 - a
                        val r = (Color.red(p) * a + Color.red(b) * ia) / 255
                        val g = (Color.green(p) * a + Color.green(b) * ia) / 255
                        val bl = (Color.blue(p) * a + Color.blue(b) * ia) / 255
                        (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
                    }
                }
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        return out
    }
}
