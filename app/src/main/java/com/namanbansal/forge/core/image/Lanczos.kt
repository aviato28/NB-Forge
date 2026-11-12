package com.namanbansal.forge.core.image

import android.graphics.Bitmap
import com.namanbansal.forge.core.ToolException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Lanczos-3 resampling plus an optional unsharp mask. This is classic signal processing, not machine learning. */
object Lanczos {
    const val MAX_OUTPUT_PIXELS = 48_000_000L

    private fun kernel(x: Double, a: Int = 3): Double {
        val ax = abs(x)
        if (ax < 1e-9) return 1.0
        if (ax >= a) return 0.0
        val px = PI * x
        return a * sin(px) * sin(px / a) / (px * px)
    }

    fun outputSize(w: Int, h: Int, factor: Int): Pair<Int, Int> = w * factor to h * factor

    private class Weights(val start: IntArray, val w: Array<FloatArray>)

    private fun weights(srcLen: Int, dstLen: Int): Weights {
        val scale = srcLen.toDouble() / dstLen
        val support = 3 * max(1.0, scale)
        val starts = IntArray(dstLen)
        val ws = Array(dstLen) { FloatArray(0) }
        for (i in 0 until dstLen) {
            val centre = (i + 0.5) * scale - 0.5
            val lo = max(0, ceil(centre - support).toInt())
            val hi = min(srcLen - 1, floor(centre + support).toInt())
            val arr = FloatArray(max(1, hi - lo + 1))
            var sum = 0.0
            for (k in lo..hi) { val v = kernel((k - centre) / max(1.0, scale)); arr[k - lo] = v.toFloat(); sum += v }
            if (sum != 0.0) for (k in arr.indices) arr[k] = (arr[k] / sum).toFloat() else arr[0] = 1f
            starts[i] = lo; ws[i] = arr
        }
        return Weights(starts, ws)
    }

    /** Resamples [src] to [dw] x [dh]. Channels are filtered independently in 8-bit, in two separable passes. */
    fun resize(src: Bitmap, dw: Int, dh: Int, checkCancelled: () -> Unit = {}, onProgress: (Float) -> Unit = {}): Bitmap {
        if (dw.toLong() * dh > MAX_OUTPUT_PIXELS) throw ToolException("That would be over ${MAX_OUTPUT_PIXELS / 1_000_000} megapixels. Choose 2x, or start from a smaller picture.")
        val sw = src.width
        val sh = src.height
        val wx = weights(sw, dw)
        val wy = weights(sh, dh)
        // Horizontal pass: sh rows of dw pixels, kept as packed ARGB.
        val mid = IntArray(dw * sh)
        val row = IntArray(sw)
        for (y in 0 until sh) {
            if (y % 64 == 0) { checkCancelled(); onProgress(0.5f * y / sh) }
            src.getPixels(row, 0, sw, 0, y, sw, 1)
            for (x in 0 until dw) {
                val st = wx.start[x]; val wt = wx.w[x]
                var a = 0f; var r = 0f; var g = 0f; var b = 0f
                for (k in wt.indices) {
                    val p = row[st + k]; val f = wt[k]
                    a += f * (p ushr 24); r += f * ((p shr 16) and 0xFF); g += f * ((p shr 8) and 0xFF); b += f * (p and 0xFF)
                }
                mid[y * dw + x] = pack(a, r, g, b)
            }
        }
        val out = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
        val line = IntArray(dw)
        for (y in 0 until dh) {
            if (y % 64 == 0) { checkCancelled(); onProgress(0.5f + 0.5f * y / dh) }
            val st = wy.start[y]; val wt = wy.w[y]
            for (x in 0 until dw) {
                var a = 0f; var r = 0f; var g = 0f; var b = 0f
                for (k in wt.indices) {
                    val p = mid[(st + k) * dw + x]; val f = wt[k]
                    a += f * (p ushr 24); r += f * ((p shr 16) and 0xFF); g += f * ((p shr 8) and 0xFF); b += f * (p and 0xFF)
                }
                line[x] = pack(a, r, g, b)
            }
            out.setPixels(line, 0, dw, 0, y, dw, 1)
        }
        return out
    }

    private fun c(v: Float) = min(255, max(0, v.roundToInt()))
    private fun pack(a: Float, r: Float, g: Float, b: Float) = (c(a) shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)

