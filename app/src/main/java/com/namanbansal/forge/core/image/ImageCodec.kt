package com.namanbansal.forge.core.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.namanbansal.forge.core.ToolException
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** Output formats the image tools can write. */
enum class ImgFormat(val label: String, val ext: String, val mime: String, val lossy: Boolean, val alpha: Boolean) {
    JPEG("JPG", "jpg", "image/jpeg", true, false),
    PNG("PNG", "png", "image/png", false, true),
    WEBP_LOSSY("WebP (lossy)", "webp", "image/webp", true, true),
    WEBP_LOSSLESS("WebP (lossless)", "webp", "image/webp", false, true),
    AVIF("AVIF", "avif", "image/avif", true, true),
    ICO("ICO", "ico", "image/x-icon", false, true);

    companion object {
        /** Formats offered for output on this device (AVIF only when the platform can encode it). */
        fun available(): List<ImgFormat> = entries.filter { it != AVIF || ImageCodec.avifEncodeSupported() }

        fun fromMime(m: String?): ImgFormat? = when (m?.lowercase()) {
            "image/jpeg", "image/jpg" -> JPEG
            "image/png" -> PNG
            "image/webp" -> WEBP_LOSSY
            "image/avif" -> AVIF
            "image/x-icon", "image/vnd.microsoft.icon" -> ICO
            else -> null
        }
    }
}

object ImageCodec {
    /** Android has no AVIF encoder in `Bitmap.CompressFormat` (as of API 37); this checks by name so a future platform lights it up. */
    fun avifEncodeSupported(): Boolean = avifFormat() != null

    private fun avifFormat(): Bitmap.CompressFormat? =
        Bitmap.CompressFormat.entries.firstOrNull { it.name == "AVIF" }

    /** Writes [bmp] as [fmt]; [quality] 1..100 applies to lossy formats. Alpha is flattened onto white for JPEG. */
    fun encode(bmp: Bitmap, fmt: ImgFormat, quality: Int, out: OutputStream) {
        val q = quality.coerceIn(1, 100)
        when (fmt) {
            ImgFormat.JPEG -> check(flatten(bmp).compress(Bitmap.CompressFormat.JPEG, q, out))
            ImgFormat.PNG -> check(bmp.compress(Bitmap.CompressFormat.PNG, 100, out))
            ImgFormat.WEBP_LOSSY -> check(bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, q, out))
            ImgFormat.WEBP_LOSSLESS -> check(bmp.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out))
            ImgFormat.AVIF -> {
                val f = avifFormat() ?: throw ToolException("This device can't write AVIF.")
                check(bmp.compress(f, q, out))
            }
            ImgFormat.ICO -> IcoWriter.write(bmp, IcoWriter.DEFAULT_SIZES, out)
        }
    }

    private fun check(ok: Boolean) { if (!ok) throw ToolException("Couldn't encode the picture.") }

    fun encodeToBytes(bmp: Bitmap, fmt: ImgFormat, quality: Int): ByteArray = ByteArrayOutputStream().also { encode(bmp, fmt, quality, it) }.toByteArray()

    /** Size in bytes of [bmp] encoded as [fmt] at [quality] (used by the size estimate and the target-size search). */
    fun encodedSize(bmp: Bitmap, fmt: ImgFormat, quality: Int): Long = CountingSink().also { encode(bmp, fmt, quality, it) }.count

    /** JPEG has no alpha: draw on white so transparent areas do not turn black. */
    fun flatten(bmp: Bitmap, background: Int = Color.WHITE): Bitmap {
        if (!bmp.hasAlpha()) return bmp
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(background); drawBitmap(bmp, 0f, 0f, null) }
        return out
    }

    private class CountingSink : OutputStream() {
        var count = 0L
        override fun write(b: Int) { count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { count += len }
    }
}

/** Writes a multi-size ICO container: ICONDIR + ICONDIRENTRYs + one PNG image per size (valid since Windows Vista). */
object IcoWriter {
    val DEFAULT_SIZES = listOf(16, 32, 48, 64, 128, 256)

    fun write(src: Bitmap, sizes: List<Int>, out: OutputStream) {
        require(sizes.isNotEmpty() && sizes.all { it in 1..256 })
        val pngs = sizes.map { s -> ByteArrayOutputStream().also { squareScaled(src, s).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
        val head = ByteArrayOutputStream()
        fun le16(v: Int) { head.write(v and 0xFF); head.write((v shr 8) and 0xFF) }
        fun le32(v: Int) { le16(v and 0xFFFF); le16((v shr 16) and 0xFFFF) }
        le16(0); le16(1); le16(sizes.size)
        var offset = 6 + 16 * sizes.size
        sizes.forEachIndexed { i, s ->
            head.write(if (s == 256) 0 else s); head.write(if (s == 256) 0 else s) // width, height (0 means 256)
            head.write(0); head.write(0)                                          // palette, reserved
            le16(1); le16(32)                                                     // planes, bits per pixel
            le32(pngs[i].size); le32(offset)
            offset += pngs[i].size
        }
        out.write(head.toByteArray())
        pngs.forEach { out.write(it) }
    }

    /** Fits [src] inside a transparent [s] x [s] square (icons are square). */
    private fun squareScaled(src: Bitmap, s: Int): Bitmap {
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val k = minOf(s.toFloat() / src.width, s.toFloat() / src.height)
        val w = src.width * k
        val h = src.height * k
        val dst = android.graphics.RectF((s - w) / 2f, (s - h) / 2f, (s + w) / 2f, (s + h) / 2f)
        Canvas(out).drawBitmap(src, null, dst, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** Parsed ICONDIR entry (used by tests and by anything that wants to inspect an ICO). */
    data class Entry(val width: Int, val height: Int, val bytes: ByteArray)

    fun parse(data: ByteArray): List<Entry> {
        fun u16(o: Int) = (data[o].toInt() and 0xFF) or ((data[o + 1].toInt() and 0xFF) shl 8)
        fun u32(o: Int) = u16(o) or (u16(o + 2) shl 16)
        require(u16(0) == 0 && u16(2) == 1) { "not an ICO" }
        return (0 until u16(4)).map { i ->
            val e = 6 + 16 * i
            val w = data[e].toInt() and 0xFF
            val h = data[e + 1].toInt() and 0xFF
            val size = u32(e + 8)
            val off = u32(e + 12)
            Entry(if (w == 0) 256 else w, if (h == 0) 256 else h, data.copyOfRange(off, off + size))
        }
    }
}
