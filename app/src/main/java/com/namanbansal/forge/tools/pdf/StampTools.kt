package com.namanbansal.forge.tools.pdf

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.pdf.NumberFormat
import com.namanbansal.forge.core.pdf.PdfSource
import com.namanbansal.forge.core.pdf.PdfStamper
import com.namanbansal.forge.core.pdf.PdfTransformViewModel
import com.namanbansal.forge.core.pdf.PdfWatermarker
import com.namanbansal.forge.core.pdf.StampItem
import com.namanbansal.forge.core.pdf.StampSpec
import com.namanbansal.forge.core.pdf.TransformCtx
import com.namanbansal.forge.core.pdf.WatermarkFont
import com.namanbansal.forge.core.pdf.WatermarkPosition
import com.namanbansal.forge.core.pdf.WatermarkSpec
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.tools.image.ChipRow
import com.namanbansal.forge.tools.image.SliderRow
import com.namanbansal.forge.ui.components.ForgeTextField
import com.namanbansal.forge.ui.components.GroupLabel
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.OptionChip
import com.namanbansal.forge.ui.components.Segmented
import com.namanbansal.forge.ui.components.Stepper
import com.namanbansal.forge.ui.components.TextAction
import com.namanbansal.forge.ui.components.ToggleRow
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.ColorDots
import com.namanbansal.forge.ui.toolkit.LivePreview
import com.namanbansal.forge.ui.toolkit.PagesChooser
import com.namanbansal.forge.ui.toolkit.PdfToolScreen
import com.namanbansal.forge.ui.toolkit.PositionGrid
import com.namanbansal.forge.ui.toolkit.PreviewPageRow
import com.namanbansal.forge.ui.toolkit.rememberFilePicker
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

private val WM_COLORS = listOf(0xFF9A9AA2.toInt(), 0xFF000000.toInt(), 0xFFD92D20.toInt(), 0xFF1B64F2.toInt(), 0xFF1F9D55.toInt(), 0xFFF76B1C.toInt())

/** P20 Watermark. */
class WatermarkViewModel(application: Application, saved: SavedStateHandle) : PdfTransformViewModel(application, saved) {
    override val toolCode = "P20"
    override val action = "watermarked"
    override val headline get() = "Watermark added"

    private val modeS = persistedInt("wm.mode", 0)
    private val textS = persistedString("wm.text", "CONFIDENTIAL")
    private val fontS = persistedInt("wm.font", 1)
    private val sizeS = persistedInt("wm.size", 64)
    private val colorS = persistedInt("wm.color", 0)
    private val opacityS = persistedInt("wm.opacity", 30)
    private val rotationS = persistedInt("wm.rotation", 45)
    private val positionS = persistedInt("wm.position", WatermarkPosition.CENTER)
    private val scaleS = persistedInt("wm.scale", 40)
    private val behindS = persistedBool("wm.behind", false)
    private val previewS = persistedInt("wm.page", 0)
    private val imagePathS = persistedString("wm.imagePath", "")
    val mode: StateFlow<Int> = modeS.flow; val text: StateFlow<String> = textS.flow; val font: StateFlow<Int> = fontS.flow
    val size: StateFlow<Int> = sizeS.flow; val color: StateFlow<Int> = colorS.flow; val opacity: StateFlow<Int> = opacityS.flow
    val rotation: StateFlow<Int> = rotationS.flow; val position: StateFlow<Int> = positionS.flow; val scale: StateFlow<Int> = scaleS.flow
    val behind: StateFlow<Boolean> = behindS.flow; val previewPage: StateFlow<Int> = previewS.flow
    private val _image = MutableStateFlow<ByteArray?>(null)
    val image: StateFlow<ByteArray?> = _image.asStateFlow()

    fun setMode(v: Int) { modeS.value = v }; fun setText(v: String) { textS.value = v }; fun setFont(v: Int) { fontS.value = v }
    fun setSize(v: Int) { sizeS.value = v }; fun setColor(v: Int) { colorS.value = v }; fun setOpacity(v: Int) { opacityS.value = v }
    fun setRotation(v: Int) { rotationS.value = v }; fun setPosition(v: Int) { positionS.value = v }; fun setScale(v: Int) { scaleS.value = v }
    fun setBehind(v: Boolean) { behindS.value = v }; fun setPreviewPage(v: Int) { previewS.value = v }

