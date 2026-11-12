package com.namanbansal.forge.core.image

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Colour formats (HEX / RGB / HSL) for an opaque 0xRRGGBB value. */
object ColourFormat {
    fun hex(c: Int) = "#%02X%02X%02X".format((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
    fun rgb(c: Int) = "rgb(${(c shr 16) and 0xFF}, ${(c shr 8) and 0xFF}, ${c and 0xFF})"

    fun hsl(c: Int): String {
        val (h, s, l) = hslParts(c)
        return "hsl($h, $s%, $l%)"
    }

    fun hslParts(c: Int): Triple<Int, Int, Int> {
        val r = ((c shr 16) and 0xFF) / 255.0
        val g = ((c shr 8) and 0xFF) / 255.0
        val b = (c and 0xFF) / 255.0
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2
        val d = mx - mn
        var h = 0.0
        var s = 0.0
        if (d > 1e-9) {
            s = d / (1 - Math.abs(2 * l - 1))
            h = when (mx) {
                r -> ((g - b) / d) % 6
                g -> (b - r) / d + 2
                else -> (r - g) / d + 4
            } * 60
            if (h < 0) h += 360
        }
        return Triple(h.roundToInt() % 360, (s * 100).roundToInt(), (l * 100).roundToInt())
    }

    /** Black or white, whichever reads better on [c]. */
    fun onColour(c: Int): Int {
        val lum = 0.2126 * ((c shr 16) and 0xFF) + 0.7152 * ((c shr 8) and 0xFF) + 0.0722 * (c and 0xFF)
        return if (lum > 150) 0x000000 else 0xFFFFFF
    }
}

/** Median-cut palette extraction. Pure and deterministic: same pixels in, same colours out. */
object PaletteExtractor {
    data class Swatch(val colour: Int, val share: Float)

    /** [pixels] are ARGB ints; transparent ones are ignored. Returns up to [count] swatches, biggest share first. */
    fun extract(pixels: IntArray, count: Int = 6): List<Swatch> {
        val opaque = pixels.filter { (it ushr 24) >= 128 }.map { it and 0xFFFFFF }
        if (opaque.isEmpty()) return emptyList()
        val boxes = ArrayList<List<Int>>()
        boxes += opaque
        while (boxes.size < count) {
            // Split the box with the most pixels that still has more than one distinct colour.
            val idx = boxes.indices.filter { boxes[it].distinct().size > 1 }.maxByOrNull { boxes[it].size } ?: break
            val box = boxes.removeAt(idx)
            val rr = box.maxOf { (it shr 16) and 0xFF } - box.minOf { (it shr 16) and 0xFF }
            val gr = box.maxOf { (it shr 8) and 0xFF } - box.minOf { (it shr 8) and 0xFF }
            val br = box.maxOf { it and 0xFF } - box.minOf { it and 0xFF }
            val shift = if (rr >= gr && rr >= br) 16 else if (gr >= br) 8 else 0
            val sorted = box.sortedWith(compareBy<Int> { (it shr shift) and 0xFF }.thenBy { it })
            // Cut at the colour boundary nearest the median so identical colours never straddle two boxes.
            val cuts = (1 until sorted.size).filter { sorted[it] != sorted[it - 1] }
            val cut = cuts.minByOrNull { Math.abs(it - sorted.size / 2) } ?: (sorted.size / 2)
            boxes += sorted.subList(0, cut)
            boxes += sorted.subList(cut, sorted.size)
        }
        val total = opaque.size.toFloat()
        return boxes.map { b ->
            val r = b.sumOf { (it shr 16) and 0xFF } / b.size
            val g = b.sumOf { (it shr 8) and 0xFF } / b.size
            val bl = b.sumOf { it and 0xFF } / b.size
            Swatch((r shl 16) or (g shl 8) or bl, b.size / total)
        }.sortedWith(compareByDescending<Swatch> { it.share }.thenBy { it.colour })
    }
}
