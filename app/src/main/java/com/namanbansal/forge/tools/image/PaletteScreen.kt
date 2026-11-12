package com.namanbansal.forge.tools.image

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.RunScope
import com.namanbansal.forge.core.ToolOutcome
import com.namanbansal.forge.core.image.BitmapOps
import com.namanbansal.forge.core.image.ColourFormat
import com.namanbansal.forge.core.image.ImageInfo
import com.namanbansal.forge.core.image.ImgFormat
import com.namanbansal.forge.core.image.PaletteExtractor
import com.namanbansal.forge.core.image.PaletteImage
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.ForgeCard
import com.namanbansal.forge.ui.components.Stepper
import com.namanbansal.forge.ui.components.TextAction
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.PickSpec
import com.namanbansal.forge.ui.toolkit.ToolScaffold
import com.namanbansal.forge.ui.toolkit.rememberSnack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** I6 Colour picker / palette: tap or drag on the picture (with a loupe) to read a colour; a palette is extracted and can be saved as PNG. */
class PaletteViewModel(application: Application, saved: SavedStateHandle) : ImageToolViewModel(application, saved) {
    override val toolCode = "I6"
    private val countS = persistedInt("pl.n", 6)
    private val pickedS = persisted<Int>("pl.c", 0, decode = { it as? Int })
    val count: StateFlow<Int> = countS.flow
    /** Picked colour as 0xRRGGBB, or null until the user picks one. */
    val picked: StateFlow<Int> = pickedS.flow

    private val _palette = MutableStateFlow<List<PaletteExtractor.Swatch>>(emptyList())
    val palette: StateFlow<List<PaletteExtractor.Swatch>> = _palette.asStateFlow()
    private var pixels: IntArray = IntArray(0)

    fun setCount(n: Int) { countS.value = n.coerceIn(5, 8); recompute() }
    fun pick(rgb: Int) { pickedS.value = (rgb and 0xFFFFFF) or HAS_PICK }
    fun hasPick(): Boolean = (pickedS.value and HAS_PICK) != 0
    fun pickedRgb(): Int = pickedS.value and 0xFFFFFF

    override suspend fun onImageReady(files: List<PickedFile>, info: ImageInfo, preview: Bitmap) {
        pixels = BitmapOps.argbPixels(preview, 160)
        recompute()
        if (!stateRestored || !hasPick()) _palette.value.firstOrNull()?.let { pick(it.colour) }
    }

    private fun recompute() { _palette.value = PaletteExtractor.extract(pixels, countS.value) }

    override suspend fun execute(scope: RunScope): ToolOutcome {
        val file = inputs.value.first()
        val bmp = PaletteImage.render(_palette.value)
        val saved = scope.writeImage(file, "palette", bmp, ImgFormat.PNG)
        return ToolOutcome(saved, "Palette saved", _palette.value.joinToString(" ") { ColourFormat.hex(it.colour) })
    }

    companion object { const val HAS_PICK = 0x1000000 }
}

@Composable
fun PaletteScreen(host: ToolHost, vm: PaletteViewModel = viewModel()) {
    val info by vm.info.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val palette by vm.palette.collectAsStateWithLifecycle()
    val count by vm.count.collectAsStateWithLifecycle()
    val picked by vm.picked.collectAsStateWithLifecycle()
    val c = ForgeTheme.colors
    val rgb = picked and 0xFFFFFF
    ToolScaffold(
        tool = host.tool, vm = vm, onBack = host.onBack, onOpenTool = host.onOpenTool,
        pick = PickSpec(listOf(FileKind.IMAGE), title = "Choose a picture", hint = "Pick colours from it and get a palette."),
        actionLabel = "Save palette as PNG", inputSubtitle = { it.subtitle(info) }, optionsReady = preview != null && palette.isNotEmpty(),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            preview?.let { bmp -> PickerImage(bmp, { vm.pick(it) }, Modifier.fillMaxWidth().height(260.dp)) }
            ForgeCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF000000.toInt() or rgb)).border(1.dp, c.border, RoundedCornerShape(14.dp)).semantics { contentDescription = "Picked colour ${ColourFormat.hex(rgb)}" })
                    Column(Modifier.weight(1f)) {
                        listOf(ColourFormat.hex(rgb), ColourFormat.rgb(rgb), ColourFormat.hsl(rgb)).forEach { v -> CopyRow(v) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Palette", style = ForgeType.Item, color = c.text)
                Stepper(count, 5..8, { vm.setCount(it) }, "Colours in palette")
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                palette.forEach { s ->
                    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable { vm.pick(s.colour) }.padding(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF000000.toInt() or s.colour)).border(1.dp, c.border, RoundedCornerShape(12.dp)))
                        Text(ColourFormat.hex(s.colour), style = ForgeType.Small, color = c.text2)
                    }
                }
            }
        }
    }
}

