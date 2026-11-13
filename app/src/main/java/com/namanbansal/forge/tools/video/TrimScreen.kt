package com.namanbansal.forge.tools.video

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.ToolOutcome
import com.namanbansal.forge.core.video.JobContext
import com.namanbansal.forge.core.video.TrimMath
import com.namanbansal.forge.core.video.TrimMode
import com.namanbansal.forge.core.video.TrimPlanner
import com.namanbansal.forge.core.video.TrimRange
import com.namanbansal.forge.core.video.VideoInfo
import com.namanbansal.forge.core.video.VideoOps.exportAndSave
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.tools.image.ChipRow
import com.namanbansal.forge.tools.image.LabelText
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.OptionChip
import com.namanbansal.forge.ui.components.TextAction
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.PickSpec
import com.namanbansal.forge.ui.toolkit.ToolScaffold
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** V1 Trim / cut: keep a range, or cut a section out of the middle (two clips joined). Frame-accurate; the file is re-cut, not re-encoded where possible. */
class TrimViewModel(application: Application, saved: SavedStateHandle) : VideoToolViewModel(application, saved) {
    override val toolCode = "V1"
    private val startS = persistedInt("tr.start", 0)
    private val endS = persistedInt("tr.end", 0)
    private val modeS = persistedString("tr.mode", TrimMode.KEEP.name)
    val startMs: StateFlow<Int> = startS.flow
    val endMs: StateFlow<Int> = endS.flow
    val mode: StateFlow<String> = modeS.flow

    private val _thumbs = MutableStateFlow<List<Bitmap?>>(List(THUMBS) { null })
    val thumbs: StateFlow<List<Bitmap?>> = _thumbs.asStateFlow()

    fun setMode(m: TrimMode) { modeS.value = m.name }
    fun setRange(s: Long, e: Long, duration: Long) {
        val r = TrimMath.clamp(s, e, duration); startS.value = r.startMs.toInt(); endS.value = r.endMs.toInt()
    }
    fun range(info: VideoInfo) = TrimMath.clamp(startS.value.toLong(), endS.value.toLong(), info.durationMs)
    fun segments(info: VideoInfo): List<TrimRange> = TrimMath.keptSegments(TrimMode.valueOf(modeS.value), range(info), info.durationMs)

    override fun jobLabel() = "Trimming video"

    override fun onInputsReset() { super.onInputsReset(); _thumbs.value = List(THUMBS) { null } }

    override suspend fun onVideoReady(file: PickedFile, info: VideoInfo) {
        if (endS.value <= 0 || endS.value > info.durationMs) { startS.value = 0; endS.value = info.durationMs.toInt() }
        viewModelScope.launch {
            io { FrameThumbs.load(app, file.uri, info.durationMs, THUMBS, 180) { i, b -> _thumbs.value = _thumbs.value.toMutableList().also { it[i] = b } } }
        }
    }

    override fun buildJob(file: PickedFile, info: VideoInfo): suspend JobContext.() -> ToolOutcome {
        val segs = segments(info)
        if (segs.isEmpty()) throw com.namanbansal.forge.core.ToolException("Nothing would be left. Choose a smaller section to remove.")
        val request = TrimPlanner.request(info, file.uri, segs)
        val ex = exporter; val dir = workDir
        val removed = TrimMode.valueOf(modeS.value) == TrimMode.REMOVE
        return {
            val out = exportAndSave(ex, dir, request, if (removed) "cut" else "trimmed", "mp4", "video/mp4")
            val len = TrimMath.keptLengthMs(segs)
            ToolOutcome(out, if (removed) "Section removed" else "Video trimmed", "${TrimMath.clock(info.durationMs, false)} → ${TrimMath.clock(len, false)}" + if (segs.size > 1) " · two clips joined" else "")
        }
    }

    companion object { const val THUMBS = 12 }
}

private enum class Handle { START, END }