    fun spec(): WatermarkSpec {
        val textMode = modeS.value == 0
        return WatermarkSpec(
            text = if (textMode) textS.value else null, image = if (textMode) null else _image.value,
            font = WatermarkFont.entries[fontS.value.coerceIn(0, WatermarkFont.entries.size - 1)], size = sizeS.value.toFloat(),
            color = WM_COLORS[colorS.value.coerceIn(0, WM_COLORS.size - 1)], opacity = opacityS.value / 100f,
            rotation = rotationS.value.toFloat(), position = positionS.value, imageScale = scaleS.value / 100f, behind = behindS.value,
        )
    }

    fun ready(): Boolean = if (modeS.value == 0) textS.value.isNotBlank() else _image.value != null

    override fun onPdfReady(source: PdfSource) {
        super.onPdfReady(source)
        previewS.value = previewS.value.coerceIn(0, source.pageCount - 1)
        val path = imagePathS.value
        if (path.isNotEmpty() && _image.value == null) viewModelScope.launch(Dispatchers.IO) { runCatching { File(path).readBytes() }.getOrNull()?.let { _image.value = it } }
    }

    /** Reads the picked picture (downsampled to at most 1600 px, alpha kept) and remembers it across process death. */
    fun pickImage(uri: Uri) {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                    var sample = 1
                    while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= 1600) sample *= 2
                    val bmp = app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                    bmp?.let { b -> ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
                }.getOrNull()
            }
            if (bytes == null) { notify("That picture couldn't be read."); return@launch }
            _image.value = bytes
            val f = File(app.cacheDir, "wm-image.png")
            withContext(Dispatchers.IO) { runCatching { f.writeBytes(bytes) } }
            imagePathS.value = f.absolutePath
        }
    }

    suspend fun preview(page: Int, widthPx: Int): Bitmap? { val sp = spec(); return previewPage(page, widthPx) { PdfWatermarker.apply(it, sp, setOf(page)) } }

    override fun validate(source: PdfSource) {
        if (!ready()) throw ToolException(if (modeS.value == 0) "Type the watermark text first." else "Choose a picture first.")
        if (selection.value.isEmpty()) throw ToolException("Choose at least one page.")
    }

    override fun transform(doc: PDDocument, source: PdfSource, ctx: TransformCtx): String {
        val n = PdfWatermarker.apply(doc, spec(), selectedOrNull(), ctx)
        return "Added to $n ${if (n == 1) "page" else "pages"}"
    }
}

