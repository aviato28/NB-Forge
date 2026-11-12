package com.namanbansal.forge.ui

import android.app.Application
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.FakeMediaProvider
import com.namanbansal.forge.core.PdfBoxRasterizer
import com.namanbansal.forge.core.PdfFixtures
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.annot.Annot
import com.namanbansal.forge.core.pdf.annot.AnnotPalette
import com.namanbansal.forge.core.pdf.annot.AnnotType
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.tools.ToolRegistry
import com.namanbansal.forge.tools.pdf.ViewerScreen
import com.namanbansal.forge.tools.pdf.ViewerViewModel
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

/** Round 2E in the viewer: Share / Save icons, the temporary-file banner, the overflow File group, sheets and the save-where flow. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewerShareTest {
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

    private fun open(name: String = "Amazon invoice.pdf", pages: Int = 3): ViewerViewModel {
        val vm = ViewerViewModel(app, SavedStateHandle())
        compose.setContent { ForgeTheme { ViewerScreen(ToolHost(ToolRegistry.get("P40")!!, {}, { _, _ -> }), vm) } }
        val f = PdfFixtures.withText(File(tmp.root, name), pages, uniqueHeights = false)
        vm.onFilesPicked(listOf(PickedFile(Uri.fromFile(f), name, f.length(), "application/pdf")))
        pump { vm.source.value != null && vm.aspects.value != null && vm.restored.value }
        return vm
    }

    private val hl = Annot("h1", 0, AnnotType.HIGHLIGHT, AnnotPalette.YELLOW, quads = listOf(NormRect(0.1f, 0.1f, 0.5f, 0.13f)))

    @Test fun topBarHasShareAndSaveAndTheMenuGroupsTheRest() {
        open()
        compose.onNodeWithContentDescription("Share").assertExists()
        compose.onNodeWithContentDescription("Save to phone").assertExists()
        compose.onNodeWithContentDescription("More actions").performClick()
        for (label in listOf("Share pages…", "Save as…", "Print", "Open with…", "File info", "Dark pages")) compose.onNodeWithText(label).assertExists()
    }

    @Test fun temporaryFileShowsTheBannerAndSaveKeepsTheRealName() {
        val vm = open()
        compose.onNodeWithText("Not saved on your phone").assertExists()
        assertTrue(vm.origin.value!!.temporary)
        compose.onNodeWithText("Save").performClick()
        pump { vm.savedTo.value != null }
        assertEquals("Amazon invoice.pdf", vm.savedTo.value)
        val row = media.rows.values.single().values
        assertEquals("Amazon invoice.pdf", row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertEquals("Download/Forge/", row.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        compose.onNodeWithText("Not saved on your phone").assertDoesNotExist()
    }

    @Test fun bannerCanBeDismissed() {
        open()
        compose.onNodeWithContentDescription("Hide this notice").performClick()
        compose.onNodeWithText("Not saved on your phone").assertDoesNotExist()
    }

    @Test fun editingATemporaryFileAsksWhereToSaveAndDownloadsKeepsTheOriginal() {
        val vm = open("bank.pdf")
        val before = File(tmp.root, "bank.pdf").readBytes()
        vm.annotate.session.add(hl)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Save, unsaved changes").performClick()
        compose.waitForIdle()
        pump { runCatching { compose.onNodeWithText("Where do you want to save it?").assertExists() }.isSuccess }
        compose.onNodeWithText("Save as… (choose folder and name)").assertExists()
        compose.onNodeWithText("Save to Downloads/Forge").performClick()
        pump { !vm.anyDirty.value && !vm.saving.value }
        assertEquals("bank_edited.pdf", media.rows.values.single().values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertTrue("the original file was not touched", before.contentEquals(File(tmp.root, "bank.pdf").readBytes()))
    }

    @Test fun neededSaveLocationIsDecidedBeforeAnythingIsWritten() {
        val vm = open()
        var ask: Boolean? = null
        kotlinx.coroutines.runBlocking { ask = vm.needsSaveLocation() }
        assertEquals(true, ask)
    }

    @Test fun shareWithUnsavedEditsAsksWhichVersion() {
        val vm = open()
        vm.annotate.session.add(hl)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Share").performClick()
        compose.onNodeWithText("Share with your changes?").assertExists()
        compose.onNodeWithText("With your changes").assertExists()
        compose.onNodeWithText("Original").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Share with your changes?").assertDoesNotExist()
    }

    @Test fun stagedShareKeepsTheNameAndWithChangesContainsTheEdit() {
        val vm = open("Order 77.pdf")
        var plain: ViewerViewModel.Staged? = null
        var edited: ViewerViewModel.Staged? = null
        kotlinx.coroutines.runBlocking { plain = vm.stage(false) }
        assertEquals("Order 77.pdf", plain!!.uri.lastPathSegment)
        assertTrue(File(tmp.root, "Order 77.pdf").readBytes().contentEquals(plain!!.file.readBytes()))
        vm.annotate.session.add(hl)
        kotlinx.coroutines.runBlocking { edited = vm.stage(true) }
        assertEquals("Order 77.pdf", edited!!.name)
        PDDocument.load(edited!!.file).use { d -> assertEquals(1, com.namanbansal.forge.core.pdf.annot.PdfAnnotIo.read(d).size) }
        assertFalse("the original is not changed by sharing", plain!!.file.readBytes().contentEquals(edited!!.file.readBytes()))
    }

    @Test fun fileInfoSheetShowsNameOriginAndPdfDetails() {
        open("Statement.pdf", 4)
        compose.onNodeWithContentDescription("More actions").performClick()
        compose.onNodeWithText("File info").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("File info").assertExists()
        compose.onAllNodesWithText("Statement.pdf").assertCountEquals(2) // top bar title + the Name row
        compose.onAllNodesWithText("Not saved on your phone").assertCountEquals(2) // banner + the Saved row
        compose.onNodeWithText("Password").assertExists()
    }

    @Test fun sharePagesSheetOffersPdfAndImage() {
        open(pages = 3)
        compose.onNodeWithContentDescription("More actions").performClick()
        compose.onNodeWithText("Share pages…").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Share page 1 as PDF").assertExists()
        compose.onNodeWithText("Share page as image").assertExists()
    }

    @Test fun pagesOfTheOpenDocumentCanBeStagedAsPdfAndPng() {
        val vm = open("Doc.pdf", 4)
        var pdf: ViewerViewModel.Staged? = null; var png: ViewerViewModel.Staged? = null
        kotlinx.coroutines.runBlocking { pdf = vm.stagePages(listOf(1, 2), false); png = vm.stagePages(listOf(3), true) }
        assertEquals("Doc_pages_2-3.pdf", pdf!!.name)
        PDDocument.load(pdf!!.file).use { assertEquals(2, it.numberOfPages) }
        assertEquals("Doc_page_4.png", png!!.name)
        assertNotNull(android.graphics.BitmapFactory.decodeFile(png!!.file.path))
    }

    @Test fun temporaryPdfsKeepAPrivateCopyForRecent() {
        val vm = open("Keep me.pdf")
        pump { vm.recents.value.any { it.name == "Keep me.pdf" && it.kept } }
        val p = vm.recents.value.first { it.name == "Keep me.pdf" }
        assertTrue(Uri.parse(p.uri).path!!.contains("/kept/"))
        assertTrue(File(Uri.parse(p.uri).path!!).isFile)
    }
}
