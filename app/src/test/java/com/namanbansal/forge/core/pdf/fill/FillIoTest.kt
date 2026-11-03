package com.namanbansal.forge.core.pdf.fill

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.FormFixtures
import com.namanbansal.forge.core.PdfFixtures
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.PdfProtection
import com.namanbansal.forge.core.pdf.annot.AnnotSaver
import com.namanbansal.forge.core.pdf.sign.SignatureImage
import com.namanbansal.forge.core.DocumentIo
import com.namanbansal.forge.core.DocumentSink
import com.namanbansal.forge.core.DocumentWriter
import com.namanbansal.forge.core.SavedOutput
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDListBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.rendering.PDFRenderer
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
class FillIoTest {
    @get:Rule val tmp = TemporaryFolder()
    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext()) }

    private val noImages: (String) -> ByteArray? = { null }

    /** 40x20 picture: left half black, right half transparent. */
    private fun halfPng(): ByteArray {
        val b = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        Canvas(b).drawRect(0f, 0f, 20f, 20f, android.graphics.Paint().apply { color = Color.BLACK })
        return SignatureImage.toPng(b)
    }

    private fun fill(src: File, state: FillState, opts: FillSaveOptions = FillSaveOptions(), images: (String) -> ByteArray? = noImages): File {
        val dst = tmp.newFile("out${System.nanoTime()}.pdf"); dst.delete()
        FillIo.apply(src, dst, state, opts, images)
        return dst
    }

    private val values = mapOf("person.name" to "Alice Example", "notes" to "line one\nline two", "agree" to "Yes", "colour" to "Blue", "fruit" to "Banana", "pets" to "Dog", "code" to "123456", "locked" to "hacked")

    @Test fun fillSaveReReadValuesWithAppearances() {
        val out = fill(FormFixtures.acroForm(tmp.newFile("f.pdf")), FillState(values))
        PDDocument.load(out).use { doc ->
            val form = doc.documentCatalog.acroForm
            assertEquals("Alice Example", form.getField("person.name").valueAsString)
            assertEquals("line one\nline two", form.getField("notes").valueAsString.replace("\r", ""))
            assertTrue((form.getField("agree") as PDCheckBox).isChecked)
            assertEquals("Blue", (form.getField("colour") as PDRadioButton).value)
            assertEquals(listOf("Banana"), (form.getField("fruit") as com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox).value)
            assertEquals(listOf("Dog"), (form.getField("pets") as PDListBox).value)
            // appearances are there, so other viewers show the values
            val widget = form.getField("person.name").widgets.single()
            val ap = widget.normalAppearanceStream
            assertNotNull(ap)
            assertTrue(String(ap.contentStream.toByteArray(), Charsets.ISO_8859_1).contains("Alice Example"))
            assertFalse(form.needAppearances)
            assertNotNull(form.getField("fruit").widgets.single().normalAppearanceStream)
        }
    }

    @Test fun readOnlyFieldsAndMaxLengthAreRespected() {
        val out = fill(FormFixtures.acroForm(tmp.newFile("f.pdf")), FillState(values))
        PDDocument.load(out).use { doc ->
            val form = doc.documentCatalog.acroForm
            assertEquals("fixed", form.getField("locked").valueAsString)
            assertEquals("1234", form.getField("code").valueAsString)
        }
    }

    @Test fun formStaysFillableUnlessFlattened() {
        val src = FormFixtures.acroForm(tmp.newFile("f.pdf"))
        val live = fill(src, FillState(values), FillSaveOptions(flattenForm = false))
        PDDocument.load(live).use { doc ->
            assertTrue(doc.documentCatalog.acroForm.fields.isNotEmpty())
            assertTrue(doc.getPage(0).annotations.any { it.subtype == "Widget" })
        }
        val flat = fill(src, FillState(values), FillSaveOptions(flattenForm = true))
        PDDocument.load(flat).use { doc ->
            assertTrue(doc.documentCatalog.acroForm == null || doc.documentCatalog.acroForm.fields.isEmpty())
            assertTrue(doc.getPage(0).annotations.none { it.subtype == "Widget" })
        }
    }

    @Test fun freeTextRoundTripHasAppearanceAndPosition() {
        val src = PdfFixtures.create(tmp.newFile("p.pdf"))
        val rect = NormRect(0.1f, 0.2f, 0.5f, 0.26f)
        val items = listOf(
            FillItem("a", 0, ItemKind.TEXT, rect, text = "Hello (world) ✓", size = 2, color = FillPalette.BLUE),
            FillItem("b", 0, ItemKind.CHECK, NormRect(0.6f, 0.2f, 0.64f, 0.23f)),
            FillItem("c", 0, ItemKind.CROSS, NormRect(0.7f, 0.2f, 0.74f, 0.23f)),
        )
        val out = fill(src, FillState(items = items))
        val read = FillIo.readFreeText(out)
        assertEquals(3, read.size)
        assertTrue(read.all { it.hasAppearance })
        assertEquals("Hello (world) ✓", read[0].text)
        assertEquals(0.1f * 595.28f, read[0].rect.lowerLeftX, 0.5f)
        assertEquals(841.89f * (1 - 0.26f), read[0].rect.lowerLeftY, 0.5f)
        PDDocument.load(out).use { doc ->
            val ap = doc.getPage(0).annotations[0].normalAppearanceStream
            assertTrue(String(ap.contentStream.toByteArray(), Charsets.ISO_8859_1).contains("Hello (world)".replace("(", "\\(").replace(")", "\\)")))
        }
    }

    @Test fun stampAnnotationByDefaultAndFlattenedIntoThePageOnRequest() {
        val src = PdfFixtures.create(tmp.newFile("p.pdf"))
        val item = FillItem("s", 0, ItemKind.SIGNATURE, NormRect(0.2f, 0.4f, 0.5f, 0.5f), sigId = "sig1")
        val images = { id: String -> if (id == "sig1") halfPng() else null }
        val stamped = fill(src, FillState(items = listOf(item)), images = images)
        PDDocument.load(stamped).use { doc ->
            val stamp = doc.getPage(0).annotations.single()
            assertEquals("Stamp", stamp.subtype)
            val xo = stamp.normalAppearanceStream.resources.xObjectNames.toList()
            assertEquals(1, xo.size)
            assertTrue(stamp.normalAppearanceStream.resources.isImageXObject(xo[0]))
        }
        val flat = fill(src, FillState(items = listOf(item)), FillSaveOptions(flattenSignatures = true), images)
        PDDocument.load(flat).use { doc ->
            assertTrue(doc.getPage(0).annotations.isEmpty())
            assertTrue(doc.getPage(0).resources.xObjectNames.iterator().hasNext())
        }
        val before = PDDocument.load(src).use { d -> d.getPage(0).contents.readBytes().size }
        val after = PDDocument.load(flat).use { d -> d.getPage(0).contents.readBytes().size }
        assertTrue("content stream changed", after > before)
    }

    @Test fun missingSignatureImageGivesAFriendlyError() {
        val src = PdfFixtures.create(tmp.newFile("p.pdf"))
        try { fill(src, FillState(items = listOf(FillItem("s", 0, ItemKind.SIGNATURE, NormRect(0.2f, 0.4f, 0.5f, 0.5f), sigId = "gone")))); fail() } catch (e: ToolException) { assertTrue(e.message!!.contains("library")) }
    }

    /** Renders [out] page 1 and tells whether the pixel at the displayed fraction is dark. */
    private fun darkAt(out: File, fx: Float, fy: Float): Boolean = PDDocument.load(out).use { doc ->
        val bmp = PDFRenderer(doc).renderImage(0, 1f)
        val px = bmp.getPixel((fx * (bmp.width - 1)).toInt(), (fy * (bmp.height - 1)).toInt())
        Color.red(px) < 90 && Color.alpha(px) > 200
    }

    @Test fun signatureStaysUprightOnEveryPageRotation() {
        val img = { _: String -> halfPng() }
        for (rot in listOf(0, 90, 180, 270)) {
            val src = PdfFixtures.create(tmp.newFile("r$rot.pdf"), rotations = mapOf(0 to rot))
            val aspect = if (rot % 180 == 0) 595.28f / 841.89f else 841.89f / 595.28f
            val rect = Placement.centered(2f, aspect)
            // default: left half of the picture is black, right half empty
            val st = fill(src, FillState(items = listOf(FillItem("s", 0, ItemKind.SIGNATURE, rect, sigId = "x"))), images = img)
            val cy = (rect.top + rect.bottom) / 2
            assertTrue("rot $rot left dark", darkAt(st, rect.left + (rect.right - rect.left) * 0.25f, cy))
            assertFalse("rot $rot right empty", darkAt(st, rect.left + (rect.right - rect.left) * 0.75f, cy))
            val fl = fill(src, FillState(items = listOf(FillItem("s", 0, ItemKind.SIGNATURE, rect, sigId = "x"))), FillSaveOptions(flattenSignatures = true), img)
            assertTrue("rot $rot flat left", darkAt(fl, rect.left + (rect.right - rect.left) * 0.25f, cy))
            assertFalse("rot $rot flat right", darkAt(fl, rect.left + (rect.right - rect.left) * 0.75f, cy))
            // one quarter turn clockwise: the black half moves to the top
            val turned = Placement.rotated(rect, aspect)
            val t = fill(src, FillState(items = listOf(FillItem("s", 0, ItemKind.SIGNATURE, turned, sigId = "x", turns = 1))), images = img)
            val cx = (turned.left + turned.right) / 2
            assertTrue("rot $rot turned top dark", darkAt(t, cx, turned.top + (turned.bottom - turned.top) * 0.25f))
            assertFalse("rot $rot turned bottom empty", darkAt(t, cx, turned.top + (turned.bottom - turned.top) * 0.75f))
        }
    }

    // ---- encrypted round trip through the real save path ----
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

    @Test fun encryptedPdfKeepsItsPasswordAndPermissionsWhenFilled() {
        val plain = FormFixtures.acroForm(tmp.newFile("plain.pdf"))
        val perm = AccessPermission().also { it.setCanPrint(false) }
        val protection = PdfProtection("pw", perm.permissionBytes)
        val io = FakeIo(plain.readBytes())
        val saver = AnnotSaver(DocumentWriter(io, Sink()), tmp.newFolder())
        val out = runBlocking {
            saver.save(Uri.parse("content://x/1"), "doc.pdf", plain, protection, emptyList(), emptyList(), extra = { f -> FillIo.applyInPlace(f, FillState(mapOf("person.name" to "Bob")), FillSaveOptions(), noImages) })
        }
        assertTrue(out.overwritten)
        try { PDDocument.load(io.current).close(); fail("should need the password") } catch (_: java.io.IOException) { }
        PDDocument.load(io.current, "pw").use { doc ->
            assertTrue(doc.isEncrypted)
            assertEquals("Bob", doc.documentCatalog.acroForm.getField("person.name").valueAsString)
            assertFalse(doc.currentAccessPermission.canPrint())
        }
    }

    @Test fun permissionsThatForbidFillingAreExplained() {
        val noFill = AccessPermission().also { it.setCanFillInForm(false); it.setCanModifyAnnotations(false) }
        assertNotNull(FillIo.permissionProblem(noFill.permissionBytes))
        assertNull(FillIo.permissionProblem(AccessPermission().permissionBytes))
        assertNull(FillIo.permissionProblem(null))
        val onlyForms = AccessPermission().also { it.setCanModifyAnnotations(false) }
        assertNull(FillIo.permissionProblem(onlyForms.permissionBytes))
    }

    @Test fun fillSessionUndoRedoAndPersistence() {
        val s = FillSession()
        val item = FillItem("a", 0, ItemKind.TEXT, NormRect(0.1f, 0.1f, 0.3f, 0.15f), text = "Hi \"there\"\nline 2", color = FillPalette.BLUE)
        s.setOverrides(mapOf("x" to "1")); s.addItem(item); s.replaceItem(item.copy(text = "changed")); s.removeItem("a")
        assertTrue(s.dirty.value); assertTrue(s.current.items.isEmpty())
        s.undo(); assertEquals("changed", s.find("a")!!.text)
        s.undo(); assertEquals("Hi \"there\"\nline 2", s.find("a")!!.text)
        s.redo(); assertEquals("changed", s.find("a")!!.text)
        s.addItem(item.copy(id = "b")); assertFalse(s.canRedo.value)
        repeat(10) { s.undo() }
        assertFalse(s.dirty.value); assertFalse(s.canUndo.value)
        val state = FillState(mapOf("k" to "v\n2"), listOf(item, item.copy(id = "sig", kind = ItemKind.SIGNATURE, sigId = "s1", turns = 3)))
        assertEquals(state, FillJson.decode(FillJson.encode(state)))
        val store = FillPendingStore(tmp.newFolder())
        assertNull(store.load("d")); store.save("d", state); assertEquals(state, store.load("d")); store.clear("d"); assertNull(store.load("d"))
        assertTrue(FillJson.decode("{oops").isEmpty)
    }
}
