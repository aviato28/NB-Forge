package com.namanbansal.forge.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.net.Uri
import com.caverock.androidsvg.SVG
import com.namanbansal.forge.core.ToolException
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What we know about a picture without decoding it: upright size (EXIF applied), type and orientation tag. */
data class ImageInfo(val width: Int, val height: Int, val mime: String?, val orientation: Int, val isSvg: Boolean = false) {
    val pixels: Long get() = width.toLong() * height
}

/**
 * Loading pictures safely: bounds first, then a sampled decode into at most [maxDim] / [MAX_PIXELS], EXIF orientation applied.
 * Previews use a small [maxDim]; full-resolution work goes through [decode] with a pixel budget so a 50 MP photo never OOMs.
 */
object ImageIO {
    /** Largest bitmap we will hold for full-resolution work (50 MP = 200 MB as ARGB_8888; the app asks for a large heap). */
    const val MAX_PIXELS = 50_000_000L

    private fun open(ctx: Context, uri: Uri): InputStream =
        ctx.contentResolver.openInputStream(uri) ?: throw ToolException("Couldn't open that picture.")

    fun readBytes(ctx: Context, uri: Uri): ByteArray = open(ctx, uri).use { it.readBytes() }

    fun info(ctx: Context, uri: Uri): ImageInfo = info(ctx.contentResolver.getType(uri), { open(ctx, uri) })

