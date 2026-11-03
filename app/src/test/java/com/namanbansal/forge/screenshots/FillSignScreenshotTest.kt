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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.FormFixtures
import com.namanbansal.forge.core.PdfBoxRasterizer
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.annot.NormPt
import com.namanbansal.forge.core.pdf.fill.FillIo
import com.namanbansal.forge.core.pdf.fill.FillItem
import com.namanbansal.forge.core.pdf.fill.FillPalette
import com.namanbansal.forge.core.pdf.fill.FillSaveOptions
import com.namanbansal.forge.core.pdf.fill.FillState
import com.namanbansal.forge.core.pdf.fill.ItemKind
import com.namanbansal.forge.core.pdf.fill.Placement
import com.namanbansal.forge.core.pdf.sign.SigEntry
import com.namanbansal.forge.core.pdf.sign.SigKind
import com.namanbansal.forge.core.pdf.sign.SignatureImage
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.tools.ToolRegistry
import com.namanbansal.forge.tools.pdf.AddMethodContent
import com.namanbansal.forge.tools.pdf.ChoiceContent
import com.namanbansal.forge.tools.pdf.FieldTextContent
import com.namanbansal.forge.tools.pdf.FillTool
import com.namanbansal.forge.tools.pdf.ImportSignatureContent
import com.namanbansal.forge.tools.pdf.SaveOptionsContent
import com.namanbansal.forge.tools.pdf.SignatureLibraryContent
import com.namanbansal.forge.tools.pdf.SignaturePadContent
import com.namanbansal.forge.tools.pdf.TypeSignatureContent
import com.namanbansal.forge.tools.pdf.ViewerModeId
import com.namanbansal.forge.tools.pdf.ViewerScreen
import com.namanbansal.forge.tools.pdf.ViewerViewModel
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
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
class FillSignScreenshotTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun init() { PDFBoxResourceLoader.init(app); PdfBoxRasterizer.install() }
    @After fun done() { PdfBoxRasterizer.uninstall() }

    private fun host() = ToolHost(ToolRegistry.get("P25")!!, onBack = {})
    private val settle = { Thread.sleep(700); compose.waitForIdle() }

    private fun formViewer(): ViewerViewModel {
        val vm = ViewerViewModel(app, SavedStateHandle())
        val f = FormFixtures.acroForm(File(tmp.root, "registration.pdf"), labels = true)
        vm.onFilesPicked(listOf(PickedFile(Uri.fromFile(f), "registration.pdf", 41_000, "application/pdf")))
        compose.pumpUntil { vm.source.value != null && vm.thumbnails.value != null && vm.aspects.value != null && vm.restored.value && vm.fill.form.value.hasFields }
        return vm
    }

    private fun sigPng(text: String, color: Int): ByteArray {
        val bmp = SignatureImage.renderTyped(text, null, color, 120f)!!
        return SignatureImage.toPng(bmp)
    }

    private fun wave(): List<List<NormPt>> = listOf(
        (0..60).map { NormPt(120f + it * 9f, 300f + 70f * kotlin.math.sin(it / 5f) * (1f - it / 90f)) },
        listOf(NormPt(140f, 380f), NormPt(520f, 360f), NormPt(700f, 390f)),
    )

    @Composable
    private fun SheetFrame(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(ForgeTheme.colors.surface2), contentAlignment = Alignment.BottomCenter) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(ForgeTheme.colors.surface).padding(top = 16.dp)) { content() }
        }
    }

    private fun fieldOf(vm: ViewerViewModel, name: String) = vm.fill.form.value.fields.first { it.name == name }

    @Test fun formFields() {
        val vm = formViewer(); vm.setMode(ViewerModeId.FILL_SIGN)
        val c = vm.fill
        c.setValue(fieldOf(vm, "person.name"), "Alice Example")
        c.setValue(fieldOf(vm, "fruit"), "Banana")
        c.activate(fieldOf(vm, "agree")); c.closeFieldEdit()
        c.focus(fieldOf(vm, "notes"))
        compose.shootBoth("fill-form-fields", settle = settle) { ViewerScreen(host(), vm, startMode = ViewerModeId.FILL_SIGN) }
    }

    @Test fun textSheet() {
        val vm = formViewer()
        val f = fieldOf(vm, "person.name")
        compose.shootBoth("fill-text-sheet", settle = settle) { SheetFrame { FieldTextContent(f, "Alice", hasNext = true, onDone = {}, onNext = {}, onCancel = {}) } }
    }

    @Test fun dropdown() {
        val vm = formViewer()
        val f = fieldOf(vm, "fruit")
        compose.shootBoth("fill-dropdown", settle = settle) { SheetFrame { ChoiceContent(f, "Banana", {}, {}) } }
    }

    @Test fun addTextBox() {
        val vm = formViewer(); vm.setMode(ViewerModeId.FILL_SIGN)
        val c = vm.fill
        c.setTool(FillTool.TEXT)
        c.session.addItem(FillItem("t1", 0, ItemKind.TEXT, NormRect(0.1f, 0.58f, 0.6f, 0.62f), "Paid in full, 3 Nov 2026", 1, FillPalette.BLUE))
        c.session.addItem(FillItem("t2", 0, ItemKind.CHECK, NormRect(0.7f, 0.58f, 0.76f, 0.6f)))
        c.select("t1")
        compose.shootBoth("add-text-box", settle = settle) { ViewerScreen(host(), vm, startMode = ViewerModeId.FILL_SIGN) }
    }

    private fun entries(): Pair<List<SigEntry>, Map<String, androidx.compose.ui.graphics.ImageBitmap>> {
        val a = SigEntry("a", SigKind.SIGNATURE, "Alice Example", 3.4f, 1)
        val b = SigEntry("b", SigKind.SIGNATURE, "Work signature", 3.0f, 2)
        val i = SigEntry("i", SigKind.INITIALS, "Initials", 1.6f, 3)
        fun bm(t: String, col: Int) = SignatureImage.renderTyped(t, null, col, 120f)!!.asImageBitmap()
        return listOf(a, b, i) to mapOf("a" to bm("Alice Example", 0xFF0B2A6F.toInt()), "b" to bm("A. Example", 0xFF111111.toInt()), "i" to bm("AE", 0xFF1F4FD8.toInt()))
    }

    @Test fun library() {
        val (list, bmps) = entries()
        compose.shootBoth("sign-library", settle = settle) { SheetFrame { SignatureLibraryContent(list, { bmps[it] }, true, {}, {}, {}, {}) } }
    }

    @Test fun addMethod() {
        compose.shootBoth("sign-add-method", settle = settle) { SheetFrame { AddMethodContent(SigKind.SIGNATURE, {}, {}, {}, {}) } }
    }

    @Test fun drawPad() {
        compose.shootBoth("sign-draw-pad", settle = settle) { SignaturePadContent(SigKind.SIGNATURE, {}, {}, initialStrokes = wave()) }
    }

    @Test fun typeSignature() {
        compose.shootBoth("sign-type", settle = settle) { SheetFrame { TypeSignatureContent(SigKind.SIGNATURE, {}, {}) } }
    }

    @Test fun importPreview() {
        val photo = Bitmap.createBitmap(900, 420, Bitmap.Config.ARGB_8888)
        val cv = Canvas(photo); cv.drawColor(AColor.rgb(226, 224, 218))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.rgb(24, 32, 90); style = Paint.Style.STROKE; strokeWidth = 7f; strokeCap = Paint.Cap.ROUND }
        val path = android.graphics.Path().apply { moveTo(160f, 250f); cubicTo(220f, 90f, 300f, 330f, 380f, 170f); cubicTo(440f, 60f, 520f, 300f, 640f, 190f); lineTo(760f, 215f) }
        cv.drawPath(path, p)
        compose.shootBoth("sign-import-preview", settle = { repeat(15) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); compose.mainClock.advanceTimeBy(200); compose.waitForIdle(); Thread.sleep(100) } }) { ImportSignatureContent(photo, {}, {}, {}) }
    }

    @Test fun signPlaced() {
        val vm = formViewer(); vm.setMode(ViewerModeId.FILL_SIGN)
        val e = vm.signatures.add(SigKind.SIGNATURE, sigPng("Alice Example", 0xFF0B2A6F.toInt()), "Alice Example")
        vm.refreshSignatures(); compose.pumpUntil { vm.sigEntries.value.isNotEmpty() }
        val sf = fieldOf(vm, "signature")
        vm.fill.session.addItem(FillItem("sg", sf.page, ItemKind.SIGNATURE, Placement.fitInto(sf.rect, e.aspect, 0.707f), sigId = e.id))
        vm.fill.select("sg")
        compose.shootBoth("sign-placed", settle = settle) { ViewerScreen(host(), vm, startMode = ViewerModeId.FILL_SIGN) }
    }

    @Test fun saveOptions() {
        compose.shootBoth("save-options", settle = settle) { SheetFrame { SaveOptionsContent(true, true, null, {}, {}) } }
    }

    /** The proof that a saved page carries the values and the signature: written by [FillIo], drawn by PDFBox's own rasterizer. */
    @Test fun savedPageRendered() {
        val src = FormFixtures.acroForm(File(tmp.root, "registration.pdf"), labels = true)
        val out = File(tmp.root, "saved.pdf")
        val sigAspect = SignatureImage.renderTyped("Alice Example", null, 0, 120f)!!.let { it.width.toFloat() / it.height }
        val sig = sigPng("Alice Example", 0xFF111111.toInt()) // black: the test rasterizer swaps red and blue of images
        val sf = FormFixtures.SIGN
        val rect = Placement.fitInto(NormRect(sf.lowerLeftX / 595.28f, 1f - sf.upperRightY / 841.89f, sf.upperRightX / 595.28f, 1f - sf.lowerLeftY / 841.89f), sigAspect, 0.707f)
        val state = FillState(
            mapOf("person.name" to "Alice Example", "notes" to "Please call after 5pm.\nThank you!", "agree" to "Yes", "colour" to "Blue", "fruit" to "Banana", "pets" to "Dog", "code" to "A1B2"),
            listOf(
                FillItem("sg", 0, ItemKind.SIGNATURE, rect, sigId = "sig"),
                FillItem("t", 0, ItemKind.TEXT, NormRect(0.5f, 0.62f, 0.9f, 0.66f), "Received 3 Nov 2026", 1, FillPalette.BLUE),
                FillItem("c", 0, ItemKind.CHECK, NormRect(0.8f, 0.7f, 0.86f, 0.72f)),
            ),
        )
        FillIo.apply(src, out, state, FillSaveOptions()) { sig }
        PDDocument.load(out).use { doc ->
            check(doc.getPage(0).annotations.any { it.subtype == "Stamp" }) { "signature stamp missing" }
            val bmp = PDFRenderer(doc).renderImage(0, 1.2f)
            if (System.getProperty("roborazzi.test.record") == "true")
                File("../docs/screenshots/fill-saved-render.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }
    }
}
