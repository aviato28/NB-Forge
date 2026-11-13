package com.namanbansal.forge.core.pdf

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File

/** Compress presets: images are downsampled to [dpi] and re-encoded at [quality]. */
enum class CompressPreset(val label: String, val dpi: Int, val quality: Int, val blurb: String) {
    SCREEN("Screen", 72, 55, "Smallest. Fine on a phone or in email."),
    EBOOK("Ebook", 150, 72, "Balanced. Sharp on tablets and laptops."),
    PRINT("Print", 300, 85, "Largest. Keeps detail for printing."),
    CUSTOM("Custom", 150, 75, "Pick your own resolution and quality."),
}

class CompressOptions(
    val dpi: Int, val quality: Int, val stripMetadata: Boolean = false, val gray: Boolean = false,
) {
    fun images() = ImageOptions(targetDpi = dpi, quality = quality, gray = gray, onlyIfSmaller = true)
}

/** Shrinks a PDF: pictures, content streams, unused objects and (optionally) metadata. */
object PdfCompressor {

    /** Compresses [doc] in place; returns the picture statistics. */
    fun compress(doc: PDDocument, o: CompressOptions, ctx: TransformCtx = TransformCtx({}, {})): ImageStats {
        val stats = PdfImagePass.run(doc, o.images(), TransformCtx(ctx.checkCancelled) { ctx.onProgress(it * 0.8f) })
        ctx.checkCancelled()
        compressStreams(doc)
        if (o.stripMetadata) PdfMetadataEditor.removeAll(doc)
        ctx.onProgress(0.95f)
        return stats // objects nothing refers to any more are simply not written by PDFBox's save
    }

    /** Flate-compresses every stream that is stored raw (content streams, forms, uncompressed fonts, ICC profiles). Returns how many. */
    fun compressStreams(doc: PDDocument): Int {
        var n = 0
        for (obj in doc.document.objects.toList()) {
            val s = obj.`object` as? COSStream ?: continue
            if (s.containsKey(COSName.FILTER)) continue
            val type = s.getNameAsString(COSName.TYPE)
            if (type == "XRef" || type == "ObjStm" || type == "Metadata") continue
            if (s.length < 64) continue
            val raw = s.createRawInputStream().use { it.readBytes() }
            s.createOutputStream(COSName.FLATE_DECODE).use { it.write(raw) }
            n++
        }
        return n
    }

    class Estimate(val originalBytes: Long, val estimatedBytes: Long, val imageBytes: Long, val sampled: Int, val images: Int)

    /**
     * Estimates the output size without compressing everything: sizes of all pictures are summed, up to [maxSamples] (largest first)
     * are really re-encoded, and their saving ratio is applied to the rest. Text-only files estimate close to their current size.
     */
    fun estimate(file: File, originalBytes: Long, o: CompressOptions, maxSamples: Int = 8): Estimate = PdfOps.load(file).use { doc ->
        val all = PdfImagePass.allImages(doc, null, withPlacement = true)
        // Identical pictures (same size and same first 4 KB) will be merged, so only the first of each group counts as work.
        val seen = HashSet<String>()
        val unique = ArrayList<Pair<com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject, Pair<Float, Float>?>>()
        var dupBytes = 0L
        for (e in all) {
            val img = e.first
            val head = runCatching { img.cosObject.createRawInputStream().use { i -> val b = ByteArray(4096); val n = i.read(b); java.util.Arrays.hashCode(b.copyOf(maxOf(n, 0))) } }.getOrDefault(0)
            val key = "${img.cosObject.length}/${img.width}x${img.height}/$head"
            if (seen.add(key)) unique += e
            else dupBytes += img.cosObject.length + ((img.cosObject.getDictionaryObject(COSName.SMASK) as? COSStream)?.length ?: 0L)
        }
        val total = unique.sumOf { it.first.cosObject.length }
        val sample = unique.sortedByDescending { it.first.cosObject.length }.take(maxSamples)
        var before = 0L; var after = 0L
        for ((img, placed) in sample) {
            val len = img.cosObject.length
            before += len
            val scale = run {
                val (pw, ph) = placed ?: return@run 1f
                if (pw < 1f || ph < 1f) return@run 1f
                val dpi = minOf(img.width / (pw / 72f), img.height / (ph / 72f))
                if (dpi <= o.dpi * 1.15f) 1f else (o.dpi / dpi).coerceIn(0.05f, 1f)
            }
            val enc = PdfImagePass.encode(img, scale, o.gray, o.quality)
            after += if (enc != null && enc.bytes.size < len) enc.bytes.size.toLong() else len
        }
        val ratio = if (before > 0) after.toDouble() / before else 1.0
        val saved = (total * (1 - ratio)).toLong() + dupBytes
        Estimate(originalBytes, (originalBytes - saved).coerceAtLeast((originalBytes - total - dupBytes).coerceAtLeast(1024L)), total + dupBytes, sample.size, all.size)
    }
}
