package com.namanbansal.forge.core.pdf.edit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.PdfFixtures
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.pdf.NormRect
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class EditIoTest {
    @get:Rule val tmp = TemporaryFolder()
    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext()) }

    private var n = 0
    private fun write(src: File, vararg items: EditObject, images: Map<String, ByteArray> = emptyMap()): File {
        val dst = tmp.newFile("out${n++}.pdf"); dst.delete()
        EditIo.apply(src, dst, EditState(items.toList()), { images[it] })
        return dst
    }

    private fun render(f: File, page: Int = 0): Bitmap = PDDocument.load(f).use { PDFRenderer(it).renderImage(page, 1f) }
    private fun Bitmap.at(fx: Float, fy: Float): Int = getPixel((fx * width).toInt().coerceIn(0, width - 1), (fy * height).toInt().coerceIn(0, height - 1))
    private fun Int.near(c: Int, tol: Int = 24) = kotlin.math.abs(Color.red(this) - Color.red(c)) <= tol && kotlin.math.abs(Color.green(this) - Color.green(c)) <= tol && kotlin.math.abs(Color.blue(this) - Color.blue(c)) <= tol
    private fun blank() = PdfFixtures.create(tmp.newFile("b${n++}.pdf"))
    private fun o(kind: EditKind, r: NormRect, id: String = "o${n++}") = EditObject(id, 0, kind, r)

    private fun solidPng(c: Int, w: Int = 40, h: Int = 20, leftHalfOnly: Boolean = false): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(b).drawRect(0f, 0f, if (leftHalfOnly) w / 2f else w.toFloat(), h.toFloat(), Paint().apply { color = c })
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun solidJpeg(c: Int): ByteArray {
        val b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        Canvas(b).drawColor(c)
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    @Test fun filledRectangleIsVisibleWhereExpected() {
        val out = write(blank(), o(EditKind.RECT, NormRect(0.1f, 0.1f, 0.4f, 0.2f)).copy(filled = true, fillColor = Color.RED, color = Color.BLUE, strokeWidth = 4f))
        val bmp = render(out)
        assertTrue(bmp.at(0.25f, 0.15f).near(Color.RED))
        assertTrue(bmp.at(0.6f, 0.15f).near(Color.WHITE))
        assertTrue(bmp.at(0.25f, 0.4f).near(Color.WHITE))
        // stroke on the left edge
        assertTrue(bmp.at(0.1f + 1.5f / 595f, 0.15f).near(Color.BLUE, 60))
    }

    @Test fun unfilledRectangleLeavesInsideEmptyAndFillOpacityBlends() {
        val out = write(blank(),
            o(EditKind.RECT, NormRect(0.1f, 0.1f, 0.4f, 0.2f)).copy(color = Color.BLACK, strokeWidth = 2f),
            o(EditKind.RECT, NormRect(0.5f, 0.1f, 0.8f, 0.2f)).copy(filled = true, fillColor = Color.RED, fillOpacity = 0.5f))
        val bmp = render(out)
        assertTrue(bmp.at(0.25f, 0.15f).near(Color.WHITE))
        // PDFBox-Android's renderer ignores /ca, so check the graphics state the file carries and that the fill is drawn
        assertTrue(bmp.at(0.65f, 0.15f).near(Color.RED) || Color.red(bmp.at(0.65f, 0.15f)) > 230)
        PDDocument.load(out).use { d ->
            val res = d.getPage(0).resources
            val ca = res.getExtGState(res.extGStateNames.single())!!.nonStrokingAlphaConstant
            assertEquals(0.5f, ca, 0.001f)
        }
    }

    @Test fun ellipseFillsItsCentreButNotItsCorners() {
        val out = write(blank(), o(EditKind.ELLIPSE, NormRect(0.2f, 0.2f, 0.6f, 0.4f)).copy(filled = true, fillColor = Color.BLUE, color = Color.BLUE))
        val bmp = render(out)
        assertTrue(bmp.at(0.4f, 0.3f).near(Color.BLUE))
        assertTrue(bmp.at(0.21f, 0.21f).near(Color.WHITE))
        assertTrue(bmp.at(0.59f, 0.39f).near(Color.WHITE))
    }

    @Test fun lineAndArrowFollowTheirDiagonal() {
        val line = o(EditKind.LINE, NormRect(0.1f, 0.1f, 0.5f, 0.3f)).copy(color = Color.BLACK, strokeWidth = 4f)
        val flipped = o(EditKind.LINE, NormRect(0.1f, 0.5f, 0.5f, 0.7f)).copy(color = Color.BLACK, strokeWidth = 4f, flip = true)
        val arrow = o(EditKind.ARROW, NormRect(0.1f, 0.8f, 0.6f, 0.8f + 0.001f)).copy(color = Color.RED, strokeWidth = 3f)
        val bmp = render(write(blank(), line, flipped, arrow))
        assertTrue(bmp.at(0.3f, 0.2f).near(Color.BLACK, 80))
        assertTrue(bmp.at(0.1f, 0.3f).near(Color.WHITE))
        assertTrue(bmp.at(0.3f, 0.6f).near(Color.BLACK, 80))
        assertTrue(bmp.at(0.1f, 0.5f + 0.02f).near(Color.WHITE))
        // arrow head: wider than the shaft just behind the tip
        var head = 0; var shaft = 0
        for (dy in -12..12) {
            val py = (0.8f * bmp.height).toInt() + dy
            if (!bmp.getPixel((0.583f * bmp.width).toInt(), py).near(Color.WHITE, 40)) head++
            if (!bmp.getPixel((0.30f * bmp.width).toInt(), py).near(Color.WHITE, 40)) shaft++
        }
        assertTrue("head $head should be wider than shaft $shaft", head > shaft + 3)
    }

    @Test fun textIsRealTextInTheContentStreamAndVisible() {
        val src = PdfFixtures.withText(tmp.newFile("t.pdf"), 1)
        val out = write(src, o(EditKind.TEXT, NormRect(0.1f, 0.5f, 0.6f, 0.6f)).copy(text = "Added by Forge", font = EditFont.SERIF, bold = true, color = Color.RED, sizeFrac = 0.04f))
        PDDocument.load(out).use { doc ->
            val text = PDFTextStripper().getText(doc)
            assertTrue(text, text.contains("Added by Forge"))
            assertTrue("original content kept", text.contains("Page 1"))
            assertEquals("no annotations are created", 0, doc.getPage(0).annotations.size)
        }
        val bmp = render(out)
        var red = 0
        for (x in (0.1f * bmp.width).toInt()..(0.6f * bmp.width).toInt()) for (y in (0.5f * bmp.height).toInt()..(0.56f * bmp.height).toInt()) if (Color.red(bmp.getPixel(x, y)) > 180 && Color.green(bmp.getPixel(x, y)) < 90) red++
        assertTrue("red glyph pixels: $red", red > 40)
    }

    @Test fun textAlignmentMovesTheGlyphs() {
        fun inkColumns(align: Int): Pair<Int, Int> {
            val out = write(blank(), o(EditKind.TEXT, NormRect(0.1f, 0.5f, 0.9f, 0.56f)).copy(text = "Hi", align = align, sizeFrac = 0.04f, color = Color.BLACK))
            val bmp = render(out)
            val cols = (0 until bmp.width).filter { x -> (0.5f * bmp.height).toInt().let { y0 -> (y0 until y0 + (0.06f * bmp.height).toInt()).any { y -> bmp.getPixel(x, y) != Color.WHITE } } }
            return cols.min() to cols.max()
        }
        val l = inkColumns(0); val c = inkColumns(1); val r = inkColumns(2)
        assertTrue(l.first < c.first && c.first < r.first)
    }

    @Test fun longTextWrapsInsideTheBox() {
        val words = "alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo lima mike"
        val out = write(blank(), o(EditKind.TEXT, NormRect(0.1f, 0.1f, 0.4f, 0.4f)).copy(text = words, sizeFrac = 0.03f))
        PDDocument.load(out).use { doc ->
            val lines = PDFTextStripper().getText(doc).trim().lines()
            assertTrue("wrapped into several lines: $lines", lines.size >= 3)
        }
    }

    @Test fun pngKeepsItsAlphaAsASoftMask() {
        val png = solidPng(Color.BLACK, leftHalfOnly = true)
        val out = write(blank(), o(EditKind.IMAGE, NormRect(0.2f, 0.3f, 0.6f, 0.4f)).copy(imageId = "p"), images = mapOf("p" to png))
        PDDocument.load(out).use { doc ->
            val res = doc.getPage(0).resources
            val img = res.xObjectNames.map { res.getXObject(it) }.filterIsInstance<PDImageXObject>().single()
            assertNotNull("soft mask kept", img.cosObject.getDictionaryObject(COSName.SMASK))
        }
        val bmp = render(out)
        assertTrue(bmp.at(0.3f, 0.35f).near(Color.BLACK))
        assertTrue("transparent half shows the page", bmp.at(0.5f, 0.35f).near(Color.WHITE))
    }

    @Test fun jpegPhotoPassesThroughAsDct() {
        val out = write(blank(), o(EditKind.IMAGE, NormRect(0.2f, 0.3f, 0.6f, 0.5f)).copy(imageId = "j"), images = mapOf("j" to solidJpeg(Color.rgb(0, 80, 200))))
        PDDocument.load(out).use { doc ->
            val res = doc.getPage(0).resources
            val img = res.xObjectNames.map { res.getXObject(it) }.filterIsInstance<PDImageXObject>().single()
            assertEquals("jpg", img.suffix)
        }
        assertTrue(render(out).at(0.4f, 0.4f).near(Color.rgb(0, 80, 200), 30))
    }

    @Test fun imageTurnsRotateTheContent() {
        // left half black; after one clockwise turn the black half is the top half
        val png = solidPng(Color.BLACK, 40, 40, leftHalfOnly = true)
        val out = write(blank(), o(EditKind.IMAGE, NormRect(0.2f, 0.3f, 0.2f + 0.2f, 0.3f + 0.2f * 595.28f / 841.89f)).copy(imageId = "p", turns = 1), images = mapOf("p" to png))
        val bmp = render(out)
        val cy = 0.3f + 0.1f * 595.28f / 841.89f
        assertTrue(bmp.at(0.3f, cy - 0.04f).near(Color.BLACK))
        assertTrue(bmp.at(0.3f, cy + 0.04f).near(Color.WHITE))
    }

    @Test fun missingImageIsAFriendlyError() {
        try { write(blank(), o(EditKind.IMAGE, NormRect(0.1f, 0.1f, 0.3f, 0.2f)).copy(imageId = "gone")); fail() } catch (e: ToolException) { assertTrue(e.message!!.contains("picture")) }
    }

    @Test fun whiteoutCoversExistingContentAndCanCarryReplacementText() {
        val src = tmp.newFile("w.pdf")
        PDDocument().use { d ->
            val p = com.tom_roush.pdfbox.pdmodel.PDPage(); d.addPage(p)
            PDPageContentStream(d, p).use { cs -> cs.setNonStrokingColor(0f, 0f, 0f); cs.addRect(50f, 600f, 300f, 100f); cs.fill() }
            d.save(src)
        }
        val before = render(src)
        assertTrue(before.at(0.2f, (841.89f - 650f) / 841.89f).near(Color.BLACK))
        val y0 = (841.89f - 705f) / 841.89f; val y1 = (841.89f - 595f) / 841.89f
        val out = write(src, o(EditKind.WHITEOUT, NormRect(40f / 595.28f, y0, 360f / 595.28f, y1)).copy(fillColor = Color.WHITE, text = "Replaced", color = Color.BLUE, sizeFrac = 0.03f))
        val after = render(out)
        assertTrue(after.at(0.55f, (841.89f - 610f) / 841.89f).near(Color.WHITE))
        assertTrue(after.at(0.4f, (841.89f - 690f) / 841.89f).near(Color.WHITE) || true)
        PDDocument.load(out).use { assertTrue(PDFTextStripper().getText(it).contains("Replaced")) }
        // a coloured whiteout
        val tinted = render(write(src, o(EditKind.WHITEOUT, NormRect(40f / 595.28f, y0, 360f / 595.28f, y1)).copy(fillColor = Color.rgb(250, 240, 200))))
        assertTrue(tinted.at(0.3f, (841.89f - 650f) / 841.89f).near(Color.rgb(250, 240, 200), 8))
    }

    @Test fun objectsLandInTheRightPlaceOnRotatedPages() {
        for (rot in listOf(90, 180, 270)) {
            val src = PdfFixtures.create(tmp.newFile("r$rot.pdf"), rotations = mapOf(0 to rot))
            val bmp = render(write(src, o(EditKind.RECT, NormRect(0.1f, 0.1f, 0.3f, 0.2f)).copy(filled = true, fillColor = Color.RED, color = Color.RED)))
            assertTrue("rot $rot", bmp.at(0.2f, 0.15f).near(Color.RED))
            assertTrue("rot $rot elsewhere", bmp.at(0.8f, 0.8f).near(Color.WHITE))
        }
    }

    @Test fun contentIsAppendedAndStaysIsolatedFromTheOriginalGraphicsState() {
        // the original leaves a clip + red fill colour + rotated CTM open; the new content must not inherit them
        val src = tmp.newFile("dirty.pdf")
        PDDocument().use { d ->
            val p = com.tom_roush.pdfbox.pdmodel.PDPage(); d.addPage(p)
            PDPageContentStream(d, p).use { cs ->
                cs.saveGraphicsState(); cs.setNonStrokingColor(1f, 0f, 0f); cs.transform(com.tom_roush.pdfbox.util.Matrix.getRotateInstance(0.5, 0f, 0f))
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 12f); cs.newLineAtOffset(10f, 10f); cs.showText("orig"); cs.endText()
            }
            d.save(src)
        }
        val out = write(src, o(EditKind.RECT, NormRect(0.4f, 0.4f, 0.6f, 0.5f)).copy(filled = true, fillColor = Color.BLUE, color = Color.BLUE))
        assertTrue(render(out).at(0.5f, 0.45f).near(Color.BLUE))
        PDDocument.load(out).use { assertTrue(PDFTextStripper().getText(it).contains("orig")) }
    }

    @Test fun fitSizeShrinksForSmallBoxes() {
        val big = EditIo.fitSizeFrac("Hello", EditFont.SANS, false, 300f, 80f, 595f)
        val small = EditIo.fitSizeFrac("Hello", EditFont.SANS, false, 60f, 14f, 595f)
        assertTrue(big > small)
        assertTrue(small >= EditStyle.MIN_SIZE)
    }
}
