package com.namanbansal.forge.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.namanbansal.forge.core.pdf.PageRasterizer
import com.namanbansal.forge.core.pdf.PageRasterizers
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.File

/**
 * Test-only rasterizer: Robolectric's PdfRenderer draws nothing, so screenshots and render tests use PDFBox's own renderer
 * (drawn through Android Canvas, which works under Robolectric native graphics).
 */
class PdfBoxRasterizer(file: File) : PageRasterizer {
    private val doc = PDDocument.load(file)
    private val renderer = PDFRenderer(doc)
    override val pageCount: Int = doc.numberOfPages

    override fun pageSize(page: Int): Pair<Int, Int>? {
        val p = doc.getPage(page)
        val box = p.cropBox
        val swap = p.rotation % 180 != 0
        val w = box.width.toInt(); val h = box.height.toInt()
        return if (swap) h to w else w to h
    }

    override fun render(page: Int, bitmap: Bitmap) {
        val full = renderer.renderImage(page, 1f)
        Canvas(bitmap).drawBitmap(full, null, Rect(0, 0, bitmap.width, bitmap.height), Paint(Paint.FILTER_BITMAP_FLAG))
    }

    override fun close() = doc.close()

    companion object {
        fun install() { PageRasterizers.factory = { PdfBoxRasterizer(it) } }
        fun uninstall() { PageRasterizers.factory = { com.namanbansal.forge.core.pdf.AndroidPageRasterizer(it) } }
    }
}