    /** Unsharp mask: out = src + amount * (src - blur(src)) where blur is a [radius] px box blur (x3 ~ gaussian). */
    fun unsharp(bmp: Bitmap, amount: Float, radius: Int, checkCancelled: () -> Unit = {}) {
        if (amount <= 0f) return
        val w = bmp.width
        val h = bmp.height
        val blurred = Blur.box(bmp, radius, passes = 3, checkCancelled = checkCancelled)
        val a = IntArray(w); val b = IntArray(w)
        for (y in 0 until h) {
            if (y % 64 == 0) checkCancelled()
            bmp.getPixels(a, 0, w, 0, y, w, 1)
            blurred.getPixels(b, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = a[x]; val q = b[x]
                val r = ((p shr 16) and 0xFF) + amount * (((p shr 16) and 0xFF) - ((q shr 16) and 0xFF))
                val g = ((p shr 8) and 0xFF) + amount * (((p shr 8) and 0xFF) - ((q shr 8) and 0xFF))
                val bl = (p and 0xFF) + amount * ((p and 0xFF) - (q and 0xFF))
                a[x] = (p and 0xFF000000.toInt()) or (c(r.toFloat()) shl 16) or (c(g.toFloat()) shl 8) or c(bl.toFloat())
            }
            bmp.setPixels(a, 0, w, 0, y, w, 1)
        }
        blurred.recycle()
    }
}

/** Box blur and pixelate on bitmaps, used by Annotate (privacy blur), unsharp and the "blur original" background. */
object Blur {
    /** Returns a blurred copy of the whole bitmap (separable box blur, [passes] times). */
    fun box(src: Bitmap, radius: Int, passes: Int = 3, checkCancelled: () -> Unit = {}): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        blurInPlace(px, w, h, radius, passes, checkCancelled)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    fun blurInPlace(px: IntArray, w: Int, h: Int, radius: Int, passes: Int = 3, checkCancelled: () -> Unit = {}) {
        if (radius < 1) return
        val tmp = IntArray(px.size)
        repeat(passes) {
            checkCancelled()
            pass(px, tmp, w, h, radius, horizontal = true)
            pass(tmp, px, w, h, radius, horizontal = false)
        }
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val stride = if (horizontal) 1 else w
        val lineStride = if (horizontal) w else 1
        for (l in 0 until lines) {
            val base = l * lineStride
            var sa = 0L; var sr = 0L; var sg = 0L; var sb = 0L
            // Sliding window with clamped edges.
            fun at(i: Int) = src[base + i.coerceIn(0, len - 1) * stride]
            for (k in -r..r) { val p = at(k); sa += p ushr 24; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF }
            val n = 2 * r + 1
            for (i in 0 until len) {
                dst[base + i * stride] = ((sa / n).toInt() shl 24) or ((sr / n).toInt() shl 16) or ((sg / n).toInt() shl 8) or (sb / n).toInt()
                val add = at(i + r + 1); val rem = at(i - r)
                sa += (add ushr 24) - (rem ushr 24); sr += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
                sg += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF); sb += (add and 0xFF) - (rem and 0xFF)
            }
        }
    }

    /** Replaces [rect] (pixels, clamped) with a heavily blurred version; the original pixels are overwritten in [bmp]. */
    fun blurRegion(bmp: Bitmap, l: Int, t: Int, r: Int, b: Int, strength: Int = 24) {
        val (x0, y0, x1, y1) = clamp(bmp, l, t, r, b) ?: return
        val w = x1 - x0; val h = y1 - y0
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, x0, y0, w, h)
        blurInPlace(px, w, h, max(2, min(strength, max(w, h) / 2)), passes = 4)
        bmp.setPixels(px, 0, w, x0, y0, w, h)
    }

    /** Replaces [rect] with [block] px mosaic cells (each cell becomes its average colour). */
    fun pixelateRegion(bmp: Bitmap, l: Int, t: Int, r: Int, b: Int, block: Int = 16) {
        val (x0, y0, x1, y1) = clamp(bmp, l, t, r, b) ?: return
        val bs = max(2, block)
        val buf = IntArray(bs * bs)
        var y = y0
        while (y < y1) {
            val ch = min(bs, y1 - y)
            var x = x0
            while (x < x1) {
                val cw = min(bs, x1 - x)
                bmp.getPixels(buf, 0, cw, x, y, cw, ch)
                var sr = 0L; var sg = 0L; var sb = 0L
                val n = cw * ch
                for (i in 0 until n) { val p = buf[i]; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF }
                val avg = (0xFF shl 24) or ((sr / n).toInt() shl 16) or ((sg / n).toInt() shl 8) or (sb / n).toInt()
                java.util.Arrays.fill(buf, 0, n, avg)
                bmp.setPixels(buf, 0, cw, x, y, cw, ch)
                x += bs
            }
            y += bs
        }
    }

    private fun clamp(bmp: Bitmap, l: Int, t: Int, r: Int, b: Int): IntArray? {
        val x0 = l.coerceIn(0, bmp.width); val x1 = r.coerceIn(0, bmp.width)
        val y0 = t.coerceIn(0, bmp.height); val y1 = b.coerceIn(0, bmp.height)
        return if (x1 - x0 < 1 || y1 - y0 < 1) null else intArrayOf(x0, y0, x1, y1)
    }
}
