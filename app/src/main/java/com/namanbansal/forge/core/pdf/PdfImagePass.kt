package com.namanbansal.forge.core.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import com.tom_roush.pdfbox.contentstream.PDFStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.DrawObject
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.contentstream.operator.state.Concatenate
import com.tom_roush.pdfbox.contentstream.operator.state.Restore
import com.tom_roush.pdfbox.contentstream.operator.state.Save
import com.tom_roush.pdfbox.contentstream.operator.state.SetGraphicsStateParameters
import com.tom_roush.pdfbox.contentstream.operator.state.SetMatrix
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.IdentityHashMap

/** How a pass over the document's pictures behaves. [targetDpi] null = no resampling. */
class ImageOptions(
    val targetDpi: Int? = null,
    val quality: Int = 75,
    val gray: Boolean = false,
    /** Keep the original picture when the new encoding is not smaller (Compress). Grayscale must replace regardless. */
    val onlyIfSmaller: Boolean = true,
    /** Also turn lossless (Flate) photos into JPEG, not only downsample. */
    val reencodeLossless: Boolean = true,
)

/** What a pass did, for the result detail. */
class ImageStats {
    var images = 0; var recompressed = 0; var skipped = 0; var deduped = 0
    var bytesBefore = 0L; var bytesAfter = 0L
}

/** A picture's size as painted on the page, in PDF points: the largest placement found. */
private class Placement(var w: Float, var h: Float)

/** Finds how big (in points) each picture is painted, by running the page's content stream through PDFBox's engine. */
private class PlacementScanner : PDFStreamEngine() {
    val found = IdentityHashMap<COSStream, Placement>()

    init {
        addOperator(Concatenate()); addOperator(DrawObject()); addOperator(SetGraphicsStateParameters())
        addOperator(Save()); addOperator(Restore()); addOperator(SetMatrix())
    }

    override fun processOperator(operator: Operator, operands: List<COSBase>) {
        if (operator.name == "Do" && operands.isNotEmpty() && operands[0] is COSName) {
            val xo = runCatching { resources.getXObject(operands[0] as COSName) }.getOrNull()
            if (xo is PDImageXObject) {
                val m = graphicsState.currentTransformationMatrix
                val w = m.scalingFactorX; val h = m.scalingFactorY
                val p = found.getOrPut(xo.cosObject) { Placement(0f, 0f) }
                if (w * h > p.w * p.h) { p.w = w; p.h = h }
                return
            }
        }
        super.processOperator(operator, operands)
    }
}

/** Downsample / re-encode / gray-convert the pictures of a document, deduplicating identical ones. Used by Compress and Grayscale. */
object PdfImagePass {

