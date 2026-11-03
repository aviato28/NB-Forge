package com.namanbansal.forge.core.pdf.sign

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.namanbansal.forge.core.pdf.annot.NormPt
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/** Pixel rectangle, right / bottom exclusive. */
data class PixelBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Turns pictures into signature images: crop to the ink, make the paper transparent with a luminance threshold, render drawn
 * strokes and typed names. The pixel functions work on plain ARGB arrays so they are unit-tested without graphics.
 */
object SignatureImage {
    const val MAX_SIDE = 1400
    const val DEFAULT_THRESHOLD = 150

    fun luminance(argb: Int): Int {
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /** Ink = darker than [threshold] (0..255) and not already transparent. */
    fun inkBounds(px: IntArray, w: Int, h: Int, threshold: Int): PixelBox? {
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) for (x in 0 until w) {
            val p = px[y * w + x]
            if ((p ushr 24) > 16 && luminance(p) < threshold) { if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y }
        }
        return if (r < 0) null else PixelBox(l, t, r + 1, b + 1)
    }

    /**
     * Ink pixels keep their colour and get an alpha that grows as they get darker than [threshold]; everything at or above it
     * becomes fully transparent (so a grey phone-photo background disappears but pen edges stay soft).
     */
    fun makeTransparent(px: IntArray, threshold: Int): IntArray = IntArray(px.size) { i ->
        val p = px[i]
        val lum = luminance(p)
        if (lum >= threshold) 0 else {
            val strength = ((threshold - lum) * 255f / (threshold * 0.5f + 1f)).coerceIn(0f, 255f)
            val a = (strength * (p ushr 24) / 255f).toInt().coerceIn(0, 255)
            (a shl 24) or (p and 0xFFFFFF)
        }
    }

    /** Otsu's threshold of the luminance histogram, nudged towards the paper so thin pen lines survive. */
    fun autoThreshold(px: IntArray): Int {
        val hist = IntArray(256)
        px.forEach { hist[luminance(it)]++ }
        val total = px.size.toFloat()
        var sum = 0f; for (i in 0..255) sum += i * hist[i]
        var sumB = 0f; var wB = 0f; var best = 0f; var thr = DEFAULT_THRESHOLD
        for (i in 0..255) {
            wB += hist[i]; if (wB == 0f) continue
            val wF = total - wB; if (wF == 0f) break
            sumB += i * hist[i]
            val mB = sumB / wB; val mF = (sum - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; thr = i }
        }
        return (thr + (255 - thr) / 5).coerceIn(60, 235)
    }

    /** Crop to the ink with a small margin and make the paper transparent. Returns null when there is no ink at [threshold]. */
    fun process(src: Bitmap, threshold: Int): Bitmap? {
        val scale = min(1f, MAX_SIDE.toFloat() / max(src.width, src.height))
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true) else src
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        val box = inkBounds(px, w, h, threshold) ?: return null
        val pad = (max(box.width, box.height) * 0.04f).toInt().coerceAtLeast(2)
        val l = max(0, box.left - pad); val t = max(0, box.top - pad)
        val r = min(w, box.right + pad); val b = min(h, box.bottom + pad)
        val cropped = IntArray((r - l) * (b - t))
        for (y in t until b) System.arraycopy(px, y * w + l, cropped, (y - t) * (r - l), r - l)
        val out = Bitmap.createBitmap(r - l, b - t, Bitmap.Config.ARGB_8888)
        val cut = makeTransparent(cropped, threshold)
        out.setPixels(cut, 0, r - l, 0, 0, r - l, b - t)
        return out
    }

    /**
     * Renders strokes drawn on a pad (points in pad pixels) into a tightly cropped transparent bitmap. [width] is the pen
     * thickness in pad pixels; strokes are smoothed with midpoint quadratic curves.
     */
    fun renderStrokes(strokes: List<List<NormPt>>, width: Float, color: Int, scale: Float = 1f): Bitmap? {
        val pts = strokes.filter { it.isNotEmpty() }
        if (pts.isEmpty()) return null
        val all = pts.flatten()
        val pad = width * scale / 2 + 4f
        val minX = all.minOf { it.x } * scale; val maxX = all.maxOf { it.x } * scale
        val minY = all.minOf { it.y } * scale; val maxY = all.maxOf { it.y } * scale
        val bw = (maxX - minX + 2 * pad).toInt().coerceAtLeast(8); val bh = (maxY - minY + 2 * pad).toInt().coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = width * scale; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        for (s in pts) {
            val p = smoothPath(s.map { NormPt(it.x * scale - minX + pad, it.y * scale - minY + pad) })
            if (s.size == 1) c.drawCircle(s[0].x * scale - minX + pad, s[0].y * scale - minY + pad, width * scale / 2, dot) else c.drawPath(p, paint)
        }
        return bmp
    }

    fun smoothPath(s: List<NormPt>): Path {
        val p = Path()
        if (s.isEmpty()) return p
        p.moveTo(s[0].x, s[0].y)
        if (s.size == 2) { p.lineTo(s[1].x, s[1].y); return p }
        for (i in 1 until s.size - 1) {
            val mx = (s[i].x + s[i + 1].x) / 2; val my = (s[i].y + s[i + 1].y) / 2
            p.quadTo(s[i].x, s[i].y, mx, my)
        }
        p.lineTo(s.last().x, s.last().y)
        return p
    }

    /** A typed name in [typeface], tightly cropped, transparent background. */
    fun renderTyped(text: String, typeface: Typeface?, color: Int, textSizePx: Float = 220f): Bitmap? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; this.typeface = typeface; textSize = textSizePx }
        val r = android.graphics.Rect(); paint.getTextBounds(t, 0, t.length, r)
        val pad = (textSizePx * 0.12f).toInt()
        val bmp = Bitmap.createBitmap(r.width() + 2 * pad, r.height() + 2 * pad, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(t, (pad - r.left).toFloat(), (pad - r.top).toFloat(), paint)
        return bmp
    }

    fun toPng(b: Bitmap): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}