@Composable
private fun CopyRow(value: String) {
    val context = LocalContext.current
    val snack = rememberSnack()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(value, style = ForgeType.Body, color = ForgeTheme.colors.text)
        TextAction("Copy", {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("colour", value))
            snack("Copied $value")
        }, icon = ForgeIcons.Copy)
    }
}

/** Picture where touching or dragging reads the colour under the finger; a magnifier circle shows the neighbourhood. */
@Composable
fun PickerImage(bmp: Bitmap, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val img = remember(bmp) { bmp.asImageBitmap() }
    var touch by remember { mutableStateOf<Offset?>(null) }
    BoxWithConstraints(modifier.clip(RoundedCornerShape(14.dp)).semantics { contentDescription = "Picture. Touch and drag to pick a colour." }) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val mw = with(density) { maxWidth.toPx() }
        val mh = with(density) { maxHeight.toPx() }
        val s = minOf(mw / bmp.width, mh / bmp.height)
        val ox = (mw - bmp.width * s) / 2
        val oy = (mh - bmp.height * s) / 2
        fun colourAt(p: Offset): Int {
            val x = ((p.x - ox) / s).toInt().coerceIn(0, bmp.width - 1)
            val y = ((p.y - oy) / s).toInt().coerceIn(0, bmp.height - 1)
            return bmp.getPixel(x, y) and 0xFFFFFF
        }
        Checkerboard(Modifier.fillMaxSize())
        Canvas(
            Modifier.fillMaxSize().pointerInput(bmp) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    touch = down.position; onPick(colourAt(down.position))
                    drag(down.id) { ch -> ch.consume(); touch = ch.position; onPick(colourAt(ch.position)) }
                    touch = null
                }
            },
        ) {
            drawImage(img, dstOffset = IntOffset(ox.toInt(), oy.toInt()), dstSize = IntSize((bmp.width * s).toInt(), (bmp.height * s).toInt()))
            val t = touch ?: return@Canvas
            val r = 48.dp.toPx()
            val cx = t.x.coerceIn(r, size.width - r)
            val cy = (t.y - r * 1.9f).let { if (it < r) t.y + r * 1.9f else it }
            val zoom = 8f
            val srcPx = (r * 2 / (s * zoom))
            val sx = ((t.x - ox) / s - srcPx / 2).coerceIn(0f, (bmp.width - srcPx).coerceAtLeast(0f))
            val sy = ((t.y - oy) / s - srcPx / 2).coerceIn(0f, (bmp.height - srcPx).coerceAtLeast(0f))
            val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(cx, cy), r)) }
            clipPath(circle) {
                drawRect(Color.Black, Offset(cx - r, cy - r), Size(r * 2, r * 2))
                drawImage(
                    img, srcOffset = IntOffset(sx.toInt(), sy.toInt()), srcSize = IntSize(srcPx.toInt().coerceAtLeast(1), srcPx.toInt().coerceAtLeast(1)),
                    dstOffset = IntOffset((cx - r).toInt(), (cy - r).toInt()), dstSize = IntSize((r * 2).toInt(), (r * 2).toInt()), filterQuality = FilterQuality.None,
                )
            }
            drawCircle(Color.White, r, Offset(cx, cy), style = Stroke(3.dp.toPx()))
            drawCircle(Color.Black, 6.dp.toPx(), Offset(cx, cy), style = Stroke(1.5.dp.toPx()))
        }
    }
}