@Composable
fun WatermarkScreen(host: ToolHost, vm: WatermarkViewModel = viewModel()) {
    val c = ForgeTheme.colors
    val mode by vm.mode.collectAsStateWithLifecycle(); val text by vm.text.collectAsStateWithLifecycle()
    val font by vm.font.collectAsStateWithLifecycle(); val size by vm.size.collectAsStateWithLifecycle()
    val color by vm.color.collectAsStateWithLifecycle(); val opacity by vm.opacity.collectAsStateWithLifecycle()
    val rotation by vm.rotation.collectAsStateWithLifecycle(); val position by vm.position.collectAsStateWithLifecycle()
    val scale by vm.scale.collectAsStateWithLifecycle(); val behind by vm.behind.collectAsStateWithLifecycle()
    val page by vm.previewPage.collectAsStateWithLifecycle(); val image by vm.image.collectAsStateWithLifecycle()
    val selected by vm.selection.collectAsStateWithLifecycle(); val rangeText by vm.selectionText.collectAsStateWithLifecycle()
    val pickPicture = rememberFilePicker(FileKind.pickerMimes(FileKind.IMAGE), false) { it.firstOrNull()?.let(vm::pickImage) }
    val ready = vm.ready() && selected.isNotEmpty()
    PdfToolScreen(host, vm, "Choose a PDF to watermark", "Add watermark", actionEnabled = ready, actionHint = if (!ready) "Add text or a picture, and pick pages" else null) { s, thumbs ->
        val pg = page.coerceIn(0, s.pageCount - 1)
        LivePreview(key = listOf(mode, text, font, size, color, opacity, rotation, position, scale, behind, pg, image?.size), description = "Watermark preview") { vm.preview(pg, 700) }
        PreviewPageRow(pg, s.pageCount, vm::setPreviewPage, Modifier.padding(top = 8.dp))
        Segmented(listOf("Text", "Picture"), mode, vm::setMode, Modifier.padding(vertical = 8.dp))
        if (mode == 0) {
            ForgeTextField(text, vm::setText, label = "Watermark text")
            Text("Font", style = ForgeType.Label, color = c.text2, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
            ChipRow { WatermarkFont.entries.forEachIndexed { i, f -> OptionChip(f.label, font == i, { vm.setFont(i) }) } }
            SliderRow("Size", size.toFloat(), 12f..200f, "$size pt", { vm.setSize(it.toInt()) }, Modifier.padding(top = 8.dp))
            Text("Colour", style = ForgeType.Label, color = c.text2, modifier = Modifier.padding(bottom = 6.dp))
            ColorDots(WM_COLORS, color, vm::setColor)
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(if (image == null) "No picture chosen" else "Picture ready", style = ForgeType.Body, color = c.text, modifier = Modifier.weight(1f))
                TextAction(if (image == null) "Choose picture" else "Change", { pickPicture() })
            }
            SliderRow("Size (of page width)", scale.toFloat(), 5f..100f, "$scale%", { vm.setScale(it.toInt()) }, Modifier.padding(top = 8.dp))
        }
        SliderRow("Opacity", opacity.toFloat(), 5f..100f, "$opacity%", { vm.setOpacity(it.toInt()) }, Modifier.padding(top = 4.dp))
        SliderRow("Rotation", rotation.toFloat(), -90f..90f, "$rotation°", { vm.setRotation(it.toInt()) })
        GroupLabel("Position")
        PositionGrid(position, vm::setPosition, tiled = WatermarkPosition.TILED)
        ToggleRow("Behind the content", behind, vm::setBehind, Modifier.padding(top = 12.dp), subtitle = "Over the page by default; behind can be hidden by full-page images.")
        PagesChooser(s.pageCount, thumbs, selected, rangeText, vm::setSelection, vm::setSelectionText, Modifier.padding(top = 12.dp))
    }
}

/** P21 Page numbers, headers, footers, Bates. */
class StampViewModel(application: Application, saved: SavedStateHandle) : PdfTransformViewModel(application, saved) {
    override val toolCode = "P21"
    override val action = "numbered"
    override val headline get() = "Numbers added"

    private val numOn = persistedBool("st.numOn", true); private val numPos = persistedInt("st.numPos", 4); private val fmt = persistedInt("st.fmt", 1)
    private val startS = persistedInt("st.start", 1); private val skipS = persistedBool("st.skip", false)
    private val hdrOn = persistedBool("st.hdrOn", false); private val hdrText = persistedString("st.hdr", "{filename}"); private val hdrPos = persistedInt("st.hdrPos", 1)
    private val ftrOn = persistedBool("st.ftrOn", false); private val ftrText = persistedString("st.ftr", "{date}"); private val ftrPos = persistedInt("st.ftrPos", 3)
    private val batesOn = persistedBool("st.batesOn", false); private val batesPre = persistedString("st.batesPre", "DOC-"); private val batesDig = persistedInt("st.batesDig", 6)
    private val batesStartS = persistedInt("st.batesStart", 1); private val batesPos = persistedInt("st.batesPos", 5)
    private val sizeS = persistedInt("st.size", 10); private val marginS = persistedInt("st.margin", 28); private val pageS = persistedInt("st.page", 0)
    val numberOn: StateFlow<Boolean> = numOn.flow; val numberPos: StateFlow<Int> = numPos.flow; val format: StateFlow<Int> = fmt.flow
    val start: StateFlow<Int> = startS.flow; val skipFirst: StateFlow<Boolean> = skipS.flow
    val headerOn: StateFlow<Boolean> = hdrOn.flow; val headerText: StateFlow<String> = hdrText.flow; val headerPos: StateFlow<Int> = hdrPos.flow
    val footerOn: StateFlow<Boolean> = ftrOn.flow; val footerText: StateFlow<String> = ftrText.flow; val footerPos: StateFlow<Int> = ftrPos.flow
    val bates: StateFlow<Boolean> = batesOn.flow; val batesPrefix: StateFlow<String> = batesPre.flow; val batesDigits: StateFlow<Int> = batesDig.flow
    val batesFirst: StateFlow<Int> = batesStartS.flow; val batesPosition: StateFlow<Int> = batesPos.flow
    val fontSize: StateFlow<Int> = sizeS.flow; val margin: StateFlow<Int> = marginS.flow; val previewPage: StateFlow<Int> = pageS.flow

