package com.namanbansal.forge.tools.video

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.core.MediaTarget
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.ToolOutcome
import com.namanbansal.forge.core.formatBytes
import com.namanbansal.forge.core.video.GifBuilder
import com.namanbansal.forge.core.video.GifMath
import com.namanbansal.forge.core.video.GifPreview
import com.namanbansal.forge.core.video.JobContext
import com.namanbansal.forge.core.video.TrimMath
import com.namanbansal.forge.core.video.VideoInfo
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.tools.image.SliderRow
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.PickSpec
import com.namanbansal.forge.ui.toolkit.ToolScaffold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** V4 Video to GIF: a range of up to 30 s, 5-20 fps, 240-720 px wide. Per-frame palette (median cut) with Floyd-Steinberg dithering, own LZW writer. */
class GifViewModel(application: Application, saved: SavedStateHandle) : VideoToolViewModel(application, saved) {
    override val toolCode = "V4"
    private val startS = persistedInt("gf.start", 0)
    private val endS = persistedInt("gf.end", 0)
    private val fpsS = persistedInt("gf.fps", 12)
    private val widthS = persistedInt("gf.w", 480)
    val startMs: StateFlow<Int> = startS.flow
    val endMs: StateFlow<Int> = endS.flow
    val fps: StateFlow<Int> = fpsS.flow
    val width: StateFlow<Int> = widthS.flow

    private val _preview = MutableStateFlow<GifPreview?>(null)
    val preview: StateFlow<GifPreview?> = _preview.asStateFlow()
    private val _previewBusy = MutableStateFlow(false)
    val previewBusy: StateFlow<Boolean> = _previewBusy.asStateFlow()
    private val _previewError = MutableStateFlow<String?>(null)
    val previewError: StateFlow<String?> = _previewError.asStateFlow()
    private var previewJob: Job? = null

    fun setRange(s: Long, e: Long, duration: Long) {
        var start = s; var end = e
        val max = GifMath.MAX_SECONDS * 1000L
        if (end - start > max) { if (start != startS.value.toLong()) end = start + max else start = end - max }
        val r = TrimMath.clamp(start, end, duration)
        startS.value = r.startMs.toInt(); endS.value = r.endMs.toInt()
        refreshPreview()
    }
    fun setFps(v: Int) { fpsS.value = v.coerceIn(GifMath.FPS_RANGE); refreshPreview() }
    fun setWidth(v: Int) { widthS.value = v.coerceIn(GifMath.WIDTH_RANGE); refreshPreview() }
    fun rangeMs(): Long = (endS.value - startS.value).toLong()
    fun totalFrames(): Int = GifMath.frameCount(rangeMs(), fpsS.value)

    fun estimateBytes(info: VideoInfo): Long {
        val (w, h) = GifMath.outputSize(info.width, info.height, widthS.value)
        val p = _preview.value
        return if (p != null) GifMath.extrapolate(p.bytes, p.frames.size, totalFrames()) else GifMath.roughBytes(totalFrames(), w, h)
    }

    override fun jobLabel() = "Making GIF"

    override fun onInputsReset() { super.onInputsReset(); previewJob?.cancel(); _preview.value = null; _previewError.value = null }

    override suspend fun onVideoReady(file: PickedFile, info: VideoInfo) {
        if (endS.value <= 0 || endS.value > info.durationMs) { startS.value = 0; endS.value = minOf(info.durationMs, 6_000L).toInt() }
        widthS.value = widthS.value.coerceAtMost(maxOf(info.width, GifMath.WIDTH_RANGE.first))
        refreshPreview()
    }

    /** Re-renders the first second with the current options (debounced while a slider is moving). */
    fun refreshPreview() {
        val file = inputs.value.firstOrNull() ?: return
        val info = info.value ?: return
        previewJob?.cancel()
        val start = startS.value.toLong(); val range = rangeMs(); val fps = fpsS.value; val w = widthS.value
        previewJob = viewModelScope.launch {
            delay(350)
            _previewBusy.value = true; _previewError.value = null
            try {
                val p = withContext(Dispatchers.IO) { app.frameSourceFactory(app, file.uri).use { GifBuilder.preview(it, info.width, info.height, start, range, info.durationMs, fps, w) } }
                _preview.value = p
            } catch (e: CancellationException) { throw e } catch (t: Throwable) {
                _preview.value = null; _previewError.value = (t as? ToolException)?.message ?: "The preview couldn't be made."
            } finally { _previewBusy.value = false }
        }
    }

