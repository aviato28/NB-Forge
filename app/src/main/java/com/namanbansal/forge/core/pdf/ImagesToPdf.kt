package com.namanbansal.forge.core.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.namanbansal.forge.core.ToolException
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.OutputStream

enum class ImagePageSize(val label: String) { FIT("Fit image"), A4("A4"), LETTER("Letter"), CUSTOM("Custom") }
enum class ImageOrientation(val label: String) { AUTO("Auto"), PORTRAIT("Portrait"), LANDSCAPE("Landscape") }

class ImagesPdfSpec(
    val size: ImagePageSize = ImagePageSize.A4,
    val orientation: ImageOrientation = ImageOrientation.AUTO,
    val marginPt: Float = 18f,
    val quality: Int = 85,
    val customWidthMm: Float = 210f,
    val customHeightMm: Float = 297f,
)

/** Pictures into a PDF, one per page. Pictures arrive already upright (EXIF applied) from [com.namanbansal.forge.core.image.ImageIO]. */
object ImagesToPdf {
    private const val MM = 72f / 25.4f
    /** Pictures are assumed to be 300 dpi when the page is sized to fit them. */
    private const val ASSUMED_DPI = 300f

    /** The page size in points for a picture of [w] x [h] pixels. */
    fun pageSize(spec: ImagePageSize, orientation: ImageOrientation, w: Int, h: Int, customWidthMm: Float = 210f, customHeightMm: Float = 297f): Pair<Float, Float> {
        if (spec == ImagePageSize.FIT) return (w * 72f / ASSUMED_DPI).coerceIn(72f, 14400f) to (h * 72f / ASSUMED_DPI).coerceIn(72f, 14400f)
        val (bw, bh) = when (spec) {
            ImagePageSize.A4 -> PDRectangle.A4.width to PDRectangle.A4.height
            ImagePageSize.LETTER -> PDRectangle.LETTER.width to PDRectangle.LETTER.height
            else -> (customWidthMm * MM).coerceIn(72f, 14400f) to (customHeightMm * MM).coerceIn(72f, 14400f)
        }
        val short = minOf(bw, bh); val long = maxOf(bw, bh)
        val landscape = when (orientation) {
            ImageOrientation.AUTO -> w > h
            ImageOrientation.LANDSCAPE -> true
            ImageOrientation.PORTRAIT -> false
        }
        return if (landscape) long to short else short to long
    }

    /** Where the picture goes on the page: (x, y, width, height) from the bottom-left, fitted inside the margins. */
    fun placement(pageW: Float, pageH: Float, imgW: Int, imgH: Int, margin: Float): FloatArray {
        val aw = (pageW - 2 * margin).coerceAtLeast(10f); val ah = (pageH - 2 * margin).coerceAtLeast(10f)
        val s = minOf(aw / imgW, ah / imgH)
        val w = imgW * s; val h = imgH * s
        return floatArrayOf((pageW - w) / 2, (pageH - h) / 2, w, h)
    }

    /** @param decode returns picture [index] upright; called once per picture so memory stays at one bitmap. */
    fun build(count: Int, spec: ImagesPdfSpec, out: OutputStream, protection: PdfProtection? = null, ctx: TransformCtx = TransformCtx({}, {}), decode: (Int) -> Bitmap) {
        if (count == 0) throw ToolException("Add at least one picture.")
        PDDocument(MemoryUsageSetting.setupMixed(PdfLoader.MIXED_MEMORY_BYTES)).use { doc ->
            for (i in 0 until count) {
                ctx.checkCancelled()
                val src = decode(i)
                try {
                    val bmp = if (src.hasAlpha()) flatten(src) else src
                    val (pw, ph) = pageSize(spec.size, spec.orientation, bmp.width, bmp.height, spec.customWidthMm, spec.customHeightMm)
                    val page = PDPage(PDRectangle(pw, ph)); doc.addPage(page)
                    val img = JPEGFactory.createFromImage(doc, bmp, spec.quality.coerceIn(10, 100) / 100f)
                    val m = if (spec.size == ImagePageSize.FIT) 0f else spec.marginPt
                    val p = placement(pw, ph, bmp.width, bmp.height, m)
                    PDPageContentStream(doc, page).use { cs -> cs.drawImage(img, p[0], p[1], p[2], p[3]) }
                    if (bmp !== src) bmp.recycle()
                } finally { src.recycle() }
                ctx.onProgress((i + 1).toFloat() / count * 0.9f)
            }
            protection?.applyTo(doc)
            doc.save(out)
        }
    }

    private fun flatten(src: Bitmap): Bitmap {
        val b = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Color.WHITE); c.drawBitmap(src, 0f, 0f, null)
        return b
    }

    /** What page [index] will look like: a white page with the picture fitted inside the margins. */
    fun previewPage(spec: ImagesPdfSpec, picture: Bitmap, widthPx: Int): Bitmap {
        val (pw, ph) = pageSize(spec.size, spec.orientation, picture.width, picture.height, spec.customWidthMm, spec.customHeightMm)
        val scale = widthPx / pw
        val out = Bitmap.createBitmap(widthPx, (ph * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(out); c.drawColor(Color.WHITE)
        val m = if (spec.size == ImagePageSize.FIT) 0f else spec.marginPt
        val p = placement(pw, ph, picture.width, picture.height, m)
        c.drawBitmap(picture, null, RectF(p[0] * scale, out.height - (p[1] + p[3]) * scale, (p[0] + p[2]) * scale, out.height - p[1] * scale), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }
}
