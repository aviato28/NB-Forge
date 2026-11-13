package com.namanbansal.forge.core

import android.graphics.Bitmap
import android.graphics.Color
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.File
import java.util.Random

/** Generated pictures and image-heavy PDFs for tests. */
object ImageFixtures {
    /** Colourful, noisy picture (compresses poorly losslessly, well as JPEG). */
    fun photo(w: Int, h: Int, seed: Long = 1): Bitmap {
        val rnd = Random(seed)
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val n = rnd.nextInt(24)
            px[y * w + x] = Color.rgb(((x * 255 / w) + n).coerceAtMost(255), ((y * 255 / h) + n).coerceAtMost(255), (((x + y) * 255 / (w + h)) + n).coerceAtMost(255))
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * [pages] pages, each with a "Page n" heading, a red rectangle (colour operator) and a [px] x [px] lossless picture painted
     * [sizePt] points wide. The same bitmap on every page, but each page gets its own image object (so dedupe has work to do).
     */
    fun imagePdf(file: File, pages: Int = 3, px: Int = 900, sizePt: Float = 216f, seed: Long = 1): File {
        val bmp = photo(px, px, seed)
        PDDocument().use { doc ->
            repeat(pages) { i ->
                val page = PDPage(PDRectangle.A4); doc.addPage(page)
                val img = LosslessFactory.createFromImage(doc, bmp)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA_BOLD, 24f); cs.newLineAtOffset(50f, 780f); cs.showText("Page ${i + 1}"); cs.endText()
                    cs.setNonStrokingColor(1f, 0f, 0f); cs.addRect(350f, 600f, 150f, 150f); cs.fill()
                    cs.drawImage(img, 50f, 450f, sizePt, sizePt)
                }
            }
            doc.save(file)
        }
        return file
    }
}
