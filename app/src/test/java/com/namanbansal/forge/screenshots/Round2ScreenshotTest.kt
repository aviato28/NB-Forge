package com.namanbansal.forge.screenshots

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.PdfBoxRasterizer
import com.namanbansal.forge.core.PdfFixtures
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.pdf.PdfThumbnails
import com.namanbansal.forge.core.pdf.edit.ExistingApplier
import com.namanbansal.forge.core.pdf.edit.content.CoverageFixtures
import com.namanbansal.forge.core.pdf.edit.content.CoverageFixtures.BLUE
import com.namanbansal.forge.core.pdf.edit.content.CoverageFixtures.Span
import com.namanbansal.forge.core.pdf.edit.content.FontOpener
import com.namanbansal.forge.core.pdf.edit.content.scanBlocks
import com.namanbansal.forge.core.pdf.edit.content.textEdit
import com.namanbansal.forge.core.share.FileInfoRows
import com.namanbansal.forge.core.share.FileOrigin
import com.namanbansal.forge.core.share.PdfInfo
import com.namanbansal.forge.data.ViewerPrefs
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.tools.ToolRegistry
import com.namanbansal.forge.tools.pdf.EditTool
import com.namanbansal.forge.tools.pdf.ExRoute
import com.namanbansal.forge.tools.pdf.FileMenuActions
import com.namanbansal.forge.tools.pdf.FileInfoContent
import com.namanbansal.forge.tools.pdf.SaveWhereContent
import com.namanbansal.forge.tools.pdf.SharePagesContent
import com.namanbansal.forge.tools.pdf.ViewerMenuItems
import com.namanbansal.forge.tools.pdf.ViewerModeId
import com.namanbansal.forge.tools.pdf.ViewerScreen
import com.namanbansal.forge.tools.pdf.ViewerViewModel
import com.namanbansal.forge.tools.pdf.WhyLocked
import com.namanbansal.forge.tools.pdf.WhyLockedContent
import com.namanbansal.forge.ui.screens.RecentMenuItems
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.rendering.PDFRenderer
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

