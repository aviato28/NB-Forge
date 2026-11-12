package com.namanbansal.forge.core.image

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How a preset reaches its size: [FIT] keeps the whole picture (can be smaller on one side), [FILL] crops to the exact size. */
enum class FitMode { FIT, FILL }

data class SizePreset(val id: String, val label: String, val width: Int, val height: Int, val mode: FitMode, val note: String = "")

/** Pure resize maths and presets (no Android types, unit-tested). */
object ResizeMath {
    private const val PASSPORT_DPI = 300
    private fun mmToPx(mm: Double, dpi: Int) = (mm / 25.4 * dpi).roundToInt()

    val PRESETS: List<SizePreset> = listOf(
        SizePreset("ig-square", "Instagram square", 1080, 1080, FitMode.FILL, "1080 x 1080"),
        SizePreset("ig-portrait", "Instagram portrait", 1080, 1350, FitMode.FILL, "1080 x 1350"),
        SizePreset("story", "Story / Reel", 1080, 1920, FitMode.FILL, "1080 x 1920"),
        SizePreset("passport", "Passport 35 x 45 mm", mmToPx(35.0, PASSPORT_DPI), mmToPx(45.0, PASSPORT_DPI), FitMode.FILL, "at 300 dpi"),
        SizePreset("hd", "HD 1280", 1280, 720, FitMode.FIT, "fits 1280 x 720"),
        SizePreset("fhd", "Full HD", 1920, 1080, FitMode.FIT, "fits 1920 x 1080"),
        SizePreset("4k", "4K", 3840, 2160, FitMode.FIT, "fits 3840 x 2160"),
    )

    fun preset(id: String): SizePreset? = PRESETS.firstOrNull { it.id == id }

    /** Output (w, h) when scaling a [w] x [h] picture to [percent] %. */
    fun percent(w: Int, h: Int, percent: Double): Pair<Int, Int> =
        max(1, (w * percent / 100.0).roundToInt()) to max(1, (h * percent / 100.0).roundToInt())

    /** The other side for a locked aspect ratio: set width -> height, or height -> width. */
    fun lockedHeight(w: Int, h: Int, newW: Int): Int = max(1, (h.toDouble() * newW / w).roundToInt())
    fun lockedWidth(w: Int, h: Int, newH: Int): Int = max(1, (w.toDouble() * newH / h).roundToInt())

    /** Size that fits [w] x [h] inside a [boxW] x [boxH] box without upscaling. */
    fun fitInside(w: Int, h: Int, boxW: Int, boxH: Int): Pair<Int, Int> {
        val s = min(1.0, min(boxW.toDouble() / w, boxH.toDouble() / h))
        return max(1, (w * s).roundToInt()) to max(1, (h * s).roundToInt())
    }

    /** Centered crop rect (left, top, right, bottom in source pixels) of a [w] x [h] picture matching the ratio [tw] : [th]. */
    fun fillCrop(w: Int, h: Int, tw: Int, th: Int): IntArray {
        val target = tw.toDouble() / th
        val cw: Int
        val ch: Int
        if (w.toDouble() / h > target) { ch = h; cw = min(w, (h * target).roundToInt()) } else { cw = w; ch = min(h, (w / target).roundToInt()) }
        val l = (w - cw) / 2
        val t = (h - ch) / 2
        return intArrayOf(l, t, l + cw, t + ch)
    }

    /** Final pixel size a preset produces for a [w] x [h] source. FILL is exact; FIT never upscales. */
    fun outputFor(p: SizePreset, w: Int, h: Int): Pair<Int, Int> = when (p.mode) {
        FitMode.FILL -> p.width to p.height
        FitMode.FIT -> fitInside(w, h, if (w >= h) p.width else p.height, if (w >= h) p.height else p.width)
    }
}
