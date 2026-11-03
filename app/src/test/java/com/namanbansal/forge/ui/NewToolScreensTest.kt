package com.namanbansal.forge.ui

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.up
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.PdfBoxRasterizer
import com.namanbansal.forge.core.PdfFixtures
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.SavedOutput
import com.namanbansal.forge.core.ToolResult
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
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.toolkit.ResultView
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Compose smoke tests: every new tool screen renders, its primary action follows the state, and the new gestures work. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NewToolScreensTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun init() { PDFBoxResourceLoader.init(app); PdfBoxRasterizer.install() }
    @After fun done() { PdfBoxRasterizer.uninstall() }

    private fun host(code: String) = ToolHost(ToolRegistry.get(code)!!, onBack = {})

    private fun pump(until: () -> Boolean) {
        val end = System.currentTimeMillis() + 30_000
        while (!until() && System.currentTimeMillis() < end) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); compose.waitForIdle(); Thread.sleep(25)
        }
        assertTrue("condition not reached in time", until())
        compose.waitForIdle()
    }

    private fun pdf(name: String, pages: Int): PickedFile {
        val f = PdfFixtures.withText(File(tmp.root, name), pages, uniqueHeights = false)
        return PickedFile(Uri.fromFile(f), name, f.length(), "application/pdf")
    }

    // ---- every screen builds its view model through the default factory, like the nav graph --------------------------

    @Test fun everyNewScreenShowsItsPickStateViaTheDefaultViewModelFactory() {
        val cases = listOf(
            "P1" to "Choose PDFs to merge", "P2" to "Choose a PDF to split", "P3" to "Choose a PDF to organise",
            "P5" to "Choose a PDF to extract pages from", "P6" to "Choose the PDF to add pages to", "P40" to "Choose a PDF to read",
        )
        var current by androidx.compose.runtime.mutableStateOf(cases.first().first)
        compose.setContent { ForgeTheme { androidx.compose.runtime.key(current) { com.namanbansal.forge.tools.ToolScreens.screens.getValue(current)(host(current)) } } }
        for ((code, title) in cases) {
            current = code
            compose.waitForIdle()
            compose.onNodeWithText(title).assertExists()
        }
    }

    @Test fun mergeScreenListsFilesAndGuardsTheButton() {
        val vm = MergeViewModel(app, SavedStateHandle())
        compose.setContent { ForgeTheme { MergeScreen(host("P1"), vm) } }
        vm.onFilesPicked(listOf(pdf("a.pdf", 2), pdf("b.pdf", 3)))
        pump { vm.items.value.size == 2 && vm.items.value.all { it.status == MergeStatus.Ready } }
        compose.onNodeWithText("2 files").assertExists()
        compose.onNodeWithText("1 · a.pdf").assertExists()
        compose.onNodeWithText("2 · b.pdf").assertExists()
        compose.onNodeWithText("Add a blank page between files").assertExists()
        compose.onNode(hasText("Merge") and hasClickAction()).assertIsEnabled()
        compose.onNodeWithContentDescription("Remove b.pdf").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("Merge") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithText("Add at least two PDFs to merge").assertExists()
    }

    @Test fun mergeShowsAnErrorRowNamingTheBrokenFile() {
        val vm = MergeViewModel(app, SavedStateHandle())
        compose.setContent { ForgeTheme { MergeScreen(host("P1"), vm) } }
        val bad = PickedFile(Uri.fromFile(PdfFixtures.corrupt(File(tmp.root, "broken.pdf"))), "broken.pdf", 2048, "application/pdf")
        vm.onFilesPicked(listOf(pdf("a.pdf", 1), bad))
        pump { vm.items.value.size == 2 && vm.items.value.none { it.status == MergeStatus.Opening } }
        compose.onNodeWithText("\"broken.pdf\" can't be used", substring = true).assertExists()
        compose.onNodeWithText("Remove the file that can't be read to continue").assertExists()
        compose.onNode(hasText("Merge") and hasClickAction()).assertIsNotEnabled()
    }

    @Test fun splitValidatesRangesLiveAndShowsPreviewChips() {
        val vm = SplitViewModel(app, SavedStateHandle())
        compose.setContent { ForgeTheme { SplitScreen(host("P2"), vm) } }
        vm.onFilesPicked(listOf(pdf("s.pdf", 12)))
        pump { vm.source.value != null && vm.thumbnails.value != null && vm.bookmarks.value != null }
        compose.onNode(hasText("Split") and hasClickAction()).assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("1-3, 5")
        compose.waitForIdle()
        compose.onNodeWithText("p1-3 · 3 pages").assertExists()
        compose.onNodeWithText("p5 · 1 page").assertExists()
        compose.onNodeWithText("Split into 2 files").assertIsEnabled()
        vm.setRanges("1-3, 40"); compose.waitForIdle()
        compose.onNodeWithText("Page 40 doesn't exist. This PDF has 12 pages.").assertExists()
        compose.onNode(hasText("Split") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithText("Bookmarks is unavailable", substring = true).assertExists()
        compose.onNodeWithText("Also save a ZIP").assertExists()
    }

    @Test fun pageManagerRotateEnablesSaveAndShowsToolbar() {
        val vm = PageManagerViewModel(app, SavedStateHandle())
        compose.setContent { ForgeTheme { PageManagerScreen(host("P3"), vm) } }
        vm.onFilesPicked(listOf(pdf("pm.pdf", 6)))
        pump { vm.state.value.pages.size == 6 && vm.thumbnails.value != null }
        compose.onNodeWithText("6 pages · 0 selected").assertExists()
        compose.onNode(hasText("Save PDF") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithContentDescription("Page 2, from original page 2").performClick()
        compose.onNodeWithText("6 pages · 1 selected").assertExists()
        compose.onNodeWithContentDescription("Rotate selected pages right").performClick()
        compose.onNode(hasText("Save PDF") and hasClickAction()).assertIsEnabled()
        compose.onNodeWithContentDescription("Undo").performClick()
        compose.onNode(hasText("Save PDF") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithContentDescription("Redo").performClick()
        compose.onNodeWithContentDescription("Duplicate selected pages").performClick()
        compose.onNodeWithText("7 pages · 1 selected").assertExists()
        compose.onNodeWithContentDescription("Delete selected pages").performClick()
        compose.onNodeWithText("6 pages · 0 selected").assertExists()
    }

    @Test fun pageManagerDragReordersPages() {
        val vm = PageManagerViewModel(app, SavedStateHandle())
        compose.setContent { ForgeTheme { PageManagerScreen(host("P3"), vm) } }
        vm.onFilesPicked(listOf(pdf("pd.pdf", 6)))
        pump { vm.state.value.pages.size == 6 && vm.thumbnails.value != null }
        // Long-press page 1 and drag it right over page 3.
        compose.onNodeWithContentDescription("Page 1, from original page 1").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(androidx.compose.ui.geometry.Offset(width * 0.6f, 0f))
            moveBy(androidx.compose.ui.geometry.Offset(width * 0.6f, 0f))
            moveBy(androidx.compose.ui.geometry.Offset(width * 0.6f, 0f))
            moveBy(androidx.compose.ui.geometry.Offset(width * 0.6f, 0f))
            up()
        }
        compose.waitForIdle()
        val order = vm.state.value.pages.map { it.source }
        assertTrue("page 1 should have moved right, got $order", order.first() != 0 && order.indexOf(0) >= 1)
        assertTrue(vm.state.value.canUndo)
        vm.undo()
        assertEquals((0 until 6).toList(), vm.state.value.pages.map { it.source })
    }

    @Test fun extractSelectionDrivesTheButtonAndRangeView() {
        val vm = ExtractViewModel(app, SavedStateHandle())
        compose.setContent { ForgeTheme { ExtractScreen(host("P5"), vm) } }
        vm.onFilesPicked(listOf(pdf("e.pdf", 6)))
        pump { vm.source.value != null && vm.thumbnails.value != null }
        compose.onNode(hasText("Extract") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithContentDescription("Page 2").performClick()
        compose.onNodeWithContentDescription("Page 3").performClick()
        compose.onNodeWithText("Extract 2 pages").assertIsEnabled()
        compose.onNodeWithText("Page ranges").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("2-3").assertExists() // the text field shows the grid selection
        compose.onNodeWithText("p2-3").assertExists()
    }

    @Test fun insertDefaultsAndBlankSummary() {
        val vm = InsertViewModel(app, SavedStateHandle())
        compose.setContent { ForgeTheme { InsertScreen(host("P6"), vm) } }
        vm.onFilesPicked(listOf(pdf("i.pdf", 5)))
        pump { vm.source.value != null && vm.thumbnails.value != null }
        compose.onNodeWithText("Insert 1 page").assertIsEnabled()
        compose.onNodeWithText("Adds 1 page at the end. The new PDF will have 6 pages.").assertExists()
        compose.onNodeWithText("Before page").performClick()
        compose.onNodeWithText("Page number (1-5)").assertExists()
        compose.onNodeWithContentDescription("Increase Number of blank pages").performClick()
        compose.onNodeWithText("Insert 2 pages").assertExists()
        compose.onNodeWithText("From another PDF").performClick()
        compose.onNodeWithText("Choose PDF to take pages from").assertExists()
        compose.onNode(hasText("Insert") and hasClickAction()).assertIsNotEnabled()
    }

    @Test fun viewerShowsPagesIndicatorSearchAndDarkToggle() {
        val vm = ViewerViewModel(app, SavedStateHandle())
        compose.setContent { ForgeTheme { ViewerScreen(host("P40"), vm) } }
        val f = PdfFixtures.withText(File(tmp.root, "v.pdf"), 8, body = { listOf("find the needle here") })
        vm.onFilesPicked(listOf(PickedFile(Uri.fromFile(f), "v.pdf", f.length(), "application/pdf")))
        pump { vm.source.value != null && vm.aspects.value != null }
        compose.onNodeWithText("v.pdf").assertExists()
        compose.onNodeWithContentDescription("of 8", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Dark pages").performClick()
        assertTrue(vm.invert.value)
        compose.onNodeWithContentDescription("Search in document").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("needle")
        compose.mainClock.advanceTimeBy(600) // the search box waits 350 ms after the last key
        pump { vm.query.value == "needle" && !vm.searching.value }
        assertEquals(8, vm.hits.value.size)
        compose.onNodeWithText("1 of 8").assertExists()
        compose.onNodeWithContentDescription("Next result").performClick()
        assertEquals(1, vm.current.value)
        compose.onNodeWithContentDescription("Close search").performClick()
        assertTrue(vm.hits.value.isEmpty())
        compose.onNodeWithContentDescription("of 8", substring = true, useUnmergedTree = true).performClick() // opens "Go to page"
        compose.onNodeWithText("Go to page").assertExists()
    }

    @Test fun viewerQuickActionsListOtherAvailableTools() {
        val vm = ViewerViewModel(app, SavedStateHandle())
        var opened: Pair<String, List<PickedFile>>? = null
        compose.setContent { ForgeTheme { ViewerScreen(ToolHost(ToolRegistry.get("P40")!!, {}, { c, f -> opened = c to f }), vm) } }
        vm.onFilesPicked(listOf(pdf("q.pdf", 3)))
        pump { vm.source.value != null && vm.aspects.value != null }
        compose.onNodeWithContentDescription("More actions").performClick()
        compose.onNodeWithText("Open this PDF in…").assertExists()
        compose.onNodeWithText("Split PDF").performClick()
        assertEquals("P2", opened!!.first)
        assertEquals("q.pdf", opened!!.second.single().name)
    }

    @Test fun multiFileResultOffersShareAllAndZip() {
        fun o(n: String) = SavedOutput(Uri.parse("content://x/$n"), "doc_$n.pdf", 1000, "application/pdf")
        val outs = (1..6).map { o("p$it") }
        val r = ToolResult(outs.first(), "doc.pdf", "P2", 9000, 6000, "Split into 6 files", "6 pages · ZIP included", outs, SavedOutput(Uri.parse("content://x/z"), "doc_split.zip", 5000, "application/zip"))
        var opened: Pair<String, Int>? = null
        compose.setContent { ForgeTheme { ResultView(r, ToolRegistry.get("P2")!!, onOpenTool = { c, f -> opened = c to f.size }, onEditAgain = {}, onNewFile = {}, onDone = {}) } }
        compose.onNodeWithText("Split into 6 files").assertExists()
        compose.onNodeWithText("6 files").assertExists()
        compose.onNodeWithText("+ 2 more").assertExists()
        compose.onNodeWithText("Share all").assertExists()
        compose.onNodeWithText("Share ZIP (doc_split.zip)").assertExists()
        compose.onNodeWithText("Merge PDF").performScrollTo().performClick() // Split -> Merge gets every part
        assertEquals("P1" to 6, opened)
    }
}
