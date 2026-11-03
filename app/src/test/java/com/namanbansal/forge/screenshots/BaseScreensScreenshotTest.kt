package com.namanbansal.forge.screenshots

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.ForgeApp
import com.namanbansal.forge.core.PdfFixtures
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.RecentEntry
import com.namanbansal.forge.core.RecentItem
import com.namanbansal.forge.core.SavedOutput
import com.namanbansal.forge.core.ToolResult
import com.namanbansal.forge.core.pdf.PasswordPrompt
import com.namanbansal.forge.data.Settings
import com.namanbansal.forge.tools.Category
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.tools.ToolRegistry
import com.namanbansal.forge.tools.pdf.RotateScreen
import com.namanbansal.forge.tools.pdf.RotateViewModel
import com.namanbansal.forge.ui.screens.CategoryScreen
import com.namanbansal.forge.ui.screens.FilesScreen
import com.namanbansal.forge.ui.screens.HomeScreen
import com.namanbansal.forge.ui.screens.SearchScreen
import com.namanbansal.forge.ui.screens.SettingsScreen
import com.namanbansal.forge.ui.toolkit.ErrorView
import com.namanbansal.forge.ui.toolkit.PasswordDialog
import com.namanbansal.forge.ui.toolkit.ResultView
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class BaseScreensScreenshotTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun init() { PDFBoxResourceLoader.init(app); com.namanbansal.forge.core.PdfBoxRasterizer.install() }
    @org.junit.After fun done() { com.namanbansal.forge.core.PdfBoxRasterizer.uninstall() }

    private fun recent(name: String, tool: String, before: Long, after: Long, ageMin: Long) = RecentItem(
        RecentEntry(name, tool, before, after, System.currentTimeMillis() - ageMin * 60_000, "content://x/$name", "application/pdf"), exists = true,
    )

    private val recents get() = listOf(
        recent("invoice_oct_compressed.pdf", "P9", 4_400_000, 680_000, 4),
        recent("contract_merged.pdf", "P1", 3_100_000, 3_090_000, 95),
        recent("scan_rotated.pdf", "P4", 900_000, 900_000, 60 * 30),
    )

    @Test fun home() = compose.shootBoth("home") {
        HomeScreen(
            pins = listOf("P9", "P1", "P43", "D1"), recents = recents,
            onSearch = {}, onSettings = {}, onTool = {}, onCategory = {}, onTogglePin = {}, onReorderPins = {}, onSeeAllRecents = {}, onRemoveRecent = {},
        )
    }

    private fun searchShot(dark: Boolean) {
        compose.setThemed(dark) {
            SearchScreen(listOf("merge", "compress"), onBack = {}, onTool = { _, _ -> }, onSubmit = {}, onClearRecent = {}, onTogglePin = {})
        }
        compose.onNode(hasSetTextAction()).performTextInput("rot")
        compose.waitForIdle()
        compose.captureShot("search-${if (dark) "dark" else "light"}")
    }

    @Test fun searchLight() = searchShot(false)
    @Test fun searchDark() = searchShot(true)

    @Test fun categoryPdf() = compose.shootBoth("category-pdf") {
        CategoryScreen(Category.PDF, pins = listOf("P9", "P1"), onBack = {}, onTool = {}, onTogglePin = {})
    }

    @Test fun files() = compose.shootBoth("files") { FilesScreen(recents, onRefresh = {}, onRemove = {}) }

    @Test fun settings() = compose.shootBoth("settings") {
        SettingsScreen(ForgeApp.from(app), Settings(), onLicenses = {})
    }

    @Test fun rotate() {
        val pdf = PdfFixtures.withText(tmp.newFile("invoice_oct.pdf"), pages = 6, uniqueHeights = false)
        val vm = RotateViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(PickedFile(Uri.fromFile(pdf), "invoice_oct.pdf", 4_200_000, "application/pdf")))
        compose.pumpUntil { vm.source.value != null && vm.thumbnails.value != null }
        vm.selectNone(); vm.togglePage(1); vm.rotateSelected(90)
        compose.shootBoth("rotate", settle = { Thread.sleep(300); compose.waitForIdle() }) { RotateScreen(ToolHost(ToolRegistry.get("P4")!!, onBack = {}), vm) }
    }

    @Test fun result() {
        val result = ToolResult(
            SavedOutput(Uri.parse("content://x/1"), "invoice_oct_compressed.pdf", 680_000, "application/pdf"),
            "invoice_oct.pdf", "P9", 4_200_000, 680_000, "Compressed successfully", "12 pages",
        )
        compose.shootBoth("result") {
            ResultView(result, ToolRegistry.get("P9")!!, onOpenTool = { _, _ -> }, onEditAgain = {}, onNewFile = {}, onDone = {})
        }
    }

    @Test fun error() = compose.shootBoth("error") {
        ErrorView("\"contract.pdf\" looks damaged and can't be read. Try repairing it first.", onRetry = {}, onBack = {})
    }
}

/**
 * Dialogs never let Compose go idle under Robolectric when the window has an explicit w/h qualifier (verified with
 * w411dp-h891dp-xhdpi/xxhdpi/420dpi), so dialog shots use plain xxhdpi (320x470dp default screen).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class DialogScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun passwordDialogLight() = compose.shootDialog("password-dialog", dark = false) {
        PasswordDialog(PasswordPrompt("contract_signed.pdf", wrong = true), onSubmit = {}, onCancel = {})
    }

    @Test fun passwordDialogDark() = compose.shootDialog("password-dialog", dark = true) {
        PasswordDialog(PasswordPrompt("contract_signed.pdf", wrong = true), onSubmit = {}, onCancel = {})
    }
}
