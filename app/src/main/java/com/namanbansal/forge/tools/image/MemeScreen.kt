package com.namanbansal.forge.tools.image

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.RunScope
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.ToolOutcome
import com.namanbansal.forge.core.image.ImageInfo
import com.namanbansal.forge.core.image.ImgFormat
import com.namanbansal.forge.core.image.TextAnchor
import com.namanbansal.forge.core.image.TextBox
import com.namanbansal.forge.core.image.TextBoxes
import com.namanbansal.forge.core.image.TextFonts
import com.namanbansal.forge.core.image.TextOverlayRenderer
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.ForgeTextField
import com.namanbansal.forge.ui.components.OptionChip
import com.namanbansal.forge.ui.components.TextAction
import com.namanbansal.forge.ui.components.ToggleRow
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.toolkit.PickSpec
import com.namanbansal.forge.ui.toolkit.ToolScaffold
import kotlinx.coroutines.flow.StateFlow

/** I10 Text overlay / meme: top and bottom meme captions (Anton, white with black outline) plus free text boxes you can move and resize. */
class MemeViewModel(application: Application, saved: SavedStateHandle) : ImageToolViewModel(application, saved) {
    override val toolCode = "I10"
    private val boxesS = persistedString("tx.boxes", "")
    private val selS = persistedInt("tx.sel", -1)
    val boxes: StateFlow<String> = boxesS.flow
    val selectedId: StateFlow<Int> = selS.flow

    fun list(): List<TextBox> = TextBoxes.decode(boxesS.value)
    private fun put(l: List<TextBox>) { boxesS.value = TextBoxes.encode(l) }
    fun topText(): String = list().firstOrNull { it.id == TOP }?.text.orEmpty()
    fun bottomText(): String = list().firstOrNull { it.id == BOTTOM }?.text.orEmpty()
    fun selected(): TextBox? = list().firstOrNull { it.id == selS.value }

    override suspend fun onImageReady(files: List<PickedFile>, info: ImageInfo, preview: Bitmap) {
        if (!stateRestored || list().isEmpty()) put(listOf(TextBoxes.meme(TOP, true), TextBoxes.meme(BOTTOM, false)))
    }

    fun setMeme(top: Boolean, text: String) {
        val id = if (top) TOP else BOTTOM
        put(list().map { if (it.id == id) it.copy(text = text) else it })
    }

    fun addFree() {
        val l = list()
        val id = (l.maxOfOrNull { it.id } ?: 2).coerceAtLeast(2) + 1
        put(l + TextBox(id, "Text", 0.5f, 0.5f, 0.09f, 0xFFFFFF, 0x000000, 0.08f, shadow = false, font = "Sans"))
        selS.value = id
    }

    fun select(id: Int) { selS.value = id }
    fun update(id: Int, f: (TextBox) -> TextBox) { put(list().map { if (it.id == id) f(it) else it }) }
    fun remove(id: Int) { put(list().filter { it.id != id }); if (selS.value == id) selS.value = -1 }

    fun draw(bmp: Bitmap): Bitmap {
        val out = bmp.copy(Bitmap.Config.ARGB_8888, true)
        TextOverlayRenderer.draw(Canvas(out), out.width, out.height, list()) { TextFonts.typeface(app, it) }
        return out
    }

    fun hit(x: Float, y: Float, w: Int, h: Int): Int? =
        list().reversed().firstOrNull { b -> TextOverlayRenderer.bounds(b, w, h, TextFonts.typeface(app, b.font))?.let { r -> x in r.l - 20..r.r + 20 && y in r.t - 20..r.b + 20 } == true }?.id

    override suspend fun execute(scope: RunScope): ToolOutcome {
        val file = inputs.value.first()
        if (list().all { it.text.isBlank() }) throw ToolException("Type some text first.")
        val src = decodeFull(file, maxPixels = 24_000_000L)
        try {
            val out = draw(src)
            val fmt = if (info.value?.mime == "image/jpeg") ImgFormat.JPEG else ImgFormat.PNG
            val saved = scope.writeImage(file, "caption", out, fmt, 95)
            return ToolOutcome(saved, "Text added", "${out.width} × ${out.height} px")
        } finally { src.recycle() }
    }

    companion object { const val TOP = 1; const val BOTTOM = 2 }
}

private val TEXT_COLOURS = listOf(0xFFFFFF, 0x000000, 0xFFD60A, 0xFF6A1A, 0xE5484D, 0x2E7DFF, 0x22B573)

