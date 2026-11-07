package com.namanbansal.forge.core.pdf.edit.content

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.DocumentIo
import com.namanbansal.forge.core.DocumentSink
import com.namanbansal.forge.core.DocumentWriter
import com.namanbansal.forge.core.SavedOutput
import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.PdfProtection
import com.namanbansal.forge.core.pdf.annot.AnnotSaver
import com.namanbansal.forge.core.pdf.edit.ExistingApplier
import com.namanbansal.forge.core.pdf.edit.ExistingEdit
import com.namanbansal.forge.core.pdf.edit.ExistingKind
import com.namanbansal.forge.core.pdf.edit.content.ExistingFixtures.line
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.rendering.PDFRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExistingObjectsTest {
    @get:Rule val tmp = TemporaryFolder()
    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext()) }
    private var n = 0

    /** 100x100: left half green, right half magenta (colours that survive a red/blue swap in the test bitmap path). */
    private fun twoTone(): Bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).also { b ->
        val c = Canvas(b); val p = Paint()
        p.color = Color.GREEN; c.drawRect(0f, 0f, 50f, 100f, p); p.color = Color.MAGENTA; c.drawRect(50f, 0f, 100f, 100f, p)
    }
    private fun png(color: Int): ByteArray = ByteArrayOutputStream().also { o -> Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).also { Canvas(it).drawColor(color) }.compress(Bitmap.CompressFormat.PNG, 100, o) }.toByteArray()

    private fun pdf(content: (PDDocument, PDPage) -> String): File {
        val f = tmp.newFile("o${n++}.pdf")
        PDDocument().use { d ->
            val p = PDPage(PDRectangle.LETTER); d.addPage(p)
            p.resources = PDResources().also { it.put(COSName.getPDFName("F1"), PDType1Font.HELVETICA) }
            val s = PDStream(d); s.createOutputStream().use { it.write(content(d, p).toByteArray(Charsets.ISO_8859_1)) }
            p.setContents(s)
            d.save(f)
        }
        return f
    }

    /** An image at 100,300 sized 200x200: red left half, blue right half. */
    private fun imagePdf(): File = pdf { d, p -> val nm = p.resources.add(LosslessFactory.createFromImage(d, twoTone())); "q 200 0 0 200 100 300 cm /${nm.name} Do Q" }

    private fun render(f: File): Bitmap = PDDocument.load(f).use { PDFRenderer(it).renderImage(0, 1f) }
    private fun Bitmap.at(x: Int, userY: Int) = getPixel(x, 792 - userY)
    private fun Int.near(c: Int, tol: Int = 40) = kotlin.math.abs(Color.red(this) - Color.red(c)) <= tol && kotlin.math.abs(Color.green(this) - Color.green(c)) <= tol && kotlin.math.abs(Color.blue(this) - Color.blue(c)) <= tol
    private fun norm(l: Float, b: Float, r: Float, t: Float) = NormRect(l / 612f, 1f - t / 792f, r / 612f, 1f - b / 792f)
    private fun imgEdit(key: Int = 0, f: (ExistingEdit) -> ExistingEdit) = f(ExistingEdit("i$key", 0, ExistingKind.IMAGE, key, norm(100f, 300f, 300f, 500f)))
    private fun run(f: File, vararg e: ExistingEdit, images: (String) -> ByteArray? = { null }) = ExistingApplier.applyInPlace(f, e.toList(), TEST_FONTS, images)

    @Test fun scanFindsThePictureWithItsMatrixAndBounds() {
        val f = imagePdf()
        val scan = PDDocument.load(f).use { ContentWalker.scan(it, it.getPage(0)) }
        assertEquals(1, scan.images.size)
        val b = scan.images[0].box
        assertEquals(100f, b.l, 0.5f); assertEquals(300f, b.b, 0.5f); assertEquals(300f, b.r, 0.5f); assertEquals(500f, b.t, 0.5f)
    }

    @Test fun movedPictureAppearsAtTheNewSpotAndTheOldOneIsBlank() {
        val f = imagePdf()
        val px = render(f).at(150, 400); assertTrue("pixel ${Integer.toHexString(px)}", px.near(Color.GREEN))
        run(f, imgEdit { it.copy(newRect = norm(350f, 100f, 550f, 300f)) })
        val bmp = render(f)
        assertTrue("old spot blank", bmp.at(150, 400).near(Color.WHITE)); assertTrue(bmp.at(250, 400).near(Color.WHITE))
        assertTrue("new spot red left", bmp.at(400, 200).near(Color.GREEN)); assertTrue("new spot blue right", bmp.at(500, 200).near(Color.MAGENTA))
    }

    @Test fun resizedPictureShrinksAroundItsCorner() {
        val f = imagePdf()
        run(f, imgEdit { it.copy(newRect = norm(100f, 400f, 200f, 500f)) })
        val bmp = render(f)
        assertTrue(bmp.at(120, 450).near(Color.GREEN)); assertTrue(bmp.at(180, 450).near(Color.MAGENTA))
        assertTrue("the rest of the old area is blank", bmp.at(250, 350).near(Color.WHITE)); assertTrue(bmp.at(150, 320).near(Color.WHITE))
    }

    @Test fun rotatedPictureTurnsAQuarterClockwise() {
        val f = imagePdf()
        run(f, imgEdit { it.copy(newRect = it.rect, turns = 1) })
        val bmp = render(f)
        assertTrue("red moves to the top", bmp.at(200, 470).near(Color.GREEN)); assertTrue("blue to the bottom", bmp.at(200, 330).near(Color.MAGENTA))
    }

    @Test fun replacedPictureKeepsThePlacement() {
        val f = imagePdf()
        run(f, imgEdit { it.copy(replaceImageId = "g", newRect = it.rect) }, images = { if (it == "g") png(Color.GRAY) else null })
        val bmp = render(f)
        assertTrue(bmp.at(150, 400).near(Color.GRAY)); assertTrue(bmp.at(250, 400).near(Color.GRAY)); assertTrue(bmp.at(200, 320).near(Color.GRAY))
        assertTrue(bmp.at(50, 400).near(Color.WHITE))
    }

    @Test fun deletedPictureIsGone() {
        val f = imagePdf()
        run(f, imgEdit { it.copy(delete = true) })
        assertTrue(render(f).at(200, 400).near(Color.WHITE))
        assertEquals(0, PDDocument.load(f).use { ContentWalker.scan(it, it.getPage(0)).images.size })
    }

    private fun formImagePdf(): File {
        val f = tmp.newFile("form${n++}.pdf")
        PDDocument().use { d ->
            val form = PDFormXObject(d)
            form.bBox = PDRectangle(0f, 0f, 400f, 400f)
            form.resources = PDResources().also { it.put(COSName.getPDFName("Im"), LosslessFactory.createFromImage(d, twoTone())) }
            form.contentStream.createOutputStream(COSName.FLATE_DECODE).use { it.write("q 100 0 0 100 0 0 cm /Im Do Q".toByteArray()) }
            repeat(2) {
                val p = PDPage(PDRectangle.LETTER); d.addPage(p); p.resources = PDResources()
                val nm = p.resources.add(form)
                val s = PDStream(d); s.createOutputStream().use { o -> o.write("q 2 0 0 2 100 300 cm /${nm.name} Do Q".toByteArray()) }
                p.setContents(s)
            }
            d.save(f)
        }
        return f
    }

    @Test fun pictureInsideAFormMovesCopyOnWriteAndTheOtherPageKeepsIt() {
        val f = formImagePdf()
        PDDocument.load(f).use { d -> val s = ContentWalker.scan(d, d.getPage(0)); assertEquals(1, s.images.size); assertTrue(s.images[0].inForm); assertEquals(200f, s.images[0].box.w, 0.5f) }
        run(f, imgEdit { it.copy(newRect = norm(350f, 100f, 550f, 300f)) })
        PDDocument.load(f).use { d ->
            val r = PDFRenderer(d)
            val p0 = r.renderImage(0, 1f); val p1 = r.renderImage(1, 1f)
            val dbg = "p0old=${Integer.toHexString(p0.at(150, 400))} p0new=${Integer.toHexString(p0.at(400, 200))} p1old=${Integer.toHexString(p1.at(150, 350))} p1new=${Integer.toHexString(p1.at(400,200))}"
            assertTrue(dbg, p0.at(150, 400).near(Color.WHITE)); assertTrue(dbg, p0.at(400, 200).near(Color.GREEN))
            assertTrue("other page unchanged", p1.at(150, 350).near(Color.GREEN)); assertTrue(p1.at(400, 200).near(Color.WHITE))
        }
    }

    private fun pathsPdf(): File = pdf { _, _ -> "1 0 0 rg 100 100 100 100 re f 0 0 1 rg 300 100 100 100 re f 0 0 0 RG 4 w 100 600 m 300 700 l S" }

    @Test fun pathsAreListedAndDeletedByIndexOnly() {
        val f = pathsPdf()
        val scan = PDDocument.load(f).use { ContentWalker.scan(it, it.getPage(0)) }
        assertEquals(3, scan.paths.size); assertTrue(scan.paths[0].filled); assertTrue(scan.paths[2].stroked)
        assertEquals(100f, scan.paths[0].box.l, 1f)
        run(f, ExistingEdit("p", 0, ExistingKind.PATH, 0, NormRect(0f, 0f, 1f, 1f), delete = true))
        val bmp = render(f)
        assertTrue(bmp.at(150, 150).near(Color.WHITE)); assertTrue("neighbour stays", bmp.at(350, 150).near(Color.BLUE))
    }

    @Test fun lassoDeletesSeveralObjectsAtOnce() {
        val f = pdf { d, p ->
            val nm = p.resources.add(LosslessFactory.createFromImage(d, twoTone()))
            "1 0 0 rg 100 100 100 100 re f 0 0 1 rg 300 100 100 100 re f q 200 0 0 200 100 300 cm /${nm.name} Do Q BT /F1 14 Tf 72 700 Td (Remove me please) Tj ET BT /F1 14 Tf 72 740 Td (Keep me please) Tj ET"
        }
        val block = scanBlocks(f).first { it.text.startsWith("Remove") }
        run(f,
            ExistingEdit("a", 0, ExistingKind.PATH, 0, NormRect(0f, 0f, 1f, 1f), delete = true),
            ExistingEdit("b", 0, ExistingKind.PATH, 1, NormRect(0f, 0f, 1f, 1f), delete = true),
            ExistingEdit("c", 0, ExistingKind.IMAGE, 0, NormRect(0f, 0f, 1f, 1f), delete = true),
            textEdit(block, "", id = "d") { it.copy(delete = true) })
        val bmp = render(f)
        assertTrue(bmp.at(150, 150).near(Color.WHITE)); assertTrue(bmp.at(350, 150).near(Color.WHITE)); assertTrue(bmp.at(150, 400).near(Color.WHITE))
        val t = pageText(f)
        assertFalse(t, t.contains("Remove")); assertTrue(t, t.contains("Keep me please"))
    }

    @Test fun textDrawnInvisiblyOrAsOutlinesIsNotEditableAndPageWithOnlyAnImageHasNoText() {
        val ocr = pdf { d, p -> val nm = p.resources.add(LosslessFactory.createFromImage(d, twoTone())); "q 612 0 0 792 0 0 cm /${nm.name} Do Q BT 3 Tr /F1 12 Tf 72 700 Td (hidden ocr words) Tj ET" }
        val scan = PDDocument.load(ocr).use { ContentWalker.scan(it, it.getPage(0)) }
        assertTrue(scan.editableGlyphs.isEmpty()); assertTrue(scan.invisibleText > 0); assertEquals(1, scan.images.size)
        val outlined = pdf { _, _ -> "BT 1 Tr /F1 30 Tf 72 700 Td (Outline) Tj ET" }
        assertTrue(PDDocument.load(outlined).use { ContentWalker.scan(it, it.getPage(0)) }.editableGlyphs.isEmpty())
    }

    // ---- encrypted round trip through the real save path -------------------------------------------------------------------

    private class FakeIo(val original: ByteArray) : DocumentIo {
        var current = original
        override fun takeWritePermission(uri: Uri) = true
        override fun probeWrite(uri: Uri) = true
        override fun openInput(uri: Uri): InputStream? = ByteArrayInputStream(if (uri.scheme == "backup") original else current)
        override fun openOverwrite(uri: Uri): OutputStream = object : ByteArrayOutputStream() { override fun close() { super.close(); current = toByteArray() } }
    }
    private class Sink : DocumentSink {
        override suspend fun saveBackup(originalName: String, timestamp: String, content: () -> InputStream) = SavedOutput(Uri.parse("backup://b"), "b.pdf", 1, "application/pdf").also { content().readBytes() }
        override suspend fun saveCopy(originalName: String, content: () -> InputStream) = SavedOutput(Uri.parse("copy://c"), "c.pdf", 1, "application/pdf").also { content().readBytes() }
    }

    @Test fun encryptedInputKeepsItsProtectionAfterAnEdit() {
        val plain = ExistingFixtures.doc(tmp.newFile("enc.pdf")) { _, _, cs -> cs.line(ExistingFixtures.HELV, 14f, 72f, 700f, "Confidential figures for review") }
        val b = scanBlocks(plain).single()
        val edit = textEdit(b, "Public figures for review")
        val protection = PdfProtection("pw", AccessPermission().also { it.setCanPrint(false) }.permissionBytes)
        val io = FakeIo(plain.readBytes())
        val saver = AnnotSaver(DocumentWriter(io, Sink()), tmp.newFolder())
        val out = runBlocking { saver.save(Uri.parse("content://x/1"), "doc.pdf", plain, protection, emptyList(), emptyList(), extra = { f -> ExistingApplier.applyInPlace(f, listOf(edit), TEST_FONTS) { null } }) }
        assertTrue(out.overwritten)
        PDDocument.load(io.current, "pw").use { d ->
            assertTrue(d.isEncrypted); assertFalse(d.currentAccessPermission.canPrint())
            val t = com.tom_roush.pdfbox.text.PDFTextStripper().getText(d)
            assertTrue(t, t.contains("Public figures for review")); assertFalse(t.contains("Confidential"))
        }
    }
}
