package com.namanbansal.forge.core.pdf.viewer

/** Where one page sits inside its slot (a slot is one page, or a two-page spread). All values are device pixels. */
data class PageBox(val page: Int, val x: Int, val y: Int, val w: Int, val h: Int)

data class SlotGeometry(val height: Int, val boxes: List<PageBox>)

/** Which pages share a screen position, and how big they are. Pure maths shared by the scroller, tiles and hit-testing. */
object SlotLayout {
    /** Pages per slot: singles, or pairs (0,1), (2,3)... for a spread. */
    fun slots(pageCount: Int, twoPage: Boolean): List<IntRange> {
        if (pageCount <= 0) return emptyList()
        if (!twoPage) return List(pageCount) { it..it }
        return (0 until pageCount step 2).map { it..minOf(it + 1, pageCount - 1) }
    }

    fun slotOf(page: Int, twoPage: Boolean) = if (twoPage) page / 2 else page

    /**
     * Sizes the pages of one slot. [viewportW]/[viewportH] are the unzoomed viewport; the content is [zoom] times wider.
     * Fit-width makes a single page as wide as the viewport (a spread splits the width); fit-page also keeps it within the
     * viewport height. Pages are centred in the slot; the slot is at least [minHeight] tall (paged mode centres vertically).
     */
    fun compute(pages: IntRange, aspects: FloatArray?, viewportW: Int, viewportH: Int, zoom: Float, fitPage: Boolean, minHeight: Int = 0): SlotGeometry {
        val n = pages.last - pages.first + 1
        val cell = viewportW.toFloat() / n
        val widths = pages.map { p ->
            val a = (aspects?.getOrNull(p) ?: 0.707f).coerceAtLeast(0.05f)
            val base = if (fitPage) minOf(cell, viewportH * a) else cell
            (base * zoom).toInt().coerceAtLeast(1)
        }
        val heights = pages.mapIndexed { i, p -> (widths[i] / (aspects?.getOrNull(p) ?: 0.707f).coerceAtLeast(0.05f)).toInt().coerceAtLeast(1) }
        val slotH = maxOf(heights.max(), minHeight)
        val slotW = (viewportW * zoom).toInt()
        var x = ((slotW - widths.sum()) / 2).coerceAtLeast(0)
        val boxes = pages.mapIndexed { i, p ->
            PageBox(p, x, (slotH - heights[i]) / 2, widths[i], heights[i]).also { x += widths[i] }
        }
        return SlotGeometry(slotH, boxes)
    }

    /** The page box containing content point ([cx], [cy]) of a slot, with the point as fractions of that page; null on a gap. */
    fun hit(g: SlotGeometry, cx: Float, cy: Float): Pair<PageBox, Pair<Float, Float>>? {
        val b = g.boxes.firstOrNull { cx >= it.x && cx < it.x + it.w && cy >= it.y && cy < it.y + it.h } ?: return null
        return b to ((cx - b.x) / b.w to (cy - b.y) / b.h)
    }
}
