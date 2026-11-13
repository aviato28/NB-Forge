package com.namanbansal.forge.screenshots

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.FormFixtures
import com.namanbansal.forge.core.ImageFixtures
import com.namanbansal.forge.core.PdfBoxRasterizer
import com.namanbansal.forge.core.PdfFixtures
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.SanitizeFixtures
import com.namanbansal.forge.core.SavedOutput
import com.namanbansal.forge.core.ToolResult
import com.namanbansal.forge.core.pdf.NewLink
import com.namanbansal.forge.core.pdf.PdfPermissions
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.tools.ToolRegistry
import com.namanbansal.forge.tools.pdf.BookmarksScreen
import com.namanbansal.forge.tools.pdf.BookmarksViewModel
import com.namanbansal.forge.tools.pdf.CompressScreen
import com.namanbansal.forge.tools.pdf.CompressViewModel
import com.namanbansal.forge.tools.pdf.CropScreen
import com.namanbansal.forge.tools.pdf.CropViewModel
import com.namanbansal.forge.tools.pdf.FlattenScreen
import com.namanbansal.forge.tools.pdf.FlattenViewModel
import com.namanbansal.forge.tools.pdf.GrayscaleScreen
import com.namanbansal.forge.tools.pdf.GrayscaleViewModel
import com.namanbansal.forge.tools.pdf.ImagesToPdfScreen
import com.namanbansal.forge.tools.pdf.ImagesToPdfViewModel
import com.namanbansal.forge.tools.pdf.ImposeScreen
import com.namanbansal.forge.tools.pdf.ImposeViewModel
import com.namanbansal.forge.tools.pdf.LinksScreen
import com.namanbansal.forge.tools.pdf.LinksViewModel
import com.namanbansal.forge.tools.pdf.MetadataScreen
import com.namanbansal.forge.tools.pdf.MetadataViewModel
import com.namanbansal.forge.tools.pdf.OptimiseScreen
import com.namanbansal.forge.tools.pdf.OptimiseViewModel
import com.namanbansal.forge.tools.pdf.PdfToImagesScreen
import com.namanbansal.forge.tools.pdf.PdfToImagesViewModel
import com.namanbansal.forge.tools.pdf.ProtectScreen
import com.namanbansal.forge.tools.pdf.ProtectViewModel
import com.namanbansal.forge.tools.pdf.RepairScreen
import com.namanbansal.forge.tools.pdf.RepairViewModel
import com.namanbansal.forge.tools.pdf.SanitizeScreen
import com.namanbansal.forge.tools.pdf.SanitizeViewModel
import com.namanbansal.forge.tools.pdf.StampScreen
import com.namanbansal.forge.tools.pdf.StampViewModel
import com.namanbansal.forge.tools.pdf.UnlockScreen
import com.namanbansal.forge.tools.pdf.UnlockViewModel
import com.namanbansal.forge.tools.pdf.WatermarkScreen
import com.namanbansal.forge.tools.pdf.WatermarkViewModel
import com.namanbansal.forge.ui.toolkit.ResultView
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Light + dark screenshots of every Chunk B tool screen, in a state worth looking at. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class ChunkBScreenshotTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun init() { PDFBoxResourceLoader.init(app); PdfBoxRasterizer.install() }
    @After fun done() { PdfBoxRasterizer.uninstall() }

    private fun host(code: String) = ToolHost(ToolRegistry.get(code)!!, onBack = {})
    private fun picked(f: File, size: Long = f.length(), mime: String = "application/pdf") = PickedFile(Uri.fromFile(f), f.name, size, mime)
    private fun text(name: String, pages: Int = 8, size: Long = 2_400_000, pw: String? = null, body: (Int) -> List<String> = { listOf("Quarterly report line one", "Totals and figures", "Signed and approved") }) =
        picked(PdfFixtures.withText(File(tmp.root, name), pages, uniqueHeights = false, userPassword = pw, body = body), size)
    private fun settle() {
        repeat(10) {
            compose.mainClock.advanceTimeBy(300)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
            Thread.sleep(150); compose.waitForIdle()
        }
    }

    private fun <V : com.namanbansal.forge.core.pdf.PdfToolViewModel> ready(vm: V, f: PickedFile): V {
        vm.onFilesPicked(listOf(f))
        compose.pumpUntil { vm.source.value != null && vm.thumbnails.value != null }
        return vm
    }

    @Test fun compress() {
        val vm = ready(CompressViewModel(app, SavedStateHandle()), picked(ImageFixtures.imagePdf(File(tmp.root, "scan_pack.pdf"), pages = 4, px = 1100)))
        vm.setPreset(1)
        val end = System.currentTimeMillis() + 60_000
        while (vm.estimate.value !is CompressViewModel.Est.Ready && System.currentTimeMillis() < end) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200)); Thread.sleep(30) }
        compose.shootBoth("p9-compress", settle = ::settle) { CompressScreen(host("P9"), vm) }
    }

    @Test fun compressResult() {
        val out = SavedOutput(Uri.parse("content://x/c"), "scan_pack_compressed.pdf", 410_000, "application/pdf")
        val r = ToolResult(out, "scan_pack.pdf", "P9", 8_200_000, 410_000, "Compressed", "8.2 MB to 410 KB · 4 pictures recompressed · 3 duplicate pictures merged")
        compose.shootBoth("p9-compress-result") { ResultView(r, ToolRegistry.get("P9")!!, onOpenTool = { _, _ -> }, onEditAgain = {}, onNewFile = {}, onDone = {}) }
    }

    @Test fun compressKeptOriginal() {
        val out = SavedOutput(Uri.parse("content://x/c"), "notes_compressed.pdf", 52_000, "application/pdf")
        val r = ToolResult(out, "notes.pdf", "P9", 52_000, 52_000, "Already compact", "Compressing would have made this file larger, so the original was kept unchanged.")
        compose.shootBoth("p9-compress-kept") { ResultView(r, ToolRegistry.get("P9")!!, onOpenTool = { _, _ -> }, onEditAgain = {}, onNewFile = {}, onDone = {}) }
    }

    @Test fun grayscale() {
        val vm = ready(GrayscaleViewModel(app, SavedStateHandle()), picked(ImageFixtures.imagePdf(File(tmp.root, "brochure.pdf"), pages = 2, px = 500)))
        compose.shootBoth("p10-grayscale", settle = ::settle) { GrayscaleScreen(host("P10"), vm) }
    }

    @Test fun watermark() {
        val vm = ready(WatermarkViewModel(app, SavedStateHandle()), text("contract.pdf", 6))
        vm.setText("DRAFT"); vm.setOpacity(35)
        compose.shootBoth("p20-watermark", settle = ::settle) { WatermarkScreen(host("P20"), vm) }
    }

    @Test fun watermarkTiled() {
        val vm = ready(WatermarkViewModel(app, SavedStateHandle()), text("contract.pdf", 6))
        vm.setText("CONFIDENTIAL"); vm.setSize(28); vm.setPosition(9); vm.setRotation(30); vm.setColor(2)
        compose.shootBoth("p20-watermark-tiled", settle = ::settle) { WatermarkScreen(host("P20"), vm) }
    }

    @Test fun pageNumbers() {
        val vm = ready(StampViewModel(app, SavedStateHandle()), text("report.pdf", 10))
        vm.set("fmt", 1); vm.set("hdrOn", true); vm.set("hdr", "{filename}  -  {date}"); vm.set("batesOn", true); vm.set("batesPre", "ACME-")
        compose.shootBoth("p21-page-numbers", settle = ::settle) { StampScreen(host("P21"), vm) }
    }

    @Test fun protect() {
        val vm = ready(ProtectViewModel(app, SavedStateHandle()), text("payslip.pdf", 2))
        vm.setUser("Tr1cky-Horse"); vm.setConfirm("Tr1cky-Horse"); vm.setPerm(1, false); vm.setPerm(2, false); vm.setOwner("owner-key-9")
        compose.shootBoth("p29-protect", settle = ::settle) { ProtectScreen(host("P29"), vm) }
    }

    @Test fun protectMismatch() {
        val vm = ready(ProtectViewModel(app, SavedStateHandle()), text("payslip.pdf", 2))
        vm.setUser("abc"); vm.setConfirm("abd")
        compose.shootBoth("p29-protect-weak", settle = ::settle) { ProtectScreen(host("P29"), vm) }
    }

    @Test fun unlock() {
        val vm = UnlockViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(text("locked.pdf", 3, pw = "pw1")))
        compose.pumpUntil { vm.passwordPrompt.value != null }
        vm.submitPassword("pw1")
        compose.pumpUntil { vm.source.value != null && vm.thumbnails.value != null }
        compose.shootBoth("p30-unlock", settle = ::settle) { UnlockScreen(host("P30"), vm) }
    }

    @Test fun pdfToImages() {
        val vm = ready(PdfToImagesViewModel(app, SavedStateHandle()), text("brochure.pdf", 6))
        vm.setDpi(300); vm.setFormat(1); vm.setZip(true); vm.setSelection(setOf(0, 1, 2, 3))
        compose.shootBoth("p31-pdf-to-images", settle = ::settle) { PdfToImagesScreen(host("P31"), vm) }
    }

    private fun photos(): List<PickedFile> {
        val specs = listOf(Triple("IMG_2041.jpg", 900, 1200), Triple("scan_receipt.jpg", 1200, 900), Triple("IMG_2044.jpg", 900, 1200))
        return specs.map { (n, w, h) ->
            val f = File(tmp.root, n)
            f.outputStream().use { ImageFixtures.photo(w, h, n.hashCode().toLong()).compress(Bitmap.CompressFormat.JPEG, 85, it) }
            picked(f, mime = "image/jpeg")
        }
    }

    @Test fun imagesToPdfList() {
        val vm = ImagesToPdfViewModel(app, SavedStateHandle())
        vm.onFilesPicked(photos())
        compose.pumpUntil { vm.items.value.size == 3 }
        compose.shootBoth("p32-images-to-pdf", settle = ::settle) { ImagesToPdfScreen(host("P32"), vm) }
    }

    @Test fun imagesToPdfPage() {
        val vm = ImagesToPdfViewModel(app, SavedStateHandle())
        vm.onFilesPicked(photos())
        compose.pumpUntil { vm.items.value.size == 3 }
        vm.setTab(1)
        compose.shootBoth("p32-images-to-pdf-page", settle = ::settle) { ImagesToPdfScreen(host("P32"), vm) }
    }

    @Test fun crop() {
        val vm = ready(CropViewModel(app, SavedStateHandle()), text("scan.pdf", 4))
        vm.setBox(120, 90, 880, 520)
        compose.shootBoth("p7-crop", settle = ::settle) { CropScreen(host("P7"), vm) }
    }

    @Test fun booklet() {
        val vm = ready(ImposeViewModel(app, SavedStateHandle()), text("newsletter.pdf", 6))
        vm.setMode(2); vm.setBorders(true)
        compose.shootBoth("p8-booklet", settle = ::settle) { ImposeScreen(host("P8"), vm) }
    }

    @Test fun fourUp() {
        val vm = ready(ImposeViewModel(app, SavedStateHandle()), text("slides.pdf", 9))
        vm.setMode(1); vm.setBorders(true)
        compose.shootBoth("p8-4up", settle = ::settle) { ImposeScreen(host("P8"), vm) }
    }

    @Test fun flatten() {
        val vm = ready(FlattenViewModel(app, SavedStateHandle()), picked(FormFixtures.acroForm(File(tmp.root, "application_form.pdf"), labels = true)))
        compose.pumpUntil { vm.counts.value != null }
        compose.shootBoth("p11-flatten", settle = ::settle) { FlattenScreen(host("P11"), vm) }
    }

    @Test fun repair() {
        val good = PdfFixtures.withText(File(tmp.root, "g.pdf"), 5)
        val cut = File(tmp.root, "damaged_report.pdf").apply { writeBytes(good.readBytes().let { it.copyOf((it.size * 0.9).toInt()) }) }
        val vm = RepairViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(picked(cut)))
        compose.pumpUntil { vm.diagnosis.value != null }
        compose.shootBoth("p12-repair", settle = ::settle) { RepairScreen(host("P12"), vm) }
    }

    @Test fun sanitise() {
        val vm = ready(SanitizeViewModel(app, SavedStateHandle()), picked(SanitizeFixtures.dirty(File(tmp.root, "form_to_send.pdf"))))
        compose.pumpUntil { vm.found.value != null }
        compose.shootBoth("p13-sanitise", settle = ::settle) { SanitizeScreen(host("P13"), vm) }
    }

    @Test fun optimise() {
        val vm = ready(OptimiseViewModel(app, SavedStateHandle()), text("long_report.pdf", 12))
        compose.shootBoth("p14-optimise", settle = ::settle) { OptimiseScreen(host("P14"), vm) }
    }

    @Test fun bookmarks() {
        val vm = ready(BookmarksViewModel(app, SavedStateHandle()), text("handbook.pdf", 12))
        compose.pumpUntil { vm.loaded.value }
        vm.add("Introduction", 0, null); vm.add("Getting started", 2, 0); vm.add("Installing", 3, 1); vm.add("Reference", 6, 0); vm.add("Appendix A", 10, 3)
        vm.indent(1); vm.indent(2); vm.indent(2); vm.outdent(2)
        compose.shootBoth("p22-bookmarks", settle = ::settle) { BookmarksScreen(host("P22"), vm) }
    }

    @Test fun metadata() {
        val vm = ready(MetadataViewModel(app, SavedStateHandle()), text("report.pdf", 3))
        compose.pumpUntil { vm.loaded.value }
        vm.set("title", "Q3 board report"); vm.set("author", "Finance team"); vm.set("keywords", "board, finance, 2026"); vm.set("created", "2026-10-02 09:15")
        compose.shootBoth("p23-metadata", settle = ::settle) { MetadataScreen(host("P23"), vm) }
    }

    @Test fun links() {
        val vm = ready(LinksViewModel(app, SavedStateHandle()), text("newsletter.pdf", 3, body = { listOf("Read more at https://forge.app/docs today", "Questions: hello@forge.app", "See www.example.org/news") }))
        compose.pumpUntil { vm.found.value != null && vm.detected.value != null }
        vm.addLink(NewLink(1, 0.1f, 0.5f, 0.6f, 0.58f, "https://forge.app/pricing")); vm.setAuto(true)
        compose.shootBoth("p24-links", settle = ::settle) { LinksScreen(host("P24"), vm) }
    }
}
