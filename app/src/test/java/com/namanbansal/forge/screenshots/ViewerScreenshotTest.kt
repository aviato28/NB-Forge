package com.namanbansal.forge.screenshots

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.DefaultViewer
import com.namanbansal.forge.core.PdfBoxRasterizer
import com.namanbansal.forge.core.PdfFixtures
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.pdf.PdfIntake
import com.namanbansal.forge.core.pdf.viewer.OutlineItem
import com.namanbansal.forge.core.pdf.viewer.TextSelection
import com.namanbansal.forge.core.pdf.viewer.ViewerPosition
import com.namanbansal.forge.data.ViewerPrefs
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.tools.ToolRegistry
import com.namanbansal.forge.tools.pdf.OutlinePanel
import com.namanbansal.forge.tools.pdf.OutlineSheet
import com.namanbansal.forge.tools.pdf.ViewerHome
import com.namanbansal.forge.tools.pdf.ViewerMenuItems
import com.namanbansal.forge.tools.pdf.ViewerScreen
import com.namanbansal.forge.tools.pdf.ViewerViewModel
import com.namanbansal.forge.ui.components.GroupLabel
import com.namanbansal.forge.ui.screens.PdfViewerSettings
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.runBlocking
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class ViewerScreenshotTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun init() { PDFBoxResourceLoader.init(app); PdfBoxRasterizer.install() }
    @After fun done() { PdfBoxRasterizer.uninstall() }

    private fun host() = ToolHost(ToolRegistry.get("P40")!!, onBack = {})

    private val lorem = listOf(
        "Revenue grew 14 percent year on year, driven by subscriptions.",
        "Operating costs were flat, which lifted margin to 21 percent.",
        "The board approved the plan for the second half of the year.",
        "See https://nbansal28.com for the full figures and notes.",
    )

    private fun reader(): ViewerViewModel {
        val vm = ViewerViewModel(app, SavedStateHandle())
        val f = PdfFixtures.withText(File(tmp.root, "annual_report.pdf"), 12, title = "Annual report 2026", body = { lorem }, uniqueHeights = false)
        vm.onFilesPicked(listOf(PickedFile(Uri.fromFile(f), "annual_report.pdf", 5_100_000, "application/pdf")))
        compose.pumpUntil { vm.source.value != null && vm.thumbnails.value != null && vm.aspects.value != null && vm.restored.value }
        return vm
    }

    private val NOW = 1_765_000_000_000L
    private val settle = { Thread.sleep(500); compose.waitForIdle() }

    @Test fun home() {
        val covers = File(tmp.root, "covers").apply { mkdirs() }
        val names = listOf("Annual report 2026.pdf", "Lease agreement.pdf", "Boarding pass.pdf", "Thesis draft v3.pdf")
        val recents = names.mapIndexed { i, n ->
            val f = PdfFixtures.withText(File(tmp.root, "r$i.pdf"), 3, title = n.removeSuffix(".pdf"), uniqueHeights = false)
            val bmp = runBlocking { PdfIntake.cover(f) }
            File(covers, "k$i.jpg").outputStream().use { bmp!!.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
            ViewerPosition("k$i", "content://x/$i", n, 1_000_000, listOf(12, 4, 1, 86)[i], listOf(3, 0, 0, 41)[i], 1f, NOW - i * listOf(3_600_000L, 86_400_000L, 3L * 86_400_000, 14L * 86_400_000)[i])
        }
        compose.shootBoth("viewer-home", settle = settle) {
            ViewerHome("Viewer", recents, false, null, { File(covers, "${it.key}.jpg") }, {}, {}, {}, {}, {}, {}, now = NOW + 60_000)
        }
    }

    @Test fun reading() {
        val vm = reader()
        compose.shootBoth("viewer-reading", settle = settle) { ViewerScreen(host(), vm) }
    }

    @Test fun immersive() {
        val vm = reader()
        compose.setThemed(false) { ViewerScreen(host(), vm) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Page 1").performClick() // a tap on the page hides the bars
        compose.mainClock.advanceTimeBy(900)
        settle()
        compose.captureShot("viewer-immersive-light")
    }

    @Test fun immersiveDark() {
        val vm = reader()
        compose.setThemed(true) { ViewerScreen(host(), vm) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Page 1").performClick()
        compose.mainClock.advanceTimeBy(900)
        settle()
        compose.captureShot("viewer-immersive-dark")
    }

    @Test fun selection() {
        val vm = reader()
        val t = runBlocking { vm.pageText(0)!! }
        val i = t.text.indexOf("subscriptions")
        val s = t.text.indexOf("Revenue")
        vm.setSelection(TextSelection(0, s, i + "subscriptions".length - 1))
        compose.pumpUntil { vm.selection.value != null }
        compose.shootBoth("viewer-selection", settle = { Thread.sleep(700); compose.waitForIdle() }) { ViewerScreen(host(), vm) }
    }

    private val outline = listOf(
        OutlineItem("Summary", 0, emptyList()),
        OutlineItem("1. Results", 2, listOf(OutlineItem("1.1 Revenue", 2, emptyList()), OutlineItem("1.2 Margin", 3, emptyList()))),
        OutlineItem("2. Outlook", 6, emptyList()),
        OutlineItem("Appendix", 9, emptyList()),
    )

    @Test fun outlineLight() = outlineShot(false)
    @Test fun outlineDark() = outlineShot(true)
    private fun outlineShot(dark: Boolean) {
        compose.shootDialog("viewer-outline", dark) { OutlineSheet({}) { OutlinePanel(outline, null, 12, 2, {}) } }
    }

    @Test fun settingsMenu() {
        compose.shootBoth("viewer-settings-menu", settle = settle) {
            Column(Modifier.padding(16.dp).width(300.dp).background(ForgeTheme.colors.surface).border(1.dp, ForgeTheme.colors.border)) {
                ViewerMenuItems(ViewerPrefs(), {}, {})
            }
        }
    }

    @Test fun defaultViewerOther() = compose.shootBoth("settings-default-viewer-other", settle = settle) {
        Column(Modifier.padding(16.dp)) { GroupLabel("PDF viewer"); PdfViewerSettings(DefaultViewer.Other("Drive PDF Viewer", "com.google.pdf"), true, {}, {}, {}) }
    }

    @Test fun defaultViewerForge() = compose.shootBoth("settings-default-viewer-forge", settle = settle) {
        Column(Modifier.padding(16.dp)) { GroupLabel("PDF viewer"); PdfViewerSettings(DefaultViewer.Forge, true, {}, {}, {}) }
    }
}
