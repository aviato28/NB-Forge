package com.namanbansal.forge.ui

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.ImageFixtures
import com.namanbansal.forge.core.PdfBoxRasterizer
import com.namanbansal.forge.core.PdfFixtures
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.tools.ToolRegistry
import com.namanbansal.forge.tools.ToolScreens
import com.namanbansal.forge.tools.ToolStatus
import com.namanbansal.forge.ui.theme.ForgeTheme
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/**
 * Compose smoke test for every Chunk B screen, built through the same path as the nav graph (no view model argument, so
 * `viewModel()` must construct it): it shows its picker, and once a PDF is open its primary action appears.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChunkBScreensTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun init() { PDFBoxResourceLoader.init(app); PdfBoxRasterizer.install() }
    @After fun done() { PdfBoxRasterizer.uninstall() }

    private class Spec(val code: String, val pick: String, val action: String, val enabled: Boolean = true)

    // code, picker headline, primary button, whether the button is enabled right after picking
    private val specs = listOf(
        Spec("P9", "Choose a PDF to compress", "Compress"), Spec("P10", "Choose a PDF to convert", "Convert to grayscale"),
        Spec("P11", "Choose a PDF to flatten", "Flatten", enabled = false), // a plain PDF has nothing to flatten
        Spec("P12", "Choose a damaged PDF to repair", "Repair"), Spec("P13", "Choose a PDF to clean", "Remove selected", enabled = false),
        Spec("P14", "Choose a PDF to optimise", "Optimise"), Spec("P20", "Choose a PDF to watermark", "Add watermark"),
        Spec("P21", "Choose a PDF to number", "Add to PDF"), Spec("P22", "Choose a PDF to edit bookmarks", "Save bookmarks"),
        Spec("P23", "Choose a PDF to edit", "Save"), Spec("P24", "Choose a PDF to edit links", "Save links", enabled = false),
        Spec("P29", "Choose a PDF to protect", "Protect", enabled = false), Spec("P7", "Choose a PDF to crop", "Crop", enabled = false),
        Spec("P8", "Choose a PDF to arrange", "Combine pages"), Spec("P31", "Choose a PDF to convert", "Save 3 images"),
    )

    private fun pump(until: () -> Boolean) {
        val end = System.currentTimeMillis() + 30_000
        while (!until() && System.currentTimeMillis() < end) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)); compose.waitForIdle(); Thread.sleep(20) }
        assertTrue("condition not reached in time", until())
    }

    private fun content(code: String): @Composable () -> Unit {
        val screen = ToolScreens.screens.getValue(code)
        val host = ToolHost(ToolRegistry.get(code)!!, onBack = {})
        return { ForgeTheme { screen(host) } }
    }

    @Test fun everyChunkBToolIsAvailableAndRouted() {
        val codes = listOf("P7", "P8", "P9", "P10", "P11", "P12", "P13", "P14", "P20", "P21", "P22", "P23", "P24", "P29", "P30", "P31", "P32")
        for (c in codes) {
            assertEquals("$c status", ToolStatus.Available, ToolRegistry.get(c)!!.status)
            assertTrue("$c screen", ToolScreens.screens.containsKey(c))
        }
        // they all accept PDFs (images for P32), so the grid, the viewer's More menu and share-in offer them
        for (c in codes - "P32") assertTrue("$c accepts pdf", ToolRegistry.get(c)!!.accepts.contains(com.namanbansal.forge.core.FileKind.PDF))
        assertTrue(ToolRegistry.get("P32")!!.accepts.contains(com.namanbansal.forge.core.FileKind.IMAGE))
        val offered = ToolRegistry.availableFor(setOf(com.namanbansal.forge.core.FileKind.PDF)).map { it.code }
        assertTrue(offered.containsAll(codes - "P32"))
    }

    @Test fun compressShowsItsPickerThroughTheDefaultFactory() {
        compose.setContent(content("P9"))
        compose.onNodeWithText("Choose a PDF to compress").assertExists()
    }

    private fun picked(spec: Spec) {
        val pdf = PdfFixtures.withText(tmp.newFile("${spec.code}-x.pdf"), 3, uniqueHeights = false)
        val app = this.app as com.namanbansal.forge.ForgeApp
        app.pendingFiles.put(spec.code, listOf(com.namanbansal.forge.core.PickedFile(Uri.fromFile(pdf), pdf.name, pdf.length(), "application/pdf")))
        compose.setContent(content(spec.code))
        // the title can read like the button ("Repair"), so take the last match: the button sits below the title
        fun button() = compose.onAllNodesWithText(spec.action).onLast()
        pump { runCatching { button().assertExists() }.isSuccess }
        if (spec.enabled) pump { runCatching { button().assertIsEnabled() }.isSuccess } else button().assertIsNotEnabled()
    }

    @Test fun compress() = picked(specs.first { it.code == "P9" })
    @Test fun grayscale() = picked(specs.first { it.code == "P10" })
    @Test fun flatten() = picked(specs.first { it.code == "P11" })
    @Test fun repair() = picked(specs.first { it.code == "P12" })
    @Test fun sanitise() = picked(specs.first { it.code == "P13" })
    @Test fun optimise() = picked(specs.first { it.code == "P14" })
    @Test fun watermark() = picked(specs.first { it.code == "P20" })
    @Test fun pageNumbers() = picked(specs.first { it.code == "P21" })
    @Test fun bookmarks() = picked(specs.first { it.code == "P22" })
    @Test fun metadata() = picked(specs.first { it.code == "P23" })
    @Test fun links() = picked(specs.first { it.code == "P24" })
    @Test fun protect() = picked(specs.first { it.code == "P29" })
    @Test fun crop() = picked(specs.first { it.code == "P7" })
    @Test fun booklet() = picked(specs.first { it.code == "P8" })
    @Test fun pdfToImages() = picked(specs.first { it.code == "P31" })

    @Test fun unlockExplainsWhenThereIsNothingToRemove() {
        val pdf = PdfFixtures.withText(tmp.newFile("plain.pdf"), 2)
        (app as com.namanbansal.forge.ForgeApp).pendingFiles.put("P30", listOf(com.namanbansal.forge.core.PickedFile(Uri.fromFile(pdf), "plain.pdf", pdf.length(), "application/pdf")))
        compose.setContent(content("P30"))
        pump { runCatching { compose.onNodeWithText("This PDF has no password and no restrictions, so there is nothing to remove.").assertExists() }.isSuccess }
    }

    @Test fun imagesToPdfShowsItsPicker() {
        compose.setContent(content("P32"))
        compose.onNodeWithText("Choose pictures to turn into a PDF").assertExists()
    }
}
