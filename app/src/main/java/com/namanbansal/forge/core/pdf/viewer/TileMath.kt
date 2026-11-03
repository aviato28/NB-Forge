package com.namanbansal.forge.core.pdf.viewer

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Side of one hi-res tile in device pixels. */
const val TILE_PX = 512

/** A rectangle in whole pixels (right / bottom exclusive). */
data class PxRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * One tile of one page at one zoom. [pageWidthPx] is the width the whole page has on screen (device px) and doubles as the
 * "scale key": the same page at a different zoom has different tiles.
 */
data class TileKey(val page: Int, val pageWidthPx: Int, val col: Int, val row: Int)

/** Which tiles cover the visible part of a page. Pure maths, no Android types. */
object TileMath {
    fun cols(pageWpx: Int, tile: Int = TILE_PX) = ceil(pageWpx / tile.toFloat()).toInt().coerceAtLeast(1)
    fun rows(pageHpx: Int, tile: Int = TILE_PX) = ceil(pageHpx / tile.toFloat()).toInt().coerceAtLeast(1)

    /** Pixel rectangle of [key] inside a page of [pageWpx] x [pageHpx] (clamped, so edge tiles are smaller). */
    fun tileRect(key: TileKey, pageWpx: Int, pageHpx: Int, tile: Int = TILE_PX): PxRect {
        val l = (key.col * tile).coerceAtMost(pageWpx)
        val t = (key.row * tile).coerceAtMost(pageHpx)
        return PxRect(l, t, minOf(l + tile, pageWpx), minOf(t + tile, pageHpx))
    }

    /**
     * Tiles of [page] needed to fill the visible region ([visLeft]..[visRight], [visTop]..[visBottom], in page pixels, may
     * stick out of the page) plus [margin] px around it, nearest the centre of the view first.
     * Empty when the page is off screen.
     */
    fun visibleTiles(
        page: Int, pageWpx: Int, pageHpx: Int,
        visLeft: Float, visTop: Float, visRight: Float, visBottom: Float,
        margin: Int = TILE_PX / 2, tile: Int = TILE_PX,
    ): List<TileKey> {
        if (pageWpx <= 0 || pageHpx <= 0) return emptyList()
        val l = maxOf(visLeft - margin, 0f); val t = maxOf(visTop - margin, 0f)
        val r = minOf(visRight + margin, pageWpx.toFloat()); val b = minOf(visBottom + margin, pageHpx.toFloat())
        if (r <= l || b <= t || visRight <= 0f || visBottom <= 0f || visLeft >= pageWpx || visTop >= pageHpx) return emptyList()
        val c0 = floor(l / tile).toInt(); val c1 = ceil(r / tile).toInt() - 1
        val r0 = floor(t / tile).toInt(); val r1 = ceil(b / tile).toInt() - 1
        val cx = (visLeft + visRight) / 2; val cy = (visTop + visBottom) / 2
        val out = ArrayList<TileKey>((c1 - c0 + 1) * (r1 - r0 + 1))
        for (row in r0..r1) for (col in c0..c1) out += TileKey(page, pageWpx, col, row)
        return out.sortedBy { k ->
            val dx = (k.col + 0.5f) * tile - cx; val dy = (k.row + 0.5f) * tile - cy
            abs(dx) + abs(dy)
        }
    }
}
