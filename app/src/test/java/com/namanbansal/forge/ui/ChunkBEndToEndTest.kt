package com.namanbansal.forge.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.FakeMediaProvider
import com.namanbansal.forge.core.FormFixtures
import com.namanbansal.forge.core.ImageFixtures
import com.namanbansal.forge.core.PdfBoxRasterizer
import com.namanbansal.forge.core.PdfFixtures
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.RenderProbe
import com.namanbansal.forge.core.ToolPhase
import com.namanbansal.forge.core.ToolViewModel
import com.namanbansal.forge.core.pdf.BookmarkEntry
import com.namanbansal.forge.core.pdf.LinkEdits
import com.namanbansal.forge.core.pdf.NewLink
import com.namanbansal.forge.core.pdf.PdfBookmarks
import com.namanbansal.forge.core.pdf.PdfLinks
import com.namanbansal.forge.core.pdf.PdfMetadataEditor
import com.namanbansal.forge.core.pdf.PdfOps
import com.namanbansal.forge.core.pdf.PdfPermissions
import com.namanbansal.forge.core.pdf.PdfProtector
import com.namanbansal.forge.tools.pdf.BookmarksViewModel
import com.namanbansal.forge.tools.pdf.CompressViewModel
import com.namanbansal.forge.tools.pdf.CropViewModel
import com.namanbansal.forge.tools.pdf.FlattenViewModel
import com.namanbansal.forge.tools.pdf.GrayscaleViewModel
import com.namanbansal.forge.tools.pdf.ImagesToPdfViewModel
import com.namanbansal.forge.tools.pdf.ImposeViewModel
import com.namanbansal.forge.tools.pdf.LinksViewModel
import com.namanbansal.forge.tools.pdf.MetadataViewModel
import com.namanbansal.forge.tools.pdf.OptimiseViewModel
import com.namanbansal.forge.tools.pdf.PdfToImagesViewModel
import com.namanbansal.forge.tools.pdf.ProtectViewModel
import com.namanbansal.forge.tools.pdf.RepairViewModel
import com.namanbansal.forge.tools.pdf.SanitizeViewModel
import com.namanbansal.forge.tools.pdf.StampViewModel
import com.namanbansal.forge.tools.pdf.UnlockViewModel
import com.namanbansal.forge.tools.pdf.WatermarkViewModel
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionJavaScript
import org.junit.After
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Each Chunk B tool, whole: pick -> options -> run -> read the saved file back. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChunkBEndToEndTest {
    @get:Rule val tmp = TemporaryFolder()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var media: FakeMediaProvider

    @Before fun init() {
        PDFBoxResourceLoader.init(app)
        PdfBoxRasterizer.install()
        media = Robolectric.setupContentProvider(FakeMediaProvider::class.java, "media")
        media.dir = tmp.newFolder("media")
    }

    @After fun done() { PdfBoxRasterizer.uninstall() }

    private fun pump(timeoutMs: Long = 90_000, until: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!until() && System.currentTimeMillis() < end) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)); Thread.sleep(20) }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("condition not reached in time", until())
    }

    private fun picked(f: File, mime: String = "application/pdf") = PickedFile(Uri.fromFile(f), f.name, f.length(), mime)

    private fun <V : com.namanbansal.forge.core.pdf.PdfToolViewModel> open(vm: V, f: File): V {
        vm.onFilesPicked(listOf(picked(f)))
        pump { vm.source.value != null }
        return vm
    }

    private fun run(vm: ToolViewModel) {
        vm.start()
        pump { vm.phase.value == ToolPhase.Result || vm.phase.value == ToolPhase.Failed }
        assertEquals(vm.error.value, ToolPhase.Result, vm.phase.value)
    }

    private fun out(vm: ToolViewModel): File = media.rows.values.first { it.values.getAsString("_display_name") == vm.result.value!!.output.displayName }.file

    // ---- Compress / Grayscale / Optimise
    @Test fun compressShrinksAndNeverGrows() {
        val vm = open(CompressViewModel(app, SavedStateHandle()), ImageFixtures.imagePdf(tmp.newFile("big.pdf"), pages = 3))
        vm.setPreset(0)
        pump { vm.estimate.value is CompressViewModel.Est.Ready }
        val est = (vm.estimate.value as CompressViewModel.Est.Ready).e
        run(vm)
        val r = vm.result.value!!
        assertTrue("${r.afterSize} vs ${r.beforeSize}", r.afterSize < r.beforeSize / 5)
        assertEquals("Compressed", r.headline)
        assertTrue(est.estimatedBytes < est.originalBytes)
        assertTrue(RenderProbe.text(out(vm)).contains("Page 1"))

        // a file with nothing to gain: the output is never bigger than the input
        val tiny = open(CompressViewModel(app, SavedStateHandle()), PdfFixtures.create(tmp.newFile("tiny.pdf")))
        run(tiny)
        assertTrue(tiny.result.value!!.afterSize <= tiny.result.value!!.beforeSize)
    }

    @Test fun grayscaleRunsAndProducesNeutralPixels() {
        val vm = open(GrayscaleViewModel(app, SavedStateHandle()), ImageFixtures.imagePdf(tmp.newFile("c.pdf"), pages = 1, px = 300))
        run(vm)
        val p = RenderProbe.pixel(RenderProbe.render(out(vm)), 420f / 595f, (842f - 675f) / 842f)
        assertTrue(Math.abs(RenderProbe.r(p) - RenderProbe.g(p)) <= 2 && Math.abs(RenderProbe.g(p) - RenderProbe.b(p)) <= 2)
    }

    @Test fun optimiseKeepsContent() {
        val vm = open(OptimiseViewModel(app, SavedStateHandle()), PdfFixtures.withText(tmp.newFile("o.pdf"), 3))
        run(vm)
        assertTrue(RenderProbe.text(out(vm)).contains("Page 3"))
    }

    // ---- Watermark / stamps
    @Test fun watermarkAndPageNumbers() {
        val wm = open(WatermarkViewModel(app, SavedStateHandle()), PdfFixtures.create(tmp.newFile("w.pdf"), pages = 2))
        wm.setText("HELLO MARK"); wm.setRotation(0); wm.setSelection(setOf(1))
        run(wm)
        PdfOps.load(out(wm)).use { d ->
            val st = com.tom_roush.pdfbox.text.PDFTextStripper()
            st.startPage = 1; st.endPage = 1; assertFalse(st.getText(d).contains("HELLO"))
            st.startPage = 2; st.endPage = 2; assertTrue(st.getText(d).contains("HELLO MARK"))
        }
        val st = open(StampViewModel(app, SavedStateHandle()), PdfFixtures.create(tmp.newFile("s.pdf"), pages = 3))
        st.set("fmt", 1)
        run(st)
        assertTrue(RenderProbe.text(out(st)).contains("Page 3 of 3"))
    }

    @Test fun watermarkPreviewRenders() {
        val wm = open(WatermarkViewModel(app, SavedStateHandle()), PdfFixtures.withText(tmp.newFile("pv.pdf"), 2))
        var bmp: Bitmap? = null
        kotlinx.coroutines.runBlocking { bmp = wm.preview(0, 400) }
        assertNotNull(bmp); assertEquals(400, bmp!!.width)
    }

    // ---- Protect / Unlock
    @Test fun protectThenUnlockRoundTrip() {
        val vm = open(ProtectViewModel(app, SavedStateHandle()), PdfFixtures.withText(tmp.newFile("pr.pdf"), 2))
        assertNotNull(vm.problem()) // nothing set yet
        vm.setUser("Op3n-sesame!"); vm.setConfirm("Op3n-sesame!"); vm.setOwner("owner-pw"); vm.setPerm(1, false)
        assertNull(vm.problem())
        run(vm)
        val protectedFile = out(vm)
        try { PDDocument.load(protectedFile).close(); fail() } catch (_: InvalidPasswordException) {}
        PDDocument.load(protectedFile, "Op3n-sesame!").use { assertEquals(256, it.encryption.length); assertFalse(it.currentAccessPermission.canExtractContent()) }

        val un = UnlockViewModel(app, SavedStateHandle())
        un.onFilesPicked(listOf(picked(protectedFile)))
        pump { un.passwordPrompt.value != null }
        un.submitPassword("wrong"); pump { un.passwordPrompt.value?.wrong == true }
        un.submitPassword("Op3n-sesame!"); pump { un.source.value != null }
        run(un)
        PDDocument.load(out(un)).use { assertFalse(it.isEncrypted); assertEquals(2, it.numberOfPages) }
    }

    @Test fun protectRejectsMismatchedPasswords() {
        val vm = open(ProtectViewModel(app, SavedStateHandle()), PdfFixtures.create(tmp.newFile("m.pdf")))
        vm.setUser("abc12345"); vm.setConfirm("abc1234")
        assertTrue(vm.problem()!!.contains("match"))
    }

    // ---- PDF -> images, images -> PDF
    @Test fun pdfToImagesSavesOneFilePerPageAndAZip() {
        val vm = open(PdfToImagesViewModel(app, SavedStateHandle()), PdfFixtures.withText(tmp.newFile("doc.pdf"), 3, uniqueHeights = false))
        vm.setDpi(72); vm.setFormat(1); vm.setZip(true); vm.setSelection(setOf(0, 2))
        run(vm)
        val r = vm.result.value!!
        assertEquals(2, r.outputs.size)
        assertEquals(listOf("doc_page-01.jpg", "doc_page-03.jpg"), r.outputs.map { it.displayName })
        assertTrue(r.outputs.all { it.folder == "Pictures/Forge/doc" })
        assertNotNull(r.archive)
        val bmp = android.graphics.BitmapFactory.decodeFile(media.rows.values.first { it.values.getAsString("_display_name") == "doc_page-01.jpg" }.file.absolutePath)
        assertEquals(595, bmp.width)
    }

    @Test fun imagesToPdfOrdersAndSizesPages() {
        fun img(name: String, w: Int, h: Int) = File(tmp.root, name).also { f -> f.outputStream().use { ImageFixtures.photo(w, h).compress(Bitmap.CompressFormat.JPEG, 90, it) } }
        val a = img("a.jpg", 300, 400); val b = img("b.jpg", 400, 300)
        val vm = ImagesToPdfViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(picked(a, "image/jpeg"), picked(b, "image/jpeg")))
        pump { vm.items.value.size == 2 }
        vm.move(1, 0); vm.dragFinished()
        run(vm)
        PdfOps.load(out(vm)).use { d ->
            assertEquals(2, d.numberOfPages)
            assertTrue(d.getPage(0).mediaBox.width > d.getPage(0).mediaBox.height) // the landscape picture is first now
            assertTrue(d.getPage(1).mediaBox.width < d.getPage(1).mediaBox.height)
        }
    }

    // ---- Crop / N-up
    @Test fun cropAndBooklet() {
        val c = open(CropViewModel(app, SavedStateHandle()), PdfFixtures.create(tmp.newFile("cr.pdf"), pages = 2))
        c.setBox(100, 100, 900, 900)
        run(c)
        PdfOps.load(out(c)).use { d -> assertEquals(476f, d.getPage(0).cropBox.width, 1f); assertEquals(595f, d.getPage(0).mediaBox.width, 1f) }

        val b = open(ImposeViewModel(app, SavedStateHandle()), PdfFixtures.withText(tmp.newFile("bk.pdf"), 6, uniqueHeights = false))
        b.setMode(2)
        run(b)
        PdfOps.load(out(b)).use { assertEquals(4, it.numberOfPages) } // 6 pages padded to 8 -> 4 sides
        assertTrue(b.result.value!!.output.displayName.contains("booklet"))
    }

    // ---- Flatten / Repair / Sanitise
    @Test fun flattenRemovesFields() {
        val vm = open(FlattenViewModel(app, SavedStateHandle()), FormFixtures.acroForm(tmp.newFile("fm.pdf")))
        pump { vm.counts.value != null }
        run(vm)
        PdfOps.load(out(vm)).use { assertTrue(it.documentCatalog.acroForm?.fields.isNullOrEmpty()) }
    }

    @Test fun repairRecoversATruncatedFile() {
        val good = PdfFixtures.withText(tmp.newFile("g.pdf"), 3)
        val cut = File(tmp.root, "cut.pdf").apply { writeBytes(good.readBytes().let { it.copyOf((it.size * 0.9).toInt()) }) }
        val vm = RepairViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(picked(cut)))
        pump { vm.diagnosis.value != null }
        run(vm)
        assertTrue(vm.result.value!!.detail!!.contains("Recovered"))
        PdfOps.load(out(vm)).use { assertTrue(it.numberOfPages >= 1) }
        // and a file that is not a PDF at all says so
        val junk = RepairViewModel(app, SavedStateHandle())
        junk.onFilesPicked(listOf(picked(PdfFixtures.corrupt(File(tmp.root, "junk.pdf")))))
        pump { junk.diagnosis.value != null }
        junk.start(); pump { junk.phase.value == ToolPhase.Failed }
        assertTrue(junk.error.value!!, junk.error.value!!.contains("too damaged"))
    }

    @Test fun sanitiseRemovesScriptsAndMetadata() {
        val f = File(tmp.root, "dirty.pdf")
        PDDocument().use { d ->
            d.addPage(com.tom_roush.pdfbox.pdmodel.PDPage()); d.documentCatalog.openAction = PDActionJavaScript("app.alert(1)")
            PdfMetadataEditor.write(d, com.namanbansal.forge.core.pdf.PdfMeta(title = "Private title")); d.save(f)
        }
        val vm = open(SanitizeViewModel(app, SavedStateHandle()), f)
        pump { vm.found.value != null }
        assertEquals(1, vm.found.value!![com.namanbansal.forge.core.pdf.SanitizeKind.JAVASCRIPT])
        run(vm)
        val raw = String(out(vm).readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains("app.alert")); assertFalse(raw.contains("Private title"))
    }

    // ---- Bookmarks / Metadata / Links
    @Test fun bookmarksMetadataAndLinks() {
        val bm = open(BookmarksViewModel(app, SavedStateHandle()), PdfFixtures.create(tmp.newFile("bm.pdf"), pages = 4))
        pump { bm.loaded.value }
        bm.add("Intro", 0, null); bm.add("Details", 2, 0); bm.indent(1)
        run(bm)
        assertEquals(listOf(BookmarkEntry("Intro", 0, 0), BookmarkEntry("Details", 2, 1)), PdfOps.load(out(bm)).use { PdfBookmarks.read(it) })

        val md = open(MetadataViewModel(app, SavedStateHandle()), PdfFixtures.create(tmp.newFile("md.pdf")))
        pump { md.loaded.value }
        md.set("title", "My title"); md.set("author", "Me")
        run(md)
        PdfOps.load(out(md)).use { d -> assertEquals("My title", d.documentInformation.title); assertTrue(PdfMetadataEditor.readXmp(d)!!.contains("My title")) }

        val lk = open(LinksViewModel(app, SavedStateHandle()), PdfFixtures.create(tmp.newFile("lk.pdf"), pages = 2))
        pump { lk.found.value != null }
        lk.addLink(NewLink(1, 0.1f, 0.1f, 0.5f, 0.2f, "forge.app"))
        run(lk)
        assertEquals("https://forge.app", PdfOps.load(out(lk)).use { PdfLinks.list(it) }.single().uri)
    }

    @Test fun encryptedInputKeepsItsPasswordThroughATool() {
        val vm = WatermarkViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(picked(PdfFixtures.withText(tmp.newFile("enc.pdf"), 2, userPassword = "pw1"))))
        pump { vm.passwordPrompt.value != null }
        vm.submitPassword("pw1"); pump { vm.source.value != null }
        run(vm)
        PDDocument.load(out(vm), "pw1").use { assertTrue(it.isEncrypted) }
    }
}
