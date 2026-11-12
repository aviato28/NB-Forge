package com.namanbansal.forge.tools.image

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.RunScope
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.ToolOutcome
import com.namanbansal.forge.core.image.AnnotationRenderer
import com.namanbansal.forge.core.image.FaceFinders
import com.namanbansal.forge.core.image.ImageInfo
import com.namanbansal.forge.core.image.ImgFormat
import com.namanbansal.forge.core.image.Mark
import com.namanbansal.forge.core.image.Marks
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.ForgeTextField
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.OptionChip
import com.namanbansal.forge.ui.components.TextAction
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.toolkit.PickSpec
import com.namanbansal.forge.ui.toolkit.ToolScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AnnoTool(val label: String) { ARROW("Arrow"), BOX("Box"), FREE("Draw"), TEXT("Text"), BLUR("Blur"), PIXELATE("Pixelate") }

/** I12 Annotate / blur: arrows, boxes, freehand, text and real pixel blur / pixelate (irreversible in the saved picture), plus optional face detection. */
class AnnotateViewModel(application: Application, saved: SavedStateHandle) : ImageToolViewModel(application, saved) {
    override val toolCode = "I12"
    override val previewDim: Int get() = 1400
    private val marksS = persistedString("an.marks", "[]")
    private val toolS = persistedString("an.tool", AnnoTool.ARROW.name)
    private val colourS = persistedInt("an.col", 0xE5484D)
    private val widthS = persistedInt("an.w", 2)
    val marksJson: StateFlow<String> = marksS.flow
    val tool: StateFlow<String> = toolS.flow
    val colour: StateFlow<Int> = colourS.flow
    val width: StateFlow<Int> = widthS.flow
    fun currentTool(): AnnoTool = AnnoTool.entries.firstOrNull { it.name == toolS.value } ?: AnnoTool.ARROW
    fun setTool(t: AnnoTool) { toolS.value = t.name }
    fun setColour(c: Int) { colourS.value = c }
    fun setWidth(w: Int) { widthS.value = w }
    fun strokeWidth(): Float = 0.003f + widthS.value * 0.002f

    private val redo = ArrayList<Mark>()
    fun marks(): List<Mark> = Marks.decode(marksS.value)
    private fun put(l: List<Mark>) { marksS.value = Marks.encode(l); renderPreview() }
    fun add(m: Mark) { redo.clear(); put(marks() + m) }
    fun undo() { val l = marks(); if (l.isNotEmpty()) { redo += l.last(); put(l.dropLast(1)) } }
    fun redoLast() { if (redo.isNotEmpty()) put(marks() + redo.removeAt(redo.lastIndex)) }
    fun clear() { redo.clear(); put(emptyList()) }
    val canRedo: Boolean get() = redo.isNotEmpty()

    private val _rendered = MutableStateFlow<Bitmap?>(null)
    /** The picture with all marks applied (preview resolution). */
    val rendered: StateFlow<Bitmap?> = _rendered.asStateFlow()
    private val _faceMsg = MutableStateFlow<String?>(null)
    val faceMessage: StateFlow<String?> = _faceMsg.asStateFlow()
    private var renderJob: Job? = null

    override suspend fun onImageReady(files: List<PickedFile>, info: ImageInfo, preview: Bitmap) {
        if (!stateRestored) { marksS.value = "[]" }
        renderPreview()
    }

    override fun onInputsReset() { super.onInputsReset(); _rendered.value = null; redo.clear(); _faceMsg.value = null }

    private fun renderPreview() {
        val base = preview.value ?: return
        val marks = marks()
        renderJob?.cancel()
        renderJob = viewModelScope.launch(Dispatchers.Default) {
            val copy = base.copy(Bitmap.Config.ARGB_8888, true)
            AnnotationRenderer.render(copy, marks)
            _rendered.value = copy
        }
    }