@Composable
fun TrimScreen(host: ToolHost, vm: TrimViewModel = viewModel()) {
    val info by vm.info.collectAsStateWithLifecycle()
    val poster by vm.poster.collectAsStateWithLifecycle()
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val s by vm.startMs.collectAsStateWithLifecycle()
    val e by vm.endMs.collectAsStateWithLifecycle()
    val modeName by vm.mode.collectAsStateWithLifecycle()
    val thumbs by vm.thumbs.collectAsStateWithLifecycle()
    val i = info
    val mode = TrimMode.valueOf(modeName)
    val player = rememberPreviewPlayer(inputs.firstOrNull()?.uri, exact = true)
    val (playing, pos) = rememberPlayback(player)
    var active by remember { mutableStateOf(Handle.START) }
    val segs = remember(i, s, e, mode) { i?.let { vm.segments(it) }.orEmpty() }
    AskNotificationPermission()

    val startNow by rememberUpdatedState(s.toLong()); val endNow by rememberUpdatedState(e.toLong()); val modeNow by rememberUpdatedState(mode)
    // Preview loop: stop at the end (keep mode) or hop over the removed section.
    LaunchedEffect(player) {
        if (player == null) return@LaunchedEffect
        while (true) {
            delay(40)
            if (!player.isPlaying) continue
            val p = player.currentPosition
            if (modeNow == TrimMode.KEEP && p >= endNow) { player.pause(); player.seekTo(startNow) }
            else if (modeNow == TrimMode.REMOVE && p >= startNow && p < endNow) player.seekTo(endNow)
        }
    }

    ToolScaffold(
        tool = host.tool, vm = vm, onBack = host.onBack, onOpenTool = host.onOpenTool,
        pick = PickSpec(listOf(FileKind.VIDEO), title = "Choose a video to trim", hint = "Cut the start and end, or remove a section from the middle."),
        actionLabel = if (mode == TrimMode.KEEP) "Save trimmed video" else "Remove section",
        actionEnabled = segs.isNotEmpty() && i != null && !(mode == TrimMode.KEEP && s <= 0 && e >= i.durationMs),
        actionHint = when {
            i == null -> null
            segs.isEmpty() -> "Nothing would be left. Choose a smaller section to remove."
            mode == TrimMode.KEEP && s <= 0 && e >= i.durationMs -> "Drag the handles to choose what to keep."
            else -> null
        },
        inputSubtitle = { i?.summary().orEmpty() }, optionsReady = i != null,
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (i != null) {
                VideoPreviewBox(
                    player, poster, aspect = i.width.toFloat() / i.height.coerceAtLeast(1),
                    onToggle = {
                        player?.let { p ->
                            if (p.isPlaying) p.pause() else {
                                val at = p.currentPosition
                                if (mode == TrimMode.KEEP && (at < s || at >= e)) p.seekTo(s.toLong())
                                p.play()
                            }
                        }
                    },
                )
                TrimTimeline(
                    thumbs = thumbs, durationMs = i.durationMs, start = s.toLong(), end = e.toLong(), position = pos, mode = mode,
                    onStart = { ms -> val r = TrimMath.moveStart(vm.range(i), TrimMath.snapToFrame(ms, i.frameRate), i.durationMs); vm.setRange(r.startMs, r.endMs, i.durationMs); active = Handle.START; player?.pause(); player?.seekTo(r.startMs) },
                    onEnd = { ms -> val r = TrimMath.moveEnd(vm.range(i), TrimMath.snapToFrame(ms, i.frameRate), i.durationMs); vm.setRange(r.startMs, r.endMs, i.durationMs); active = Handle.END; player?.pause(); player?.seekTo(r.endMs) },
                    onSeek = { ms -> player?.pause(); player?.seekTo(ms) },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TimeReadout("Start", s.toLong(), active == Handle.START)
                    TimeReadout("End", e.toLong(), active == Handle.END)
                    TimeReadout(if (mode == TrimMode.KEEP) "Length" else "Result", TrimMath.keptLengthMs(segs), false)
                }
                val frameMs = (1000f / (if (i.frameRate > 0f) i.frameRate else 30f)).toLong().coerceAtLeast(1)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextAction("◀ 1 frame", { nudge(vm, i, active, -frameMs, player) })
                    TextAction("1 frame ▶", { nudge(vm, i, active, frameMs, player) })
                    TextAction("Set to playhead", { val p = player?.currentPosition ?: return@TextAction; if (active == Handle.START) vm.setRange(p, maxOf(e.toLong(), p + TrimMath.MIN_CLIP_MS), i.durationMs) else vm.setRange(minOf(s.toLong(), p - TrimMath.MIN_CLIP_MS), p, i.durationMs) }, enabled = player != null)
                }
                LabelText("Mode")
                ChipRow {
                    OptionChip("Keep selection", mode == TrimMode.KEEP, { vm.setMode(TrimMode.KEEP) })
                    OptionChip("Remove middle section", mode == TrimMode.REMOVE, { vm.setMode(TrimMode.REMOVE) })
                }
                if (mode == TrimMode.REMOVE) InfoNote("The highlighted section is cut out; what's before and after are joined into one video.")
                else InfoNote("Handles snap to frames. The result is cut at exactly those frames.")
            }
        }
    }
}

