package com.namanbansal.forge.ui

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.PdfFixtures
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.SavedOutput
import com.namanbansal.forge.core.ToolResult
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.tools.ToolRegistry
import com.namanbansal.forge.tools.pdf.RotateScreen
import com.namanbansal.forge.tools.pdf.RotateViewModel
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.toolkit.ErrorView
import com.namanbansal.forge.ui.toolkit.PasswordDialog
import com.namanbansal.forge.ui.toolkit.PickSpec
import com.namanbansal.forge.ui.toolkit.PickView
import com.namanbansal.forge.ui.toolkit.ResultView
import com.namanbansal.forge.ui.toolkit.RunningView
import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.core.pdf.PasswordPrompt
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RotateScreenTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val tool get() = ToolRegistry.get("P4")!!

    @Before fun init() { PDFBoxResourceLoader.init(app) }

    private fun pump(until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!until() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            Thread.sleep(25)
        }
        assertTrue("condition not reached in time", until())
    }

    @Test fun screenCreatesItsViewModelThroughTheDefaultFactory() {
        // Same path as the real nav graph: no vm argument, so viewModel() must build RotateViewModel(Application, SavedStateHandle).
        compose.setContent { ForgeTheme { RotateScreen(ToolHost(tool, onBack = {})) } }
        compose.onNodeWithText("Choose a PDF to rotate").assertExists()
    }

    @Test fun pickStateShowsChooseButton() {
        val vm = RotateViewModel(app, SavedStateHandle())
        compose.setContent { ForgeTheme { RotateScreen(ToolHost(tool, onBack = {}), vm) } }
        compose.onNodeWithText("Choose a PDF to rotate").assertExists()
        compose.onNodeWithText("Choose file", useUnmergedTree = true).assertExists()
    }

    @Test fun loadedPdfShowsPagesAndSaveStaysDisabledUntilRotated() {
        val pdf = PdfFixtures.create(tmp.newFile("doc.pdf"), pages = 5)
        val vm = RotateViewModel(app, SavedStateHandle())
        compose.setContent { ForgeTheme { RotateScreen(ToolHost(tool, onBack = {}), vm) } }
        vm.onUrisPicked(listOf(Uri.fromFile(pdf)))
        pump { vm.source.value != null && vm.thumbnails.value != null }

        compose.onNodeWithText("doc.pdf").assertExists()
        compose.onNodeWithText("5 of 5 selected").assertExists()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Page 1", substring = true).assertExists()

        compose.onNodeWithContentDescription("Rotate selected pages right").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Save").assertIsEnabled()
        assertEquals(5, vm.changedCount())

        compose.onNodeWithText("None").performClick()
        compose.onNodeWithText("0 of 5 selected").assertExists()
        compose.onNodeWithContentDescription("Undo all rotations").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun fullRunSavesAndShowsResultWithShare() {
        val pdf = PdfFixtures.create(tmp.newFile("run.pdf"), pages = 3)
        val vm = RotateViewModel(app, SavedStateHandle())
        vm.onUrisPicked(listOf(Uri.fromFile(pdf)))
        pump { vm.source.value != null }
        vm.rotateSelected(90)
        assertEquals(3, vm.changedCount())
        // Without MediaStore in Robolectric the save fails: this proves the error path and Try again are wired.
        compose.setContent { ForgeTheme { RotateScreen(ToolHost(tool, onBack = {}), vm) } }
        vm.start()
        pump { vm.phase.value == com.namanbansal.forge.core.ToolPhase.Failed || vm.phase.value == com.namanbansal.forge.core.ToolPhase.Result }
        compose.waitForIdle()
        if (vm.phase.value == com.namanbansal.forge.core.ToolPhase.Failed) {
            compose.onNodeWithText("Try again").assertExists()
            compose.onNodeWithText("Back to options").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Save").assertIsEnabled()
        } else {
            compose.onNodeWithText("Share").assertExists()
        }
    }

    @Test fun passwordProtectedPdfPromptsAndRejectsWrongPassword() {
        val pdf = PdfFixtures.create(tmp.newFile("locked.pdf"), pages = 2, userPassword = "pw123")
        val vm = RotateViewModel(app, SavedStateHandle())
        compose.setContent { ForgeTheme { RotateScreen(ToolHost(tool, onBack = {}), vm) } }
        vm.onUrisPicked(listOf(Uri.fromFile(pdf)))
        pump { vm.passwordPrompt.value != null }
        compose.onNodeWithText("Password needed").assertExists()

        vm.submitPassword("wrong")
        pump { vm.passwordPrompt.value?.wrong == true }
        compose.onNodeWithText("That password is wrong. Try again.").assertExists()

        vm.submitPassword("pw123")
        pump { vm.source.value != null }
        compose.waitForIdle()
        compose.onNodeWithText("Password needed").assertDoesNotExist()
        compose.onNodeWithText("2 of 2 selected").assertExists()
        compose.onNodeWithText("The saved copy keeps the password.").assertExists()
    }

    @Test fun endToEndRotateSavesResultAndRecordsRecent() {
        val provider = org.robolectric.Robolectric.setupContentProvider(com.namanbansal.forge.core.FakeMediaProvider::class.java, "media")
        provider.dir = tmp.newFolder()
        val pdf = PdfFixtures.create(tmp.newFile("e2e.pdf"), pages = 4)
        val vm = RotateViewModel(app, SavedStateHandle())
        compose.setContent { ForgeTheme { RotateScreen(ToolHost(tool, onBack = {}), vm) } }
        vm.onUrisPicked(listOf(Uri.fromFile(pdf)))
        pump { vm.source.value != null && vm.thumbnails.value != null }
        vm.selectNone(); vm.togglePage(0); vm.togglePage(3)
        vm.rotateSelected(-90)
        vm.start()
        pump { vm.phase.value == com.namanbansal.forge.core.ToolPhase.Result }
        compose.waitForIdle()

        val result = vm.result.value!!
        assertEquals("e2e_rotated.pdf", result.output.displayName)
        assertEquals("2 pages rotated", result.detail)
        compose.onNodeWithText("Rotated successfully").assertExists()
        compose.onNodeWithText("Share").assertExists()
        // The produced file really has the rotation, and Recent got an entry.
        val saved = provider.rows.values.single().file
        assertEquals(listOf(270, 0, 0, 270), PdfFixtures.pageRotations(saved))
        pump { com.namanbansal.forge.ForgeApp.from(app).recents.entries.value.any { it.name == "e2e_rotated.pdf" } }
    }

    private fun collectMessages(vm: RotateViewModel): MutableList<String> {
        val msgs = java.util.concurrent.CopyOnWriteArrayList<String>()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch { vm.messages.collect { msgs += it } }
        return msgs
    }

    @Test fun nonPdfIsRejectedBackToPickWithMessage() {
        val txt = tmp.newFile("notes.txt").apply { writeText("hi") }
        val vm = RotateViewModel(app, SavedStateHandle())
        val msgs = collectMessages(vm)
        compose.setContent { ForgeTheme { RotateScreen(ToolHost(tool, onBack = {}), vm) } }
        vm.onUrisPicked(listOf(Uri.fromFile(txt)))
        pump { msgs.isNotEmpty() }
        assertTrue(msgs.single(), msgs.single().contains("isn't a PDF"))
        assertEquals(com.namanbansal.forge.core.ToolPhase.Pick, vm.phase.value)
        compose.onNodeWithText("Choose file", useUnmergedTree = true).assertExists()
    }

    @Test fun corruptPdfIsRejectedBackToPickWithMessage() {
        val bad = PdfFixtures.corrupt(tmp.newFile("bad.pdf"))
        val vm = RotateViewModel(app, SavedStateHandle())
        val msgs = collectMessages(vm)
        compose.setContent { ForgeTheme { RotateScreen(ToolHost(tool, onBack = {}), vm) } }
        vm.onUrisPicked(listOf(Uri.fromFile(bad)))
        pump { msgs.isNotEmpty() }
        assertTrue(msgs.single(), msgs.single().contains("valid PDF"))
        assertEquals(com.namanbansal.forge.core.ToolPhase.Pick, vm.phase.value)
        assertTrue(vm.inputs.value.isEmpty())
    }

    @Test fun sharedScaffoldStates() {
        val result = ToolResult(
            SavedOutput(Uri.parse("content://x/1"), "invoice_compressed.pdf", 680_000, "application/pdf"),
            "invoice.pdf", "P4", 4_200_000, 680_000, "Compressed successfully", "12 pages",
        )
        var state by androidx.compose.runtime.mutableIntStateOf(0)
        var retried = false
        var cancelled = false
        compose.setContent {
            ForgeTheme {
                when (state) {
                    0 -> PickView(PickSpec(listOf(FileKind.PDF), title = "Choose a PDF"), loading = false, onChoose = {})
                    1 -> RunningView(0.42f, onCancel = { cancelled = true })
                    2 -> ErrorView("This PDF is damaged.", onRetry = { retried = true }, onBack = {})
                    3 -> ResultView(result, tool, onOpenTool = { _, _ -> }, onEditAgain = {}, onNewFile = {}, onDone = {})
                    4 -> PasswordDialog(PasswordPrompt("a.pdf", wrong = true), onSubmit = {}, onCancel = {})
                }
            }
        }
        compose.onNodeWithText("Choose a PDF").assertExists()
        state = 1; compose.waitForIdle()
        compose.onNodeWithText("42%").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(cancelled)
        state = 2; compose.waitForIdle()
        compose.onNodeWithText("This PDF is damaged.").assertExists()
        compose.onNodeWithText("Try again").performClick()
        assertTrue(retried)
        state = 3; compose.waitForIdle()
        compose.onNodeWithText("Compressed successfully").assertExists()
        compose.onNodeWithText("${com.namanbansal.forge.core.formatBytes(4_200_000)} → ${com.namanbansal.forge.core.formatBytes(680_000)}").assertExists()
        compose.onNodeWithText("invoice_compressed.pdf").assertExists()
        compose.onNodeWithText("Share").assertExists()
        compose.onNodeWithText("Open").assertExists()
        compose.onNodeWithText("Save as").assertExists()
        state = 4; compose.waitForIdle()
        compose.onNodeWithText("That password is wrong. Try again.").assertExists()
    }
}
