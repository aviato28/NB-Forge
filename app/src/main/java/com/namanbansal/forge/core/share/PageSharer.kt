package com.namanbansal.forge.core.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.namanbansal.forge.core.OutputNaming
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.pdf.PageRanges
import com.namanbansal.forge.core.pdf.PageRasterizers
import com.namanbansal.forge.core.pdf.PageSpec
import com.namanbansal.forge.core.pdf.PdfAssembler
import com.namanbansal.forge.core.pdf.PdfProtection
import java.io.File

/** Builds what "Share selected pages" and "Share page as image" hand to the share sheet. */
object PageSharer {
    /** `invoice_pages_2-4.pdf`; a single page is `invoice_page_3.pdf`. */
    fun pdfName(original: String, pages: Collection<Int>): String {
        val base = OutputNaming.baseName(original)
        val sorted = pages.sorted()
        if (sorted.size == 1) return "${base}_page_${sorted.first() + 1}.pdf"
        val label = PageRanges.format(sorted).replace(", ", "_").take(40).trimEnd('_', '-')
        return "${base}_pages_$label.pdf"
    }

    fun imageName(original: String, page: Int): String = "${OutputNaming.baseName(original)}_page_${page + 1}.png"

    /** Copies [pages] (0-based, in the given order) of [source] into the new PDF [out]. */
    fun pagesPdf(source: File, pages: List<Int>, out: File, protection: PdfProtection? = null) =
        specsPdf(source, pages.map { PageSpec.FromSource(0, it) }, out, protection)

    /** Same with explicit page specs (the Page manager passes its rotated / reordered pages). */
    fun specsPdf(source: File, specs: List<PageSpec>, out: File, protection: PdfProtection? = null) {
        if (specs.isEmpty()) throw ToolException("Select at least one page first.")
        out.parentFile?.mkdirs()
        out.outputStream().use { o -> PdfAssembler.build(listOf(source), specs, o, protection) }
    }

    /** Renders [page] (0-based) as a PNG about [widthPx] wide with a white background. */
    fun pageImage(source: File, page: Int, out: File, widthPx: Int = 1600, rotation: Int = 0) {
        val r = PageRasterizers.factory(source)
        try {
            val size = r.pageSize(page) ?: throw ToolException("Couldn't read that page.")
            val w = widthPx.coerceIn(200, 3000)
            val h = (w.toLong() * size.second / size.first.coerceAtLeast(1)).toInt().coerceIn(1, 6000)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                Canvas(bmp).drawColor(Color.WHITE)
                r.render(page, bmp)
                out.parentFile?.mkdirs()
                val turned = if (rotation % 360 == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }, true)
                try { out.outputStream().use { turned.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { if (turned !== bmp) turned.recycle() }
            } finally { bmp.recycle() }
        } finally { r.close() }
    }
}
