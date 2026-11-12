package com.namanbansal.forge.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File

/** Test pictures generated in code (no binaries committed). */
object ImageFixtures {
    /** A busy picture: gradient + shapes, so compression behaves like a photo. */
    fun photo(w: Int = 200, h: Int = 120): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint()
        for (x in 0 until w) { p.color = Color.rgb(x * 255 / w, 255 - x * 255 / w, (x * 7) % 255); c.drawRect(x.toFloat(), 0f, x + 1f, h.toFloat(), p) }
        p.color = Color.RED; c.drawCircle(w / 3f, h / 2f, h / 4f, p)
        p.color = Color.BLUE; c.drawRect(w / 2f, h / 4f, w * 0.8f, h * 0.7f, p)
        return b
    }

    fun jpeg(bmp: Bitmap = photo(), q: Int = 90): ByteArray = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()

    /** A JPEG carrying GPS, camera make/model and a date, written through ExifInterface into [dir]. */
    fun jpegWithExif(dir: File, orientation: Int = 6): ByteArray {
        val f = File.createTempFile("fixture", ".jpg", dir)
        f.writeBytes(jpeg())
        val e = ExifInterface(f.absolutePath)
        e.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "51/1,30/1,0/1"); e.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
        e.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "0/1,7/1,0/1"); e.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
        e.setAttribute(ExifInterface.TAG_MAKE, "ForgeCam"); e.setAttribute(ExifInterface.TAG_MODEL, "T-1000")
        e.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:11:01 10:20:30")
        e.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
        e.saveAttributes()
        return f.readBytes().also { f.delete() }
    }

    /** A colourful scene (sky, sun, hills) that reads well in screenshots. */
    fun scene(w: Int = 1200, h: Int = 800): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h * 0.7f, Color.rgb(255, 170, 90), Color.rgb(90, 150, 230), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
        p.color = Color.rgb(255, 235, 150); c.drawCircle(w * 0.72f, h * 0.28f, h * 0.12f, p)
        p.color = Color.rgb(60, 110, 80); c.drawOval(-w * 0.2f, h * 0.55f, w * 0.7f, h * 1.4f, p)
        p.color = Color.rgb(35, 70, 60); c.drawOval(w * 0.3f, h * 0.65f, w * 1.3f, h * 1.5f, p)
        p.color = Color.rgb(220, 60, 60); c.drawRect(w * 0.18f, h * 0.45f, w * 0.30f, h * 0.62f, p)
        return b
    }

    fun write(dir: File, name: String, bmp: Bitmap = scene(), q: Int = 90): File = File(dir, name).also { it.writeBytes(jpeg(bmp, q)) }
}