    fun info(mime: String?, openStream: () -> InputStream): ImageInfo {
        val head = openStream().use { ins -> val buf = ByteArray(512); var n = 0; while (n < buf.size) { val r = ins.read(buf, n, buf.size - n); if (r < 0) break; n += r }; buf.copyOf(n) }
        if (looksLikeSvg(mime, head)) {
            val svg = parseSvg(openStream)
            val (w, h) = svgSize(svg)
            return ImageInfo(w, h, "image/svg+xml", ExifInterface.ORIENTATION_NORMAL, isSvg = true)
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream().use { BitmapFactory.decodeStream(it, null, o) }
        var w = o.outWidth
        var h = o.outHeight
        var detected = o.outMimeType ?: mime
        if (w <= 0 || h <= 0) {
            // BitmapFactory gave up (some HEIC / AVIF): ImageDecoder knows more formats.
            val sz = try { decoderSize(openStream) } catch (_: Throwable) { null } ?: throw ToolException("That file isn't a picture Forge can read.")
            w = sz.first; h = sz.second
        }
        val orient = orientationOf(detected, openStream)
        val (uw, uh) = ExifOrientation.uprightSize(w, h, orient)
        return ImageInfo(uw, uh, detected, orient)
    }

    private fun decoderSize(openStream: () -> InputStream): Pair<Int, Int>? {
        val bytes = openStream().use { it.readBytes() }
        var out: Pair<Int, Int>? = null
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { d, i, _ ->
            out = i.size.width to i.size.height
            d.setTargetSampleSize(64)
        }.recycle()
        return out
    }

    private fun orientationOf(mime: String?, openStream: () -> InputStream): Int {
        val m = mime?.lowercase().orEmpty()
        // HEIF decoders already apply the container's rotation; only JPEG / PNG / WebP carry a separate EXIF orientation.
        if (!(m.contains("jpeg") || m.contains("jpg") || m.contains("png") || m.contains("webp"))) return ExifInterface.ORIENTATION_NORMAL
        return try {
            openStream().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        } catch (_: Throwable) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    /** Decodes [uri] upright, scaled down so no side exceeds [maxDim] and the area stays within [maxPixels]. */
    fun decode(ctx: Context, uri: Uri, maxDim: Int = Int.MAX_VALUE, maxPixels: Long = MAX_PIXELS): Bitmap {
        return decode(ctx.contentResolver.getType(uri), { open(ctx, uri) }, maxDim, maxPixels)
    }

    fun decode(mime: String?, openStream: () -> InputStream, maxDim: Int = Int.MAX_VALUE, maxPixels: Long = MAX_PIXELS): Bitmap {
        try {
            val info = info(mime, openStream)
            val scale = scaleFor(info.width, info.height, maxDim, maxPixels)
            val tw = max(1, (info.width * scale).roundToInt())
            val th = max(1, (info.height * scale).roundToInt())
            if (info.isSvg) return renderSvg(parseSvg(openStream), tw, th)
            return decodeRaster(openStream, info, scale, tw, th)
        } catch (e: OutOfMemoryError) {
            throw ToolException("That picture is too large to process on this device. Try resizing it first.")
        }
    }

    /** Scale (<= 1) that satisfies both the longest-side and the pixel budget. */
    fun scaleFor(w: Int, h: Int, maxDim: Int, maxPixels: Long): Double {
        var s = 1.0
        if (max(w, h) > maxDim) s = min(s, maxDim.toDouble() / max(w, h))
        if (w.toLong() * h > maxPixels) s = min(s, Math.sqrt(maxPixels.toDouble() / (w.toDouble() * h)))
        return s
    }

    private fun decodeRaster(openStream: () -> InputStream, info: ImageInfo, scale: Double, tw: Int, th: Int): Bitmap {
        val (rw, rh) = if (ExifOrientation.swapsAxes(info.orientation)) info.height to info.width else info.width to info.height // stored size
        var sample = 1
        while (min(rw, rh) / (sample * 2) >= 1 && max(rw, rh) / (sample * 2.0) >= max(tw, th)) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var bmp = openStream().use { BitmapFactory.decodeStream(it, null, opts) }
        if (bmp == null) {
            val bytes = openStream().use { it.readBytes() }
            bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { d, _, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                if (sample > 1) d.setTargetSampleSize(sample)
            }
            // ImageDecoder already applied orientation.
            return fit(bmp, tw, th)
        }
        bmp = ExifOrientation.apply(bmp, info.orientation)
        return fit(bmp, tw, th)
    }

    private fun fit(bmp: Bitmap, tw: Int, th: Int): Bitmap {
        val b = if (bmp.config == Bitmap.Config.ARGB_8888 && bmp.isMutable.not()) bmp else bmp.copy(Bitmap.Config.ARGB_8888, true).also { if (it !== bmp) bmp.recycle() }
        if (b.width == tw && b.height == th) return b
        // Never upscale here: a sampled decode can only be a little too big.
        if (b.width <= tw && b.height <= th) return b
        val s = Bitmap.createScaledBitmap(b, tw, th, true)
        if (s !== b) b.recycle()
        return s
    }

    // ---- SVG -------------------------------------------------------------------------------------------------

    fun looksLikeSvg(mime: String?, head: ByteArray): Boolean {
        if (mime?.contains("svg", ignoreCase = true) == true) return true
        val s = String(head, Charsets.UTF_8)
        return s.contains("<svg", ignoreCase = true)
    }

    private fun parseSvg(openStream: () -> InputStream): SVG = try {
        openStream().use { SVG.getFromInputStream(it) }
    } catch (e: Exception) {
        throw ToolException("That SVG can't be read: ${e.message ?: "invalid file"}.")
    }

    private fun svgSize(svg: SVG): Pair<Int, Int> {
        val w = svg.documentWidth
        val h = svg.documentHeight
        if (w > 0 && h > 0) return w.roundToInt().coerceAtLeast(1) to h.roundToInt().coerceAtLeast(1)
        val vb = svg.documentViewBox
        if (vb != null && vb.width() > 0 && vb.height() > 0) return vb.width().roundToInt() to vb.height().roundToInt()
        return 512 to 512
    }

    fun renderSvg(svg: SVG, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        svg.setDocumentWidth(w.toFloat()); svg.setDocumentHeight(h.toFloat())
        svg.renderToCanvas(Canvas(bmp))
        return bmp
    }

    /** Renders an SVG so its longest side is [target] px (default 1024), keeping its aspect ratio. */
    fun renderSvgBytes(bytes: ByteArray, target: Int = 1024): Bitmap {
        val svg = parseSvg { ByteArrayInputStream(bytes) }
        val (w, h) = svgSize(svg)
        val s = target.toDouble() / max(w, h)
        return renderSvg(svg, max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()))
    }
}

/** Plain bitmap helpers shared by the image tools. */
object BitmapOps {
    /** Mirror then rotate clockwise, as [ViewTransform] describes. Returns [src] itself when nothing changes. */
    fun transform(src: Bitmap, t: ViewTransform): Bitmap {
        if (t.normalizedRotation == 0 && !t.flipH) return src
        val m = android.graphics.Matrix()
        if (t.flipH) m.postScale(-1f, 1f)
        m.postRotate(t.normalizedRotation.toFloat())
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun crop(src: Bitmap, rect: IntArray): Bitmap {
        if (rect[0] == 0 && rect[1] == 0 && rect[2] == src.width && rect[3] == src.height) return src
        return Bitmap.createBitmap(src, rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1])
    }

    /** Smooth resize: big reductions go in halving steps so thin details do not alias. */
    fun resize(src: Bitmap, w: Int, h: Int): Bitmap {
        if (src.width == w && src.height == h) return src
        var cur = src
        while (cur.width / 2 >= w && cur.height / 2 >= h) {
            val next = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
            if (cur !== src) cur.recycle()
            cur = next
        }
        val out = Bitmap.createScaledBitmap(cur, w, h, true)
        if (cur !== src && cur !== out) cur.recycle()
        return out
    }

    fun argbPixels(bmp: Bitmap, maxSide: Int = 160): IntArray {
        val s = minOf(1.0, maxSide.toDouble() / maxOf(bmp.width, bmp.height))
        val small = if (s < 1.0) Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * s).toInt()), maxOf(1, (bmp.height * s).toInt()), true) else bmp
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        if (small !== bmp) small.recycle()
        return px
    }
}
