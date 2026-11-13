package com.namanbansal.forge.core.pdf

import android.graphics.Bitmap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle

enum class CropPages(val label: String) { ALL("All"), ODD("Odd"), EVEN("Even"), CUSTOM("Chosen") }

/** Lossless crop: the page's CropBox is set (nothing is cut out of the file), optionally the MediaBox too. */
object PdfCropper {
    /** Rectangle as fractions of the displayed page, origin top-left. */
    class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
        val isFull get() = l <= 0f && t <= 0f && r >= 1f && b >= 1f
    }

    fun pagesFor(mode: CropPages, count: Int, chosen: Set<Int>): Set<Int> = when (mode) {
        CropPages.ALL -> (0 until count).toSet()
        CropPages.ODD -> (0 until count step 2).toSet() // pages 1, 3, 5...
        CropPages.EVEN -> (1 until count step 2).toSet()
        CropPages.CUSTOM -> chosen.filter { it in 0 until count }.toSet()
    }

    /** @return pages cropped */
    fun apply(doc: PDDocument, pages: Set<Int>, setMediaBox: Boolean, boxFor: (Int) -> Box?, ctx: TransformCtx = TransformCtx({}, {})): Int {
        var n = 0
        for ((i, page) in doc.pages.withIndex()) {
            if (i !in pages) continue
            ctx.checkCancelled()
            val box = boxFor(i) ?: continue
            if (box.isFull) continue
            val target = PageGeometry.fractionsToUser(page, box.l, box.t, box.r, box.b)
            val cur = page.cropBox
            val x0 = maxOf(cur.lowerLeftX, target.lowerLeftX); val y0 = maxOf(cur.lowerLeftY, target.lowerLeftY)
            val x1 = minOf(cur.upperRightX, target.upperRightX); val y1 = minOf(cur.upperRightY, target.upperRightY)
            if (x1 - x0 < 10f || y1 - y0 < 10f) continue
            val rect = PDRectangle(x0, y0, x1 - x0, y1 - y0)
            page.cropBox = rect
            if (setMediaBox) page.mediaBox = rect
            n++
            ctx.onProgress((i + 1).toFloat() / doc.numberOfPages * 0.9f)
        }
        return n
    }

    /** The bounding box of everything that isn't (nearly) white, with a little padding; null if the page is blank. */
    fun detectContent(bmp: Bitmap, threshold: Int = 244, pad: Float = 0.012f): Box? {
        val w = bmp.width; val h = bmp.height
        val row = IntArray(w)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = row[x]
                if (((p shr 16) and 0xFF) < threshold || ((p shr 8) and 0xFF) < threshold || (p and 0xFF) < threshold) {
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                    if (y < minY) minY = y; if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return null
        return Box(
            (minX.toFloat() / w - pad).coerceAtLeast(0f), (minY.toFloat() / h - pad).coerceAtLeast(0f),
            ((maxX + 1).toFloat() / w + pad).coerceAtMost(1f), ((maxY + 1).toFloat() / h + pad).coerceAtMost(1f),
        )
    }
}
