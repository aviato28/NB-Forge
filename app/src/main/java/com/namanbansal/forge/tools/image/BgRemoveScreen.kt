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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.namanbansal.forge.core.image.BgKind
import com.namanbansal.forge.core.image.Blur
import com.namanbansal.forge.core.image.ImageInfo
import com.namanbansal.forge.core.image.ImgFormat
import com.namanbansal.forge.core.image.MaskOps
import com.namanbansal.forge.core.image.SegmenterProvider
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.OptionChip
import com.namanbansal.forge.ui.components.TextAction
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.PickSpec
import com.namanbansal.forge.ui.toolkit.ToolScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface BgStatus { data object Working : BgStatus; data class Ready(val engine: String) : BgStatus; data class Failed(val message: String) : BgStatus }

/** I4 Background remover: ML Kit finds the subject, a brush refines the cut-out, the background can be transparent, a colour or the blurred original. */
class BgRemoveViewModel(application: Application, saved: SavedStateHandle) : ImageToolViewModel(application, saved) {
    override val toolCode = "I4"
    override val previewDim: Int get() = 1024
    private val kindS = persistedString("bg.kind", BgKind.TRANSPARENT.name)
    private val colourS = persistedInt("bg.col", 0xFFFFFF)
    private val restoreS = persistedBool("bg.restore", false)
    private val sizeS = persistedInt("bg.size", 40)
    val kind: StateFlow<String> = kindS.flow
    val colour: StateFlow<Int> = colourS.flow
    val restoring: StateFlow<Boolean> = restoreS.flow
    val brushSize: StateFlow<Int> = sizeS.flow

    private val _status = MutableStateFlow<BgStatus>(BgStatus.Working)
    val status: StateFlow<BgStatus> = _status.asStateFlow()
    private val _composite = MutableStateFlow<Bitmap?>(null)
    /** The cut-out shown on screen (preview resolution). */
    val composite: StateFlow<Bitmap?> = _composite.asStateFlow()

    private var mask: ByteArray? = null
    private var original: Bitmap? = null
    private var blurred: Bitmap? = null
    private var segmentedMask: ByteArray? = null
    private var composeJob: Job? = null
    private var engine = ""

    fun bgKind(): BgKind = BgKind.entries.firstOrNull { it.name == kindS.value } ?: BgKind.TRANSPARENT
    fun setKind(k: BgKind) { kindS.value = k.name; recompose() }
    fun setColour(c: Int) { colourS.value = c; kindS.value = BgKind.COLOUR.name; recompose() }
    fun setRestoring(v: Boolean) { restoreS.value = v }
    fun setBrushSize(s: Int) { sizeS.value = s }

    override suspend fun onImageReady(files: List<PickedFile>, info: ImageInfo, preview: Bitmap) {
        _status.value = BgStatus.Working
        original = preview
        blurred = null
        segJob?.cancel()
        // Segmenting takes a moment: let the screen show the picture with a spinner meanwhile instead of blocking "Opening…".
        segJob = viewModelScope.launch(Dispatchers.Default) { segment(preview) }
    }

    private var segJob: Job? = null

    private suspend fun segment(preview: Bitmap) {
        try {
            val seg = SegmenterProvider.factory()
            val m = seg.segment(preview)
            mask = m; segmentedMask = m.copyOf(); engine = seg.engine
            _status.value = BgStatus.Ready(seg.engine)
            recompose()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
            _status.value = BgStatus.Failed((e as? ToolException)?.message ?: "Couldn't find a subject in this picture.")
        }
    }

    override fun onInputsReset() { segJob?.cancel(); super.onInputsReset(); mask = null; original = null; blurred = null; segmentedMask = null; _composite.value = null; _status.value = BgStatus.Working }

    /** Brush at a point given as a fraction of the picture (0..1). */
    fun stroke(fx: Float, fy: Float) {
        val m = mask ?: return
        val o = original ?: return
        val r = sizeS.value / 100f * minOf(o.width, o.height) * 0.25f
        MaskOps.brush(m, o.width, o.height, fx * o.width, fy * o.height, r.coerceAtLeast(2f), restoreS.value)
        recompose()
    }

    fun resetMask() { segmentedMask?.let { mask = it.copyOf(); recompose() } }

    private fun recompose() {
        val o = original ?: return
        val m = mask ?: return
        composeJob?.cancel()
        composeJob = viewModelScope.launch(Dispatchers.Default) {
            val k = bgKind()
            if (k == BgKind.BLUR && blurred == null) blurred = Blur.box(o, (o.width / 40).coerceAtLeast(4), 3)
            _composite.value = MaskOps.compose(o, m.copyOf(), k, 0xFF000000.toInt() or colourS.value, blurred)
        }
    }

    override suspend fun execute(scope: RunScope): ToolOutcome {
        val file = inputs.value.first()
        val m = mask ?: throw ToolException("The cut-out isn't ready yet.")
        val o = original ?: throw ToolException("The cut-out isn't ready yet.")
        val full = decodeFull(file, maxPixels = 16_000_000L)
        try {
            scope.progress(0.2f)
            val fm = if (full.width == o.width && full.height == o.height) m else MaskOps.resize(m, o.width, o.height, full.width, full.height)
            val k = bgKind()
            val blur = if (k == BgKind.BLUR) Blur.box(full, (full.width / 40).coerceAtLeast(4), 3) else null
            scope.progress(0.6f)
            val out = MaskOps.compose(full, fm, k, 0xFF000000.toInt() or colourS.value, blur)
            val saved = scope.writeImage(file, "nobg", out, ImgFormat.PNG)
            return ToolOutcome(saved, if (k == BgKind.TRANSPARENT) "Background removed" else "Background replaced", "${full.width} × ${full.height} px")
        } finally { full.recycle() }
    }
}

