package com.namanbansal.forge.core.pdf

import android.graphics.Bitmap
import com.namanbansal.forge.core.ToolException
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.util.Matrix
import java.io.File

enum class ImposeMode(val label: String) { NUP2("2-up"), NUP4("4-up"), BOOKLET("Booklet") }
enum class SheetSize(val label: String, val rect: PDRectangle?) { A4("A4", PDRectangle.A4), LETTER("Letter", PDRectangle.LETTER), FIRST("Same as page 1", null) }

class ImposeSpec(
    val mode: ImposeMode = ImposeMode.NUP2,
    val sheet: SheetSize = SheetSize.A4,
    val margin: Float = 18f,
    val gutter: Float = 12f,
    val borders: Boolean = false,
    /** Right-to-left binding: swap left and right (and for 4-up read right-to-left). */
    val rtl: Boolean = false,
    /** 4-up only: fill down the columns first (1 above 2) instead of across the rows. */
    val columnFirst: Boolean = false,
)

/** The page-order maths behind N-up and booklet printing. Each side is a list of source page indices in cell order (row-major), null = blank. */
object ImposePlan {
    fun paddedCount(count: Int) = (count + 3) / 4 * 4

    fun nup(count: Int, cols: Int, rows: Int, columnFirst: Boolean, rtl: Boolean): List<List<Int?>> {
        val per = cols * rows
        return (0 until (count + per - 1) / per).map { s ->
            val cells = arrayOfNulls<Int>(per)
            for (k in 0 until per) {
                val idx = s * per + k
                val v = if (idx < count) idx else null
                var row: Int; var col: Int
                if (columnFirst) { col = k / rows; row = k % rows } else { row = k / cols; col = k % cols }
                if (rtl) col = cols - 1 - col
                cells[row * cols + col] = v
            }
            cells.toList()
        }
    }

    /** Saddle-stitch: sides alternate front/back of each sheet; padded to a multiple of 4 with blanks. */
    fun booklet(count: Int, rtl: Boolean): List<List<Int?>> {
        val p = paddedCount(count)
        fun pg(i: Int): Int? = if (i < count) i else null
        val sides = ArrayList<List<Int?>>()
        for (s in 0 until p / 4) {
            val front = listOf(pg(p - 1 - 2 * s), pg(2 * s))
            val back = listOf(pg(2 * s + 1), pg(p - 2 - 2 * s))
            sides += if (rtl) front.reversed() else front
            sides += if (rtl) back.reversed() else back
        }
        return sides
    }

    fun plan(count: Int, spec: ImposeSpec): List<List<Int?>> = when (spec.mode) {
        ImposeMode.NUP2 -> nup(count, 2, 1, false, spec.rtl)
        ImposeMode.NUP4 -> nup(count, 2, 2, spec.columnFirst, spec.rtl)
        ImposeMode.BOOKLET -> booklet(count, spec.rtl)
    }

    fun cols(spec: ImposeSpec) = if (spec.mode == ImposeMode.NUP4) 2 to 2 else 2 to 1
}

/** Builds imposed sheets with LayerUtility form XObjects (pages stay vector; nothing is rasterised). */
object PdfImposer {

    private fun sheetRect(src: PDDocument, spec: ImposeSpec): PDRectangle {
        val first = PageGeometry.viewSize(src.getPage(0))
        val base = spec.sheet.rect ?: PDRectangle(first.first, first.second)
        val (cols, rows) = ImposePlan.cols(spec)
        // Orientation auto: pick the orientation where the first page is scaled up the most.
        fun scale(w: Float, h: Float): Float {
            val cw = (w - 2 * spec.margin - (cols - 1) * spec.gutter) / cols; val ch = (h - 2 * spec.margin - (rows - 1) * spec.gutter) / rows
            return minOf(cw / first.first, ch / first.second)
        }
        val portrait = PDRectangle(minOf(base.width, base.height), maxOf(base.width, base.height))
        val landscape = PDRectangle(portrait.height, portrait.width)
        return if (scale(landscape.width, landscape.height) >= scale(portrait.width, portrait.height)) landscape else portrait
    }

    /** @param sides which sides to build (null = all); used by the live preview to build one sheet. */
    fun build(src: PDDocument, spec: ImposeSpec, sides: List<Int>? = null, ctx: TransformCtx = TransformCtx({}, {})): PDDocument {
        if (src.numberOfPages == 0) throw ToolException("This PDF has no pages.")
        val plan = ImposePlan.plan(src.numberOfPages, spec)
        val dest = PDDocument(MemoryUsageSetting.setupMixed(PdfLoader.MIXED_MEMORY_BYTES))
        try {
            val layer = LayerUtility(dest)
            val sheet = sheetRect(src, spec)
            val (cols, rows) = ImposePlan.cols(spec)
            val cw = (sheet.width - 2 * spec.margin - (cols - 1) * spec.gutter) / cols
            val ch = (sheet.height - 2 * spec.margin - (rows - 1) * spec.gutter) / rows
            val chosen = sides ?: plan.indices.toList()
            for ((n, sideIdx) in chosen.withIndex()) {
                ctx.checkCancelled()
                val cells = plan.getOrNull(sideIdx) ?: continue
                val page = PDPage(sheet); dest.addPage(page)
                PDPageContentStream(dest, page).use { cs ->
                    for ((k, srcIdx) in cells.withIndex()) {
                        if (srcIdx == null) continue
                        val form = layer.importPageAsForm(src, srcIdx)
                        val b = formBounds(form)
                        val s = minOf(cw / b[2], ch / b[3])
                        val col = k % cols; val row = k / cols
                        val cellX = spec.margin + col * (cw + spec.gutter)
                        val cellTop = sheet.height - spec.margin - row * (ch + spec.gutter)
                        val x = cellX + (cw - b[2] * s) / 2
                        val y = cellTop - ch + (ch - b[3] * s) / 2
                        cs.saveGraphicsState()
                        cs.transform(Matrix(s, 0f, 0f, s, x - b[0] * s, y - b[1] * s))
                        cs.drawForm(form)
                        cs.restoreGraphicsState()
                        if (spec.borders) {
                            cs.saveGraphicsState(); cs.setStrokingColor(0.55f, 0.55f, 0.58f); cs.setLineWidth(0.6f)
                            cs.addRect(x, y, b[2] * s, b[3] * s); cs.stroke(); cs.restoreGraphicsState()
                        }
                    }
                }
                ctx.onProgress((n + 1).toFloat() / chosen.size * 0.9f)
            }
            return dest
        } catch (t: Throwable) { dest.close(); throw t }
    }

    /** One imposed side as a bitmap, for the live preview. */
    fun previewSide(file: File, spec: ImposeSpec, side: Int, widthPx: Int, dir: File): Bitmap? {
        dir.mkdirs()
        val tmp = File.createTempFile("imp-", ".pdf", dir)
        return try {
            PdfOps.load(file).use { src -> build(src, spec, listOf(side)).use { it.save(tmp) } }
            PdfPreview.renderFirstPage(tmp, widthPx)
        } catch (_: Throwable) { null } finally { tmp.delete() }
    }
}
