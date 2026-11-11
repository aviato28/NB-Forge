package com.namanbansal.forge.ui

import android.app.Application
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.ForgeApp
import com.namanbansal.forge.core.FakeMediaProvider
import com.namanbansal.forge.core.PdfBoxRasterizer
import com.namanbansal.forge.core.PdfFixtures
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.RecentEntry
import com.namanbansal.forge.core.RecentFilesStore
import com.namanbansal.forge.core.RecentItem
import com.namanbansal.forge.core.RecentsViewModel
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.tools.ToolRegistry
import com.namanbansal.forge.tools.pdf.PageManagerScreen
import com.namanbansal.forge.tools.pdf.PageManagerViewModel
import com.namanbansal.forge.ui.screens.RecentActions
import com.namanbansal.forge.ui.screens.RecentRow
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.toolkit.OpenWithSheet
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Round 2E everywhere else: Files / Recent row menu, store rename, share-to-Forge "Save to phone", page-manager sharing. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FilesActionsTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var media: FakeMediaProvider

    @Before fun init() {
        com.namanbansal.forge.core.share.FileProviderReset.clear()
        PDFBoxResourceLoader.init(app); PdfBoxRasterizer.install()
        media = Robolectric.setupContentProvider(FakeMediaProvider::class.java, "media"); media.dir = tmp.newFolder()
    }
    @After fun done() { PdfBoxRasterizer.uninstall() }

    private fun pump(until: () -> Boolean) {
        val end = System.currentTimeMillis() + 30_000
        while (!until() && System.currentTimeMillis() < end) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); compose.waitForIdle(); Thread.sleep(25)
        }
        assertTrue("condition not reached in time", until())
        compose.waitForIdle()
    }

    private fun entry(uri: String = "content://media/external/downloads/1") = RecentEntry("invoice.pdf", "P4", 1000, 900, 1L, uri, "application/pdf")

    @Test fun rowMenuListsEveryAction() {
        var removed = false; var renamed: String? = null; var deleted = false
        val actions = RecentActions(onRename = { _, n -> renamed = n }, onDelete = { deleted = true })
        compose.setContent { ForgeTheme { RecentRow(RecentItem(entry(), true), false, onRemove = { removed = true }, actions = actions) } }
        compose.onNodeWithContentDescription("More actions for invoice.pdf").performClick()
        for (l in listOf("Share", "Save as…", "Open with…", "Rename", "Remove from list", "Delete file…")) compose.onNodeWithText(l).assertExists()
        compose.onNodeWithText("Remove from list").performClick()
        assertTrue(removed); assertFalse(deleted); assertEquals(null, renamed)
    }

    @Test fun deleteAsksForConfirmationFirst() {
        var deleted = false
        val actions = RecentActions(onRename = { _, _ -> }, onDelete = { deleted = true })
        compose.setContent { ForgeTheme { RecentRow(RecentItem(entry(), true), false, onRemove = {}, actions = actions) } }
        compose.onNodeWithContentDescription("More actions for invoice.pdf").performClick()
        compose.onNodeWithText("Delete file…").performClick()
        compose.onNodeWithText("Delete this file?").assertExists()
        assertFalse("nothing deleted before confirming", deleted)
        compose.onNodeWithText("Cancel").performClick()
        assertFalse(deleted)
        compose.onNodeWithContentDescription("More actions for invoice.pdf").performClick()
        compose.onNodeWithText("Delete file…").performClick()
        compose.onNodeWithText("Delete").performClick()
        assertTrue(deleted)
    }

    @Test fun renameDialogReturnsTheTypedName() {
        var renamed: String? = null
        val actions = RecentActions(onRename = { _, n -> renamed = n }, onDelete = {})
        compose.setContent { ForgeTheme { RecentRow(RecentItem(entry(), true), false, onRemove = {}, actions = actions) } }
        compose.onNodeWithContentDescription("More actions for invoice.pdf").performClick()
        compose.onNodeWithText("Rename").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Tax 2025")
        compose.onNodeWithText("Rename", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Cancel").assertExists()
        compose.onAllNodesWithText("Rename").onLast().performClick()
        assertEquals("Tax 2025", renamed)
    }

    @Test fun missingFileOnlyOffersRemoval() {
        compose.setContent { ForgeTheme { RecentRow(RecentItem(entry(), false), false, onRemove = {}, actions = RecentActions({ _, _ -> }, {})) } }
        compose.onNodeWithContentDescription("More actions for invoice.pdf").performClick()
        compose.onNodeWithText("Remove from list").assertExists()
        compose.onNodeWithText("Share").assertDoesNotExist()
        compose.onNodeWithText("Delete file…").assertDoesNotExist()
    }

    @Test fun storeRenameKeepsEverythingElse() = runBlocking {
        val store = RecentFilesStore(File(tmp.root, "r.json"))
        store.add(entry("content://a/1")); store.add(entry("content://a/2").copy(name = "other.pdf"))
        store.rename("content://a/1", "new.pdf")
        val byUri = store.entries.value.associateBy { it.uri }
        assertEquals("new.pdf", byUri["content://a/1"]!!.name)
        assertEquals("other.pdf", byUri["content://a/2"]!!.name)
        assertEquals(900L, byUri["content://a/1"]!!.afterSize)
    }

    @Test fun viewModelRenamesAndDeletesTheRealFile() {
        val a = ForgeApp.from(app)
        val saved = runBlocking { a.outputSaver.saveKeepingName("old.pdf", "application/pdf") { it.write(1) } }
        runBlocking { a.recents.add(RecentEntry("old.pdf", "P4", 1, 1, 1L, saved.uri.toString(), "application/pdf")) }
        val vm = RecentsViewModel(app)
        vm.rename(a.recents.entries.value.first { it.uri == saved.uri.toString() }, "fresh")
        pump { a.recents.entries.value.any { it.uri == saved.uri.toString() && it.name == "fresh.pdf" } }
        assertEquals("fresh.pdf", media.rows.values.single().values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        vm.deleteFile(a.recents.entries.value.first { it.uri == saved.uri.toString() })
        pump { media.rows.isEmpty() }
        assertTrue(a.recents.entries.value.none { it.uri == saved.uri.toString() })
    }

    @Test fun shareToForgeSheetOffersSaveToPhone() {
        var saved = false
        val file = PickedFile(Uri.parse("content://x/1"), "Bank statement.pdf", 1000, "application/pdf")
        compose.setContent { ForgeTheme { OpenWithSheet(listOf(file), onPick = {}, onDismiss = {}, onSaveToPhone = { saved = true }) } }
        compose.onNodeWithText("Save to phone").assertExists()
        compose.onNodeWithText("Keep it in Downloads/Forge").assertExists()
        compose.onNodeWithText("Save to phone").performClick()
        assertTrue(saved)
    }

    @Test fun pageManagerSharesSelectedPagesAsPdfOrOneAsImage() {
        val vm = PageManagerViewModel(app, SavedStateHandle())
        compose.setContent { ForgeTheme { PageManagerScreen(ToolHost(ToolRegistry.get("P3")!!, {}, { _, _ -> }), vm) } }
        val f = PdfFixtures.withText(File(tmp.root, "pm.pdf"), 5, uniqueHeights = false)
        vm.onFilesPicked(listOf(PickedFile(Uri.fromFile(f), "pm.pdf", f.length(), "application/pdf")))
        pump { vm.state.value.pages.size == 5 && vm.thumbnails.value != null }
        compose.onNodeWithContentDescription("Page 2, from original page 2").performClick()
        compose.onNodeWithContentDescription("Share the selected pages").performClick()
        compose.onNodeWithText("Share this page").assertExists()
        compose.onNodeWithText("As image").assertExists()
        var pdf: PageManagerViewModel.SharePayload? = null; var png: PageManagerViewModel.SharePayload? = null
        runBlocking { pdf = vm.stageSelected(false); png = vm.stageSelected(true) }
        assertEquals("pm_page_2.pdf", pdf!!.name); assertEquals("pm_page_2.png", png!!.name)
        assertTrue(png!!.file.length() > 0)
    }
}