private val SWATCHES = listOf(0xFFFFFF, 0x000000, 0xFF6A1A, 0x2E7DFF, 0x22B573, 0xF2C94C, 0xE5484D)

@Composable
fun BgRemoveScreen(host: ToolHost, vm: BgRemoveViewModel = viewModel()) {
    val info by vm.info.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val comp by vm.composite.collectAsStateWithLifecycle()
    val kindName by vm.kind.collectAsStateWithLifecycle()
    val colour by vm.colour.collectAsStateWithLifecycle()
    val restoring by vm.restoring.collectAsStateWithLifecycle()
    val size by vm.brushSize.collectAsStateWithLifecycle()
    val c = ForgeTheme.colors
    val ready = status is BgStatus.Ready
    ToolScaffold(
        tool = host.tool, vm = vm, onBack = host.onBack, onOpenTool = host.onOpenTool,
        pick = PickSpec(listOf(FileKind.IMAGE), title = "Choose a picture", hint = "Finds the subject and removes the background on your device."),
        actionLabel = "Save cut-out", actionEnabled = ready && comp != null,
        actionHint = (status as? BgStatus.Failed)?.message,
        inputSubtitle = { it.subtitle(info) }, optionsReady = preview != null,
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                if (ready && comp != null) BrushImage(comp!!, kindName == BgKind.TRANSPARENT.name, size, { fx, fy -> vm.stroke(fx, fy) }, Modifier.fillMaxSize())
                else ImagePreview(preview, Modifier.fillMaxSize())
                if (status is BgStatus.Working) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = c.accent, trackColor = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(36.dp))
                        Text("Finding the subject…", style = ForgeType.Label, color = Color.White, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            (status as? BgStatus.Failed)?.let { InfoNote(it.message) }
            (status as? BgStatus.Ready)?.let { Text("Cut out with the ${it.engine.replaceFirstChar { ch -> ch.lowercase() }}", style = ForgeType.Small, color = c.text2) }
            if (ready) {
                LabelText("Refine")
                ChipRow {
                    OptionChip("Erase", !restoring, { vm.setRestoring(false) })
                    OptionChip("Restore", restoring, { vm.setRestoring(true) })
                    TextAction("Reset", { vm.resetMask() }, icon = ForgeIcons.Refresh)
                }
                SliderRow("Brush size", size.toFloat(), 5f..100f, "$size", { vm.setBrushSize(it.toInt()) })
                LabelText("Background")
                ChipRow {
                    OptionChip("Transparent", kindName == BgKind.TRANSPARENT.name, { vm.setKind(BgKind.TRANSPARENT) })
                    OptionChip("Blur original", kindName == BgKind.BLUR.name, { vm.setKind(BgKind.BLUR) })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SWATCHES.forEach { s ->
                        val on = kindName == BgKind.COLOUR.name && colour == s
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF000000.toInt() or s)).border(if (on) 3.dp else 1.dp, if (on) c.accent else c.border, CircleShape)
                                .clickable { vm.setColour(s) }.semantics { contentDescription = "Background colour" },
                        )
                    }
                }
            }
        }
    }
}

/** Picture with a round brush: drags report positions as fractions of the picture. */
@Composable
fun BrushImage(bmp: Bitmap, checker: Boolean, brushPercent: Int, onStroke: (Float, Float) -> Unit, modifier: Modifier = Modifier) {
    val img = remember(bmp) { bmp.asImageBitmap() }
    var touch by remember { mutableStateOf<Offset?>(null) }
    BoxWithConstraints(modifier.clip(RoundedCornerShape(14.dp)).semantics { contentDescription = "Cut-out. Drag to erase or restore." }) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val mw = with(density) { maxWidth.toPx() }; val mh = with(density) { maxHeight.toPx() }
        val s = minOf(mw / bmp.width, mh / bmp.height)
        val ox = (mw - bmp.width * s) / 2; val oy = (mh - bmp.height * s) / 2
        if (checker) Checkerboard(Modifier.fillMaxSize()) else Box(Modifier.fillMaxSize().background(Color.Black))
        Canvas(
            Modifier.fillMaxSize().pointerInput(bmp) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    touch = down.position; onStroke((down.position.x - ox) / (bmp.width * s), (down.position.y - oy) / (bmp.height * s))
                    drag(down.id) { ch -> ch.consume(); touch = ch.position; onStroke((ch.position.x - ox) / (bmp.width * s), (ch.position.y - oy) / (bmp.height * s)) }
                    touch = null
                }
            },
        ) {
            drawImage(img, dstOffset = IntOffset(ox.toInt(), oy.toInt()), dstSize = IntSize((bmp.width * s).toInt(), (bmp.height * s).toInt()))
            touch?.let { drawCircle(Color.White, brushPercent / 100f * minOf(bmp.width, bmp.height) * 0.25f * s, it, style = Stroke(2.dp.toPx())) }
        }
    }
}
