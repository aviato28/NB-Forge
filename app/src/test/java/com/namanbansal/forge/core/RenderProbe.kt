package com.namanbansal.forge.core

import android.graphics.Bitmap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.File

/** Test-only pixel access to rendered pages (needs @GraphicsMode(NATIVE)). */
object RenderProbe {
    fun render(file: File, page: Int = 0, scale: Float = 1f, password: String = ""): Bitmap =
        PDDocument.load(file, password).use { PDFRenderer(it).renderImage(page, scale) }

    /** Colour at fractional page position (0..1 from the top-left). */
    fun pixel(bmp: Bitmap, fx: Float, fy: Float): Int = bmp.getPixel((fx * (bmp.width - 1)).toInt(), (fy * (bmp.height - 1)).toInt())

    fun r(c: Int) = (c shr 16) and 0xFF
    fun g(c: Int) = (c shr 8) and 0xFF
    fun b(c: Int) = c and 0xFF

    /** Mean absolute channel difference between two same-size renders (0 = identical). */
    fun diff(a: Bitmap, b: Bitmap): Double {
        require(a.width == b.width && a.height == b.height)
        var sum = 0L
        for (y in 0 until a.height step 3) for (x in 0 until a.width step 3) {
            val p = a.getPixel(x, y); val q = b.getPixel(x, y)
            sum += Math.abs(r(p) - r(q)) + Math.abs(g(p) - g(q)) + Math.abs(b(p) - b(q))
        }
        return sum.toDouble() / ((a.width / 3 + 1) * (a.height / 3 + 1) * 3)
    }

    fun text(file: File, password: String = ""): String = PDDocument.load(file, password).use { com.tom_roush.pdfbox.text.PDFTextStripper().getText(it) }
}