    /** Detects faces on the preview and adds a blur over each one (the user can undo or change them to pixelate). */
    fun findFaces() {
        val base = preview.value ?: return
        _faceMsg.value = "Looking for faces…"
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val faces = FaceFinders.factory().find(base)
                if (faces.isEmpty()) _faceMsg.value = "No faces found."
                else { redo.clear(); put(marks() + faces.map { Mark.Blur(it.l, it.t, it.r, it.b, pixelate = false) }); _faceMsg.value = "Blurred ${faces.size} face${if (faces.size == 1) "" else "s"}. Undo removes them." }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { _faceMsg.value = "Face detection isn't available right now." }
        }
    }

    override suspend fun execute(scope: RunScope): ToolOutcome {
        val file = inputs.value.first()
        val marks = marks()
        if (marks.isEmpty()) throw ToolException("Add at least one mark first.")
        val ctx = kotlinx.coroutines.currentCoroutineContext()
        val src = decodeFull(file, maxPixels = 24_000_000L)
        try {
            val bmp = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)
            AnnotationRenderer.render(bmp, marks) { ctx.ensureActive() }
            val fmt = if (info.value?.mime == "image/jpeg") ImgFormat.JPEG else ImgFormat.PNG
            val saved = scope.writeImage(file, "marked", bmp, fmt, 95)
            val blurs = marks.count { it is Mark.Blur }
            return ToolOutcome(saved, "Picture marked up", if (blurs > 0) "$blurs area${if (blurs == 1) "" else "s"} blurred for good" else "${marks.size} marks")
        } finally { src.recycle() }
    }
}

private val MARK_COLOURS = listOf(0xE5484D, 0xFF6A1A, 0xFFD60A, 0x22B573, 0x2E7DFF, 0xFFFFFF, 0x000000)

@Composable
fun AnnotateScreen(host: ToolHost, vm: AnnotateViewModel = viewModel()) {
    val info by vm.info.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val rendered by vm.rendered.collectAsStateWithLifecycle()
    val marksJson by vm.marksJson.collectAsStateWithLifecycle()
    val toolName by vm.tool.collectAsStateWithLifecycle()
    val colour by vm.colour.collectAsStateWithLifecycle()
    val width by vm.width.collectAsStateWithLifecycle()
    val faceMsg by vm.faceMessage.collectAsStateWithLifecycle()
    val tool = vm.currentTool()
    val c = ForgeTheme.colors
    val count = remember(marksJson) { vm.marks().size }
    var textAt by remember { mutableStateOf<Offset?>(null) }
    var textValue by remember { mutableStateOf("") }
    ToolScaffold(
        tool = host.tool, vm = vm, onBack = host.onBack, onOpenTool = host.onOpenTool,
        pick = PickSpec(listOf(FileKind.IMAGE), title = "Choose a picture", hint = "Mark it up, or blur what shouldn't be seen."),
        actionLabel = "Save", actionEnabled = count > 0, actionHint = if (count == 0) "Add a mark or blur to save." else null,
        inputSubtitle = { it.subtitle(info) }, optionsReady = preview != null,
        dialogs = {
            if (textAt != null) AlertDialog(
                onDismissRequest = { textAt = null }, title = { Text("Add text") },
                text = { ForgeTextField(textValue, { textValue = it }, placeholder = "Type here") },
                confirmButton = { TextButton({ val p = textAt; if (p != null && textValue.isNotBlank()) { vm.add(Mark.Text(p.x, p.y, textValue.trim(), colour, 0.05f)) }; textAt = null; textValue = "" }) { Text("Add") } },
                dismissButton = { TextButton({ textAt = null }) { Text("Cancel") } },
            )
        },
    ) {
        val base = preview
        val shown = rendered ?: base
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (shown != null && base != null) AnnotateCanvas(
                shown, tool, colour, vm.strokeWidth(), Modifier.fillMaxWidth().height(320.dp),
                onMark = { vm.add(it) }, onTextTap = { textAt = it },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextAction("Undo", { vm.undo() }, icon = ForgeIcons.Undo, enabled = count > 0)
                TextAction("Redo", { vm.redoLast() }, icon = ForgeIcons.Redo, enabled = vm.canRedo)
                TextAction("Clear", { vm.clear() }, icon = ForgeIcons.Trash, enabled = count > 0)
            }
            ChipRow { AnnoTool.entries.forEach { t -> OptionChip(t.label, tool == t, { vm.setTool(t) }) } }
            if (tool == AnnoTool.BLUR || tool == AnnoTool.PIXELATE) InfoNote("Drag over the area to hide. Blur and pixelate are baked into the saved picture and can't be undone from the file.")
            else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MARK_COLOURS.forEach { s -> Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF000000.toInt() or s)).border(if (colour == s) 3.dp else 1.dp, if (colour == s) c.accent else c.border, CircleShape).clickable { vm.setColour(s) }.semantics { contentDescription = "Mark colour" }) }
                }
                SliderRow("Thickness", width.toFloat(), 1f..8f, "$width", { vm.setWidth(it.toInt()) })
            }
            TextAction("Find faces and blur them", { vm.findFaces() }, icon = ForgeIcons.Eye)
            faceMsg?.let { Text(it, style = com.namanbansal.forge.ui.theme.ForgeType.Small, color = c.text2) }
        }
    }
}

