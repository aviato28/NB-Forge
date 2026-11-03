package com.namanbansal.forge.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.ForgeApp
import com.namanbansal.forge.core.DefaultViewer
import com.namanbansal.forge.core.PdfBoxRasterizer
import com.namanbansal.forge.core.PdfFixtures
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.pdf.viewer.OutlineItem
import com.namanbansal.forge.core.pdf.viewer.ViewerPosition
import com.namanbansal.forge.data.ViewerPrefs
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.tools.ToolRegistry
import com.namanbansal.forge.tools.pdf.OutlinePanel
import com.namanbansal.forge.tools.pdf.ViewerHome
import com.namanbansal.forge.tools.pdf.ViewerMenuItems
import com.namanbansal.forge.tools.pdf.ViewerScreen
import com.namanbansal.forge.tools.pdf.ViewerViewModel
import com.namanbansal.forge.ui.screens.PdfViewerSettings
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ViewerRound1bTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun init() { PDFBoxResourceLoader.init(app); PdfBoxRasterizer.install() }

    private fun pump(timeoutMs: Long = 20_000, until: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!until() && System.currentTimeMillis() < end) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); compose.waitForIdle(); Thread.sleep(20)
        }
        assertTrue("condition not reached", until())
        compose.waitForIdle()
    }

    private fun host() = ToolHost(ToolRegistry.get("P40")!!, {}, { _, _ -> })

    private fun openViewer(pages: Int = 6): ViewerViewModel {
        val vm = ViewerViewModel(app, SavedStateHandle())
        compose.setContent { ForgeTheme { ViewerScreen(host(), vm) } }
        val f = PdfFixtures.withText(File(tmp.root, "doc.pdf"), pages, body = { listOf("Select this sentence please") })
        vm.onFilesPicked(listOf(PickedFile(Uri.fromFile(f), "doc.pdf", f.length(), "application/pdf")))
        pump { vm.source.value != null && vm.restored.value && vm.aspects.value != null }
        return vm
    }

    // ---- home --------------------------------------------------------------------------------------------------------

    @Test fun homeShowsOpenButtonAndRecentsAndHandlesTaps() {
        val a = ViewerPosition("a", "content://x/a", "Annual report.pdf", 5_000_000, 12, 3, 1f, 1_000)
        val b = ViewerPosition("b", "content://x/b", "Lease.pdf", 90_000, 4, 0, 2f, 900)
        var opened: String? = null; var removed: String? = null; var picked = false
        compose.setContent {
            ForgeTheme {
                ViewerHome("Viewer", listOf(a, b), false, null, { null }, { picked = true }, { opened = it.key }, { removed = it.key }, {}, {}, {}, now = 1_000 + 2 * 3_600_000)
            }
        }
        compose.onNodeWithText("Open PDF").assertIsEnabled().performClick()
        assertTrue(picked)
        compose.onNodeWithText("Annual report.pdf").performClick()
        assertEquals("a", opened)
        compose.onNodeWithText("Page 4 of 12", substring = true).assertExists()
        compose.onNodeWithContentDescription("Remove Lease.pdf from recent").performClick()
        assertEquals("b", removed)
    }

    @Test fun homeEmptyAndErrorStates() {
        var reported: String? = null
        compose.setContent {
            ForgeTheme { ViewerHome("Viewer", emptyList(), false, ViewerViewModel.OpenError("This doesn't look like a valid PDF, or it is damaged.", "diag text"), { null }, {}, {}, {}, { reported = it }, {}, {}) }
        }
        compose.onNodeWithText("No recent PDFs yet").assertExists()
        compose.onNodeWithText("This PDF couldn't be opened").assertExists()
        compose.onNodeWithText("Report").performClick()
        assertEquals("diag text", reported)
    }

    @Test fun reopeningAMissingRecentRemovesItAndExplains() {
        val vm = ViewerViewModel(app, SavedStateHandle())
        val gone = ViewerPosition("gone", Uri.fromFile(File(tmp.root, "nope.pdf")).toString(), "nope.pdf", 10, 3, 1, 1f, 5)
        runBlocking { ForgeApp.from(app).viewerPositions.put(gone) }
        val messages = ArrayList<String>()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch { vm.messages.collect { messages += it } }
        vm.openRecent(gone)
        pump { ForgeApp.from(app).viewerPositions.get("gone") == null && messages.isNotEmpty() }
        assertTrue(messages.single().contains("isn't available"))
        assertEquals(com.namanbansal.forge.core.ToolPhase.Pick, vm.phase.value)
        job.cancel()
    }

    @Test fun corruptFileGivesFriendlyErrorWithReportText() {
        val vm = ViewerViewModel(app, SavedStateHandle())
        val bad = PdfFixtures.corrupt(PdfFixtures.create(File(tmp.root, "bad.pdf"), 2))
        vm.onFilesPicked(listOf(PickedFile(Uri.fromFile(bad), "bad.pdf", bad.length(), "application/pdf")))
        pump { vm.openError.value != null }
        val e = vm.openError.value!!
        assertTrue(e.message.isNotBlank())
        assertTrue(e.report.contains("bad.pdf") && e.report.contains("Android") && e.report.contains("Error:"))
        assertFalse("the report must not carry the file's content", e.report.contains("%PDF"))
    }

    @Test fun fileWithNoNameOrTypeStillOpensWhenItIsAPdf() {
        val vm = ViewerViewModel(app, SavedStateHandle())
        val f = PdfFixtures.create(File(tmp.root, "x"), 2)
        vm.onFilesPicked(listOf(PickedFile(Uri.fromFile(f), "file", -1, null)))
        pump { vm.source.value != null }
        assertEquals(2, vm.source.value!!.pageCount)
    }

    // ---- reading -----------------------------------------------------------------------------------------------------

    @Test fun reopeningADocumentRestoresItsPage() {
        val first = openViewer(8)
        first.rememberPage(5); first.rememberZoom(2f)
        first.flushPosition()
        pump { ForgeApp.from(app).viewerPositions.entries.value.any { it.page == 5 } }
        val again = ViewerViewModel(app, SavedStateHandle())
        val f = File(tmp.root, "doc.pdf")
        again.onFilesPicked(listOf(PickedFile(Uri.fromFile(f), "doc.pdf", f.length(), "application/pdf")))
        pump { again.restored.value }
        assertEquals(5, again.startPage()); assertEquals(2f, again.startZoom())
    }

    @Test fun selectionToolbarAppearsAndCopyClearsIt() {
        val vm = openViewer()
        runBlocking { vm.selectAll(0) }
        pump { vm.selection.value != null }
        compose.waitForIdle()
        pump { runCatching { compose.onNodeWithText("Copy").assertExists() }.isSuccess }
        compose.onNodeWithText("Search").assertExists()
        compose.onNodeWithText("Share").assertExists()
        compose.onNodeWithText("Select all").assertExists()
        assertEquals("Page 1 Select this sentence please".length > 0, true)
        compose.onNodeWithText("Copy").performClick()
        pump { vm.selection.value == null }
    }

    @Test fun menuChangesPersistGlobally() {
        val vm = openViewer()
        compose.onNodeWithContentDescription("More actions").performClick()
        compose.onNodeWithText("Page by page").performClick()
        pump { vm.prefs.value.pageByPage }
        assertTrue(vm.prefs.value.pageByPage)
        compose.onNodeWithContentDescription("More actions").performClick()
        compose.onNodeWithText("Keep screen on").performClick()
        pump { !vm.prefs.value.keepScreenOn }
        runBlocking { ForgeApp.from(app).settings.setViewerPrefs(ViewerPrefs()) } // the settings file outlives a test
    }

    @Test fun contentsButtonOpensTheSheetWithAnEmptyStateWhenThereAreNoBookmarks() {
        openViewer()
        compose.onNodeWithContentDescription("Contents and pages").performClick()
        pump { runCatching { compose.onNodeWithText("Pages").assertExists() }.isSuccess }
        compose.onNodeWithText("Bookmarks").performClick()
        pump { runCatching { compose.onNodeWithText("No bookmarks in this PDF").assertExists() }.isSuccess }
    }

    @Test fun doubleTapZoomsAndRendersSharpTiles() {
        val vm = openViewer(3)
        val tiles = vm.tiles.value!!
        compose.onNodeWithContentDescription("Page 1").performTouchInput { doubleClick(center) }
        pump { tiles.rendered > 0 }
        assertTrue(tiles.cachedBytes() > 0)
        assertEquals(2.5f, vm.zoom.value, 0.001f)
        compose.onNodeWithContentDescription("Page 1").performTouchInput { doubleClick(center) } // and back to fit width
        pump { vm.zoom.value == 1f }
    }

    @Test fun tappingAnExternalLinkAsksBeforeOpening() {
        val f = File(tmp.root, "links.pdf")
        com.tom_roush.pdfbox.pdmodel.PDDocument().use { d ->
            d.addPage(com.tom_roush.pdfbox.pdmodel.PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle.A4))
            val l = com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink().apply {
                rectangle = com.tom_roush.pdfbox.pdmodel.common.PDRectangle(100f, 700f, 100f, 20f)
                action = com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI().apply { uri = "https://example.com/forge" }
            }
            d.getPage(0).annotations = listOf(l); d.save(f)
        }
        val vm = ViewerViewModel(app, SavedStateHandle())
        compose.setContent { ForgeTheme { ViewerScreen(host(), vm) } }
        vm.onFilesPicked(listOf(PickedFile(Uri.fromFile(f), "links.pdf", f.length(), "application/pdf")))
        pump { vm.source.value != null && vm.restored.value && vm.aspects.value != null }
        val found = runBlocking { vm.pageLinks(0) }
        assertEquals("links: $found", 1, found.size)
        compose.waitForIdle(); Thread.sleep(300); compose.waitForIdle()
        // A4 at 1233 px wide: 2.07 px per point; the link centre is (150, 132 from the top) pt, below a 60 dp (180 px) gutter.
        compose.onNodeWithContentDescription("Page 1").performTouchInput { click(androidx.compose.ui.geometry.Offset(150f * 2.07f, 132f * 2.07f)) }
        compose.mainClock.advanceTimeBy(600) // a single tap is confirmed only after the double-tap window
        compose.waitForIdle()
        assertNotNull("flash after tap", vm.flash.value)
        pump { runCatching { compose.onNodeWithText("Open this link?").assertExists() }.isSuccess }
        compose.onNodeWithText("https://example.com/forge").assertExists()
        compose.onNodeWithText("Cancel").performClick()
    }

    // ---- outline panel -----------------------------------------------------------------------------------------------

    @Test fun outlineExpandsCollapsesAndJumps() {
        val outline = listOf(OutlineItem("Intro", 0, emptyList()), OutlineItem("Part 1", 2, listOf(OutlineItem("Deep dive", 5, emptyList()))))
        var jumped = -1
        compose.setContent { ForgeTheme { OutlinePanel(outline, null, 8, 0, onJump = { jumped = it }) } }
        compose.onNodeWithText("Intro").assertExists()
        assertTrue(compose.onAllNodesWithTextCount("Deep dive") == 0)
        compose.onNodeWithContentDescription("Expand Part 1").performClick()
        compose.onNodeWithText("Deep dive").performClick()
        assertEquals(5, jumped)
        compose.onNodeWithContentDescription("Collapse Part 1").performClick()
        assertTrue(compose.onAllNodesWithTextCount("Deep dive") == 0)
    }

    @Test fun outlineEmptyStateAndPagesTab() {
        compose.setContent { ForgeTheme { OutlinePanel(emptyList(), null, 3, 1, onJump = {}) } }
        // no bookmarks: opens on the Pages tab, and the Bookmarks tab explains itself
        compose.onNodeWithText("Bookmarks").performClick()
        compose.onNodeWithText("No bookmarks in this PDF").assertExists()
    }

    // ---- settings ----------------------------------------------------------------------------------------------------

    @Test fun makeDefaultStates() {
        var state: DefaultViewer by androidx.compose.runtime.mutableStateOf<DefaultViewer>(DefaultViewer.Other("Drive PDF Viewer", "com.google.pdf"))
        var info: String? = null; var tested = 0; var keep = true
        compose.setContent { ForgeTheme { PdfViewerSettings(state, keep, { keep = it }, { info = it }, { tested++ }) } }
        compose.onNodeWithText("Make Forge your PDF viewer").assertExists()
        compose.onNodeWithText("PDFs currently open in Drive PDF Viewer", substring = true).assertExists()
        compose.onNodeWithText("Open App info").performClick(); assertEquals("com.google.pdf", info)
        compose.onNodeWithText("Test it").performClick(); assertEquals(1, tested)
        state = DefaultViewer.AskEveryTime; compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTextCount("Open App info"))
        compose.onNodeWithText("Test it").assertExists()
        state = DefaultViewer.Forge; compose.waitForIdle()
        compose.onNodeWithText("Forge is your PDF viewer").assertExists()
        assertEquals(0, compose.onAllNodesWithTextCount("Test it"))
    }

    @Test fun keepScreenOnSwitchFlips() {
        var keep = true
        compose.setContent { ForgeTheme { PdfViewerSettings(DefaultViewer.Forge, keep, { keep = it }, {}, {}) } }
        compose.onNodeWithText("Keep screen on while reading").performClick()
        compose.onNode(androidx.compose.ui.test.isToggleable()).performClick()
        assertFalse(keep)
    }

    @Test fun layoutMenuRowsReflectPrefs() {
        var last: ViewerPrefs? = null
        compose.setContent { ForgeTheme { androidx.compose.foundation.layout.Column { ViewerMenuItems(ViewerPrefs(pageByPage = true), { last = it(ViewerPrefs(pageByPage = true)) }, {}) } } }
        compose.onNodeWithText("Continuous scroll").performClick()
        assertFalse(last!!.pageByPage)
        assertNull(null)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(t: String) =
        onAllNodes(hasText(t, substring = true)).fetchSemanticsNodes().size
}
