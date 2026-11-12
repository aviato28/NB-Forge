package com.namanbansal.forge.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.image.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImagePixelsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun decode(b: ByteArray) = BitmapFactory.decodeByteArray(b, 0, b.size)

    @Test fun lanczosOutputSizeAndSharpness() {
        val src = ImageFixtures.photo(60, 40)
        val x2 = Lanczos.resize(src, 120, 80)
        assertEquals(120 to 80, x2.width to x2.height)
        assertEquals(240 to 160, Lanczos.outputSize(120, 80, 2))
        val x4 = Lanczos.resize(src, 240, 160)
        assertEquals(240 to 160, x4.width to x4.height)
        // A flat colour stays flat (weights are normalised).
        val flat = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(10, 200, 90)) }
        assertEquals(Color.rgb(10, 200, 90), Lanczos.resize(flat, 40, 40).getPixel(17, 23))
        assertThrows(com.namanbansal.forge.core.ToolException::class.java) { Lanczos.resize(src, 20_000, 20_000) }
        // Unsharp mask raises edge contrast.
        val edge = Bitmap.createBitmap(40, 10, Bitmap.Config.ARGB_8888).apply { for (x in 0 until 40) for (y in 0 until 10) setPixel(x, y, if (x < 20) Color.rgb(100, 100, 100) else Color.rgb(160, 160, 160)) }
        Lanczos.unsharp(edge, 1.0f, 2)
        assertTrue(Color.red(edge.getPixel(21, 5)) > 160 && Color.red(edge.getPixel(18, 5)) < 100)
    }

    @Test fun blurAndPixelateChangeTheRegionIrreversibly() {
        val src = ImageFixtures.photo(120, 80)
        val a = src.copy(Bitmap.Config.ARGB_8888, true)
        val before = IntArray(40 * 30).also { src.getPixels(it, 0, 40, 20, 20, 40, 30) }
        Blur.blurRegion(a, 20, 20, 60, 50, 12)
        val after = IntArray(40 * 30).also { a.getPixels(it, 0, 40, 20, 20, 40, 30) }
        assertFalse(before.contentEquals(after))
        // outside untouched
        assertEquals(src.getPixel(5, 5), a.getPixel(5, 5)); assertEquals(src.getPixel(100, 70), a.getPixel(100, 70))
        val p = src.copy(Bitmap.Config.ARGB_8888, true)
        Blur.pixelateRegion(p, 20, 20, 60, 50, 10)
        assertEquals(p.getPixel(21, 21), p.getPixel(28, 28)) // one cell = one colour
        val pa = IntArray(40 * 30).also { p.getPixels(it, 0, 40, 20, 20, 40, 30) }
        assertFalse(before.contentEquals(pa))
        assertEquals(src.getPixel(5, 5), p.getPixel(5, 5))
        // Re-blurring a mosaic is not the original: information is gone (distinct colours collapse).
        assertTrue(pa.toSet().size < before.toSet().size / 4)
    }

    @Test fun icoWriterRoundTrips() {
        val out = ByteArrayOutputStream()
        IcoWriter.write(ImageFixtures.photo(100, 60), listOf(16, 32, 256), out)
        val entries = IcoWriter.parse(out.toByteArray())
        assertEquals(listOf(16, 32, 256), entries.map { it.width })
        entries.forEach { e ->
            val bmp = decode(e.bytes)
            assertEquals(e.width, bmp.width); assertEquals(e.height, bmp.height)
        }
        val head = out.toByteArray()
        assertEquals(0, head[0].toInt()); assertEquals(1, head[2].toInt()); assertEquals(3, head[4].toInt())
        assertEquals(16, head[6].toInt()); assertEquals(0, head[6 + 32].toInt()) // 256 is stored as 0
    }

    @Test fun everyFormatRoundTripsDimensions() {
        val src = ImageFixtures.photo(90, 60)
        for (f in listOf(ImgFormat.JPEG, ImgFormat.PNG, ImgFormat.WEBP_LOSSY, ImgFormat.WEBP_LOSSLESS)) {
            val bytes = ImageCodec.encodeToBytes(src, f, 80)
            val back = decode(bytes)
            assertEquals(f.name, 90 to 60, back.width to back.height)
            if (!f.lossy) assertEquals(f.name, src.getPixel(30, 30), back.getPixel(30, 30))
        }
        assertEquals("image/webp", Base64Image.sniffMime(ImageCodec.encodeToBytes(src, ImgFormat.WEBP_LOSSY, 80)))
        assertEquals("image/png", Base64Image.sniffMime(ImageCodec.encodeToBytes(src, ImgFormat.PNG, 80)))
        assertEquals("image/jpeg", Base64Image.sniffMime(ImageCodec.encodeToBytes(src, ImgFormat.JPEG, 80)))
        assertEquals("image/x-icon", Base64Image.sniffMime(ImageCodec.encodeToBytes(src, ImgFormat.ICO, 80)))
        assertFalse(ImgFormat.available().contains(ImgFormat.AVIF) && !ImageCodec.avifEncodeSupported())
        // JPEG flattens transparency to white, not black
        val clear = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val jb = decode(ImageCodec.encodeToBytes(clear, ImgFormat.JPEG, 90))
        assertTrue(Color.red(jb.getPixel(4, 4)) > 240)
    }

    @Test fun qualityChangesSize() {
        val src = ImageFixtures.photo(300, 200)
        assertTrue(ImageCodec.encodedSize(src, ImgFormat.JPEG, 20) < ImageCodec.encodedSize(src, ImgFormat.JPEG, 95))
        val r = TargetSize.search(6_000) { ImageCodec.encodedSize(src, ImgFormat.JPEG, it) }
        assertTrue(r.size <= 6_000 || !r.fits)
    }

    @Test fun svgRendersToBitmap() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="40" height="20" viewBox="0 0 40 20"><rect width="40" height="20" fill="#ff0000"/></svg>"""
        val bmp = ImageIO.renderSvgBytes(svg.toByteArray(), 80)
        assertEquals(80 to 40, bmp.width to bmp.height)
        assertEquals(Color.RED, bmp.getPixel(40, 20))
        val info = ImageIO.info("image/svg+xml") { ByteArrayInputStream(svg.toByteArray()) }
        assertTrue(info.isSvg); assertEquals(40 to 20, info.width to info.height)
    }

    @Test fun decodeHonoursExifOrientationAndBudget() {
        val bytes = ImageFixtures.jpegWithExif(tmp.root, ExifInterface.ORIENTATION_ROTATE_90) // stored 200x120
        val info = ImageIO.info("image/jpeg") { ByteArrayInputStream(bytes) }
        assertEquals(120 to 200, info.width to info.height)
        val bmp = ImageIO.decode("image/jpeg", { ByteArrayInputStream(bytes) })
        assertEquals(120 to 200, bmp.width to bmp.height)
        val small = ImageIO.decode("image/jpeg", { ByteArrayInputStream(bytes) }, maxDim = 100)
        assertTrue(maxOf(small.width, small.height) <= 100)
        assertEquals(1.0, ImageIO.scaleFor(100, 100, 1000, 50_000_000L), 0.0)
        assertEquals(0.5, ImageIO.scaleFor(8000, 4000, 4000, 50_000_000L), 0.001)
        assertTrue(ImageIO.scaleFor(10_000, 10_000, 100_000, 50_000_000L) < 0.8) // 100 MP is brought under the 50 MP budget
    }

    @Test fun jpegStripAllIsLosslessApartFromSegments() {
        val src = ImageFixtures.jpegWithExif(tmp.root)
        assertTrue(MetadataStripper.hasLocation(MetadataStripper.read(src)))
        val out = MetadataStripper.strip(src, StripMode.ALL, tmp.root)
        val items = MetadataStripper.read(out)
        assertFalse(MetadataStripper.hasLocation(items))
        assertTrue(items.none { it.group == "Camera" || it.group == "Date" })
        val e = ExifInterface(ByteArrayInputStream(out))
        assertNull(e.getAttribute(ExifInterface.TAG_GPS_LATITUDE)); assertNull(e.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals(6, e.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) // still upright
        assertEquals(6, MetadataStripper.jpegOrientation(out))
        // The scan data (from SOS to the end) is byte-identical: nothing was re-encoded.
        fun scan(b: ByteArray): ByteArray { var i = 2; while (!(b[i] == 0xFF.toByte() && b[i + 1] == 0xDA.toByte())) i += if (b[i + 1].toInt() and 0xFF in 0xD0..0xD7) 2 else 2 + (((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)); return b.copyOfRange(i, b.size) }
        assertArrayEquals(scan(src), scan(out))
        assertTrue(out.size < src.size)
        val pix = decode(out); assertEquals(200 to 120, pix.width to pix.height)
    }

    @Test fun jpegStripLocationKeepsCameraInfo() {
        val src = ImageFixtures.jpegWithExif(tmp.root)
        val out = MetadataStripper.strip(src, StripMode.LOCATION, tmp.root)
        val items = MetadataStripper.read(out)
        assertFalse(MetadataStripper.hasLocation(items))
        assertTrue(items.any { it.label == "Make" && it.value == "ForgeCam" })
        assertNotNull(decode(out))
    }

    @Test fun jpegWithoutExifIsUnchangedExceptNothing() {
        val plain = ImageFixtures.jpeg()
        val out = MetadataStripper.strip(plain, StripMode.ALL, tmp.root)
        assertNotNull(decode(out))
        assertTrue(MetadataStripper.read(out).toString(), MetadataStripper.read(out).isEmpty())
        assertThrows(com.namanbansal.forge.core.ToolException::class.java) { MetadataStripper.strip(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13), StripMode.ALL, tmp.root) }
    }

    @Test fun pngChunksAreRemoved() {
        val png = ImageCodec.encodeToBytes(ImageFixtures.photo(20, 20), ImgFormat.PNG, 100)
        // inject a tEXt and an eXIf chunk before IEND
        fun chunk(type: String, data: ByteArray): ByteArray {
            val bo = ByteArrayOutputStream()
            val crc = java.util.zip.CRC32().also { it.update(type.toByteArray()); it.update(data) }.value
            bo.write(java.nio.ByteBuffer.allocate(4).putInt(data.size).array()); bo.write(type.toByteArray()); bo.write(data)
            bo.write(java.nio.ByteBuffer.allocate(4).putInt(crc.toInt()).array()); return bo.toByteArray()
        }
        val iend = png.size - 12
        val dirty = png.copyOfRange(0, iend) + chunk("tEXt", "Comment\u0000secret".toByteArray()) + chunk("eXIf", byteArrayOf(0x4D, 0x4D, 0, 42, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0)) + png.copyOfRange(iend, png.size)
        val clean = MetadataStripper.strip(dirty, StripMode.ALL, tmp.root)
        assertFalse(String(clean, Charsets.ISO_8859_1).contains("secret")); assertFalse(String(clean, Charsets.ISO_8859_1).contains("eXIf"))
        assertArrayEquals(png, clean) // exactly the original encode again
        val locOnly = MetadataStripper.strip(dirty, StripMode.LOCATION, tmp.root)
        assertFalse(String(locOnly, Charsets.ISO_8859_1).contains("eXIf")); assertTrue(String(locOnly, Charsets.ISO_8859_1).contains("secret"))
    }

    @Test fun webpExifChunkIsRemoved() {
        val webp = ImageCodec.encodeToBytes(ImageFixtures.photo(20, 20), ImgFormat.WEBP_LOSSLESS, 100)
        val exif = "EXIF".toByteArray() + byteArrayOf(4, 0, 0, 0, 1, 2, 3, 4)
        val body = webp.copyOfRange(12, webp.size) + exif
        val total = body.size + 4
        val dirty = "RIFF".toByteArray() + byteArrayOf(total.toByte(), (total shr 8).toByte(), (total shr 16).toByte(), 0) + "WEBP".toByteArray() + body
        val clean = MetadataStripper.strip(dirty, StripMode.ALL, tmp.root)
        assertFalse(String(clean, Charsets.ISO_8859_1).contains("EXIF"))
        assertNotNull(decode(clean)); assertEquals(20, decode(clean).width)
    }
}
