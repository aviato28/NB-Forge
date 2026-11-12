package com.namanbansal.forge.core.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF

import kotlin.math.max
import kotlin.math.min

/** A collage template: [cells] are rectangles in 0..1 of the canvas, no overlap, covering it fully (before spacing). */
data class CollageTemplate(val id: String, val count: Int, val cells: List<FRect>) {
    val label: String get() = id.substringAfter(':')
}

/** Zoom (>= 1) and pan (-1..1 each axis) of a picture inside its cell. */
data class CellTransform(val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f)

object CollageLayout {
    /** Rows of equal height with [counts] equal-width cells per row, e.g. (1, 2) = one wide cell on top, two below. */
    private fun rows(vararg counts: Int): List<FRect> {
        val n = counts.size
        return counts.flatMapIndexed { r, c -> (0 until c).map { i -> FRect(i / c.toFloat(), r / n.toFloat(), (i + 1) / c.toFloat(), (r + 1) / n.toFloat()) } }
    }

    private fun cols(vararg counts: Int): List<FRect> = rows(*counts).map { FRect(it.t, it.l, it.b, it.r) }

    private fun t(count: Int, name: String, cells: List<FRect>) = CollageTemplate("$count:$name", count, cells)

    val TEMPLATES: List<CollageTemplate> = listOf(
        t(2, "Side by side", rows(2)), t(2, "Stacked", cols(2)),
        t(2, "Big + small", listOf(FRect(0f, 0f, 2f / 3, 1f), FRect(2f / 3, 0f, 1f, 1f))),
        t(3, "Three across", rows(3)), t(3, "Three down", cols(3)), t(3, "One + two", rows(1, 2)),
        t(3, "Left + two", cols(1, 2)),
        t(4, "Grid 2x2", rows(2, 2)), t(4, "One + three", rows(1, 3)), t(4, "Four across", rows(4)),
        t(5, "Two + three", rows(2, 3)), t(5, "One + four", rows(1, 4)), t(5, "Three + two", rows(3, 2)),
        t(6, "Grid 3x2", rows(3, 3)), t(6, "Grid 2x3", rows(2, 2, 2)), t(6, "Pyramid", rows(1, 2, 3)),
        t(7, "Three rows", rows(2, 3, 2)), t(7, "Four + three", rows(4, 3)),
        t(8, "Grid 4x2", rows(4, 4)), t(8, "Three rows", rows(3, 3, 2)),
        t(9, "Grid 3x3", rows(3, 3, 3)), t(9, "Four rows", rows(2, 3, 2, 2)),
    )

    fun forCount(n: Int): List<CollageTemplate> = TEMPLATES.filter { it.count == n }

    /** Aspect ratios offered for the canvas (width / height). */
    val CANVAS_RATIOS: List<Pair<String, Float>> = listOf("1:1" to 1f, "4:5" to 0.8f, "3:4" to 0.75f, "9:16" to 9f / 16, "16:9" to 16f / 9)

    /** Canvas pixel size with [longSide] on the longer axis. */
    fun canvasSize(ratio: Float, longSide: Int): Pair<Int, Int> =
        if (ratio >= 1f) longSide to max(1, Math.round(longSide / ratio)) else max(1, Math.round(longSide * ratio)) to longSide

    /**
     * Pixel rectangles for [template] on a [w] x [h] canvas: [spacing] px between cells and around the edge.
     * Cells never leave the canvas and never overlap (tests check both).
     */
    fun layout(template: CollageTemplate, w: Int, h: Int, spacing: Int): List<FRect> {
        val half = spacing / 2f
        val sx = w - 2 * half
        val sy = h - 2 * half
        return template.cells.map { c ->
            val l = half + c.l * sx + half; val tp = half + c.t * sy + half
            val r = half + c.r * sx - half; val b = half + c.b * sy - half
            FRect(l, tp, max(l + 1f, r), max(tp + 1f, b))
        }
    }

    /** Which part of an [imgW] x [imgH] picture shows in a [cellW] x [cellH] cell (cover fit, then zoom + pan). In source pixels. */
    fun sourceRect(imgW: Int, imgH: Int, cellW: Float, cellH: Float, tr: CellTransform): FRect {
        val zoom = max(1f, tr.zoom)
        val cellRatio = cellW / cellH
        val baseW: Float
        val baseH: Float
        if (imgW.toFloat() / imgH > cellRatio) { baseH = imgH.toFloat(); baseW = baseH * cellRatio } else { baseW = imgW.toFloat(); baseH = baseW / cellRatio }
        val cw = baseW / zoom
        val ch = baseH / zoom
        val freeX = imgW - cw
        val freeY = imgH - ch
        val cx = imgW / 2f + tr.panX.coerceIn(-1f, 1f) * freeX / 2f
        val cy = imgH / 2f + tr.panY.coerceIn(-1f, 1f) * freeY / 2f
        val l = (cx - cw / 2).coerceIn(0f, max(0f, imgW - cw))
        val t = (cy - ch / 2).coerceIn(0f, max(0f, imgH - ch))
        return FRect(l, t, min(imgW.toFloat(), l + cw), min(imgH.toFloat(), t + ch))
    }
}

/** Paints a collage: [images] go into the template cells in order, each with its own zoom / pan, rounded corners and a background. */
object CollageRenderer {
    fun render(images: List<Bitmap>, template: CollageTemplate, w: Int, h: Int, spacing: Int, radius: Float, background: Int, transforms: List<CellTransform>): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(background)
        val cells = CollageLayout.layout(template, w, h, spacing)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        cells.forEachIndexed { i, cell ->
            val img = images.getOrNull(i) ?: return@forEachIndexed
            val src = CollageLayout.sourceRect(img.width, img.height, cell.w, cell.h, transforms.getOrElse(i) { CellTransform() })
            canvas.save()
            val r = radius.coerceAtMost(minOf(cell.w, cell.h) / 2f)
            canvas.clipPath(Path().apply { addRoundRect(RectF(cell.l, cell.t, cell.r, cell.b), r, r, Path.Direction.CW) })
            canvas.drawBitmap(img, Rect(src.l.toInt(), src.t.toInt(), src.r.toInt().coerceAtLeast(src.l.toInt() + 1), src.b.toInt().coerceAtLeast(src.t.toInt() + 1)), RectF(cell.l, cell.t, cell.r, cell.b), paint)
            canvas.restore()
        }
        return out
    }

    /** New transform after the finger moved by ([dx], [dy]) px inside a cell of [cellW] x [cellH] px, and scaled by [zoomBy]. */
    fun gesture(imgW: Int, imgH: Int, cellW: Float, cellH: Float, tr: CellTransform, dx: Float, dy: Float, zoomBy: Float): CellTransform {
        val zoom = (tr.zoom * zoomBy).coerceIn(1f, 5f)
        val cur = CollageLayout.sourceRect(imgW, imgH, cellW, cellH, tr.copy(zoom = zoom))
        val scale = cellW / cur.w
        val freeX = imgW - cur.w
        val freeY = imgH - cur.h
        val px = if (freeX > 0.5f) tr.panX - dx / scale / (freeX / 2f) else 0f
        val py = if (freeY > 0.5f) tr.panY - dy / scale / (freeY / 2f) else 0f
        return CellTransform(zoom, px.coerceIn(-1f, 1f), py.coerceIn(-1f, 1f))
    }
}