/** Shows the marked picture and turns drags into new [Mark]s (fractions of the picture). */
@Composable
fun AnnotateCanvas(bmp: Bitmap, tool: AnnoTool, colour: Int, strokeWidth: Float, modifier: Modifier, onMark: (Mark) -> Unit, onTextTap: (Offset) -> Unit) {
    val img = remember(bmp) { bmp.asImageBitmap() }
    var a by remember { mutableStateOf<Offset?>(null) }
    var b by remember { mutableStateOf<Offset?>(null) }
    var pts by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val accent = ForgeTheme.colors.accent
    BoxWithConstraints(modifier.clip(RoundedCornerShape(14.dp)).semantics { contentDescription = "Picture to mark up. Drag to draw." }) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val mw = with(density) { maxWidth.toPx() }; val mh = with(density) { maxHeight.toPx() }
        val s = minOf(mw / bmp.width, mh / bmp.height)
        val ox = (mw - bmp.width * s) / 2; val oy = (mh - bmp.height * s) / 2
        fun fx(p: Offset) = ((p.x - ox) / (bmp.width * s)).coerceIn(0f, 1f)
        fun fy(p: Offset) = ((p.y - oy) / (bmp.height * s)).coerceIn(0f, 1f)
        Box(Modifier.fillMaxSize().background(Color.Black))
        Canvas(
            Modifier.fillMaxSize().pointerInput(bmp, tool, colour, strokeWidth) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    a = down.position; b = down.position; pts = listOf(down.position)
                    drag(down.id) { ch -> ch.consume(); b = ch.position; if (tool == AnnoTool.FREE) pts = pts + ch.position }
                    val p1 = a; val p2 = b
                    if (p1 != null && p2 != null) {
                        val moved = (p2 - p1).getDistance()
                        when (tool) {
                            AnnoTool.TEXT -> onTextTap(Offset(fx(p1), fy(p1)))
                            AnnoTool.ARROW -> if (moved > 12) onMark(Mark.Arrow(fx(p1), fy(p1), fx(p2), fy(p2), colour, strokeWidth))
                            AnnoTool.BOX -> if (moved > 12) onMark(Mark.Box(minOf(fx(p1), fx(p2)), minOf(fy(p1), fy(p2)), maxOf(fx(p1), fx(p2)), maxOf(fy(p1), fy(p2)), colour, strokeWidth))
                            AnnoTool.FREE -> if (pts.size > 1) onMark(Mark.Free(pts.flatMap { listOf(fx(it), fy(it)) }, colour, strokeWidth))
                            AnnoTool.BLUR, AnnoTool.PIXELATE -> if (moved > 12) onMark(Mark.Blur(minOf(fx(p1), fx(p2)), minOf(fy(p1), fy(p2)), maxOf(fx(p1), fx(p2)), maxOf(fy(p1), fy(p2)), tool == AnnoTool.PIXELATE))
                        }
                    }
                    a = null; b = null; pts = emptyList()
                }
            },
        ) {
            drawImage(img, dstOffset = IntOffset(ox.toInt(), oy.toInt()), dstSize = IntSize((bmp.width * s).toInt(), (bmp.height * s).toInt()))
            val p1 = a; val p2 = b
            if (p1 != null && p2 != null) {
                val col = Color(0xFF000000.toInt() or colour)
                when (tool) {
                    AnnoTool.ARROW -> drawLine(col, p1, p2, strokeWidth * minOf(bmp.width, bmp.height) * s)
                    AnnoTool.BOX -> drawRect(col, Offset(minOf(p1.x, p2.x), minOf(p1.y, p2.y)), androidx.compose.ui.geometry.Size(kotlin.math.abs(p2.x - p1.x), kotlin.math.abs(p2.y - p1.y)), style = Stroke(strokeWidth * minOf(bmp.width, bmp.height) * s))
                    AnnoTool.BLUR, AnnoTool.PIXELATE -> drawRect(accent, Offset(minOf(p1.x, p2.x), minOf(p1.y, p2.y)), androidx.compose.ui.geometry.Size(kotlin.math.abs(p2.x - p1.x), kotlin.math.abs(p2.y - p1.y)), style = Stroke(2.dp.toPx()))
                    AnnoTool.FREE -> pts.zipWithNext().forEach { (u, v) -> drawLine(col, u, v, strokeWidth * minOf(bmp.width, bmp.height) * s) }
                    AnnoTool.TEXT -> {}
                }
            }
        }
    }
}
