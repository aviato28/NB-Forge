package com.namanbansal.forge.tools.image

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.RunScope
import com.namanbansal.forge.core.SavedOutput
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.ToolOutcome
import com.namanbansal.forge.core.ToolViewModel
import com.namanbansal.forge.core.formatBytes
import com.namanbansal.forge.core.image.ImageCodec
import com.namanbansal.forge.core.image.ImageInfo
import com.namanbansal.forge.core.image.ImageIO
import com.namanbansal.forge.core.image.ImgFormat
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Longest side of the on-screen preview decode (sampled; the full picture is only decoded when saving). */
const val PREVIEW_DIM = 1600

/**
 * Base for every image tool: checks all picked files are readable pictures, keeps a sampled preview + info of the first one
 * (survives rotation; reloaded after process death from the restored input), and offers batch + save helpers.
 */
abstract class ImageToolViewModel(application: Application, saved: SavedStateHandle) : ToolViewModel(application, saved) {

    private val _preview = MutableStateFlow<Bitmap?>(null)
    val preview: StateFlow<Bitmap?> = _preview.asStateFlow()
    private val _info = MutableStateFlow<ImageInfo?>(null)
    val info: StateFlow<ImageInfo?> = _info.asStateFlow()

    protected open val previewDim: Int get() = PREVIEW_DIM

    override fun onInputsReset() { _preview.value = null; _info.value = null }

    override suspend fun onInputsReady(files: List<PickedFile>) {
        io {
            files.forEach { f ->
                try { ImageIO.info(app, f.uri) } catch (e: ToolException) { throw ToolException("${f.name}: ${e.message}") } catch (e: Exception) { throw ToolException("${f.name} isn't a picture Forge can read.") }
            }
            val first = files.first()
            _info.value = ImageIO.info(app, first.uri)
            _preview.value = ImageIO.decode(app, first.uri, maxDim = previewDim)
        }
        onImageReady(files, _info.value!!, _preview.value!!)
    }

    /** The first picture is loaded: set defaults (sizes, crop...). Runs again after a restore. */
    protected open suspend fun onImageReady(files: List<PickedFile>, info: ImageInfo, preview: Bitmap) {}

    protected fun decodeFull(file: PickedFile, maxDim: Int = Int.MAX_VALUE, maxPixels: Long = ImageIO.MAX_PIXELS): Bitmap = ImageIO.decode(app, file.uri, maxDim, maxPixels)

    protected fun readBytes(file: PickedFile): ByteArray = ImageIO.readBytes(app, file.uri)

    /** Encodes [bmp] into `Pictures/Forge/<original>_<action>.<ext>`. */
    protected suspend fun RunScope.writeImage(file: PickedFile, action: String, bmp: Bitmap, fmt: ImgFormat, quality: Int = 92): SavedOutput =
        saveAs(file.name, action, fmt.ext, fmt.mime, images = true) { out -> ImageCodec.encode(bmp, fmt, quality, out) }

    class BatchResult(val outputs: List<SavedOutput>, val failures: List<String>)

    /**
     * Runs [block] for each input with progress + cancellation. A file that fails is reported and skipped; when every file fails
     * (or there is just one) the error is thrown so the user lands on the friendly error page with Try again.
     */
    protected suspend fun RunScope.batch(files: List<PickedFile>, block: suspend (index: Int, file: PickedFile) -> SavedOutput): BatchResult {
        val outs = ArrayList<SavedOutput>()
        val fails = ArrayList<String>()
        var last: Throwable? = null
        files.forEachIndexed { i, f ->
            checkCancelled()
            progress(i / files.size.toFloat())
            try { outs += block(i, f) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: OutOfMemoryError) {
                last = ToolException("${f.name} is too large to process on this device."); fails += "${f.name}: too large"
            } catch (e: Throwable) { last = e; fails += "${f.name}: ${e.message ?: "failed"}" }
        }
        if (outs.isEmpty()) throw (last ?: ToolException("Nothing was saved."))
        progress(1f)
        return BatchResult(outs, fails)
    }

    protected fun outcome(r: BatchResult, verb: String, extra: String? = null): ToolOutcome {
        val n = r.outputs.size
        val head = if (n == 1) verb else "$verb $n pictures"
        val detail = listOfNotNull(extra, r.failures.takeIf { it.isNotEmpty() }?.let { "${it.size} skipped" }).joinToString(" · ").ifBlank { null }
        return ToolOutcome(r.outputs.first(), head, detail, r.outputs)
    }
}

/** Light checkerboard behind transparent pictures. */
@Composable
fun Checkerboard(modifier: Modifier = Modifier, tile: Int = 12) {
    val dark = ForgeTheme.colors.bg.red < 0.5f
    val a = if (dark) Color(0xFF2A2A2E) else Color(0xFFF2F2F4)
    val b = if (dark) Color(0xFF38383D) else Color(0xFFDCDCE0)
    Canvas(modifier) {
        val t = tile.dp.toPx()
        var y = 0f; var row = 0
        while (y < size.height) {
            var x = 0f; var col = row
            while (x < size.width) {
                drawRect(if (col % 2 == 0) a else b, Offset(x, y), Size(minOf(t, size.width - x), minOf(t, size.height - y)))
                x += t; col++
            }
            y += t; row++
        }
    }
}

/** A picture fitted into its box over a checkerboard. */
@Composable
fun ImagePreview(bmp: Bitmap?, modifier: Modifier = Modifier, description: String = "Preview", scale: ContentScale = ContentScale.Fit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Checkerboard(Modifier.fillMaxSize())
        if (bmp != null) {
            val img = remember(bmp) { bmp.asImageBitmap() }
            Image(img, description, Modifier.fillMaxSize(), contentScale = scale)
        }
    }
}

/** "Label ........ value" over a slider. */
@Composable
fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, onChange: (Float) -> Unit, modifier: Modifier = Modifier, steps: Int = 0, onFinished: (() -> Unit)? = null) {
    val c = ForgeTheme.colors
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = ForgeType.Label, color = c.text)
            Text(valueText, style = ForgeType.Label, color = c.text2)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range, steps = steps,
            onValueChangeFinished = onFinished,
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.border),
        )
    }
}

/** Wrapping row of option chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
fun LabelText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = ForgeType.Label, color = ForgeTheme.colors.text2, modifier = modifier.padding(top = 12.dp, bottom = 6.dp))
}

fun sizeLine(info: ImageInfo?, bytes: Long): String = info?.let { "${it.width} × ${it.height} px" + if (bytes >= 0) " · ${formatBytes(bytes)}" else "" }.orEmpty()

fun List<PickedFile>.subtitle(info: ImageInfo?): String =
    if (size > 1) "$size pictures · ${formatBytes(sumOf { it.size.coerceAtLeast(0) })}" else sizeLine(info, firstOrNull()?.size ?: -1)
