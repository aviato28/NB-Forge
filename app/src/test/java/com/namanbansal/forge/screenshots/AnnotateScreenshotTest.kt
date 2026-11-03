package com.namanbansal.forge.screenshots

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.PdfBoxRasterizer
import com.namanbansal.forge.core.PdfFixtures
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.pdf.annot.Annot
import com.namanbansal.forge.core.pdf.annot.AnnotPalette
import com.namanbansal.forge.core.pdf.annot.AnnotTool
import com.namanbansal.forge.core.pdf.annot.AnnotType
import com.namanbansal.forge.core.pdf.annot.NormPt
import com.namanbansal.forge.core.pdf.annot.PdfAnnotIo
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.tools.ToolRegistry
import com.namanbansal.forge.tools.pdf.NoteEditorContent
import com.namanbansal.forge.tools.pdf.UnsavedChangesDialog
import com.namanbansal.forge.tools.pdf.ViewerModeId
import com.namanbansal.forge.tools.pdf.ViewerScreen
import com.namanbansal.forge.tools.pdf.ViewerViewModel
import com.namanbansal.forge.tools.pdf.ComingSoonContent
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
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
class AnnotateScreenshotTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun init() { PDFBoxResourceLoader.init(app); PdfBoxRasterizer.install() }
    @After fun done() { PdfBoxRasterizer.uninstall() }

    private fun host() = ToolHost(ToolRegistry.get("P17")!!, onBack = {})
    private val settle = { Thread.sleep(600); compose.waitForIdle() }

    private val lorem = listOf(
        "Revenue grew 14 percent year on year, driven by subscriptions.",
        "Operating costs were flat, which lifted margin to 21 percent.",
        "The board approved the plan for the second half of the year.",
        "See https://nbansal28.com for the full figures and notes.",
    )

    private fun reader(): ViewerViewModel {
        val vm = ViewerViewModel(app, SavedStateHandle())
        val f = PdfFixtures.withText(File(tmp.root, "annual_report.pdf"), 6, title = "Annual report 2026", body = { lorem }, uniqueHeights = false)
        vm.onFilesPicked(listOf(PickedFile(Uri.fromFile(f), "annual_report.pdf", 5_100_000, "application/pdf")))
        compose.pumpUntil { vm.source.value != null && vm.thumbnails.value != null && vm.aspects.value != null && vm.restored.value }
        return vm
    }

    /** Boxes of the first occurrence of [phrase] on page 1, from the real text positions. */
    private fun quadsOf(vm: ViewerViewModel, phrase: String): List<com.namanbansal.forge.core.pdf.NormRect> {
        val t = runBlocking { vm.pageText(0)!! }
        val i = t.text.indexOf(phrase)
        return t.lineRects(i, i + phrase.length)
    }

    private fun annotated(vm: ViewerViewModel) {
        val s = vm.annotate.session
        s.add(Annot("h1", 0, AnnotType.HIGHLIGHT, AnnotPalette.YELLOW, quads = quadsOf(vm, "grew 14 percent year on year")))
        s.add(Annot("u1", 0, AnnotType.UNDERLINE, AnnotPalette.RED, quads = quadsOf(vm, "lifted margin to 21 percent")))
        s.add(Annot("s1", 0, AnnotType.STRIKEOUT, AnnotPalette.BLUE, quads = quadsOf(vm, "second half")))
    }

    private fun ink(vm: ViewerViewModel) {
        val s = vm.annotate.session
        val wave = (0..40).map { NormPt(0.12f + it * 0.0165f, 0.52f + 0.025f * kotlin.math.sin(it / 3.5f)) }
        s.add(Annot("p1", 0, AnnotType.INK, AnnotPalette.EMBER, strokes = listOf(wave), width = AnnotPalette.thicknesses[1]))
        s.add(Annot("p2", 0, AnnotType.INK, AnnotPalette.BLUE, strokes = listOf(listOf(NormPt(0.15f, 0.62f), NormPt(0.3f, 0.6f), NormPt(0.45f, 0.66f), NormPt(0.6f, 0.61f), NormPt(0.8f, 0.65f))), width = AnnotPalette.thicknesses[2]))
        s.add(Annot("m1", 0, AnnotType.INK, AnnotPalette.GREEN, AnnotPalette.HIGHLIGHTER_OPACITY, strokes = listOf(listOf(NormPt(0.1f, 0.72f), NormPt(0.9f, 0.72f))), width = AnnotPalette.thicknesses[1] * AnnotPalette.HIGHLIGHTER_FACTOR))
    }

    @Test fun modeBar() {
        val vm = reader()
        compose.shootBoth("viewer-modebar", settle = settle) { ViewerScreen(host(), vm) }
    }

    @Test fun toolbar() {
        val vm = reader(); vm.setMode(ViewerModeId.ANNOTATE); vm.annotate.selectTool(AnnotTool.HIGHLIGHT)
        compose.shootBoth("annotate-toolbar", settle = settle) { ViewerScreen(host(), vm, startMode = ViewerModeId.ANNOTATE) }
    }

    @Test fun highlightDrawn() {
        val vm = reader(); vm.setMode(ViewerModeId.ANNOTATE); annotated(vm); vm.annotate.selectTool(AnnotTool.HIGHLIGHT)
        compose.shootBoth("annotate-highlight-drawn", settle = settle) { ViewerScreen(host(), vm, startMode = ViewerModeId.ANNOTATE) }
    }

    @Test fun inkAndHighlighter() {
        val vm = reader(); vm.setMode(ViewerModeId.ANNOTATE); annotated(vm); ink(vm); vm.annotate.selectTool(AnnotTool.PEN)
        compose.shootBoth("annotate-ink", settle = settle) { ViewerScreen(host(), vm, startMode = ViewerModeId.ANNOTATE) }
    }

    @Test fun selectedAnnotation() {
        val vm = reader(); vm.setMode(ViewerModeId.ANNOTATE); annotated(vm)
        vm.annotate.session.add(Annot("n1", 0, AnnotType.NOTE, AnnotPalette.YELLOW, anchor = NormPt(0.82f, 0.2f), text = "Check with finance"))
        vm.annotate.select("h1")
        compose.shootBoth("annotate-selected-annotation", settle = settle) { ViewerScreen(host(), vm, startMode = ViewerModeId.ANNOTATE) }
    }

    @Test fun noteEditor() {
        compose.shootBoth("annotate-note-editor", settle = settle) {
            Box(Modifier.fillMaxSize().background(ForgeTheme.colors.surface2), contentAlignment = Alignment.BottomCenter) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(ForgeTheme.colors.surface).padding(top = 16.dp)) {
                    NoteEditorContent("Ask finance to confirm the 21 percent margin before the board meeting.", isNew = false, onSave = {}, onCancel = {}, onDelete = {})
                }
            }
        }
    }

    @Test fun comingSoon() {
        compose.shootBoth("viewer-coming-soon", settle = settle) {
            Box(Modifier.fillMaxSize().background(ForgeTheme.colors.surface2), contentAlignment = Alignment.BottomCenter) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(ForgeTheme.colors.surface).padding(top = 20.dp)) {
                    ComingSoonContent(ViewerModeId.FILL_SIGN, {})
                }
            }
        }
    }

    @Test fun unsavedLight() = compose.shootDialog("unsaved-dialog", false) { UnsavedChangesDialog({}, {}, {}) }
    @Test fun unsavedDark() = compose.shootDialog("unsaved-dialog", true) { UnsavedChangesDialog({}, {}, {}) }

    /** The proof that saved annotations carry appearance streams: a PDF written by [PdfAnnotIo] drawn by PDFBox's own rasterizer. */
    @Test fun savedPageRendered() {
        val vm = reader()
        annotated(vm); ink(vm)
        val n = Annot("n1", 0, AnnotType.NOTE, AnnotPalette.YELLOW, anchor = NormPt(0.82f, 0.2f), text = "Check with finance")
        val src = File(tmp.root, "annual_report.pdf")
        val out = File(tmp.root, "saved.pdf")
        PdfAnnotIo.write(src, out, emptyList(), vm.annotate.session.current + n)
        PDDocument.load(out).use { doc ->
            check(doc.getPage(0).annotations.size == 7) { "annotations were not written" }
            val bmp = PDFRenderer(doc).renderImage(0, 1.2f)
            check(bmp.width > 100)
            if (System.getProperty("roborazzi.test.record") == "true") // only when recording screenshots
                File("../docs/screenshots/annotate-saved-render.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
        }
    }
}
