package com.namanbansal.forge.core.pdf

import com.namanbansal.forge.core.ToolException
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix

/** Where the watermark sits: a cell of a 3 x 3 grid (0 = top-left ... 4 = centre ... 8 = bottom-right) or [TILED]. */
object WatermarkPosition { const val CENTER = 4; const val TILED = 9 }

enum class WatermarkFont(val label: String) {
    HELVETICA("Sans"), HELVETICA_BOLD("Sans bold"), TIMES("Serif"), TIMES_BOLD("Serif bold"), COURIER("Mono");
    fun font(): PDFont = when (this) {
        HELVETICA -> PDType1Font.HELVETICA; HELVETICA_BOLD -> PDType1Font.HELVETICA_BOLD
        TIMES -> PDType1Font.TIMES_ROMAN; TIMES_BOLD -> PDType1Font.TIMES_BOLD; COURIER -> PDType1Font.COURIER
    }
}

class WatermarkSpec(
    val text: String? = "CONFIDENTIAL",
    /** Encoded picture (PNG with alpha, or JPEG) for a picture watermark; wins over [text] when set. */
    val image: ByteArray? = null,
    val font: WatermarkFont = WatermarkFont.HELVETICA_BOLD,
    val size: Float = 64f,
    val color: Int = 0xFF9A9AA2.toInt(),
    val opacity: Float = 0.3f,
    /** Anticlockwise degrees, as the person sees it. */
    val rotation: Float = 45f,
    val position: Int = WatermarkPosition.CENTER,
    /** Picture width as a fraction of the page width. */
    val imageScale: Float = 0.4f,
    val behind: Boolean = false,
    val margin: Float = 36f,
)

/** Stamps text or a picture on pages: opacity, rotation, 3x3 position or tiled, behind or over the content. */
object PdfWatermarker {

    /** @return pages stamped */
    fun apply(doc: PDDocument, spec: WatermarkSpec, pages: Set<Int>? = null, ctx: TransformCtx = TransformCtx({}, {})): Int {
        if (spec.image == null && spec.text.isNullOrBlank()) throw ToolException("Type the watermark text or choose a picture.")
        val img: PDImageXObject? = spec.image?.let { runCatching { PDImageXObject.createFromByteArray(doc, it, "watermark") }.getOrNull() ?: throw ToolException("That picture couldn't be used as a watermark.") }
        val font = spec.font.font()
        val text = spec.text?.let { TextSafe.sanitize(it, font) }.orEmpty()
        val gs = PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = spec.opacity; strokingAlphaConstant = spec.opacity }
        var n = 0
        val total = doc.numberOfPages
        for ((i, page) in doc.pages.withIndex()) {
            ctx.checkCancelled()
            if (pages != null && i !in pages) continue
            stampPage(doc, page, spec, img, text, font, gs)
            n++
            ctx.onProgress((i + 1).toFloat() / total * 0.9f)
        }
        return n
    }

    private fun stampPage(doc: PDDocument, page: PDPage, spec: WatermarkSpec, img: PDImageXObject?, text: String, font: PDFont, gs: PDExtendedGraphicsState) {
        val mode = if (spec.behind) PDPageContentStream.AppendMode.PREPEND else PDPageContentStream.AppendMode.APPEND
        val (vw, vh) = PageGeometry.viewSize(page)
        PDPageContentStream(doc, page, mode, true, !spec.behind).use { cs ->
            cs.saveGraphicsState()
            cs.setGraphicsStateParameters(gs)
            cs.transform(PageGeometry.viewToUser(page))
            val (w, h) = if (img != null) {
                val iw = (vw * spec.imageScale).coerceAtLeast(8f)
                iw to iw * img.height / img.width.coerceAtLeast(1)
            } else TextSafe.width(text, font, spec.size) to spec.size
            val r = (spec.color shr 16 and 0xFF) / 255f; val g = (spec.color shr 8 and 0xFF) / 255f; val b = (spec.color and 0xFF) / 255f
            fun drawAt(cx: Float, cy: Float) {
                cs.saveGraphicsState()
                cs.transform(Matrix.getTranslateInstance(cx, cy))
                cs.transform(Matrix.getRotateInstance(Math.toRadians(spec.rotation.toDouble()), 0f, 0f))
                if (img != null) cs.drawImage(img, -w / 2, -h / 2, w, h)
                else {
                    cs.beginText(); cs.setFont(font, spec.size); cs.setNonStrokingColor(r, g, b)
                    cs.newLineAtOffset(-w / 2, -h * 0.35f); cs.showText(text); cs.endText()
                }
                cs.restoreGraphicsState()
            }
            if (spec.position == WatermarkPosition.TILED) {
                val stepX = w * 1.4f + 48f; val stepY = h * 3f + 24f
                val diag = Math.hypot(vw.toDouble(), vh.toDouble()).toFloat()
                // Tile in a plane rotated about the page centre so rows follow the watermark's own angle.
                cs.saveGraphicsState()
                cs.transform(Matrix.getTranslateInstance(vw / 2, vh / 2))
                cs.transform(Matrix.getRotateInstance(Math.toRadians(spec.rotation.toDouble()), 0f, 0f))
                var y = -diag
                var row = 0
                while (y <= diag) {
                    var x = -diag + if (row % 2 == 1) stepX / 2 else 0f
                    while (x <= diag) {
                        cs.saveGraphicsState()
                        cs.transform(Matrix.getTranslateInstance(x, y))
                        if (img != null) cs.drawImage(img, -w / 2, -h / 2, w, h)
                        else { cs.beginText(); cs.setFont(font, spec.size); cs.setNonStrokingColor(r, g, b); cs.newLineAtOffset(-w / 2, -h * 0.35f); cs.showText(text); cs.endText() }
                        cs.restoreGraphicsState()
                        x += stepX
                    }
                    y += stepY; row++
                }
                cs.restoreGraphicsState()
            } else {
                val col = spec.position % 3; val row = spec.position / 3
                val cx = when (col) { 0 -> spec.margin + w / 2; 1 -> vw / 2; else -> vw - spec.margin - w / 2 }
                val cy = when (row) { 0 -> vh - spec.margin - h / 2; 1 -> vh / 2; else -> spec.margin + h / 2 }
                drawAt(cx, cy)
            }
            cs.restoreGraphicsState()
        }
    }
}
