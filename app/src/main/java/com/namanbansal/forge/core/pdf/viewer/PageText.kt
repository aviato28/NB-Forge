package com.namanbansal.forge.core.pdf.viewer

import com.namanbansal.forge.core.pdf.NormRect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The text of one page with the box of every character as fractions (0..1) of the displayed page (rotation applied,
 * origin top-left). [rects] is null for characters that have no box (the spaces and line breaks the extractor adds).
 * Mapping to the screen is just `fraction * pagePixels`, so it is the same at any zoom.
 */
class PageText(val text: String, val rects: List<NormRect?>) {
    val length: Int get() = text.length

    /** Index of the character under ([nx], [ny]), or the nearest one on the same line within [slop]; -1 if none. */
    fun charAt(nx: Float, ny: Float, slop: Float = 0.012f): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in rects.indices) {
            val r = rects[i] ?: continue
            if (nx < r.left - slop || nx > r.right + slop || ny < r.top - slop || ny > r.bottom + slop) continue
            val dx = if (nx < r.left) r.left - nx else if (nx > r.right) nx - r.right else 0f
            val dy = if (ny < r.top) r.top - ny else if (ny > r.bottom) ny - r.bottom else 0f
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /** The word around [index] as a half-open range, or null if [index] is not on a letter or digit. */
    fun wordAt(index: Int): IntRange? {
        if (index !in 0 until length || !isWordChar(text[index])) return null
        var s = index; var e = index
        while (s > 0 && isWordChar(text[s - 1])) s--
        while (e + 1 < length && isWordChar(text[e + 1])) e++
        return s..e
    }

    private fun isWordChar(c: Char) = !c.isWhitespace()

    /** Highlight boxes for characters [start] until [end]: one box per line. */
    fun lineRects(start: Int, end: Int): List<NormRect> {
        val out = ArrayList<NormRect>()
        var l = 0f; var t = 0f; var r = 0f; var b = 0f; var open = false
        var prev: NormRect? = null
        for (i in max(0, start) until min(end, length)) {
            val c = rects[i] ?: continue
            val p = prev
            // Compare with the previous glyph: horizontal text keeps its height band, text on a turned page keeps its column.
            val sameLine = open && p != null &&
                (abs((c.top + c.bottom) / 2 - (p.top + p.bottom) / 2) < (p.bottom - p.top) * 0.6f ||
                    abs((c.left + c.right) / 2 - (p.left + p.right) / 2) < (p.right - p.left) * 0.6f &&
                    abs((c.left + c.right) / 2 - (p.left + p.right) / 2) * 3 < abs((c.top + c.bottom) / 2 - (p.top + p.bottom) / 2))
            if (sameLine) { l = min(l, c.left); t = min(t, c.top); r = max(r, c.right); b = max(b, c.bottom) }
            else { if (open) out += NormRect(l, t, r, b); l = c.left; t = c.top; r = c.right; b = c.bottom; open = true }
            prev = c
        }
        if (open) out += NormRect(l, t, r, b)
        return out
    }

    /** The selected text with the extractor's separators kept. */
    fun substring(start: Int, end: Int): String = text.substring(start.coerceIn(0, length), end.coerceIn(0, length)).trim()

    /** First and last character that has a box, or null for a page without text. */
    fun bounds(): IntRange? {
        val first = rects.indexOfFirst { it != null }
        return if (first < 0) null else first..rects.indexOfLast { it != null }
    }
}

/** A selection on one page: characters [start] until [end]. */
data class TextSelection(val page: Int, val start: Int, val end: Int) {
    init { require(start <= end) }
    val isEmpty: Boolean get() = start >= end
    fun withEnd(e: Int) = if (e >= start) copy(end = e) else copy(start = e, end = start)
}
