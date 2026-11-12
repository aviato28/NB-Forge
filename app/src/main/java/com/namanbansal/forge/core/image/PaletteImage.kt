package com.namanbansal.forge.core.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/** Renders a palette as a shareable PNG: one tall swatch per colour with its HEX code. */
object PaletteImage {
    fun render(swatches: List<PaletteExtractor.Swatch>, width: Int = 1200, height: Int = 420): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val n = swatches.size.coerceAtLeast(1)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); textSize = width / n * 0.15f; textAlign = Paint.Align.CENTER }
        swatches.forEachIndexed { i, s ->
            val l = width * i / n
            val r = width * (i + 1) / n
            paint.color = 0xFF000000.toInt() or s.colour
            c.drawRect(l.toFloat(), 0f, r.toFloat(), height.toFloat(), paint)
            text.color = 0xFF000000.toInt() or ColourFormat.onColour(s.colour)
            c.drawText(ColourFormat.hex(s.colour), (l + r) / 2f, height - height * 0.08f, text)
        }
        return bmp
    }
}
