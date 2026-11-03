package com.namanbansal.forge.screenshots

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.edit.EditFont
import com.namanbansal.forge.core.pdf.edit.EditIo
import com.namanbansal.forge.core.pdf.edit.EditKind
import com.namanbansal.forge.core.pdf.edit.EditObject
import com.namanbansal.forge.core.pdf.edit.EditState
import com.namanbansal.forge.core.pdf.edit.EditStyle
import com.namanbansal.forge.core.pdf.edit.RedactHit
import com.namanbansal.forge.core.pdf.edit.RedactMark
import com.namanbansal.forge.core.pdf.edit.RedactOptions
import com.namanbansal.forge.core.pdf.edit.RedactPreset
import com.namanbansal.forge.core.pdf.edit.Redactor
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.tools.ToolRegistry
import com.namanbansal.forge.tools.pdf.ApplyRedactionsDialog
import com.namanbansal.forge.tools.pdf.EditTool
import com.namanbansal.forge.tools.pdf.RedactSearchContent
import com.namanbansal.forge.tools.pdf.RedactSearchUi
import com.namanbansal.forge.tools.pdf.ViewerModeId
import com.namanbansal.forge.tools.pdf.ViewerScreen
import com.namanbansal.forge.tools.pdf.ViewerViewModel
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
class EditScreenshotTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun init() { PDFBoxResourceLoader.init(app); PdfBoxRasterizer.install() }
    @After fun done() { PdfBoxRasterizer.uninstall() }

    private fun host() = ToolHost(ToolRegistry.get("P16")!!, onBack = {})
    private val settle = { Thread.sleep(700); compose.waitForIdle() }

    private fun body(i: Int) = listOf("Invoice 2041 for Jane Doe", "Email jane.doe@example.org or call 555-123-4567", "Card 4111 1111 1111 1111, due 2026-12-01", "Total due: 1,240.00 EUR")

    private fun document() = PdfFixtures.withText(File(tmp.root, "invoice.pdf"), 2, title = "Acme Ltd - Invoice", body = ::body, uniqueHeights = false)

    private fun viewer(): ViewerViewModel {
        val vm = ViewerViewModel(app, SavedStateHandle())
        val f = document()
        vm.onFilesPicked(listOf(PickedFile(Uri.fromFile(f), "invoice.pdf", f.length(), "application/pdf")))
        compose.pumpUntil { vm.source.value != null && vm.thumbnails.value != null && vm.aspects.value != null && vm.restored.value }
        vm.setMode(ViewerModeId.EDIT)
        return vm
    }

    @Composable
    private fun SheetFrame(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(ForgeTheme.colors.surface2), contentAlignment = Alignment.BottomCenter) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(ForgeTheme.colors.surface).padding(top = 16.dp)) { content() }
        }
    }

    private fun nodes(vm: ViewerViewModel) {
        val s = vm.edit.session
        s.add(EditObject("t1", 0, EditKind.TEXT, NormRect(0.1f, 0.5f, 0.7f, 0.56f), text = "Paid in full - thank you", font = EditFont.SERIF, bold = true, sizeFrac = 0.03f, color = EditStyle.BLUE))
        s.add(EditObject("r1", 0, EditKind.RECT, NormRect(0.1f, 0.6f, 0.45f, 0.7f), color = EditStyle.RED, strokeWidth = 4f, filled = true, fillColor = EditStyle.ORANGE, fillOpacity = 0.4f))
        s.add(EditObject("a1", 0, EditKind.ARROW, NormRect(0.5f, 0.62f, 0.85f, 0.7f), color = EditStyle.GREEN, strokeWidth = 4f))
    }

    @Test fun toolbar() {
        val vm = viewer(); nodes(vm)
        compose.shootBoth("edit-toolbar", settle = settle) { ViewerScreen(host(), vm, startMode = ViewerModeId.EDIT) }
    }

    @Test fun textOptions() {
        val vm = viewer(); nodes(vm)
        vm.edit.select("t1")
        compose.shootBoth("edit-text-options", settle = settle) { ViewerScreen(host(), vm, startMode = ViewerModeId.EDIT) }
    }

    @Test fun shapeOptions() {
        val vm = viewer(); nodes(vm)
        vm.edit.select("r1")
        compose.shootBoth("edit-shape-options", settle = settle) { ViewerScreen(host(), vm, startMode = ViewerModeId.EDIT) }
    }

    @Test fun imagePlaced() {
        val vm = viewer()
        val bmp = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(AColor.rgb(250, 214, 160))
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = AColor.rgb(46, 125, 50); drawCircle(90f, 120f, 70f, p)
            p.color = AColor.rgb(31, 79, 216); drawRect(150f, 40f, 280f, 170f, p)
        }
        vm.edit.placeImage(vm.editImages.store(bmp))
        compose.pumpUntil { vm.imageRev.value > 0 || vm.editImage(vm.edit.session.current.items.single().imageId) != null }
        compose.shootBoth("edit-image-placed", settle = settle) { ViewerScreen(host(), vm, startMode = ViewerModeId.EDIT) }
    }

    @Test fun whiteoutReplace() {
        val vm = viewer()
        vm.edit.setTool(EditTool.WHITEOUT)
        val t = runBlocking { vm.pageText(0)!! }
        val i = t.text.indexOf("Total due: 1,240.00 EUR")
        val r = t.lineRects(i, i + 23).first()
        vm.edit.session.add(EditObject("w1", 0, EditKind.WHITEOUT, NormRect(r.left - 0.01f, r.top - 0.004f, r.right + 0.06f, r.bottom + 0.004f), text = "Total due: 980.00 EUR", sizeFrac = 0.02f, color = EditStyle.BLACK, fillColor = EditStyle.WHITE))
        vm.edit.select("w1")
        compose.shootBoth("whiteout-replace", settle = settle) { ViewerScreen(host(), vm, startMode = ViewerModeId.EDIT) }
    }

    @Test fun redactMarks() {
        val vm = viewer()
        vm.edit.setTool(EditTool.REDACT)
        val t = runBlocking { vm.pageText(0)!! }
        val marks = listOf("jane.doe@example.org", "555-123-4567", "4111 1111 1111 1111").flatMap { s ->
            val i = t.text.indexOf(s); t.lineRects(i, i + s.length).map { RedactMark(vm.edit.session.newId(), 0, NormRect(it.left - 0.003f, it.top - 0.002f, it.right + 0.003f, it.bottom + 0.002f)) }
        }
        vm.edit.session.addMarks(marks)
        compose.shootBoth("redact-marks", settle = settle) { ViewerScreen(host(), vm, startMode = ViewerModeId.EDIT) }
    }

    @Test fun searchReview() {
        val hits = listOf(
            RedactHit("0:40", 0, "jane.doe@example.org", "Email", "or call 555", emptyList()),
            RedactHit("1:40", 1, "jane.doe@example.org", "Email", "or call 555", emptyList()),
            RedactHit("1:90", 1, "billing@acme.example", "Reply to", "within a week", emptyList()),
        )
        val ui = RedactSearchUi(open = true, hits = hits, on = setOf("0:40", "1:40"), done = true, label = RedactPreset.EMAIL.label)
        compose.shootBoth("redact-search-review", settle = settle) { SheetFrame { RedactSearchContent(ui, {}, {}, {}, {}, {}, {}) } }
    }

    @Test fun confirmLight() = compose.shootDialog("redact-confirm", dark = false) { ApplyRedactionsDialog(5, 2, true, {}, false, {}, {}) }
    @Test fun confirmDark() = compose.shootDialog("redact-confirm", dark = true) { ApplyRedactionsDialog(5, 2, true, {}, false, {}, {}) }

    private fun recordTo(name: String, file: File, scale: Float = 1.2f) {
        PDDocument.load(file).use { doc ->
            val bmp = PDFRenderer(doc).renderImage(0, scale)
            if (System.getProperty("roborazzi.test.record") == "true") File("../docs/screenshots/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }
    }

    /** What a saved edited page really looks like: written by [EditIo], drawn by PDFBox's own rasterizer. */
    @Test fun savedEditedPage() {
        val out = File(tmp.root, "edited.pdf")
        val state = EditState(listOf(
            EditObject("w", 0, EditKind.WHITEOUT, NormRect(0.07f, 0.285f, 0.8f, 0.325f), text = "Total due: 980.00 EUR", sizeFrac = 0.02f),
            EditObject("t", 0, EditKind.TEXT, NormRect(0.1f, 0.5f, 0.7f, 0.56f), text = "Paid in full - thank you", font = EditFont.SERIF, bold = true, sizeFrac = 0.03f, color = EditStyle.BLUE),
            EditObject("r", 0, EditKind.RECT, NormRect(0.1f, 0.6f, 0.45f, 0.7f), color = EditStyle.RED, strokeWidth = 4f, filled = true, fillColor = EditStyle.ORANGE, fillOpacity = 1f),
            EditObject("e", 0, EditKind.ELLIPSE, NormRect(0.5f, 0.6f, 0.8f, 0.7f), color = EditStyle.BLUE, strokeWidth = 3f),
            EditObject("a", 0, EditKind.ARROW, NormRect(0.1f, 0.75f, 0.8f, 0.82f), color = EditStyle.GREEN, strokeWidth = 4f),
        ))
        EditIo.apply(document(), out, state) { null }
        recordTo("edit-saved-render", out)
    }

    /** What a redacted page really looks like, after the text under the black boxes was taken out of the file. */
    @Test fun redactedPage() {
        val f = document()
        val t = com.namanbansal.forge.core.pdf.viewer.PdfDocIndex(f).use { it.text(0) }
        val marks = listOf("jane.doe@example.org", "555-123-4567", "4111 1111 1111 1111").flatMap { s ->
            val i = t.text.indexOf(s); t.lineRects(i, i + s.length).map { RedactMark("m$i", 0, NormRect(it.left - 0.002f, it.top - 0.002f, it.right + 0.002f, it.bottom + 0.002f)) }
        }
        val report = Redactor.redactInPlace(f, marks, RedactOptions())
        check(report.glyphs > 30) { report.summary }
        check(!PDDocument.load(f).use { d -> com.tom_roush.pdfbox.text.PDFTextStripper().getText(d) }.contains("4111")) { "card number still in the file" }
        recordTo("redact-saved-render", f)
    }

}
