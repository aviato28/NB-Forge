package com.namanbansal.forge.core.pdf.edit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.DocumentIo
import com.namanbansal.forge.core.DocumentSink
import com.namanbansal.forge.core.DocumentWriter
import com.namanbansal.forge.core.PdfFixtures
import com.namanbansal.forge.core.SavedOutput
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.PdfProtection
import com.namanbansal.forge.core.pdf.annot.AnnotSaver
import com.namanbansal.forge.core.pdf.viewer.PdfDocIndex
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class RedactorTest {
    @get:Rule val tmp = TemporaryFolder()
    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext()) }

    private val F1 = COSName.getPDFName("F1")
    private var n = 0

    /** A 612x792 page whose content stream is exactly [content] (font /F1 = Helvetica). */
    private fun pdf(content: String, rotation: Int = 0, extra: (PDDocument, PDPage) -> Unit = { _, _ -> }, pages: Int = 1): File {
        val f = tmp.newFile("r${n++}.pdf")
        PDDocument().use { doc ->
            repeat(pages) {
                val page = PDPage(PDRectangle.LETTER); page.rotation = rotation
                doc.addPage(page)
                page.resources = PDResources().also { it.put(F1, PDType1Font.HELVETICA) }
                val s = PDStream(doc)
                s.createOutputStream().use { it.write(content.toByteArray(Charsets.ISO_8859_1)) }
                page.setContents(s)
                extra(doc, page)
            }
            doc.save(f)
        }
        return f
    }

    private fun text(f: File, page: Int? = null): String = PDDocument.load(f).use { d ->
        val pages = if (page != null) listOf(page) else (0 until d.numberOfPages).toList()
        pages.joinToString("\n") { p -> com.namanbansal.forge.core.pdf.PdfTextSearch.Collector().also { it.setStartPage(p + 1); it.setEndPage(p + 1); it.getText(d) }.text() }
    }

    /** Marks covering every line box of [s] on [page] (found through the same PageText the app uses). */
    private fun marksOver(f: File, s: String, page: Int = 0, occurrence: Int = 0): List<RedactMark> = PdfDocIndex(f).use { idx ->
        val t = idx.text(page)
        var i = -1; repeat(occurrence + 1) { i = t.text.indexOf(s, i + 1) }
        assertTrue("'$s' not found in '${t.text}'", i >= 0)
        t.lineRects(i, i + s.length).map { r -> RedactMark("m${n++}", page, NormRect(r.left - 0.001f, r.top - 0.001f, r.right + 0.001f, r.bottom + 0.001f)) }
    }

    private fun rectOf(f: File, s: String, page: Int = 0): NormRect = PdfDocIndex(f).use { idx -> val t = idx.text(page); val i = t.text.indexOf(s); t.lineRects(i, i + s.length).first() }

    private fun redact(f: File, marks: List<RedactMark>, opts: RedactOptions = RedactOptions()): RedactionReport = Redactor.redactInPlace(f, marks, opts)

    private fun render(f: File, page: Int = 0): Bitmap = PDDocument.load(f).use { PDFRenderer(it).renderImage(page, 1f) }
    private fun Int.near(c: Int, tol: Int = 30) = kotlin.math.abs(Color.red(this) - Color.red(c)) <= tol && kotlin.math.abs(Color.green(this) - Color.green(c)) <= tol && kotlin.math.abs(Color.blue(this) - Color.blue(c)) <= tol
    private fun userNorm(l: Float, b: Float, r: Float, t: Float) = NormRect(l / 612f, 1f - t / 792f, r / 612f, 1f - b / 792f)

    // ---- text -----------------------------------------------------------------------------------------------------------

    @Test fun textInsideTheAreaIsGoneAndTheRestStaysWhereItWas() {
        val f = pdf("BT /F1 12 Tf 72 700 Td (Public line alpha) Tj 0 -30 Td (Account SECRET1234 end) Tj 0 -30 Td (Bottom line stays) Tj ET")
        val endBefore = rectOf(f, "end")
        val report = redact(f, marksOver(f, "SECRET1234"))
        val t = text(f)
        assertFalse(t, t.contains("SECRET1234") || t.contains("SECRET") || t.contains("1234"))
        assertTrue(t, t.contains("Public line alpha") && t.contains("Account") && t.contains("end") && t.contains("Bottom line stays"))
        assertEquals(10, report.glyphs)
        val endAfter = rectOf(f, "end")
        assertEquals("trailing text must not move", endBefore.left, endAfter.left, 0.002f)
        assertTrue(report.summary, report.summary.contains("10 characters"))
    }

    @Test fun tjArraysAreSplitMidString() {
        val f = pdf("BT /F1 12 Tf 72 700 Td [(Hel) -20 (lo SEC) 30 (RET text) -500 (tail)] TJ ET")
        val tailBefore = rectOf(f, "tail")
        redact(f, marksOver(f, "SECRET"))
        val t = text(f)
        assertFalse(t, t.contains("SEC") || t.contains("RET "))
        assertTrue(t, t.contains("Hel") && t.contains("text") && t.contains("tail"))
        assertEquals(tailBefore.left, rectOf(f, "tail").left, 0.002f)
    }

    @Test fun quoteOperatorsSpacingAndHorizontalScaleAreHandled() {
        val f = pdf("BT /F1 12 Tf 14 TL 0.3 Tc 5 Tw 120 Tz 72 700 Td (first line) Tj (second SECRETQ here) ' 3 1 (third SECRETD end) \" T* (last) Tj ET")
        val endBefore = rectOf(f, "last")
        val r = redact(f, marksOver(f, "SECRETQ") + marksOver(f, "SECRETD"))
        val t = text(f)
        assertFalse(t, t.contains("SECRET"))
        assertTrue(t, t.contains("first line") && t.contains("second") && t.contains("here") && t.contains("third") && t.contains("end") && t.contains("last"))
        assertEquals(14, r.glyphs)
        assertEquals(endBefore.left, rectOf(f, "last").left, 0.002f)
        assertEquals(endBefore.top, rectOf(f, "last").top, 0.002f)
    }

    @Test fun textStateSurvivesAroundTheRewrite() {
        // graphics state saved / restored, a font change and a matrix: text outside must still render in the same place
        val f = pdf("q BT /F1 20 Tf 1 0 0 1 72 600 Tm (Big SECRET) Tj ET Q q BT /F1 10 Tf 72 500 Td (Small keep) Tj ET Q")
        val keepBefore = rectOf(f, "Small keep")
        redact(f, marksOver(f, "SECRET"))
        assertTrue(text(f).contains("Small keep") && text(f).contains("Big"))
        assertEquals(keepBefore.left, rectOf(f, "Small keep").left, 0.001f)
        assertEquals(keepBefore.top, rectOf(f, "Small keep").top, 0.001f)
    }

    @Test fun pageWithoutMarksAreUntouchedAndPageRotationIsHonoured() {
        for (rot in listOf(90, 180, 270)) {
            val f = pdf("BT /F1 12 Tf 72 700 Td (keep this) Tj 0 -30 Td (kill SECRET now) Tj ET", rotation = rot, pages = 2)
            redact(f, marksOver(f, "SECRET", page = 1))
            assertTrue("rot $rot untouched page", text(f, 0).contains("SECRET"))
            assertFalse("rot $rot", text(f, 1).contains("SECRET"))
            assertTrue("rot $rot", text(f, 1).contains("keep this") && text(f, 1).contains("now"))
        }
    }

    // ---- form XObjects ---------------------------------------------------------------------------------------------------

    private fun withForm(doc: PDDocument, page: PDPage, dx: Int, matrix: String? = null) {
        val form = PDFormXObject(doc)
        form.bBox = PDRectangle(0f, 0f, 400f, 100f)
        form.resources = PDResources().also { it.put(F1, PDType1Font.HELVETICA) }
        form.contentStream.createOutputStream(COSName.FLATE_DECODE).use { it.write("BT /F1 12 Tf 10 50 Td (FORMSECRET and FORMKEEP) Tj ET".toByteArray()) }
        val name = page.resources.add(form)
        val s = PDStream(doc)
        s.createOutputStream().use { it.write("q ${matrix ?: "1 0 0 1"} $dx 400 cm /${name.name} Do Q".toByteArray()) }
        page.setContents(s)
    }

    @Test fun textInsideAFormXObjectIsRedacted() {
        val f = pdf("", extra = { d, p -> withForm(d, p, 100) })
        val r = redact(f, marksOver(f, "FORMSECRET"))
        val t = text(f)
        assertFalse(t, t.contains("FORMSECRET"))
        assertTrue(t, t.contains("FORMKEEP"))
        assertEquals(10, r.glyphs)
    }

    @Test fun scaledFormsAreMappedThroughTheirMatrix() {
        val f = pdf("", extra = { d, p -> withForm(d, p, 80, matrix = "2 0 0 2") })
        redact(f, marksOver(f, "FORMSECRET"))
        val t = text(f)
        assertFalse(t, t.contains("FORMSECRET")); assertTrue(t, t.contains("FORMKEEP"))
    }

    @Test fun aFormSharedWithAnotherPageIsOnlyChangedWhereRedacted() {
        val shared = tmp.newFile("shared.pdf")
        PDDocument().use { doc ->
            val form = PDFormXObject(doc)
            form.bBox = PDRectangle(0f, 0f, 400f, 100f)
            form.resources = PDResources().also { it.put(F1, PDType1Font.HELVETICA) }
            form.contentStream.createOutputStream(COSName.FLATE_DECODE).use { it.write("BT /F1 12 Tf 10 50 Td (SHAREDSECRET) Tj ET".toByteArray()) }
            repeat(2) {
                val page = PDPage(PDRectangle.LETTER); doc.addPage(page)
                page.resources = PDResources()
                val name = page.resources.add(form)
                val s = PDStream(doc); s.createOutputStream().use { it.write("q 1 0 0 1 100 400 cm /${name.name} Do Q".toByteArray()) }
                page.setContents(s)
            }
            doc.save(shared)
        }
        redact(shared, marksOver(shared, "SHAREDSECRET", page = 0))
        assertFalse(text(shared, 0).contains("SHAREDSECRET"))
        assertTrue("the other page keeps its text", text(shared, 1).contains("SHAREDSECRET"))
    }

    // ---- pictures, paths, annotations -------------------------------------------------------------------------------------

    private fun greenImage(doc: PDDocument, page: PDPage) {
        val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888); Canvas(bmp).drawColor(Color.GREEN)
        val img = LosslessFactory.createFromImage(doc, bmp)
        val name = page.resources.add(img)
        val s = PDStream(doc); s.createOutputStream().use { it.write("q 200 0 0 200 100 300 cm /${name.name} Do Q".toByteArray()) }
        page.setContents(s)
    }

    @Test fun aPartiallyCoveredPictureIsReEncodedWithBlackThere() {
        val f = pdf("", extra = { d, p -> greenImage(d, p) })
        val r = redact(f, listOf(RedactMark("a", 0, userNorm(100f, 300f, 200f, 500f))))
        assertEquals(1, r.imagesClipped)
        val bmp = render(f)
        assertTrue("covered half is black", bmp.getPixel(150, 792 - 400).near(Color.BLACK, 10))
        assertTrue("rest is still green", bmp.getPixel(250, 792 - 400).near(Color.GREEN, 40))
        // the picture that is drawn now is a different object with black pixels in it (the data is really gone)
        PDDocument.load(f).use { doc ->
            val res = doc.getPage(0).resources
            val names = res.xObjectNames.toList()
            assertTrue("a new picture was added", names.size == 2)
            val used = String(doc.getPage(0).contents.readBytes())
            val drawn = names.first { used.contains("/${it.name} ") }
            val img = res.getXObject(drawn) as com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
            val px = img.image
            assertEquals(Color.BLACK, px.getPixel(10, 50) or 0xFF000000.toInt())
            assertTrue(px.getPixel(80, 50).near(Color.GREEN, 10))
        }
    }

    @Test fun aFullyCoveredPictureIsDropped() {
        val f = pdf("", extra = { d, p -> greenImage(d, p) })
        val r = redact(f, listOf(RedactMark("a", 0, userNorm(90f, 290f, 310f, 510f))))
        assertEquals(1, r.imagesDropped)
        PDDocument.load(f).use { doc -> assertFalse(String(doc.getPage(0).contents.readBytes()).contains(" Do")) }
        assertTrue(render(f).getPixel(150, 792 - 400).near(Color.BLACK, 10))
    }

    @Test fun vectorPathsFullyInsideAreRemovedAndOthersStay() {
        val f = pdf("1 0 0 rg 100 100 50 50 re f 0 0 1 rg 400 100 50 50 re f 0 1 0 rg 140 100 200 50 re f")
        val r = redact(f, listOf(RedactMark("a", 0, userNorm(90f, 90f, 160f, 160f))))
        assertEquals(1, r.pathsRemoved)
        assertEquals("the green bar only crosses the area", 1, r.pathsCrossing)
        val content = PDDocument.load(f).use { String(it.getPage(0).contents.readBytes()) }
        assertFalse(content.contains("100 100 50 50 re"))
        assertTrue(content.contains("400 100 50 50 re"))
        assertTrue(render(f).getPixel(425, 792 - 125).near(Color.BLUE, 20))
    }

    @Test fun annotationsInsideTheAreaAreRemoved() {
        val f = pdf("", extra = { _, p ->
            val note = PDAnnotationText().also { it.rectangle = PDRectangle(100f, 600f, 20f, 20f); it.contents = "private note" }
            val link = PDAnnotationLink().also { it.rectangle = PDRectangle(400f, 100f, 50f, 20f) }
            p.annotations = mutableListOf(note, link)
        })
        val r = redact(f, listOf(RedactMark("a", 0, userNorm(90f, 590f, 140f, 640f))))
        assertEquals(1, r.annotationsRemoved)
        PDDocument.load(f).use { doc -> assertEquals(listOf("Link"), doc.getPage(0).annotations.map { it.subtype }) }
    }

    @Test fun filledFormFieldsUnderTheAreaAreDroppedWithTheirValue() {
        val src = com.namanbansal.forge.core.FormFixtures.acroForm(tmp.newFile("form.pdf"))
        val fi = com.namanbansal.forge.core.pdf.form.FormReader.read(src)
        val field = fi.fields.first { it.name == "person.name" }
        val out = tmp.newFile("form-out.pdf"); src.copyTo(out, true)
        com.namanbansal.forge.core.pdf.fill.FillIo.applyInPlace(out, com.namanbansal.forge.core.pdf.fill.FillState(mapOf("person.name" to "Alice Example")), com.namanbansal.forge.core.pdf.fill.FillSaveOptions()) { null }
        redact(out, listOf(RedactMark("a", field.page, field.rect)))
        PDDocument.load(out).use { doc ->
            val form = doc.documentCatalog.acroForm
            assertNull("field removed from the form", form?.getField("person.name"))
            assertFalse(String(out.readBytes(), Charsets.ISO_8859_1).contains("Alice Example") && form?.getField("person.name") != null)
        }
    }

    // ---- patterns end to end ---------------------------------------------------------------------------------------------

    @Test fun searchAndRedactEveryPreset() {
        val lines = listOf(
            "Contact jane.doe@example.org for details", "Phone +1 (555) 123-4567 anytime", "Card 4111 1111 1111 1111 on file", "Signed on 2024-03-09 in town",
            "Plain closing sentence stays",
        )
        val body = lines.withIndex().joinToString(" ") { (i, l) -> "BT /F1 12 Tf 72 ${700 - i * 30} Td ($l) Tj ET" }
        val expectGone = mapOf(RedactPreset.EMAIL to "jane.doe@example.org", RedactPreset.PHONE to "123-4567", RedactPreset.LONG_NUMBER to "4111 1111 1111 1111", RedactPreset.DATE to "2024-03-09")
        for ((preset, gone) in expectGone) {
            val f = pdf(body)
            val hits = PdfDocIndex(f).use { idx -> RedactSearch.hitsOn(0, idx.text(0), preset, null) }
            assertEquals("$preset hits", 1, hits.size)
            var k = 0
            redact(f, RedactSearch.marksFor(hits) { "s${k++}" })
            val t = text(f)
            assertFalse("$preset: $t", t.contains(gone))
            assertTrue("$preset keeps the closing line", t.contains("Plain closing sentence stays"))
            // the other three kinds of personal data are still there
            for ((other, s) in expectGone) if (other != preset) assertTrue("$preset removed $other too: $t", t.contains(s))
        }
    }

    @Test fun termSearchRedactsEveryOccurrence() {
        val f = pdf("BT /F1 12 Tf 72 700 Td (Alice met alice and ALICE) Tj 0 -30 Td (nobody else) Tj ET")
        val hits = PdfDocIndex(f).use { RedactSearch.hitsOn(0, it.text(0), null, "alice") }
        assertEquals(3, hits.size)
        var k = 0
        redact(f, RedactSearch.marksFor(hits) { "t${k++}" })
        val t = text(f)
        assertFalse(t, t.contains("lice", ignoreCase = true))
        assertTrue(t, t.contains("met") && t.contains("and") && t.contains("nobody else"))
    }

    // ---- verification, metadata, encryption ----------------------------------------------------------------------------

    @Test fun aFailedVerificationAbortsAndLeavesTheFileUntouched() {
        val f = pdf("BT /F1 12 Tf 72 700 Td (keep) Tj 0 -30 Td (Account SECRET1234 end) Tj ET")
        val before = f.readBytes()
        val marks = marksOver(f, "SECRET1234")
        val sneaky = RedactHooks(afterRewrite = { doc ->
            PDPageContentStream(doc, doc.getPage(0), PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 12f); cs.newLineAtOffset(72f + 40f, 670f); cs.showText("SECRET1234"); cs.endText()
            }
        })
        try { Redactor.redactInPlace(f, marks, RedactOptions(), sneaky); fail("should abort") } catch (e: ToolException) { assertTrue(e.message, e.message!!.contains("Nothing was saved")) }
        assertTrue("original untouched", before.contentEquals(f.readBytes()))
        assertFalse(File(f.parentFile, f.name + ".redact.tmp").exists())
    }

    @Test fun documentInfoAndXmpAreStrippedOnlyWhenAsked() {
        fun build() = pdf("BT /F1 12 Tf 72 700 Td (hello) Tj ET").also { f ->
            PDDocument.load(f).use { d ->
                d.documentInformation.author = "Jane Secret"; d.documentInformation.title = "Confidential"
                d.documentCatalog.metadata = PDMetadata(d, ByteArrayInputStream("<x:xmpmeta xmlns:x='adobe:ns:meta/'>Jane</x:xmpmeta>".toByteArray()))
                d.save(f)
            }
        }
        val mark = listOf(RedactMark("a", 0, userNorm(60f, 690f, 120f, 720f)))
        val kept = build(); redact(kept, mark)
        PDDocument.load(kept).use { assertEquals("Jane Secret", it.documentInformation.author); assertNotNull(it.documentCatalog.metadata) }
        val stripped = build(); val rep = redact(stripped, mark, RedactOptions(removeDocumentInfo = true))
        assertTrue(rep.infoRemoved)
        PDDocument.load(stripped).use { assertNull(it.documentInformation.author); assertNull(it.documentInformation.title); assertNull(it.documentCatalog.metadata) }
    }

    private class FakeIo(val original: ByteArray) : DocumentIo {
        var current = original
        override fun takeWritePermission(uri: Uri) = true
        override fun probeWrite(uri: Uri) = true
        override fun openInput(uri: Uri): InputStream? = ByteArrayInputStream(if (uri.scheme == "backup") original else current)
        override fun openOverwrite(uri: Uri): OutputStream = object : java.io.ByteArrayOutputStream() { override fun close() { super.close(); current = toByteArray() } }
    }
    private class Sink : DocumentSink {
        override suspend fun saveBackup(originalName: String, timestamp: String, content: () -> InputStream) = SavedOutput(Uri.parse("backup://b"), "b.pdf", 1, "application/pdf").also { content().readBytes() }
        override suspend fun saveCopy(originalName: String, content: () -> InputStream) = SavedOutput(Uri.parse("copy://c"), "c.pdf", 1, "application/pdf").also { content().readBytes() }
    }

    @Test fun encryptedInputsKeepTheirProtectionThroughTheRealSavePath() {
        val plain = pdf("BT /F1 12 Tf 72 700 Td (Account SECRET1234 end) Tj ET")
        val marks = marksOver(plain, "SECRET1234")
        val protection = PdfProtection("pw", AccessPermission().also { it.setCanPrint(false) }.permissionBytes)
        val io = FakeIo(plain.readBytes())
        val saver = AnnotSaver(DocumentWriter(io, Sink()), tmp.newFolder())
        val out = runBlocking {
            saver.save(Uri.parse("content://x/1"), "doc.pdf", plain, protection, emptyList(), emptyList(), extra = { f -> Redactor.redactInPlace(f, marks, RedactOptions()) })
        }
        assertTrue(out.overwritten)
        try { PDDocument.load(io.current).close(); fail("needs the password") } catch (_: java.io.IOException) { }
        PDDocument.load(io.current, "pw").use { doc ->
            assertTrue(doc.isEncrypted); assertFalse(doc.currentAccessPermission.canPrint())
            val t = PDFTextStripper().getText(doc)
            assertFalse(t, t.contains("SECRET")); assertTrue(t, t.contains("Account") && t.contains("end"))
        }
    }

    @Test fun failedVerificationInTheSavePathNeverTouchesTheOriginal() {
        val plain = pdf("BT /F1 12 Tf 72 700 Td (Account SECRET1234 end) Tj ET")
        val marks = marksOver(plain, "SECRET1234")
        val io = FakeIo(plain.readBytes())
        val saver = AnnotSaver(DocumentWriter(io, Sink()), tmp.newFolder())
        val sneaky = RedactHooks(afterRewrite = { doc ->
            PDPageContentStream(doc, doc.getPage(0), PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 12f); cs.newLineAtOffset(110f, 700f); cs.showText("SECRET1234"); cs.endText()
            }
        })
        try {
            runBlocking { saver.save(Uri.parse("content://x/1"), "doc.pdf", plain, null, emptyList(), emptyList(), extra = { f -> Redactor.redactInPlace(f, marks, RedactOptions(), sneaky) }) }
            fail()
        } catch (_: ToolException) { }
        assertTrue("device file never written", io.current.contentEquals(io.original))
    }
}
