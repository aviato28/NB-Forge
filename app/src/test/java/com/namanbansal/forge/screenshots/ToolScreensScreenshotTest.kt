package com.namanbansal.forge.screenshots

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.PdfBoxRasterizer
import com.namanbansal.forge.core.PdfFixtures
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.SavedOutput
import com.namanbansal.forge.core.ToolResult
import com.namanbansal.forge.core.pdf.PdfThumbnails
import com.namanbansal.forge.tools.pdf.SplitMode
import com.namanbansal.forge.ui.toolkit.GridPage
import com.namanbansal.forge.ui.toolkit.PagePeekContent
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.tools.ToolRegistry
import com.namanbansal.forge.tools.pdf.ExtractScreen
import com.namanbansal.forge.tools.pdf.ExtractViewModel
import com.namanbansal.forge.tools.pdf.InsertScreen
import com.namanbansal.forge.tools.pdf.InsertViewModel
import com.namanbansal.forge.tools.pdf.MergeScreen
import com.namanbansal.forge.tools.pdf.MergeStatus
import com.namanbansal.forge.tools.pdf.MergeViewModel
import com.namanbansal.forge.tools.pdf.PageManagerScreen
import com.namanbansal.forge.tools.pdf.PageManagerViewModel
import com.namanbansal.forge.tools.pdf.SplitScreen
import com.namanbansal.forge.tools.pdf.SplitViewModel
import com.namanbansal.forge.tools.pdf.ViewerScreen
import com.namanbansal.forge.tools.pdf.ViewerViewModel
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