/** Round 2E / 2F screens: Share + Save bar, temporary banner, sheets, Files row menu, why-locked sheet, debug overlay, before/after renders. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class Round2ScreenshotTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val fonts = FontOpener { File("src/main/assets/$it").inputStream() }
    private val settle = { Thread.sleep(700); compose.waitForIdle() }

    @Before fun init() { PDFBoxResourceLoader.init(app); PdfBoxRasterizer.install() }
    @After fun done() { PdfBoxRasterizer.uninstall() }

    private fun host(code: String = "P40") = ToolHost(ToolRegistry.get(code)!!, onBack = {})

    private fun open(name: String, file: File): ViewerViewModel {
        val vm = ViewerViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(PickedFile(Uri.fromFile(file), name, file.length(), "application/pdf")))
        compose.pumpUntil { vm.source.value != null && vm.thumbnails.value != null && vm.aspects.value != null && vm.restored.value }
        return vm
    }

    private fun panel(content: @androidx.compose.runtime.Composable () -> Unit): @androidx.compose.runtime.Composable () -> Unit = {
        Column(Modifier.padding(16.dp).width(380.dp).background(ForgeTheme.colors.surface).border(1.dp, ForgeTheme.colors.border)) { content() }
    }

    // ---- 2E ------------------------------------------------------------------------------------------------------------------

    @Test fun viewerBarAndBanner() {
        val vm = open("Amazon invoice.pdf", PdfFixtures.withText(File(tmp.root, "Amazon invoice.pdf"), 4, uniqueHeights = false))
        compose.shootBoth("viewer-share-save-banner", settle = settle) { ViewerScreen(host(), vm) }
    }

    @Test fun overflowMenu() {
        val noop = {}
        compose.shootBoth("viewer-file-menu", settle = settle, content = panel {
            ViewerMenuItems(ViewerPrefs(), {}, {}, FileMenuActions(noop, noop, noop, noop, noop, noop, noop), {}, false, {})
        })
    }

    @Test fun saveWhere() = compose.shootBoth("save-where-sheet", settle = settle, content = panel { SaveWhereContent("Amazon invoice.pdf", true, {}, {}, {}) })

    @Test fun fileInfo() {
        val origin = FileOrigin(true, "Amazon", "Temporary file from Amazon")
        val rows = FileInfoRows.build("Amazon invoice.pdf", 183_400, origin, FileInfoRows.savedWhere(origin), PdfInfo(4, "Order 114-2231", "Amazon.in", "Skia/PDF m118", "", "1.4", false))
        compose.shootBoth("file-info-sheet", settle = settle, content = panel { FileInfoContent(rows) })
    }

    @Test fun filesRowMenu() {
        val noop = {}
        compose.shootBoth("files-row-menu", settle = settle, content = panel { RecentMenuItems(true, true, noop, noop, noop, noop, noop, noop) })
    }

    @Test fun sharePages() {
        val f = PdfFixtures.withText(File(tmp.root, "doc.pdf"), 9, uniqueHeights = false)
        val thumbs = PdfThumbnails(f)
        compose.shootBoth("share-selected-pages", settle = { Thread.sleep(900); compose.waitForIdle() }, content = panel { SharePagesContent(thumbs, 9, 2, {}, {}) })
    }

    // ---- 2F ------------------------------------------------------------------------------------------------------------------

    @Test fun whyCantEdit() = compose.shootBoth("why-cant-edit-sheet", settle = settle, content = panel {
        WhyLockedContent(WhyLocked("Text drawn as outlines", com.namanbansal.forge.core.pdf.edit.content.LockReason.OUTLINES.detail), {}, {})
    })

    @Test fun commentBoxSheet() = compose.shootBoth("why-cant-edit-comment-box", settle = settle, content = panel {
        WhyLockedContent(WhyLocked("This is a comment box", "This text belongs to a comment box (a FreeText annotation) that sits on top of the page, not to the page's own text.", ExRoute.Annotate, "Open Annotate"), {}, {})
    })

    @Test fun debugOverlay() {
        val f = CoverageFixtures.mixedParagraph(File(tmp.root, "dbg.pdf"), listOf(Span("This line is normal and editable,"), Span("this word is bold", PDType1Font.HELVETICA_BOLD), Span("and a link", rgb = BLUE), Span("ends the paragraph on the page.")), extra = { cs ->
            cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 26f); cs.setRenderingMode(com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode.STROKE); cs.newLineAtOffset(72f, 500f); cs.showText("OUTLINED"); cs.endText()
        })
        val vm = open("dbg.pdf", f)
        vm.setMode(ViewerModeId.EDIT); vm.edit.setTool(EditTool.EXISTING)
        vm.edit.existing.ensure(0)
        compose.pumpUntil { vm.edit.existing.objects.value[0] != null }
        vm.edit.existing.setDebug(true); vm.edit.existing.ensureDebug(0)
        compose.pumpUntil { vm.edit.existing.debug.value[0] != null }
        compose.shootBoth("edit-existing-debug-overlay", settle = settle) { ViewerScreen(host("P15"), vm, startMode = ViewerModeId.EDIT, startTool = EditTool.EXISTING) }
    }

    private fun render(name: String, file: File, scale: Float = 1.2f) {
        PDDocument.load(file).use { doc ->
            val bmp = PDFRenderer(doc).renderImage(0, scale)
            if (System.getProperty("roborazzi.test.record") == "true") File("../docs/screenshots/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }
    }

    @Test fun noUnicodeParagraphBeforeAndAfter() {
        val lines = listOf("Invoice 2026-0042 total due 1,250.00", "Payable to Forge Tools Ltd within thirty days of the", "date shown above. Late payments add two percent.")
        val f = CoverageFixtures.cidNoToUnicode(File(tmp.root, "inv.pdf"), lines, subset = true)
        render("edit-existing-nounicode-before", f)
        val b = scanBlocks(f).first()
        ExistingApplier.applyInPlace(f, listOf(textEdit(b, "Invoice 2026-0042 total due 980.50. Payable to Forge Tools Ltd within thirty days of the date shown above.")), fonts) { null }
        render("edit-existing-nounicode-after", f)
    }

    @Test fun mixedStyleParagraphBeforeAndAfter() {
        val f = CoverageFixtures.mixedParagraph(File(tmp.root, "mix.pdf"), listOf(
            Span("Please review the contract and send it back by"), Span("Friday", PDType1Font.HELVETICA_BOLD), Span("so that we can sign it. All details are on the portal at"),
            Span("example.com/contract", rgb = BLUE), Span("and the team will answer every question you may have about it."),
        ), embed = true)
        render("edit-existing-mixed-before", f)
        val b = scanBlocks(f).single()
        ExistingApplier.applyInPlace(f, listOf(textEdit(b, b.text.replace("Please review", "Kindly check carefully"))), fonts) { null }
        render("edit-existing-mixed-after", f)
    }
}