    fun set(key: String, v: Any) {
        when (key) {
            "numOn" -> numOn.value = v as Boolean; "numPos" -> numPos.value = v as Int; "fmt" -> fmt.value = v as Int
            "start" -> startS.value = v as Int; "skip" -> skipS.value = v as Boolean
            "hdrOn" -> hdrOn.value = v as Boolean; "hdr" -> hdrText.value = v as String; "hdrPos" -> hdrPos.value = v as Int
            "ftrOn" -> ftrOn.value = v as Boolean; "ftr" -> ftrText.value = v as String; "ftrPos" -> ftrPos.value = v as Int
            "batesOn" -> batesOn.value = v as Boolean; "batesPre" -> batesPre.value = v as String; "batesDig" -> batesDig.value = v as Int
            "batesStart" -> batesStartS.value = v as Int; "batesPos" -> batesPos.value = v as Int
            "size" -> sizeS.value = v as Int; "margin" -> marginS.value = v as Int; "page" -> pageS.value = v as Int
        }
    }

    private fun fileName() = inputs.value.firstOrNull()?.name ?: "document.pdf"

    fun spec() = StampSpec(
        pageNumber = StampItem(numOn.value, "", numPos.value), numberFormat = NumberFormat.entries[fmt.value.coerceIn(0, NumberFormat.entries.size - 1)],
        start = startS.value, skipFirst = skipS.value,
        header = StampItem(hdrOn.value, hdrText.value, hdrPos.value), footer = StampItem(ftrOn.value, ftrText.value, ftrPos.value),
        batesEnabled = batesOn.value, batesPrefix = batesPre.value, batesDigits = batesDig.value, batesStart = batesStartS.value, batesPosition = batesPos.value,
        fontSize = sizeS.value.toFloat(), margin = marginS.value.toFloat(), fileName = fileName(),
    )

    fun anythingOn() = numOn.value || (hdrOn.value && hdrText.value.isNotBlank()) || (ftrOn.value && ftrText.value.isNotBlank()) || batesOn.value

    override fun onPdfReady(source: PdfSource) { super.onPdfReady(source); pageS.value = pageS.value.coerceIn(0, source.pageCount - 1) }

    suspend fun preview(page: Int, widthPx: Int): Bitmap? { val sp = spec(); return previewPage(page, widthPx) { PdfStamper.apply(it, sp, setOf(page)) } }

    override fun validate(source: PdfSource) {
        if (!anythingOn()) throw ToolException("Turn on page numbers, a header, a footer or Bates numbers.")
        if (selection.value.isEmpty()) throw ToolException("Choose at least one page.")
    }

    override fun transform(doc: PDDocument, source: PdfSource, ctx: TransformCtx): String {
        val n = PdfStamper.apply(doc, spec(), selectedOrNull(), ctx)
        return "Added to $n ${if (n == 1) "page" else "pages"}"
    }
}

private val STAMP_POSITIONS = listOf("Top left", "Top centre", "Top right", "Bottom left", "Bottom centre", "Bottom right")

@Composable
private fun PositionChips(selected: Int, onSelect: (Int) -> Unit) {
    ChipRow(Modifier.padding(top = 6.dp)) { STAMP_POSITIONS.forEachIndexed { i, t -> OptionChip(t, selected == i, { onSelect(i) }) } }
}