/** Screenshots of every new tool screen in the states a person actually sees. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class ToolScreensScreenshotTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun init() { PDFBoxResourceLoader.init(app); PdfBoxRasterizer.install() }
    @After fun done() { PdfBoxRasterizer.uninstall() }

    private fun host(code: String) = ToolHost(ToolRegistry.get(code)!!, onBack = {})

    private fun pdf(name: String, pages: Int, size: Long = 2_400_000, pw: String? = null, title: String = "Quarterly report"): PickedFile {
        val f = PdfFixtures.withText(File(tmp.root, name), pages, title = title, userPassword = pw, uniqueHeights = false)
        return PickedFile(Uri.fromFile(f), name, size, "application/pdf")
    }

    @Test fun merge() {
        val vm = MergeViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(pdf("contract_signed.pdf", 6, 1_800_000), pdf("appendix_a.pdf", 12, 4_200_000), pdf("invoice_oct.pdf", 3, 640_000)))
        compose.pumpUntil { vm.items.value.size == 3 && vm.items.value.all { it.status == MergeStatus.Ready } }
        compose.shootBoth("merge", settle = { Thread.sleep(200); compose.waitForIdle() }) { MergeScreen(host("P1"), vm) }
    }

    @Test fun mergeExpanded() {
        val vm = MergeViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(pdf("contract_signed.pdf", 6, 1_800_000), pdf("appendix_a.pdf", 12, 4_200_000), pdf("invoice_oct.pdf", 3, 640_000)))
        compose.pumpUntil { vm.items.value.size == 3 && vm.items.value.all { it.status == MergeStatus.Ready } }
        vm.toggleExpanded(vm.items.value[1].id)
        compose.shootBoth("merge-expanded", settle = { Thread.sleep(400); compose.waitForIdle() }) { MergeScreen(host("P1"), vm) }
    }

    @Test fun mergeWithProblems() {
        val bad = File(tmp.root, "broken_scan.pdf").also { PdfFixtures.corrupt(it) }
        val vm = MergeViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(pdf("contract_signed.pdf", 6, 1_800_000), PickedFile(Uri.fromFile(bad), "broken_scan.pdf", 2048, "application/pdf"), pdf("salary_slip.pdf", 2, 300_000)))
        compose.pumpUntil { vm.items.value.size == 3 && vm.items.value.none { it.status == MergeStatus.Opening } }
        compose.shootBoth("merge-problems", settle = { Thread.sleep(200); compose.waitForIdle() }) { MergeScreen(host("P1"), vm) }
    }

    @Test fun splitCut() {
        val vm = SplitViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(pdf("report_2026.pdf", 12, 3_100_000)))
        compose.pumpUntil { vm.source.value != null && vm.thumbnails.value != null && vm.bookmarks.value != null }
        vm.toggleCut(3); vm.toggleCut(7); vm.toggleCut(9)
        compose.shootBoth("split-cut", settle = { Thread.sleep(300); compose.waitForIdle() }) { SplitScreen(host("P2"), vm) }
    }

    @Test fun splitRanges() {
        val vm = SplitViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(pdf("report_2026.pdf", 12, 3_100_000)))
        compose.pumpUntil { vm.source.value != null && vm.thumbnails.value != null && vm.bookmarks.value != null }
        vm.setMode(SplitMode.Ranges); vm.setRanges("1-3, 5, 8-10")
        compose.shootBoth("split-ranges", settle = { Thread.sleep(300); compose.waitForIdle() }) { SplitScreen(host("P2"), vm) }
    }

    @Test fun splitInvalidRange() {
        val vm = SplitViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(pdf("report_2026.pdf", 12, 3_100_000)))
        compose.pumpUntil { vm.source.value != null && vm.thumbnails.value != null && vm.bookmarks.value != null }
        vm.setMode(SplitMode.Ranges); vm.setRanges("1-3, 15")
        compose.shootBoth("split-invalid", settle = { Thread.sleep(200); compose.waitForIdle() }) { SplitScreen(host("P2"), vm) }
    }

    @Test fun splitResult() {
        fun o(n: String) = SavedOutput(Uri.parse("content://x/$n"), "report_2026_$n.pdf", 300_000, "application/pdf")
        val outs = listOf(o("p1-3"), o("p5"), o("p8-10"))
        val result = ToolResult(outs.first(), "report_2026.pdf", "P2", 3_100_000, 900_000, "Split into 3 files", "12 pages · ZIP included", outs,
            SavedOutput(Uri.parse("content://x/zip"), "report_2026_split.zip", 880_000, "application/zip"))
        compose.shootBoth("split-result") { ResultView(result, ToolRegistry.get("P2")!!, onOpenTool = { _, _ -> }, onEditAgain = {}, onNewFile = {}, onDone = {}) }
    }

    @Test fun mergeResult() {
        val result = ToolResult(
            SavedOutput(Uri.parse("content://x/1"), "contract_signed_merged.pdf", 6_200_000, "application/pdf"),
            "contract_signed.pdf", "P1", 6_640_000, 6_200_000, "Merged successfully", "3 files · 21 pages",
        )
        compose.shootBoth("merge-result") { ResultView(result, ToolRegistry.get("P1")!!, onOpenTool = { _, _ -> }, onEditAgain = {}, onNewFile = {}, onDone = {}) }
    }

    @Test fun pageManager() {
        val vm = PageManagerViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(pdf("invoice_oct.pdf", 9, 4_200_000)))
        compose.pumpUntil { vm.source.value != null && vm.thumbnails.value != null && vm.state.value.pages.isNotEmpty() }
        val ids = vm.state.value.pages.map { it.id }
        vm.toggle(ids[3]); vm.rotateSelected()
        vm.selectNone(); vm.toggle(ids[3])
        vm.dragStart(); vm.dragMove(8, 1); vm.dragEnd()
        compose.shootBoth("page-manager", settle = { Thread.sleep(300); compose.waitForIdle() }) { PageManagerScreen(host("P3"), vm) }
    }

    @Test fun extract() {
        val vm = ExtractViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(pdf("annual_report.pdf", 9, 5_100_000)))
        compose.pumpUntil { vm.source.value != null && vm.thumbnails.value != null }
        vm.toggle(1); vm.toggle(2); vm.toggle(3); vm.toggle(6)
        compose.shootBoth("extract", settle = { Thread.sleep(300); compose.waitForIdle() }) { ExtractScreen(host("P5"), vm) }
    }

    @Test fun extractInvalidRange() {
        val vm = ExtractViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(pdf("annual_report.pdf", 9, 5_100_000)))
        compose.pumpUntil { vm.source.value != null && vm.thumbnails.value != null }
        vm.setText("2-4, 7"); vm.setText("2-4, 70")
        compose.shootBoth("extract-invalid", settle = { Thread.sleep(300); compose.waitForIdle() }) { ExtractScreen(host("P5"), vm) }
    }

    @Test fun pagePeek() {
        val f = PdfFixtures.withText(File(tmp.root, "peek.pdf"), 8, title = "Quarterly report", uniqueHeights = false)
        val thumbs = PdfThumbnails(f)
        val pages = List(8) { GridPage("p$it", thumbs, it, "${it + 1}") }
        compose.shootBoth("page-peek", settle = { Thread.sleep(500); compose.waitForIdle() }) { PagePeekContent(pages, 2, {}, {}) }
    }

    @Test fun insertBlank() {
        val vm = InsertViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(pdf("thesis_draft.pdf", 14, 7_000_000)))
        compose.pumpUntil { vm.source.value != null && vm.thumbnails.value != null }
        vm.setBlankCount(2); vm.setInsertGap(3)
        compose.shootBoth("insert-blank", settle = { Thread.sleep(200); compose.waitForIdle() }) { InsertScreen(host("P6"), vm) }
    }

    @Test fun insertFromPdf() {
        val vm = InsertViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(pdf("thesis_draft.pdf", 14, 7_000_000)))
        compose.pumpUntil { vm.source.value != null && vm.thumbnails.value != null }
        val other = pdf("cover_page.pdf", 4, 400_000)
        vm.setFromPdf(true); vm.pickOther(other.uri)
        compose.pumpUntil { vm.other.value != null }
        vm.setInsertGap(2); vm.setOtherRange("1-2")
        compose.shootBoth("insert-from-pdf", settle = { Thread.sleep(500); compose.waitForIdle() }) { InsertScreen(host("P6"), vm) }
    }

    @Test fun insertPickPages() {
        val vm = InsertViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(pdf("thesis_draft.pdf", 14, 7_000_000)))
        compose.pumpUntil { vm.source.value != null && vm.thumbnails.value != null }
        vm.setFromPdf(true); vm.pickOther(pdf("cover_page.pdf", 6, 400_000).uri)
        compose.pumpUntil { vm.other.value != null }
        vm.setStep(0); vm.setOtherSelection(setOf(0, 1, 4))
        compose.shootBoth("insert-pick-pages", settle = { Thread.sleep(500); compose.waitForIdle() }) { InsertScreen(host("P6"), vm) }
    }

    private fun viewer(searchFor: String?): ViewerViewModel {
        val vm = ViewerViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(pdf("annual_report.pdf", 12, 5_100_000, title = "Annual report 2026")))
        compose.pumpUntil { vm.source.value != null && vm.thumbnails.value != null && vm.aspects.value != null }
        if (searchFor != null) {
            vm.search(searchFor)
            compose.pumpUntil { vm.hits.value.size >= 3 && !vm.searching.value }
        }
        return vm
    }

    @Test fun viewerSearch() {
        val vm = viewer("figures")
        compose.shootBoth("viewer-search", settle = { Thread.sleep(400); compose.waitForIdle() }) { ViewerScreen(host("P40"), vm) }
    }

    @Test fun viewerPlainAndInverted() {
        val vm = viewer(null)
        vm.toggleInvert()
        compose.shootBoth("viewer-dark-pages", settle = { Thread.sleep(400); compose.waitForIdle() }) { ViewerScreen(host("P40"), vm) }
    }
}
