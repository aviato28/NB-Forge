package com.namanbansal.forge.core.pdf

import android.graphics.Bitmap
import android.graphics.Color
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.image.ImageCodec
import com.namanbansal.forge.core.image.ImgFormat
import java.io.OutputStream
import kotlin.math.roundToInt

/** PDF pages as pictures: size maths, rendering and encoding (PNG / JPG / WebP). */
object PdfPageExporter {
    const val MIN_DPI = 72
    const val MAX_DPI = 600
    /** Largest page bitmap we will allocate (about 120 MB as ARGB_8888). */
    const val MAX_PIXELS = 30_000_000L

    val formats = listOf(ImgFormat.PNG, ImgFormat.JPEG, ImgFormat.WEBP_LOSSY)

    fun pixelSize(widthPts: Int, heightPts: Int, dpi: Int): Pair<Int, Int> =
        (widthPts * dpi / 72f).roundToInt().coerceAtLeast(1) to (heightPts * dpi / 72f).roundToInt().coerceAtLeast(1)

    fun fits(widthPts: Int, heightPts: Int, dpi: Int): Boolean = pixelSize(widthPts, heightPts, dpi).let { it.first.toLong() * it.second <= MAX_PIXELS }

    /** Renders [page] at [dpi] on a white background. */
    fun render(r: PageRasterizer, page: Int, dpi: Int): Bitmap {
        val (wp, hp) = r.pageSize(page) ?: throw ToolException("Page ${page + 1} couldn't be read.")
        val (w, h) = pixelSize(wp, hp, dpi)
        if (w.toLong() * h > MAX_PIXELS) throw ToolException("Page ${page + 1} is too large at $dpi dpi for this device. Choose a lower resolution.")
        val bmp = try { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) } catch (_: OutOfMemoryError) {
            throw ToolException("Not enough memory to render at $dpi dpi. Choose a lower resolution.")
        }
        bmp.eraseColor(Color.WHITE)
        r.render(page, bmp)
        return bmp
    }

    fun encode(bmp: Bitmap, fmt: ImgFormat, quality: Int, out: OutputStream) = ImageCodec.encode(bmp, fmt, quality, out)

    /** `name_page-007.png` style file name, padded to the page count. */
    fun fileName(base: String, page: Int, pageCount: Int, fmt: ImgFormat): String {
        val digits = pageCount.toString().length.coerceAtLeast(2)
        return "${base}_page-${(page + 1).toString().padStart(digits, '0')}.${fmt.ext}"
    }

    /** About how big one exported page is: encodes a reduced copy and scales by area. Null if it can't be estimated. */
    fun estimateBytes(preview: Bitmap, fullWidth: Int, fullHeight: Int, fmt: ImgFormat, quality: Int): Long? = runCatching {
        val sample = ImageCodec.encodedSize(preview, fmt, quality)
        val ratio = (fullWidth.toDouble() * fullHeight) / (preview.width.toDouble() * preview.height)
        (sample * ratio).toLong()
    }.getOrNull()
}
