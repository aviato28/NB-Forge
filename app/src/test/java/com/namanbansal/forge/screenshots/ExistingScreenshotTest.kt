package com.namanbansal.forge.screenshots

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.PdfBoxRasterizer
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.edit.ExistingApplier
import com.namanbansal.forge.core.pdf.edit.ExistingEdit
import com.namanbansal.forge.core.pdf.edit.ExistingKind
import com.namanbansal.forge.core.pdf.edit.content.ExistingFixtures
import com.namanbansal.forge.core.pdf.edit.content.ExistingFixtures.line
import com.namanbansal.forge.core.pdf.edit.content.ExistingFixtures.paragraph
import com.namanbansal.forge.core.pdf.edit.content.FontOpener
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.tools.ToolRegistry
import com.namanbansal.forge.tools.pdf.EditTool
import com.namanbansal.forge.tools.pdf.ExBlockRef
import com.namanbansal.forge.tools.pdf.ExImageRef
import com.namanbansal.forge.tools.pdf.PageHit
import com.namanbansal.forge.tools.pdf.ViewerModeId
import com.namanbansal.forge.tools.pdf.ViewerScreen
import com.namanbansal.forge.tools.pdf.ViewerViewModel
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class ExistingScreenshotTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val fonts = FontOpener { File("src/main/assets/$it").inputStream() }

    @Before fun init() { PDFBoxResourceLoader.init(app); PdfBoxRasterizer.install() }
    @After fun done() { PdfBoxRasterizer.uninstall() }

    private fun host() = ToolHost(ToolRegistry.get("P15")!!, onBack = {})
    private val settle = { Thread.sleep(900); compose.waitForIdle() }

    private val intro = "The committee approved the budget after a long discussion about costs and about the plan for the coming year in the north office."
    private val second = "A second paragraph follows the first one. It is separate, keeps its own font and can be edited without touching anything else on the page."

    /** A report page: title, two paragraphs, a picture, a rectangle and a line. */
    private fun report(name: String = "report.pdf"): File = ExistingFixtures.doc(File(tmp.root, name)) { d, _, cs ->
        cs.line(ExistingFixtures.HELV_B, 24f, 54f, 730f, "Annual Budget Review")
        cs.paragraph(ExistingFixtures.TIMES, 13f, 54f, 695f, 500f, intro)
        cs.paragraph(ExistingFixtures.TIMES, 13f, 54f, 625f, 500f, second)
        val bmp = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply { drawColor(AColor.rgb(250, 214, 160)); val p = Paint(Paint.ANTI_ALIAS_FLAG); p.color = AColor.rgb(46, 125, 50); drawCircle(90f, 120f, 70f, p); p.color = AColor.rgb(31, 79, 216); drawRect(150f, 40f, 280f, 170f, p) }
        cs.drawImage(LosslessFactory.createFromImage(d, bmp), 54f, 380f, 240f, 160f)
        cs.setNonStrokingColor(0.9f, 0.45f, 0.1f); cs.addRect(330f, 400f, 200f, 110f); cs.fill()
        cs.setStrokingColor(0.1f, 0.1f, 0.1f); cs.setLineWidth(3f); cs.moveTo(54f, 340f); cs.lineTo(540f, 340f); cs.stroke()
    }

    private fun subset(): File {
        val f = File(tmp.root, "subset.pdf")
        PDDocument().use { d ->
            val p = PDPage(PDRectangle.LETTER); d.addPage(p)
            val font = fonts.open("fonts/NotoSerif-Regular.ttf").use { PDType0Font.load(d, it, true) }
            PDPageContentStream(d, p).use { cs ->
                cs.beginText(); cs.setFont(font, 20f); cs.newLineAtOffset(54f, 700f); cs.showText("Quick brown fox jumps"); cs.endText()
                cs.beginText(); cs.setFont(ExistingFixtures.HELV, 12f); cs.newLineAtOffset(54f, 650f); cs.showText("The text above uses an embedded subset font."); cs.endText()
            }
            d.save(f)
        }
        return f
    }

    private fun viewer(f: File): ViewerViewModel {
        val vm = ViewerViewModel(app, SavedStateHandle())
        vm.onFilesPicked(listOf(PickedFile(Uri.fromFile(f), f.name, f.length(), "application/pdf")))
        compose.pumpUntil { vm.source.value != null && vm.thumbnails.value != null && vm.aspects.value != null && vm.restored.value }
        vm.setMode(ViewerModeId.EDIT)
        vm.edit.setTool(EditTool.EXISTING)
        vm.edit.existing.ensure(0)
        compose.pumpUntil { vm.edit.existing.objects.value[0] != null }
        return vm
    }

    private fun block(vm: ViewerViewModel, start: String) = vm.edit.existing.objects.value[0]!!.blocks.first { it.text.startsWith(start) }

    @Test fun blocks() {
        val vm = viewer(report())
        compose.shootBoth("edit-existing-blocks", settle = settle) { ViewerScreen(host(), vm, startMode = ViewerModeId.EDIT, startTool = EditTool.EXISTING) }
    }

    @Test fun inlineEditor() {
        val vm = viewer(report())
        val b = block(vm, "The committee")
        vm.edit.existing.openEditor(ExBlockRef(0, b.key))
        vm.edit.existing.draft = "The committee approved the budget, but only after a short discussion. Spending on the north office will be reviewed again next spring."
        compose.shootBoth("edit-existing-inline-editor", settle = settle) { ViewerScreen(host(), vm, startMode = ViewerModeId.EDIT, startTool = EditTool.EXISTING) }
    }

    @Test fun fontNotice() {
        val vm = viewer(subset())
        val b = block(vm, "Quick")
        vm.edit.existing.openEditor(ExBlockRef(0, b.key))
        vm.edit.existing.draft = "Lazy zebra quiz jumps"
        vm.edit.existing.closeEditor(true)
        vm.edit.existing.select(ExBlockRef(0, b.key))
        compose.pumpUntil { vm.edit.existing.previews.value.isNotEmpty() }
        compose.shootBoth("edit-existing-font-notice", settle = settle) { ViewerScreen(host(), vm, startMode = ViewerModeId.EDIT, startTool = EditTool.EXISTING) }
    }

    @Test fun imageSelected() {
        val vm = viewer(report())
        vm.edit.existing.select(ExImageRef(0, vm.edit.existing.objects.value[0]!!.images.single().key))
        compose.shootBoth("edit-existing-image-selected", settle = settle) { ViewerScreen(host(), vm, startMode = ViewerModeId.EDIT, startTool = EditTool.EXISTING) }
    }

    @Test fun lasso() {
        val vm = viewer(report())
        val c = vm.edit
        c.existing.setLasso(true)
        c.onStart(PageHit(0, 0.05f, 0.40f, 0.77f)); c.onMove(0.95f, 0.69f); c.onEnd()
        compose.shootBoth("edit-existing-lasso", settle = settle) { ViewerScreen(host(), vm, startMode = ViewerModeId.EDIT, startTool = EditTool.EXISTING) }
    }

    private fun record(name: String, file: File, scale: Float = 1.2f) {
        PDDocument.load(file).use { doc ->
            val bmp = PDFRenderer(doc).renderImage(0, scale)
            if (System.getProperty("roborazzi.test.record") == "true") File("../docs/screenshots/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }
    }

    /** A saved page before and after a text edit (new text re-wrapped in place, neighbours untouched), and after moving the picture. */
    @Test fun savedPages() {
        val before = report("before.pdf")
        record("edit-existing-text-before", before)
        val blocks = com.tom_roush.pdfbox.pdmodel.PDDocument.load(before).use { d ->
            val cb = d.getPage(0).cropBox
            com.namanbansal.forge.core.pdf.edit.content.TextBlocks.detect(com.namanbansal.forge.core.pdf.edit.content.ContentWalker.scan(d, d.getPage(0)), com.namanbansal.forge.core.pdf.edit.content.Box(cb.lowerLeftX, cb.lowerLeftY, cb.upperRightX, cb.upperRightY))
        }
        val b = blocks.first { it.text.startsWith("The committee") }
        val after = File(tmp.root, "after.pdf"); before.copyTo(after, true)
        ExistingApplier.applyInPlace(after, listOf(ExistingEdit("e", 0, ExistingKind.TEXT, b.key, NormRect(0f, 0f, 1f, 1f), text = "The committee approved the budget, but only after a short discussion. Spending on the north office will be reviewed again next spring.", origText = b.text, bold = 1)), fonts) { null }
        record("edit-existing-text-after", after)
        val moved = File(tmp.root, "moved.pdf"); before.copyTo(moved, true)
        ExistingApplier.applyInPlace(moved, listOf(ExistingEdit("i", 0, ExistingKind.IMAGE, 0, NormRect(0f, 0f, 1f, 1f), newRect = NormRect(0.45f, 0.62f, 0.82f, 0.82f), turns = 0)), fonts) { null }
        record("edit-existing-image-moved", moved)
    }
}
