package com.namanbansal.forge.core.pdf

import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.util.Matrix

/**
 * The page as the person sees it (crop box with /Rotate applied) and the way back to the page's own coordinates, so text
 * stamped at "bottom centre" lands at the bottom of what is displayed even on rotated or cropped pages.
 */
object PageGeometry {
    fun box(page: PDPage): PDRectangle = page.cropBox

    /** Displayed width and height in points. */
    fun viewSize(page: PDPage): Pair<Float, Float> {
        val b = box(page)
        return if (page.rotation % 180 != 0) b.height to b.width else b.width to b.height
    }

    /** Maps displayed coordinates (origin bottom-left of what is seen) to the page's user space. */
    fun viewToUser(page: PDPage): Matrix {
        val b = box(page)
        val w = b.width; val h = b.height; val x = b.lowerLeftX; val y = b.lowerLeftY
        return when (((page.rotation % 360) + 360) % 360) {
            90 -> Matrix(0f, 1f, -1f, 0f, x + w, y)
            180 -> Matrix(-1f, 0f, 0f, -1f, x + w, y + h)
            270 -> Matrix(0f, -1f, 1f, 0f, x, y + h)
            else -> Matrix(1f, 0f, 0f, 1f, x, y)
        }
    }

    /** Converts a rectangle given as fractions of the displayed page (left, top, right, bottom; origin top-left) to user space. */
    fun fractionsToUser(page: PDPage, l: Float, t: Float, r: Float, b: Float): PDRectangle {
        val (vw, vh) = viewSize(page)
        val m = viewToUser(page)
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        for ((fx, fy) in listOf(l to t, r to t, l to b, r to b)) {
            val vx = fx * vw; val vy = (1 - fy) * vh
            xs += m.getValue(0, 0) * vx + m.getValue(1, 0) * vy + m.getValue(2, 0)
            ys += m.getValue(0, 1) * vx + m.getValue(1, 1) * vy + m.getValue(2, 1)
        }
        val x0 = xs.min(); val y0 = ys.min()
        return PDRectangle(x0, y0, xs.max() - x0, ys.max() - y0)
    }
}

/** Standard-14 fonts can only draw WinAnsi text: anything else is shown as "?" rather than crashing the save. */
object TextSafe {
    fun sanitize(s: String, font: com.tom_roush.pdfbox.pdmodel.font.PDFont): String = buildString {
        for (ch in s) {
            if (ch == '\n' || ch == '\r') { append(' '); continue }
            val ok = runCatching { font.getStringWidth(ch.toString()); true }.getOrDefault(false)
            append(if (ok) ch else '?')
        }
    }

    fun width(s: String, font: com.tom_roush.pdfbox.pdmodel.font.PDFont, size: Float) = font.getStringWidth(s) / 1000f * size

    fun roman(n: Int, upper: Boolean = false): String {
        if (n <= 0) return n.toString()
        val pairs = listOf(1000 to "m", 900 to "cm", 500 to "d", 400 to "cd", 100 to "c", 90 to "xc", 50 to "l", 40 to "xl", 10 to "x", 9 to "ix", 5 to "v", 4 to "iv", 1 to "i")
        var v = n
        val sb = StringBuilder()
        for ((k, s) in pairs) while (v >= k) { sb.append(s); v -= k }
        return if (upper) sb.toString().uppercase() else sb.toString()
    }
}

/** Bounding box (minX, minY, width, height) of a Form XObject's BBox after its own /Matrix. */
fun formBounds(form: com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject): FloatArray {
    val bb = form.bBox
    val m = form.matrix
    val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
    for ((x, y) in listOf(bb.lowerLeftX to bb.lowerLeftY, bb.upperRightX to bb.lowerLeftY, bb.lowerLeftX to bb.upperRightY, bb.upperRightX to bb.upperRightY)) {
        xs += m.getValue(0, 0) * x + m.getValue(1, 0) * y + m.getValue(2, 0)
        ys += m.getValue(0, 1) * x + m.getValue(1, 1) * y + m.getValue(2, 1)
    }
    return floatArrayOf(xs.min(), ys.min(), xs.max() - xs.min(), ys.max() - ys.min())
}
