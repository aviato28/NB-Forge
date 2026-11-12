package com.namanbansal.forge.tools.image

import android.app.Application
import android.graphics.Bitmap
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.namanbansal.forge.core.ToolViewModel
import com.namanbansal.forge.core.image.CellTransform
import com.namanbansal.forge.core.image.CollageLayout
import com.namanbansal.forge.core.image.CollageRenderer
import com.namanbansal.forge.core.image.CollageTemplate
import com.namanbansal.forge.core.image.ImageIO
import com.namanbansal.forge.core.image.ImgFormat
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.OptionChip
import com.namanbansal.forge.ui.components.TextAction
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.PickSpec
import com.namanbansal.forge.ui.toolkit.ToolScaffold
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** I9 Collage: 2 to 9 pictures in a template, with spacing, rounded corners, a background, per-cell pan and zoom, exported at 1080 or 2160 px. */
class CollageViewModel(application: Application, saved: SavedStateHandle) : ToolViewModel(application, saved) {
    override val toolCode = "I9"

    private val templateS = persistedString("cl.tpl", "")
    private val spacingS = persistedInt("cl.sp", 16)
    private val radiusS = persistedInt("cl.rad", 0)
    private val bgS = persistedInt("cl.bg", 0xFFFFFF)
    private val ratioS = persistedInt("cl.ratio", 0)
    private val sizeS = persistedInt("cl.size", 1080)
    private val orderS = persisted<IntArray>("cl.order", IntArray(0), decode = { it as? IntArray })
    private val transformsS = persisted<FloatArray>("cl.tr", FloatArray(0), decode = { it as? FloatArray })
    val spacing: StateFlow<Int> = spacingS.flow
    val radius: StateFlow<Int> = radiusS.flow
    val background: StateFlow<Int> = bgS.flow
    val ratioIndex: StateFlow<Int> = ratioS.flow
    val exportSize: StateFlow<Int> = sizeS.flow
    val templateId: StateFlow<String> = templateS.flow
    val order: StateFlow<IntArray> = orderS.flow
    val transforms: StateFlow<FloatArray> = transformsS.flow

    private val _previews = MutableStateFlow<List<Bitmap>>(emptyList())
    /** Sampled pictures, in the order the user picked them. */
    val previews: StateFlow<List<Bitmap>> = _previews.asStateFlow()
    private val _selected = MutableStateFlow(0)
    val selected: StateFlow<Int> = _selected.asStateFlow()

    val count: Int get() = _previews.value.size
    fun templates(): List<CollageTemplate> = CollageLayout.forCount(count)
    fun template(): CollageTemplate? = templates().let { l -> l.firstOrNull { it.id == templateS.value } ?: l.firstOrNull() }
    fun ratio(): Float = CollageLayout.CANVAS_RATIOS.getOrElse(ratioS.value) { CollageLayout.CANVAS_RATIOS[0] }.second

    fun setTemplate(t: CollageTemplate) { templateS.value = t.id }
    fun setSpacing(v: Int) { spacingS.value = v }
    fun setRadius(v: Int) { radiusS.value = v }
    fun setBackground(c: Int) { bgS.value = c }
    fun setRatio(i: Int) { ratioS.value = i }
    fun setExportSize(s: Int) { sizeS.value = s }
    fun select(i: Int) { _selected.value = i.coerceIn(0, (count - 1).coerceAtLeast(0)) }

    fun transformOf(cell: Int): CellTransform = transformsS.value.let { a -> if (cell * 3 + 2 < a.size) CellTransform(a[cell * 3], a[cell * 3 + 1], a[cell * 3 + 2]) else CellTransform() }
    fun setTransform(cell: Int, t: CellTransform) {
        val a = transformsS.value.copyOf(maxOf(transformsS.value.size, count * 3))
        a[cell * 3] = t.zoom; a[cell * 3 + 1] = t.panX; a[cell * 3 + 2] = t.panY
        transformsS.value = a
    }

    /** Picture shown in each cell, honouring the user's reordering. */
    fun orderedPreviews(): List<Bitmap> {
        val p = _previews.value
        val o = orderS.value.takeIf { it.size == p.size } ?: IntArray(p.size) { it }
        return o.map { p[it] }
    }