private fun nudge(vm: TrimViewModel, i: VideoInfo, active: Handle, delta: Long, player: androidx.media3.exoplayer.ExoPlayer?) {
    val r = vm.range(i)
    val n = if (active == Handle.START) TrimMath.moveStart(r, r.startMs + delta, i.durationMs) else TrimMath.moveEnd(r, r.endMs + delta, i.durationMs)
    vm.setRange(n.startMs, n.endMs, i.durationMs)
    player?.pause(); player?.seekTo(if (active == Handle.START) n.startMs else n.endMs)
}

@Composable
private fun TimeReadout(label: String, ms: Long, highlighted: Boolean) {
    val c = ForgeTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = ForgeType.Small, color = c.text2)
        Text(TrimMath.clock(ms), style = ForgeType.Item, color = if (highlighted) c.accent else c.text)
    }
}

/** Frame thumbnails with two draggable handles, a playhead and the kept / removed area marked. */
@Composable
fun TrimTimeline(
    thumbs: List<Bitmap?>, durationMs: Long, start: Long, end: Long, position: Long, mode: TrimMode,
    onStart: (Long) -> Unit, onEnd: (Long) -> Unit, onSeek: (Long) -> Unit, modifier: Modifier = Modifier,
) {
    val c = ForgeTheme.colors
    val curStart by rememberUpdatedState(start); val curEnd by rememberUpdatedState(end); val dur by rememberUpdatedState(durationMs)
    val onS by rememberUpdatedState(onStart); val onE by rememberUpdatedState(onEnd); val onSk by rememberUpdatedState(onSeek)
    var width by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf<Handle?>(null) }
    val handleW = 14.dp
    Box(modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(10.dp)).background(c.surface2)
        .semantics { contentDescription = "Trim timeline" }) {
        Row(Modifier.fillMaxSize().padding(vertical = 6.dp)) {
            thumbs.forEach { b ->
                if (b != null) Image(b.asImageBitmap(), null, Modifier.weight(1f).fillMaxSize(), contentScale = ContentScale.Crop)
                else Box(Modifier.weight(1f).fillMaxSize().background(c.border.copy(alpha = 0.5f)))
            }
        }
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { o -> onSk.invoke((o.x / size.width * dur).toLong().coerceIn(0, dur)) }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { o ->
                            val sx = curStart.toFloat() / dur * size.width; val ex = curEnd.toFloat() / dur * size.width
                            dragging = if (kotlin.math.abs(o.x - sx) <= kotlin.math.abs(o.x - ex)) Handle.START else Handle.END
                        },
                        onDragEnd = { dragging = null }, onDragCancel = { dragging = null },
                    ) { change, _ ->
                        change.consume()
                        val ms = (change.position.x / size.width * dur).toLong().coerceIn(0, dur)
                        when (dragging) { Handle.START -> onS.invoke(ms); Handle.END -> onE.invoke(ms); null -> {} }
                    }
                },
        ) {
            width = size.width.toInt().coerceAtLeast(1)
            val w = size.width; val h = size.height
            val sx = start.toFloat() / durationMs * w; val ex = end.toFloat() / durationMs * w
            val dim = Color.Black.copy(alpha = 0.55f)
            if (mode == TrimMode.KEEP) {
                drawRect(dim, Offset.Zero, Size(sx, h)); drawRect(dim, Offset(ex, 0f), Size(w - ex, h))
                drawRect(c.accent, Offset(sx, 0f), Size(ex - sx, 3.dp.toPx())); drawRect(c.accent, Offset(sx, h - 3.dp.toPx()), Size(ex - sx, 3.dp.toPx()))
            } else {
                drawRect(Color(0xCCE5484D), Offset(sx, 0f), Size(ex - sx, h).let { it.copy(width = it.width.coerceAtLeast(0f)) }, alpha = 0.55f)
            }
            val hw = handleW.toPx()
            drawRoundRect(c.accent, Offset(sx - hw / 2, 0f), Size(hw, h), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
            drawRoundRect(c.accent, Offset(ex - hw / 2, 0f), Size(hw, h), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
            for (x in listOf(sx, ex)) drawRect(Color.White, Offset(x - 1.dp.toPx(), h * 0.3f), Size(2.dp.toPx(), h * 0.4f))
            val px = position.coerceIn(0, durationMs).toFloat() / durationMs * w
            drawRect(Color.White, Offset(px - 1.dp.toPx(), 0f), Size(2.dp.toPx(), h))
        }
    }
}