    override fun buildJob(file: PickedFile, info: VideoInfo): suspend JobContext.() -> ToolOutcome {
        val start = startS.value.toLong(); val range = rangeMs(); val fps = fpsS.value; val w = widthS.value
        if (range > GifMath.MAX_SECONDS * 1000L) throw ToolException("GIFs can be at most ${GifMath.MAX_SECONDS} seconds long.")
        val factory = app.frameSourceFactory; val ctx = app
        return {
            var result: com.namanbansal.forge.core.video.GifResult? = null
            val out = save("gif", "gif", "image/gif", MediaTarget.IMAGES) { stream ->
                val buffered = stream.buffered(64 * 1024)
                factory(ctx, file.uri).use { src ->
                    result = GifBuilder.build(src, info.width, info.height, start, range, info.durationMs, fps, w, buffered, onProgress = { progress(it) })
                }
                buffered.flush()
            }
            val (ow, oh) = GifMath.outputSize(info.width, info.height, w)
            ToolOutcome(out, "GIF created", "${result?.frames ?: 0} frames · $ow × $oh · $fps fps")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GifScreen(host: ToolHost, vm: GifViewModel = viewModel()) {
    val info by vm.info.collectAsStateWithLifecycle()
    val poster by vm.poster.collectAsStateWithLifecycle()
    val s by vm.startMs.collectAsStateWithLifecycle()
    val e by vm.endMs.collectAsStateWithLifecycle()
    val fps by vm.fps.collectAsStateWithLifecycle()
    val width by vm.width.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val busy by vm.previewBusy.collectAsStateWithLifecycle()
    val perr by vm.previewError.collectAsStateWithLifecycle()
    val i = info
    val c = ForgeTheme.colors
    AskNotificationPermission()
    ToolScaffold(
        tool = host.tool, vm = vm, onBack = host.onBack, onOpenTool = host.onOpenTool,
        pick = PickSpec(listOf(FileKind.VIDEO), title = "Choose a video for a GIF", hint = "Up to ${GifMath.MAX_SECONDS} seconds. Files stay on your device."),
        actionLabel = "Make GIF", actionEnabled = i != null && e - s >= 200,
        inputSubtitle = { i?.summary().orEmpty() }, optionsReady = i != null,
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (i != null) {
                GifPreviewView(preview, poster, busy, perr, aspect = i.width.toFloat() / i.height.coerceAtLeast(1))
                val (ow, oh) = GifMath.outputSize(i.width, i.height, width)
                val est = vm.estimateBytes(i)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.tint).padding(14.dp)) {
                    Text("Estimated size", style = ForgeType.Small, color = c.text2)
                    Text((if (preview == null) "≈ " else "≈ ") + formatBytes(est), style = ForgeType.Title, color = c.text)
                    Text("${vm.totalFrames()} frames · $ow × $oh · ${if (preview != null) "measured from the first second" else "rough guess until the preview is ready"}", style = ForgeType.Small, color = c.text2)
                }
                Text("Range  ${TrimMath.clock(s.toLong())} → ${TrimMath.clock(e.toLong())}  (${TrimMath.clock((e - s).toLong())})", style = ForgeType.Label, color = c.text2, modifier = Modifier.padding(top = 8.dp))
                RangeSlider(
                    value = s.toFloat()..e.toFloat(), onValueChange = { vm.setRange(it.start.toLong(), it.endInclusive.toLong(), i.durationMs) },
                    valueRange = 0f..i.durationMs.toFloat(),
                    colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.border),
                )
                SliderRow("Frames per second", fps.toFloat(), GifMath.FPS_RANGE.first.toFloat()..GifMath.FPS_RANGE.last.toFloat(), "$fps fps", { vm.setFps(it.toInt()) }, steps = GifMath.FPS_RANGE.last - GifMath.FPS_RANGE.first - 1)
                SliderRow("Width", width.toFloat(), GifMath.WIDTH_RANGE.first.toFloat()..GifMath.WIDTH_RANGE.last.toFloat(), "$width px", { vm.setWidth((it / 20).toInt() * 20) })
                if (e - s >= GifMath.MAX_SECONDS * 1000L - 50) InfoNote("GIFs are limited to ${GifMath.MAX_SECONDS} seconds.")
                if (est > 20_000_000L) InfoNote("This GIF will be big. Lower the frame rate or width, or pick a shorter range, so it is easy to share.")
            }
        }
    }
}

/** Loops the first second of the real quantised output (palette + dithering included), so what you see is what you get. */
@Composable
fun GifPreviewView(preview: GifPreview?, poster: Bitmap?, busy: Boolean, error: String?, aspect: Float, modifier: Modifier = Modifier) {
    val c = ForgeTheme.colors
    val bitmaps = remember(preview) { preview?.frames?.map { f -> Bitmap.createBitmap(f.toArgb(), f.width, f.height, Bitmap.Config.ARGB_8888) } }
    var index by remember(preview) { mutableIntStateOf(0) }
    LaunchedEffect(preview) {
        val p = preview ?: return@LaunchedEffect
        if (p.frames.size < 2) return@LaunchedEffect
        while (true) { delay(p.delayCs * 10L); index = (index + 1) % p.frames.size }
    }
    Box(
        modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(0.5f, 2.4f)).clip(RoundedCornerShape(14.dp)).background(Color.Black)
            .semantics { contentDescription = "GIF preview" },
        contentAlignment = Alignment.Center,
    ) {
        val shown = bitmaps?.getOrNull(index) ?: poster
        if (shown != null) Image(shown.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        if (busy) CircularProgressIndicator(color = c.accent, trackColor = Color.Transparent, modifier = Modifier.padding(8.dp).align(Alignment.TopEnd).then(Modifier.padding(0.dp)), strokeWidth = 2.dp)
        if (error != null) Text(error, style = ForgeType.Small, color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp))
        if (preview != null) Text("Preview · first second", style = ForgeType.Tiny, color = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
    }
}