    fun moveSelected(delta: Int) {
        val o = (orderS.value.takeIf { it.size == count } ?: IntArray(count) { it }).copyOf()
        val a = _selected.value; val b = a + delta
        if (b !in o.indices) return
        val t = o[a]; o[a] = o[b]; o[b] = t
        orderS.value = o
        val tr = transformsS.value.copyOf(maxOf(transformsS.value.size, count * 3))
        if (tr.size >= count * 3) for (k in 0..2) { val x = tr[a * 3 + k]; tr[a * 3 + k] = tr[b * 3 + k]; tr[b * 3 + k] = x }
        transformsS.value = tr
        _selected.value = b
    }

    override fun onInputsReset() { _previews.value = emptyList(); _selected.value = 0 }

    override suspend fun onInputsReady(files: List<PickedFile>) {
        if (files.size < 2) throw ToolException("Pick at least 2 pictures for a collage.")
        val use = files.take(9)
        val bmps = io { use.map { f -> try { ImageIO.decode(app, f.uri, 900) } catch (e: ToolException) { throw ToolException("${f.name}: ${e.message}") } catch (e: Exception) { throw ToolException("${f.name} isn't a picture Forge can read.") } } }
        _previews.value = bmps
        if (files.size > 9) notify("Collages hold up to 9 pictures. Using the first 9.")
        if (!stateRestored) { orderS.value = IntArray(bmps.size) { it }; transformsS.value = FloatArray(bmps.size * 3) { if (it % 3 == 0) 1f else 0f } }
        if (templates().none { it.id == templateS.value }) templateS.value = templates().first().id
    }

    fun renderPreview(longSide: Int = 720): Bitmap? {
        val t = template() ?: return null
        val (w, h) = CollageLayout.canvasSize(ratio(), longSide)
        val k = longSide / 1080f
        return CollageRenderer.render(orderedPreviews(), t, w, h, (spacingS.value * k).toInt(), radiusS.value * k, 0xFF000000.toInt() or bgS.value, (0 until count).map { transformOf(it) })
    }

    override suspend fun execute(scope: RunScope): ToolOutcome {
        val files = inputs.value.take(9)
        val t = template() ?: throw ToolException("Choose a layout first.")
        val long = sizeS.value
        val (w, h) = CollageLayout.canvasSize(ratio(), long)
        val o = orderS.value.takeIf { it.size == files.size } ?: IntArray(files.size) { it }
        val bmps = ArrayList<Bitmap>()
        try {
            o.forEachIndexed { i, idx ->
                scope.checkCancelled(); scope.progress(i / (files.size + 1f))
                bmps += ImageIO.decode(app, files[idx].uri, maxDim = long)
            }
            val out = CollageRenderer.render(bmps, t, w, h, spacingS.value * long / 1080, radiusS.value * long / 1080f, 0xFF000000.toInt() or bgS.value, (0 until files.size).map { transformOf(it) })
            val saved = scope.saveAs(files.first().name, "collage", "jpg", "image/jpeg", images = true) { com.namanbansal.forge.core.image.ImageCodec.encode(out, ImgFormat.JPEG, 95, it) }
            return ToolOutcome(saved, "Collage saved", "$w × $h px")
        } finally { bmps.forEach { it.recycle() } }
    }
}

private val BG_SWATCHES = listOf(0xFFFFFF, 0x000000, 0xF4EDE4, 0xFF6A1A, 0x2E7DFF, 0x22B573, 0xE5484D)

