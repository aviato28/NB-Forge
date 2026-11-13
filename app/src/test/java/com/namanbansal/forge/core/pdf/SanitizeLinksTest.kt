package com.namanbansal.forge.core.pdf

import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.PdfFixtures
import com.namanbansal.forge.core.RenderProbe
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import com.tom_roush.pdfbox.pdmodel.PDJavascriptNameTreeNode
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup
import com.tom_roush.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentProperties
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionJavaScript
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SanitizeLinksTest {
    @get:Rule val tmp = TemporaryFolder()
    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext()) }

    private fun dirty(): File = com.namanbansal.forge.core.SanitizeFixtures.dirty(File(tmp.root, "dirty.pdf"))

    @Test fun scanFindsEverythingAndCleanRemovesIt() {
        val src = dirty()
        val found = PdfOps.load(src).use { PdfSanitizer.scan(it) }
        assertEquals(3, found[SanitizeKind.JAVASCRIPT]); assertEquals(1, found[SanitizeKind.EMBEDDED_FILES])
        assertEquals(1, found[SanitizeKind.HIDDEN_LAYERS]); assertEquals(1, found[SanitizeKind.THUMBNAILS]); assertEquals(1, found[SanitizeKind.APP_DATA])
        assertTrue(found[SanitizeKind.METADATA]!! >= 3)
        val out = File(tmp.root, "clean.pdf")
        var removed: Map<SanitizeKind, Int> = emptyMap()
        PdfOps.load(src).use { d -> removed = PdfSanitizer.clean(d, SanitizeKind.entries.toSet()); d.save(out) }
        assertEquals(3, removed[SanitizeKind.JAVASCRIPT])
        val after = PdfOps.load(out).use { PdfSanitizer.scan(it) }
        assertTrue(after.toString(), after.values.all { it == 0 })
        val text = RenderProbe.text(out)
        assertTrue(text.contains("PUBLIC TEXT")); assertFalse(text.contains("SECRET TEXT"))
        PdfOps.load(out).use { d ->
            assertTrue(d.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("OpenAction")) == null)
            assertEquals(1, d.numberOfPages)
        }
        // raw bytes carry no script or attachment names either
        val raw = String(out.readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains("app.alert")); assertFalse(raw.contains("note.txt")); assertFalse(raw.contains("Secret plan"))
    }

    @Test fun cleanOnlyTouchesTheChosenKinds() {
        val src = dirty()
        val out = File(tmp.root, "partial.pdf")
        PdfOps.load(src).use { d -> PdfSanitizer.clean(d, setOf(SanitizeKind.JAVASCRIPT)); d.save(out) }
        val after = PdfOps.load(out).use { PdfSanitizer.scan(it) }
        assertEquals(0, after[SanitizeKind.JAVASCRIPT]); assertEquals(1, after[SanitizeKind.EMBEDDED_FILES]); assertEquals(1, after[SanitizeKind.HIDDEN_LAYERS])
    }

    @Test fun cleanFileHasNothingToReport() {
        val clean = PdfFixtures.create(tmp.newFile("c.pdf"))
        val found = PdfOps.load(clean).use { PdfSanitizer.scan(it) }
        assertTrue(found.toString(), found.values.all { it == 0 })
    }
}
