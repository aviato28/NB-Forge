package com.namanbansal.forge.tools.video

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.PlaybackParameters
import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.ToolOutcome
import com.namanbansal.forge.core.video.JobContext
import com.namanbansal.forge.core.video.SpeedMath
import com.namanbansal.forge.core.video.SpeedPlanner
import com.namanbansal.forge.core.video.TrimMath
import com.namanbansal.forge.core.video.VideoInfo
import com.namanbansal.forge.core.video.VideoOps.exportAndSave
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.tools.image.ChipRow
import com.namanbansal.forge.tools.image.LabelText
import com.namanbansal.forge.tools.image.SliderRow
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.OptionChip
import com.namanbansal.forge.ui.toolkit.PickSpec
import com.namanbansal.forge.ui.toolkit.ToolScaffold
import kotlinx.coroutines.flow.StateFlow

/** V8 Speed: 0.25x - 4x. Sound stays pitch-corrected up to 2x and is removed above that. */
class SpeedViewModel(application: Application, saved: SavedStateHandle) : VideoToolViewModel(application, saved) {
    override val toolCode = "V8"
    private val speedS = persistedInt("sp.x100", 200)
    val speed100: StateFlow<Int> = speedS.flow
    fun setSpeed(v: Float) { speedS.value = (SpeedMath.snap(v) * 100).toInt() }
    fun speed(): Float = SpeedMath.clamp(speedS.value / 100f)

    override fun jobLabel() = "Changing speed"

    override fun buildJob(file: PickedFile, info: VideoInfo): suspend JobContext.() -> ToolOutcome {
        val speed = speed()
        val request = SpeedPlanner.request(info, file.uri, speed)
        val ex = exporter; val dir = workDir
        return {
            val out = exportAndSave(ex, dir, request, "${SpeedMath.label(speed)}".replace(".", "_"), "mp4", "video/mp4")
            ToolOutcome(out, "Speed set to ${SpeedMath.label(speed)}", SpeedPlanner.resultNote(info, speed))
        }
    }
}

@Composable
fun SpeedScreen(host: ToolHost, vm: SpeedViewModel = viewModel()) {
    val info by vm.info.collectAsStateWithLifecycle()
    val poster by vm.poster.collectAsStateWithLifecycle()
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val s100 by vm.speed100.collectAsStateWithLifecycle()
    val speed = SpeedMath.clamp(s100 / 100f)
    val i = info
    val player = rememberPreviewPlayer(inputs.firstOrNull()?.uri, exact = false)
    // Live preview at the chosen speed (ExoPlayer keeps the pitch).
    LaunchedEffect(player, speed) { player?.playbackParameters = PlaybackParameters(speed, 1f) }
    AskNotificationPermission()
    ToolScaffold(
        tool = host.tool, vm = vm, onBack = host.onBack, onOpenTool = host.onOpenTool,
        pick = PickSpec(listOf(FileKind.VIDEO), title = "Choose a video", hint = "Slow motion or time-lapse, 0.25x to 4x."),
        actionLabel = "Apply ${SpeedMath.label(speed)}", actionEnabled = speed != 1f,
        actionHint = if (speed == 1f) "Pick a speed other than 1x." else null,
        inputSubtitle = { i?.summary().orEmpty() }, optionsReady = i != null,
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (i != null) {
                VideoPreviewBox(player, poster, aspect = i.width.toFloat() / i.height.coerceAtLeast(1))
                SliderRow("Speed", speed, SpeedMath.MIN..SpeedMath.MAX, SpeedMath.label(speed), { vm.setSpeed(it) })
                ChipRow { listOf(0.25f, 0.5f, 0.75f, 1.5f, 2f, 3f, 4f).forEach { v -> OptionChip(SpeedMath.label(v), kotlin.math.abs(speed - v) < 0.001f, { vm.setSpeed(v) }) } }
                LabelText("Result")
                InfoNote("${TrimMath.clock(i.durationMs, false)} → ${TrimMath.clock(SpeedMath.outputDurationMs(i.durationMs, speed), false)}", Modifier.fillMaxWidth())
                if (i.hasAudio) {
                    if (SpeedMath.audioKept(speed, true)) InfoNote("Sound is kept and pitch-corrected, so voices don't turn squeaky or deep.")
                    else InfoNote("Above ${SpeedMath.label(SpeedMath.MAX_AUDIO_SPEED)} the sound isn't intelligible, so it is removed.")
                }
            }
        }
    }
}