@Composable
fun CollageScreen(host: ToolHost, vm: CollageViewModel = viewModel()) {
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val previews by vm.previews.collectAsStateWithLifecycle()
    val tplId by vm.templateId.collectAsStateWithLifecycle()
    val spacing by vm.spacing.collectAsStateWithLifecycle()
    val radius by vm.radius.collectAsStateWithLifecycle()
    val bg by vm.background.collectAsStateWithLifecycle()
    val ratioIdx by vm.ratioIndex.collectAsStateWithLifecycle()
    val exportPx by vm.exportSize.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val order by vm.order.collectAsStateWithLifecycle()
    val transforms by vm.transforms.collectAsStateWithLifecycle()
    val c = ForgeTheme.colors
    ToolScaffold(
        tool = host.tool, vm = vm, onBack = host.onBack, onOpenTool = host.onOpenTool,
        pick = PickSpec(listOf(FileKind.IMAGE), multiple = true, title = "Choose 2 to 9 pictures", hint = "Pick the pictures for your collage."),
        actionLabel = "Save collage", actionEnabled = previews.size >= 2,
        inputSubtitle = { f -> "${minOf(f.size, 9)} pictures" }, optionsReady = previews.size >= 2,
    ) {
        val rendered = remember(previews, tplId, spacing, radius, bg, ratioIdx, order, transforms) { vm.renderPreview() }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rendered?.let { bmp ->
                val tpl = vm.template()
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val boxW = with(density) { maxWidth.toPx() }
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .border(1.dp, c.border, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .semantics { contentDescription = "Collage preview. Tap a picture to select it, drag to move it, pinch to zoom." }
                            .pointerInput(tplId, ratioIdx, spacing) {
                                detectTapGestures { p ->
                                    val k = bmp.width / boxW
                                    val cells = CollageLayout.layout(tpl ?: return@detectTapGestures, bmp.width, bmp.height, (spacing * bmp.width / 1080f).toInt())
                                    cells.indexOfFirst { p.x * k in it.l..it.r && p.y * k in it.t..it.b }.takeIf { it >= 0 }?.let { vm.select(it) }
                                }
                            }
                            .pointerInput(tplId, ratioIdx, spacing, selected) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val t = tpl ?: return@detectTransformGestures
                                    val k = bmp.width / boxW
                                    val cell = CollageLayout.layout(t, bmp.width, bmp.height, (spacing * bmp.width / 1080f).toInt()).getOrNull(selected) ?: return@detectTransformGestures
                                    val img = vm.orderedPreviews().getOrNull(selected) ?: return@detectTransformGestures
                                    vm.setTransform(selected, CollageRenderer.gesture(img.width, img.height, cell.w, cell.h, vm.transformOf(selected), pan.x * k, pan.y * k, zoom))
                                }
                            }
                            .drawWithContent {
                                drawContent()
                                val cell = tpl?.let { CollageLayout.layout(it, bmp.width, bmp.height, (spacing * bmp.width / 1080f).toInt()).getOrNull(selected) }
                                if (cell != null) {
                                    val k = size.width / bmp.width
                                    drawRect(Color.White, Offset(cell.l * k, cell.t * k), Size(cell.w * k, cell.h * k), style = Stroke(5f))
                                    drawRect(c.accent, Offset(cell.l * k, cell.t * k), Size(cell.w * k, cell.h * k), style = Stroke(2.5f))
                                }
                            },
                    ) { Image(remember(bmp) { bmp.asImageBitmap() }, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextAction("Move earlier", { vm.moveSelected(-1) }, icon = ForgeIcons.Back)
                TextAction("Move later", { vm.moveSelected(1) }, icon = ForgeIcons.Chevron)
            }
            LabelText("Layout")
            ChipRow { vm.templates().forEach { t -> OptionChip(t.label, vm.template()?.id == t.id, { vm.setTemplate(t) }) } }
            LabelText("Shape")
            ChipRow { CollageLayout.CANVAS_RATIOS.forEachIndexed { i, (label, _) -> OptionChip(label, ratioIdx == i, { vm.setRatio(i) }) } }
            SliderRow("Spacing", spacing.toFloat(), 0f..60f, "$spacing", { vm.setSpacing(it.toInt()) })
            SliderRow("Rounded corners", radius.toFloat(), 0f..120f, "$radius", { vm.setRadius(it.toInt()) })
            LabelText("Background")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BG_SWATCHES.forEach { s ->
                    val on = bg == s
                    Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF000000.toInt() or s)).border(if (on) 3.dp else 1.dp, if (on) c.accent else c.border, CircleShape).clickable { vm.setBackground(s) }.semantics { contentDescription = "Background colour" })
                }
            }
            LabelText("Export size (longest side)")
            ChipRow { listOf(1080, 2160).forEach { s -> OptionChip("$s px", exportPx == s, { vm.setExportSize(s) }) } }
        }
    }
}
