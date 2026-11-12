package com.namanbansal.forge.image

import android.media.ExifInterface
import com.namanbansal.forge.core.image.*
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class ImageMathTest {
    @Test fun percentAndLockedSides() {
        assertEquals(50 to 25, ResizeMath.percent(100, 50, 50.0))
        assertEquals(1 to 1, ResizeMath.percent(10, 10, 1.0))
        assertEquals(600, ResizeMath.lockedHeight(1200, 1800, 400))
                assertEquals(750, ResizeMath.lockedWidth(1000, 800, 600))
    }

    @Test fun presets() {
        val passport = ResizeMath.preset("passport")!!
        assertEquals(413 to 531, passport.width to passport.height) // 35 x 45 mm at 300 dpi
        assertEquals(1080 to 1350, ResizeMath.preset("ig-portrait")!!.let { it.width to it.height })
        assertEquals(3840 to 2160, ResizeMath.preset("4k")!!.let { it.width to it.height })
        // FILL is exact, FIT never upscales
        assertEquals(1080 to 1080, ResizeMath.outputFor(ResizeMath.preset("ig-square")!!, 4000, 3000))
        assertEquals(1920 to 1080, ResizeMath.outputFor(ResizeMath.preset("fhd")!!, 4000, 2250))
        assertEquals(800 to 600, ResizeMath.outputFor(ResizeMath.preset("fhd")!!, 800, 600))
        assertEquals(1080 to 1920, ResizeMath.outputFor(ResizeMath.preset("fhd")!!, 2250, 4000)) // portrait source fits a portrait box
    }

    @Test fun fillCropIsCentredAndMatchesRatio() {
        val r = ResizeMath.fillCrop(4000, 3000, 1080, 1080)
        assertArrayEquals(intArrayOf(500, 0, 3500, 3000), r)
        val p = ResizeMath.fillCrop(3000, 4000, 1080, 1920)
        assertEquals(1080.0 / 1920, (p[2] - p[0]).toDouble() / (p[3] - p[1]), 0.01)
    }

    @Test fun exifOrientationSizes() {
        assertEquals(30 to 40, ExifOrientation.uprightSize(40, 30, ExifInterface.ORIENTATION_ROTATE_90))
        assertEquals(40 to 30, ExifOrientation.uprightSize(40, 30, ExifInterface.ORIENTATION_ROTATE_180))
        assertEquals(30 to 40, ExifOrientation.uprightSize(40, 30, ExifInterface.ORIENTATION_TRANSPOSE))
        assertTrue(ExifOrientation.isIdentity(1)); assertTrue(ExifOrientation.isIdentity(0)); assertFalse(ExifOrientation.isIdentity(6))
    }

    @Test fun cropRectMapsBackThroughRotationAndMirror() {
        // 200 x 100 source; crop the left half of the *displayed* picture.
        val crop = FRect(0f, 0f, 0.5f, 1f)
        assertArrayEquals(intArrayOf(0, 0, 100, 100), CropMath.toSourceRect(crop, ViewTransform(), 200, 100))
        // Rotated right (clockwise): the displayed left half is the source's bottom half.
        assertArrayEquals(intArrayOf(0, 50, 200, 100), CropMath.toSourceRect(crop, ViewTransform(90), 200, 100))
        // Rotated 180: displayed left half = source right half.
        assertArrayEquals(intArrayOf(100, 0, 200, 100), CropMath.toSourceRect(crop, ViewTransform(180), 200, 100))
        // Rotated left (270): displayed left half = source top half.
        assertArrayEquals(intArrayOf(0, 0, 200, 50), CropMath.toSourceRect(crop, ViewTransform(270), 200, 100))
        // Mirrored: displayed left half = source right half.
        assertArrayEquals(intArrayOf(100, 0, 200, 100), CropMath.toSourceRect(crop, ViewTransform(0, flipH = true), 200, 100))
        assertEquals(100 to 200, ViewTransform(90).size(200, 100))
        assertEquals(0, ViewTransform().rotatedRight().rotatedRight().rotatedRight().rotatedRight().normalizedRotation)
    }

    @Test fun cropHandlesRespectBoundsAndRatio() {
        val r = FRect(100f, 100f, 300f, 200f)
        val d = CropMath.drag(r, Handle.R, 1000f, 0f, 400f, 300f, null)
        assertEquals(400f, d.r, 0.01f)
        val tiny = CropMath.drag(r, Handle.L, 1000f, 0f, 400f, 300f, null)
        assertTrue(tiny.w >= CropMath.MIN_SIZE)
        val moved = CropMath.drag(r, Handle.MOVE, -500f, 500f, 400f, 300f, null)
        assertEquals(0f, moved.l, 0.01f); assertEquals(300f, moved.b, 0.01f); assertEquals(200f, moved.w, 0.01f)
        val locked = CropMath.drag(FRect(0f, 0f, 200f, 200f), Handle.BR, 100f, 20f, 800f, 600f, 1f)
        assertEquals(locked.w, locked.h, 0.5f)
        assertEquals(0f, locked.l, 0.01f)
        val sq = CropMath.initial(400f, 300f, 1f)
        assertEquals(300f, sq.w, 0.01f); assertEquals(50f, sq.l, 0.01f)
        assertEquals(Handle.TL, CropMath.hit(r, 100f, 100f, 20f)); assertEquals(Handle.MOVE, CropMath.hit(r, 200f, 150f, 20f)); assertNull(CropMath.hit(r, 10f, 10f, 20f))
    }

    @Test fun targetSizeSearchFindsHighestFittingQuality() {
        val size = { q: Int -> q * 1000L } // monotonic
        val r = TargetSize.search(42_500, sizeAt = size)
        assertEquals(42, r.quality); assertTrue(r.fits)
        assertTrue(r.steps <= 10)
        val tooSmall = TargetSize.search(1_000, sizeAt = size)
        assertFalse(tooSmall.fits); assertEquals(5, tooSmall.quality)
        val roomy = TargetSize.search(10_000_000, sizeAt = size)
        assertEquals(95, roomy.quality); assertEquals(1, roomy.steps)
    }

    @Test fun base64RoundTrip() {
        val bytes = ByteArray(300) { (it * 31).toByte() }.also { it[0] = 0x89.toByte(); it[1] = 0x50; it[2] = 0x4E; it[3] = 0x47 }
        val uri = Base64Image.encode(bytes, "image/png")
        assertTrue(uri.startsWith("data:image/png;base64,"))
        val back = Base64Image.decode(uri)
        assertArrayEquals(bytes, back.bytes); assertEquals("image/png", back.mime)
        // bare, line-wrapped, URL-safe, unpadded
        val bare = uri.substringAfter(',').chunked(60).joinToString("\n").replace('+', '-').replace('/', '_').trimEnd('=')
        assertArrayEquals(bytes, Base64Image.decode(bare).bytes)
        assertThrows(com.namanbansal.forge.core.ToolException::class.java) { Base64Image.decode("not base64 !!") }
        assertThrows(com.namanbansal.forge.core.ToolException::class.java) { Base64Image.decode(Base64Image.encode("hello".toByteArray(), "text/plain")) }
    }

    @Test fun paletteIsDeterministicAndOrdered() {
        val px = IntArray(1000) { i -> when { i < 600 -> 0xFFFF0000.toInt(); i < 900 -> 0xFF0000FF.toInt(); else -> 0x00000000 } } // 100 transparent
        val a = PaletteExtractor.extract(px, 5)
        val b = PaletteExtractor.extract(px, 5)
        assertEquals(a, b)
        assertEquals(0xFF0000, a[0].colour); assertEquals(0x0000FF, a[1].colour)
        assertEquals(2, a.size) // only two distinct colours exist
        assertEquals(0.667f, a[0].share, 0.01f)
        val many = IntArray(4096) { i -> (0xFF shl 24) or ((i * 37) and 0xFFFFFF) }
        assertEquals(8, PaletteExtractor.extract(many, 8).size)
        assertTrue(PaletteExtractor.extract(IntArray(10), 5).isEmpty())
    }

    @Test fun colourFormats() {
        assertEquals("#FF6A1A", ColourFormat.hex(0xFF6A1A))
        assertEquals("rgb(255, 106, 26)", ColourFormat.rgb(0xFF6A1A))
        assertEquals("hsl(0, 0%, 0%)", ColourFormat.hsl(0x000000))
        assertEquals("hsl(120, 100%, 50%)", ColourFormat.hsl(0x00FF00))
        assertEquals("hsl(21, 100%, 55%)", ColourFormat.hsl(0xFF6A1A))
        assertEquals(0x000000, ColourFormat.onColour(0xFFFFFF))
    }

    @Test fun collageLayoutStaysInsideAndDoesNotOverlap() {
        for (n in 2..9) assertTrue("templates for $n", CollageLayout.forCount(n).isNotEmpty())
        CollageLayout.TEMPLATES.forEach { t ->
            assertEquals(t.id, t.count, t.cells.size)
            val cells = CollageLayout.layout(t, 1080, 1080, 24)
            cells.forEach { c -> assertTrue(c.l >= 0 && c.t >= 0 && c.r <= 1080 && c.b <= 1080 && c.w > 0 && c.h > 0) }
            for (i in cells.indices) for (j in i + 1 until cells.size) {
                val a = cells[i]; val b = cells[j]
                assertFalse("${t.id} overlap $i/$j", a.l < b.r - 0.01f && b.l < a.r - 0.01f && a.t < b.b - 0.01f && b.t < a.b - 0.01f)
            }
            // Template cells tile the unit square exactly (before spacing).
            assertEquals(t.id, 1.0, t.cells.sumOf { (it.w * it.h).toDouble() }, 0.001)
        }
        assertEquals(1080 to 1080, CollageLayout.canvasSize(1f, 1080)); assertEquals(864 to 1080, CollageLayout.canvasSize(0.8f, 1080))
        assertEquals(2160 to 1215, CollageLayout.canvasSize(16f / 9, 2160))
    }

    @Test fun cellZoomAndPan() {
        val full = CollageLayout.sourceRect(400, 200, 100f, 100f, CellTransform())
        assertEquals(200f, full.w, 0.01f); assertEquals(200f, full.h, 0.01f); assertEquals(100f, full.l, 0.01f)
        val zoomed = CollageLayout.sourceRect(400, 200, 100f, 100f, CellTransform(2f, 1f, 0f))
        assertEquals(100f, zoomed.w, 0.01f); assertEquals(400f, zoomed.r, 0.5f) // panned fully right
        val clamped = CollageLayout.sourceRect(400, 200, 100f, 100f, CellTransform(1f, 5f, 5f))
        assertTrue(clamped.l >= 0 && clamped.r <= 400)
    }
}