    private val GRAY_FILTER = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )))
    }

    class Encoded(val bytes: ByteArray, val width: Int, val height: Int)

    /** Every distinct picture of the document as (image, painted size in points or null). */
    fun allImages(doc: PDDocument, pages: Set<Int>? = null, withPlacement: Boolean = false): List<Pair<PDImageXObject, Pair<Float, Float>?>> {
        val out = ArrayList<Pair<PDImageXObject, Pair<Float, Float>?>>()
        val seen = IdentityHashMap<COSStream, Boolean>()
        val forms = IdentityHashMap<COSStream, Boolean>()
        val scanner = if (withPlacement) PlacementScanner() else null
        fun walk(res: PDResources?) {
            res ?: return
            for (name in res.xObjectNames) {
                val xo = runCatching { res.getXObject(name) }.getOrNull()
                if (xo is PDImageXObject) { if (seen.put(xo.cosObject, true) == null) out += xo to null }
                else if (xo is PDFormXObject && forms.put(xo.cosObject, true) == null) walk(xo.resources)
            }
        }
        for ((i, page) in doc.pages.withIndex()) {
            if (pages != null && i !in pages) continue
            scanner?.let { runCatching { it.processPage(page) } }
            walk(page.resources)
        }
        if (scanner == null) return out
        return out.map { (img, _) -> img to scanner.found[img.cosObject]?.let { it.w to it.h } }
    }

    /**
     * Decodes [img] (JPEGs are decoded at a reduced size straight from the file), resamples to [scale] and, when asked, turns
     * it gray; returns JPEG bytes or null when the picture can't be handled (masks, huge or undecodable data).
     */
    fun encode(img: PDImageXObject, scale: Float, gray: Boolean, quality: Int): Encoded? {
        if (img.isStencil || img.cosObject.containsKey(COSName.MASK)) return null
        val w = img.width; val h = img.height
        if (w <= 0 || h <= 0) return null
        val tw = (w * scale).toInt().coerceAtLeast(1); val th = (h * scale).toInt().coerceAtLeast(1)
        var src: Bitmap? = null
        try {
            val dct = img.cosObject.filters.let { f -> f is com.tom_roush.pdfbox.cos.COSName && f == COSName.DCT_DECODE || (f is com.tom_roush.pdfbox.cos.COSArray && f.size() == 1 && f.get(0) == COSName.DCT_DECODE) }
            if (dct) {
                val raw = img.cosObject.createRawInputStream().use { it.readBytes() }
                val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(w, h, tw, th); inPreferredConfig = Bitmap.Config.ARGB_8888 }
                src = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
            }
            if (src == null) {
                if (w.toLong() * h > MAX_DECODE_PIXELS && scale >= 1f) return null
                src = if (img.cosObject.containsKey(COSName.SMASK)) runCatching { img.opaqueImage }.getOrNull() ?: img.image else img.image
            }
            if (src == null) return null
            val dst = if (gray || src.width != tw || src.height != th) {
                val b = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                val c = Canvas(b)
                c.drawColor(android.graphics.Color.WHITE)
                c.drawBitmap(src, Rect(0, 0, src.width, src.height), Rect(0, 0, tw, th), if (gray) GRAY_FILTER else Paint(Paint.FILTER_BITMAP_FLAG))
                b
            } else src
            val bos = ByteArrayOutputStream()
            dst.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(5, 100), bos)
            if (dst !== src) dst.recycle()
            return Encoded(bos.toByteArray(), tw, th)
        } catch (_: OutOfMemoryError) {
            return null
        } catch (_: Exception) {
            return null
        } finally {
            src?.recycle()
        }
    }

    private fun sampleSize(w: Int, h: Int, tw: Int, th: Int): Int {
        var s = 1
        while (w / (s * 2) >= tw && h / (s * 2) >= th) s *= 2
        return s
    }

    private fun isGray(img: PDImageXObject): Boolean = runCatching {
        val cs = img.colorSpace
        cs === PDDeviceGray.INSTANCE || cs.numberOfComponents == 1 && cs.name != "Indexed"
    }.getOrDefault(false)

    private fun isDct(img: PDImageXObject): Boolean = img.suffix == "jpg"

    private fun sha1(s: COSStream, tag: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(tag.toByteArray())
        s.createRawInputStream().use { i -> val buf = ByteArray(32 * 1024); while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Runs the pass. [pages] limits it (the live preview only needs one page). Returns what it did.
     * Pictures shared by many pages are handled once; pictures whose bytes are identical are merged into one object.
     */
    fun run(doc: PDDocument, opts: ImageOptions, ctx: TransformCtx = TransformCtx({}, {}), pages: Set<Int>? = null): ImageStats {
        val stats = ImageStats()
        val all = allImages(doc, pages, withPlacement = opts.targetDpi != null)
        val done = IdentityHashMap<COSStream, PDImageXObject>()
        val byHash = HashMap<String, PDImageXObject>()
        val total = all.size.coerceAtLeast(1)
        for ((idx, entry) in all.withIndex()) {
            ctx.checkCancelled()
            val (img, placed) = entry
            stats.images++
            val before = img.cosObject.length
            var result: PDImageXObject = img
            val scale = scaleFor(img, placed, opts)
            val needGray = opts.gray && !isGray(img)
            val wantRecode = scale < 1f || needGray || (opts.reencodeLossless && !isDct(img) && img.bitsPerComponent >= 8 && img.width.toLong() * img.height >= 40_000)
            if (wantRecode) {
                val enc = encode(img, scale, needGray, opts.quality)
                if (enc != null && (!opts.onlyIfSmaller || needGray || enc.bytes.size < before)) {
                    val fresh = JPEGFactory.createFromByteArray(doc, enc.bytes)
                    img.cosObject.getDictionaryObject(COSName.SMASK)?.let { fresh.cosObject.setItem(COSName.SMASK, it) }
                    if (img.interpolate) fresh.interpolate = true
                    result = fresh; stats.recompressed++
                } else stats.skipped++
            } else stats.skipped++
            // Merge identical pictures (soft masks are compared too; pictures with explicit /Mask are never merged).
            if (!result.cosObject.containsKey(COSName.MASK)) {
                val smask = result.cosObject.getDictionaryObject(COSName.SMASK) as? COSStream
                val smaskTag = smask?.let { sha1(it, "sm") } ?: ""
                val key = sha1(result.cosObject, "$smaskTag/${result.width}x${result.height}/${result.bitsPerComponent}/${runCatching { result.colorSpace.name }.getOrNull()}/${result.cosObject.filters}/${result.cosObject.getDictionaryObject(COSName.DECODE)}")
                val canonical = byHash[key]
                if (canonical != null && canonical !== result) { result = canonical; stats.deduped++ } else byHash[key] = result
            }
            done[img.cosObject] = result
            stats.bytesBefore += before
            stats.bytesAfter += if (result === img) before else result.cosObject.length
            if (idx % 4 == 0) ctx.onProgress((idx + 1).toFloat() / total * 0.9f)
        }
        // Point every place that uses a picture at its replacement.
        val forms = IdentityHashMap<COSStream, Boolean>()
        fun patch(res: PDResources?) {
            res ?: return
            for (name in res.xObjectNames.toList()) {
                val xo = runCatching { res.getXObject(name) }.getOrNull()
                if (xo is PDImageXObject) { val r = done[xo.cosObject]; if (r != null && r.cosObject !== xo.cosObject) res.put(name, r) }
                else if (xo is PDFormXObject && forms.put(xo.cosObject, true) == null) patch(xo.resources)
            }
        }
        for ((i, page) in doc.pages.withIndex()) if (pages == null || i in pages) patch(page.resources)
        return stats
    }

    private fun scaleFor(img: PDImageXObject, placed: Pair<Float, Float>?, opts: ImageOptions): Float {
        val target = opts.targetDpi ?: return 1f
        val (pw, ph) = placed ?: return 1f
        if (pw < 1f || ph < 1f) return 1f
        val dpi = minOf(img.width / (pw / 72f), img.height / (ph / 72f))
        if (dpi <= target * 1.15f) return 1f // already at or below the target: leave it
        return (target / dpi).coerceIn(0.05f, 1f)
    }

    const val MAX_DECODE_PIXELS = 48_000_000L
}
