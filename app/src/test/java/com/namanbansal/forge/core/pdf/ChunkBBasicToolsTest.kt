package com.namanbansal.forge.core.pdf

import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.FormFixtures
import com.namanbansal.forge.core.PdfFixtures
import com.namanbansal.forge.core.RenderProbe
import com.namanbansal.forge.core.ToolException
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquareCircle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
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
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChunkBBasicToolsTest {
    @get:Rule val tmp = TemporaryFolder()
    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext()) }

    private fun save(src: File, edit: (PDDocument) -> Unit): File {
        val out = File(tmp.root, "o-${System.nanoTime()}.pdf")
        PdfOps.load(src).use { edit(it); it.save(out) }
        return out
    }

    // ---- Metadata
    @Test fun metadataKeepsInfoAndXmpInSyncAndRemovesAll() {
        val src = PdfFixtures.withText(tmp.newFile("m.pdf"), 1)
        val meta = PdfMeta("Q4 & <Plan>", "Ana", "Budget", "a, b", "Forge", "Forge PDF", "2026-01-02 09:30", "2026-02-03")
        val out = save(src) { PdfMetadataEditor.write(it, meta) }
        PdfOps.load(out).use { d ->
            val read = PdfMetadataEditor.read(d)
            assertEquals("Q4 & <Plan>", read.title); assertEquals("Ana", read.author); assertEquals("2026-01-02 09:30", read.created)
            val xmp = PdfMetadataEditor.readXmp(d)!!
            assertTrue(xmp.contains("Q4 &amp; &lt;Plan&gt;")); assertTrue(xmp.contains("<rdf:li>Ana</rdf:li>"))
            assertTrue(xmp.contains("<pdf:Keywords>a, b</pdf:Keywords>")); assertTrue(xmp.contains("xmp:CreateDate"))
        }
        val cleared = save(out) { PdfMetadataEditor.removeAll(it) }
        PdfOps.load(cleared).use { d ->
            assertEquals(PdfMeta(), PdfMetadataEditor.read(d)); assertNull(PdfMetadataEditor.readXmp(d))
        }
        assertTrue(PdfMetadataEditor.isValidDate("")); assertFalse(PdfMetadataEditor.isValidDate("13/11/2026"))
    }

    // ---- Flatten
    @Test fun flattenFormKeepsLookAndRemovesFields() {
        val src = FormFixtures.acroForm(tmp.newFile("f.pdf"))
        val withValue = save(src) { d -> d.documentCatalog.acroForm.getField("person.name").setValue("Jane Roe") }
        val before = RenderProbe.render(withValue)
        val out = save(withValue) { d -> val r = PdfFlattener.flatten(d, forms = true, annots = false); assertTrue(r.fields > 0) }
        PdfOps.load(out).use { d ->
            assertTrue(d.documentCatalog.acroForm?.fields.isNullOrEmpty())
            assertTrue(d.pages.all { p -> p.annotations.none { it is com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget } })
        }
        assertTrue(RenderProbe.text(out).contains("Jane Roe"))
        assertTrue("render drifted", RenderProbe.diff(before, RenderProbe.render(out)) < 4.0)
    }

    @Test fun flattenAnnotationBakesAppearanceIntoPage() {
        val src = PdfFixtures.create(tmp.newFile("a.pdf"))
        val annotated = save(src) { doc ->
            val page = doc.getPage(0)
            val sq = PDAnnotationSquareCircle(PDAnnotationSquareCircle.SUB_TYPE_SQUARE); sq.rectangle = PDRectangle(100f, 600f, 100f, 50f)
            val st = PDAppearanceStream(doc); st.bBox = PDRectangle(100f, 50f)
            PDPageContentStream(doc, st).use { cs -> cs.setNonStrokingColor(1f, 0f, 0f); cs.addRect(0f, 0f, 100f, 50f); cs.fill() }
            val ap = PDAppearanceDictionary(); ap.setNormalAppearance(st); sq.appearance = ap
            page.annotations = mutableListOf(sq)
        }
        PdfOps.load(annotated).use { assertEquals(1, PdfFlattener.count(it).annotations) }
        val out = save(annotated) { val r = PdfFlattener.flatten(it, forms = false, annots = true); assertEquals(1, r.annotations) }
        PdfOps.load(out).use { assertEquals(0, it.getPage(0).annotations.size) }
        val px = RenderProbe.pixel(RenderProbe.render(out), 150f / 595f, (842f - 625f) / 842f)
        assertTrue(RenderProbe.r(px) > 200 && RenderProbe.g(px) < 60)
    }

    // ---- Repair
    @Test fun repairRebuildsTruncatedFileAndReports() {
        val good = PdfFixtures.withText(tmp.newFile("g.pdf"), 3)
        val bytes = good.readBytes()
        val cut = File(tmp.root, "cut.pdf").apply { writeBytes(bytes.copyOf((bytes.size * 0.9).toInt())) }
        assertTrue(PdfRepair.diagnose(cut).missingEof)
        val out = File(tmp.root, "fixed.pdf")
        var report = ""
        PdfRepair.open(cut).use { d -> report = PdfRepair.fix(d, cut); d.save(out) }
        assertTrue(report, report.contains("cross-reference"))
        PdfOps.load(out).use { assertTrue(it.numberOfPages in 1..3) }
        assertFalse(PdfRepair.diagnose(out).missingEof)
    }

    @Test fun repairSaysSoWhenUnrecoverable() {
        val junk = PdfFixtures.corrupt(tmp.newFile("junk.pdf"))
        try { PdfRepair.open(junk).close(); fail("should not open") } catch (e: ToolException) { assertTrue(e.message!!.contains("too damaged")) }
    }

    // ---- Protect / Unlock
    @Test fun protectRoundTripAes256WithPermissions() {
        val src = PdfFixtures.withText(tmp.newFile("p.pdf"), 2)
        val prot = PdfProtector.protection("open-me", "boss", PdfPermissions(print = false, copy = false))
        val out = save(src) { prot.applyTo(it) }
        try { PDDocument.load(out).close(); fail("needs password") } catch (_: InvalidPasswordException) {}
        PDDocument.load(out, "open-me").use { d ->
            assertTrue(d.isEncrypted); assertEquals(256, d.encryption.length)
            assertFalse(d.currentAccessPermission.canPrint()); assertFalse(d.currentAccessPermission.canExtractContent())
            assertTrue(d.currentAccessPermission.canFillInForm())
        }
        PDDocument.load(out, "boss").use { assertTrue(it.currentAccessPermission.isOwnerPermission) }
        // Unlock: open through the loader, then write with no protection
        val work = File(tmp.root, "w"); 
        val opened = runBlocking { PdfLoader.open(out, work, "open-me") } as PdfOpenResult.Ready
        assertTrue(opened.source.wasEncrypted); assertEquals("open-me", opened.source.protection!!.userPassword)
        val plain = save(opened.source.file) { }
        PDDocument.load(plain).use { assertFalse(it.isEncrypted); assertEquals(2, it.numberOfPages) }
    }

    @Test fun unlockRemovesOwnerRestrictionsWhenUserPasswordEmpty() {
        val src = PdfFixtures.withText(tmp.newFile("r.pdf"), 1)
        val restricted = save(src) { PdfProtector.protection("", "own", PdfPermissions(copy = false)).applyTo(it) }
        val opened = runBlocking { PdfLoader.open(restricted, File(tmp.root, "w2"), null) } as PdfOpenResult.Ready
        assertEquals("", opened.source.protection!!.userPassword)
        PDDocument.load(opened.source.file).use { assertFalse(it.isEncrypted) }
    }

    @Test fun protectRefusesAnEmptyConfiguration() {
        try { PdfProtector.protection("", "", PdfPermissions()); fail() } catch (_: ToolException) {}
    }

    @Test fun strengthMeterRanks() {
        assertEquals(0, PasswordStrength.score("")); assertEquals(1, PasswordStrength.score("password"))
        assertTrue(PasswordStrength.score("abc") < PasswordStrength.score("Tr1cky-Horse-Battery"))
        assertEquals(4, PasswordStrength.score("Tr1cky-Horse-Battery"))
    }
}