@Composable
fun MemeScreen(host: ToolHost, vm: MemeViewModel = viewModel()) {
    val info by vm.info.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val boxesJson by vm.boxes.collectAsStateWithLifecycle()
    val selId by vm.selectedId.collectAsStateWithLifecycle()
    val c = ForgeTheme.colors
    val boxes = remember(boxesJson) { vm.list() }
    val sel = boxes.firstOrNull { it.id == selId }
    ToolScaffold(
        tool = host.tool, vm = vm, onBack = host.onBack, onOpenTool = host.onOpenTool,
        pick = PickSpec(listOf(FileKind.IMAGE), title = "Choose a picture", hint = "Add meme captions or text anywhere."),
        actionLabel = "Save", actionEnabled = boxes.any { it.text.isNotBlank() },
        inputSubtitle = { it.subtitle(info) }, optionsReady = preview != null && boxes.isNotEmpty(),
    ) {
        val base = preview
        val drawn = remember(base, boxesJson) { base?.let { vm.draw(it) } }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (drawn != null && base != null) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val boxW = with(density) { maxWidth.toPx() }
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(base.width.toFloat() / base.height).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .semantics { contentDescription = "Picture with text. Tap text to select, drag to move, pinch to resize." }
                            .pointerInput(boxesJson) { detectTapGestures { p -> val k = base.width / boxW; vm.select(vm.hit(p.x * k, p.y * k, base.width, base.height) ?: -1) } }
                            .pointerInput(selId) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val id = vm.selectedId.value
                                    val b = vm.selected() ?: return@detectTransformGestures
                                    if (b.anchor != TextAnchor.CENTER) { vm.update(id) { it.copy(size = (it.size * zoom).coerceIn(0.03f, 0.3f)) }; return@detectTransformGestures }
                                    val k = base.width / boxW
                                    vm.update(id) { it.copy(cx = (it.cx + pan.x * k / base.width).coerceIn(0f, 1f), cy = (it.cy + pan.y * k / base.height).coerceIn(0f, 1f), size = (it.size * zoom).coerceIn(0.02f, 0.4f)) }
                                }
                            }
                            .drawWithContent {
                                drawContent()
                                val r = sel?.let { TextOverlayRenderer.bounds(it, base.width, base.height, TextFonts.typeface(vm.getApplication(), it.font)) }
                                if (r != null) {
                                    val k = size.width / base.width
                                    drawRect(c.accent, Offset(r.l * k - 6, r.t * k - 6), Size((r.r - r.l) * k + 12, (r.b - r.t) * k + 12), style = Stroke(3f))
                                }
                            },
                    ) { Image(remember(drawn) { drawn.asImageBitmap() }, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth) }
                }
            }
            ForgeTextField(vm.topText(), { vm.setMeme(true, it) }, label = "Top text", placeholder = "WHEN THE BUILD PASSES")
            ForgeTextField(vm.bottomText(), { vm.setMeme(false, it) }, label = "Bottom text", placeholder = "FIRST TRY")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { TextAction("Add text box", { vm.addFree() }, icon = ForgeIcons.Plus) }
            if (sel != null) {
                val free = sel.id > MemeViewModel.BOTTOM
                if (free) ForgeTextField(sel.text, { t -> vm.update(sel.id) { it.copy(text = t) } }, label = "Text")
                SliderRow("Size", sel.size, 0.03f..0.3f, "${(sel.size * 100).toInt()}%", { v -> vm.update(sel.id) { it.copy(size = v) } })
                LabelText("Colour")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TEXT_COLOURS.forEach { s -> Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF000000.toInt() or s)).border(if (sel.colour == s) 3.dp else 1.dp, if (sel.colour == s) c.accent else c.border, CircleShape).clickable { vm.update(sel.id) { it.copy(colour = s) } }.semantics { contentDescription = "Text colour" }) }
                }
                SliderRow("Outline", sel.stroke, 0f..0.3f, "${(sel.stroke * 100).toInt()}%", { v -> vm.update(sel.id) { it.copy(stroke = v) } })
                LabelText("Outline colour")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TEXT_COLOURS.forEach { s -> Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF000000.toInt() or s)).border(if (sel.strokeColour == s) 3.dp else 1.dp, if (sel.strokeColour == s) c.accent else c.border, CircleShape).clickable { vm.update(sel.id) { it.copy(strokeColour = s) } }.semantics { contentDescription = "Outline colour" }) }
                }
                ToggleRow("Shadow", sel.shadow, { v -> vm.update(sel.id) { it.copy(shadow = v) } })
                LabelText("Font")
                ChipRow { TextBoxes.FONTS.forEach { f -> OptionChip(f, sel.font == f, { vm.update(sel.id) { it.copy(font = f) } }) } }
                if (free) TextAction("Delete this text box", { vm.remove(sel.id) }, icon = ForgeIcons.Trash, danger = true)
            }
        }
    }
}