@Composable
fun StampScreen(host: ToolHost, vm: StampViewModel = viewModel()) {
    val c = ForgeTheme.colors
    val numberOn by vm.numberOn.collectAsStateWithLifecycle(); val numberPos by vm.numberPos.collectAsStateWithLifecycle()
    val format by vm.format.collectAsStateWithLifecycle(); val start by vm.start.collectAsStateWithLifecycle()
    val skip by vm.skipFirst.collectAsStateWithLifecycle()
    val hOn by vm.headerOn.collectAsStateWithLifecycle(); val hText by vm.headerText.collectAsStateWithLifecycle(); val hPos by vm.headerPos.collectAsStateWithLifecycle()
    val fOn by vm.footerOn.collectAsStateWithLifecycle(); val fText by vm.footerText.collectAsStateWithLifecycle(); val fPos by vm.footerPos.collectAsStateWithLifecycle()
    val bOn by vm.bates.collectAsStateWithLifecycle(); val bPre by vm.batesPrefix.collectAsStateWithLifecycle(); val bDig by vm.batesDigits.collectAsStateWithLifecycle()
    val bStart by vm.batesFirst.collectAsStateWithLifecycle(); val bPos by vm.batesPosition.collectAsStateWithLifecycle()
    val size by vm.fontSize.collectAsStateWithLifecycle(); val margin by vm.margin.collectAsStateWithLifecycle(); val page by vm.previewPage.collectAsStateWithLifecycle()
    val selected by vm.selection.collectAsStateWithLifecycle(); val rangeText by vm.selectionText.collectAsStateWithLifecycle()
    val any = numberOn || (hOn && hText.isNotBlank()) || (fOn && fText.isNotBlank()) || bOn
    PdfToolScreen(host, vm, "Choose a PDF to number", "Add to PDF", actionEnabled = any && selected.isNotEmpty(), actionHint = if (!any) "Turn on at least one item" else null) { s, thumbs ->
        val pg = page.coerceIn(0, s.pageCount - 1)
        LivePreview(key = listOf(numberOn, numberPos, format, start, skip, hOn, hText, hPos, fOn, fText, fPos, bOn, bPre, bDig, bStart, bPos, size, margin, pg), description = "Page numbers preview") { vm.preview(pg, 700) }
        PreviewPageRow(pg, s.pageCount, { vm.set("page", it) }, Modifier.padding(top = 8.dp))
        ToggleRow("Page numbers", numberOn, { vm.set("numOn", it) }, Modifier.padding(top = 4.dp))
        if (numberOn) {
            ChipRow(Modifier.padding(top = 8.dp)) { NumberFormat.entries.forEachIndexed { i, f -> OptionChip(f.label, format == i, { vm.set("fmt", i) }) } }
            PositionChips(numberPos) { vm.set("numPos", it) }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Start at", style = ForgeType.Body, color = c.text, modifier = Modifier.weight(1f))
                Stepper(start, 0..9999, { vm.set("start", it) }, "Start number")
            }
            ToggleRow("Skip the first page", skip, { vm.set("skip", it) }, Modifier.padding(top = 8.dp), subtitle = "Cover pages stay clean; numbering starts on page 2.")
        }
        ToggleRow("Header", hOn, { vm.set("hdrOn", it) }, Modifier.padding(top = 8.dp))
        if (hOn) { ForgeTextField(hText, { vm.set("hdr", it) }, label = "Header text", helper = "Use {date}, {filename}, {n}, {N}"); PositionChips(hPos) { vm.set("hdrPos", it) } }
        ToggleRow("Footer", fOn, { vm.set("ftrOn", it) }, Modifier.padding(top = 8.dp))
        if (fOn) { ForgeTextField(fText, { vm.set("ftr", it) }, label = "Footer text", helper = "Use {date}, {filename}, {n}, {N}"); PositionChips(fPos) { vm.set("ftrPos", it) } }
        ToggleRow("Bates numbers", bOn, { vm.set("batesOn", it) }, Modifier.padding(top = 8.dp), subtitle = "Legal-style running numbers, like DOC-000001.")
        if (bOn) {
            ForgeTextField(bPre, { vm.set("batesPre", it) }, label = "Prefix")
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Digits", style = ForgeType.Body, color = c.text, modifier = Modifier.weight(1f)); Stepper(bDig, 1..10, { vm.set("batesDig", it) }, "Digits")
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("First number", style = ForgeType.Body, color = c.text, modifier = Modifier.weight(1f)); Stepper(bStart, 0..9999, { vm.set("batesStart", it) }, "First Bates number")
            }
            PositionChips(bPos) { vm.set("batesPos", it) }
        }
        SliderRow("Font size", size.toFloat(), 6f..24f, "$size pt", { vm.set("size", it.toInt()) }, Modifier.padding(top = 12.dp))
        SliderRow("Margin", margin.toFloat(), 10f..90f, "$margin pt", { vm.set("margin", it.toInt()) })
        PagesChooser(s.pageCount, thumbs, selected, rangeText, vm::setSelection, vm::setSelectionText, Modifier.padding(top = 8.dp))
    }
}
