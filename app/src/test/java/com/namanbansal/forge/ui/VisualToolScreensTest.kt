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

/** The visual page tools on a phone-sized screen tall enough to show a few rows of thumbnails. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.Config(qualifiers = "w411dp-h891dp-xxhdpi")
class VisualToolScreensTest {
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

    // ---- visual page tools -----------------------------------------------------------------------------------

    @Test fun splitCutTapGapMakesFilesAndTappingAgainJoinsThem() {
        val vm = SplitViewModel(app, SavedStateHandle())
        compose.setContent { ForgeTheme { SplitScreen(host("P2"), vm) } }
        vm.onFilesPicked(listOf(pdf("s.pdf", 12)))
        pump { vm.source.value != null && vm.thumbnails.value != null && vm.bookmarks.value != null }
        compose.onNode(hasText("Split") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithContentDescription("Tap to cut between page 3 and page 4").performClick()
        compose.onNodeWithText("Split into 2 files").assertIsEnabled()
        compose.onNodeWithText("File 1 · pages 1–3").assertExists()
        compose.onNodeWithText("File 2 · pages 4–12").assertExists()
        assertEquals(setOf(3), vm.cuts.value)
        compose.onNodeWithContentDescription("Tap to cut between page 1 and page 2").performClick()
        compose.onNodeWithText("Split into 3 files").assertExists()
        compose.onNodeWithContentDescription("Cut between page 3 and page 4. Tap to join them again").performClick()
        compose.onNodeWithText("Split into 2 files").assertExists()
        assertEquals(setOf(1), vm.cuts.value)
        // other modes show their result on the same grid
        compose.onNodeWithText("Pages").performClick()
        compose.onNodeWithText("Split into 12 files").assertExists()
        compose.onNodeWithText("File 12 · page 12").assertExists()
    }

    @Test fun insertTapGapMovesThePreviewAndBlankPlaceholderShowsInPlace() {
        val vm = InsertViewModel(app, SavedStateHandle())
        compose.setContent { ForgeTheme { InsertScreen(host("P6"), vm) } }
        vm.onFilesPicked(listOf(pdf("i.pdf", 8)))
        pump { vm.source.value != null && vm.thumbnails.value != null }
        compose.onNodeWithText("Blank").assertExists() // placeholder badge, at the end by default
        compose.onNodeWithContentDescription("Insert between page 2 and page 3").performClick()
        compose.onNodeWithText("Adds 1 page before page 3. The new PDF will have 9 pages.").assertExists()
        compose.onNodeWithContentDescription("Insert here: between page 2 and page 3").assertExists()
        assertEquals(2, vm.insertGap())
        compose.onNodeWithText("Start").performClick()
        assertEquals(0, vm.insertGap())
        compose.onNodeWithText("Adds 1 page at the start. The new PDF will have 9 pages.").assertExists()
    }

    @Test fun insertFromPdfPicksPagesThenPreviewsThemInPlace() {
        val vm = InsertViewModel(app, SavedStateHandle())
        compose.setContent { ForgeTheme { InsertScreen(host("P6"), vm) } }
        vm.onFilesPicked(listOf(pdf("i.pdf", 8)))
        pump { vm.source.value != null && vm.thumbnails.value != null }
        vm.setFromPdf(true); vm.pickOther(pdf("o.pdf", 5).uri)
        pump { vm.other.value != null }
        assertEquals(5, vm.otherSelection.value.size) // all pages start selected
        compose.onNodeWithText("Insert 5 pages").assertIsEnabled()
        compose.onNodeWithText("1 · Choose pages").performClick()
        compose.onNodeWithText("5 of 5 will be inserted").assertExists()
        compose.onNodeWithText("None").performClick()
        compose.onNode(hasText("Insert") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithContentDescription("Page 2").performClick()
        compose.onNodeWithContentDescription("Page 4").performClick()
        compose.onNodeWithText("2, 4").assertExists()
        compose.onNodeWithText("Insert 2 pages").assertIsEnabled()
        compose.onNodeWithText("2 · Choose place").performClick()
        compose.onNodeWithText("New").assertExists()
        compose.onNodeWithContentDescription("Inserted page 2 of o.pdf").assertExists()
    }

    @Test fun mergeRowExpandsToShowPagesAndTapPeeks() {
        val vm = MergeViewModel(app, SavedStateHandle())
        compose.setContent { ForgeTheme { MergeScreen(host("P1"), vm) } }
        vm.onFilesPicked(listOf(pdf("a.pdf", 4), pdf("b.pdf", 6)))
        pump { vm.items.value.size == 2 && vm.items.value.all { it.status == MergeStatus.Ready } }
        compose.onNodeWithContentDescription("a.pdf, page 2. Tap to preview").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show pages of a.pdf").performClick()
        compose.onNodeWithContentDescription("a.pdf, page 2. Tap to preview").assertExists()
        compose.onNodeWithContentDescription("Hide pages of a.pdf").assertExists()
        compose.onNodeWithContentDescription("a.pdf, page 2. Tap to preview").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Close preview").assertExists()
        compose.onNodeWithContentDescription("Close preview").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Hide pages of a.pdf").performClick()
        compose.onNodeWithContentDescription("a.pdf, page 2. Tap to preview").assertDoesNotExist()
    }

    @Test fun pageManagerInvertsSelectionAndPeeksTheSelectedPage() {
        val vm = PageManagerViewModel(app, SavedStateHandle())
        compose.setContent { ForgeTheme { PageManagerScreen(host("P3"), vm) } }
        vm.onFilesPicked(listOf(pdf("m.pdf", 5)))
        pump { vm.source.value != null && vm.state.value.pages.isNotEmpty() }
        compose.onNodeWithContentDescription("Page 2, from original page 2").performClick()
        compose.onNodeWithText("1 of 5 selected").assertExists()
        compose.onNodeWithText("Invert").performClick()
        compose.onNodeWithText("4 of 5 selected").assertExists()
        compose.onNodeWithContentDescription("Preview the selected pages full screen").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Close preview").assertExists()
        compose.onNodeWithContentDescription("Close preview").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("None").performClick()
        compose.onNodeWithText("0 of 5 selected").assertExists()
    }
}
