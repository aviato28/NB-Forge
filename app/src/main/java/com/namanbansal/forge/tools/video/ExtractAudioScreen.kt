package com.namanbansal.forge.tools.video

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.core.MediaTarget
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.ToolOutcome
import com.namanbansal.forge.core.video.AudioPlanner
import com.namanbansal.forge.core.video.JobContext
import com.namanbansal.forge.core.video.TrimMath
import com.namanbansal.forge.core.video.TrimRange
import com.namanbansal.forge.core.video.VideoInfo
import com.namanbansal.forge.core.video.VideoOps.exportAndSave
import com.namanbansal.forge.core.video.WavExtractor
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.tools.image.ChipRow
import com.namanbansal.forge.tools.image.LabelText
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.OptionChip
import com.namanbansal.forge.ui.components.ToggleRow
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.toolkit.PickSpec
import com.namanbansal.forge.ui.toolkit.ToolScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

enum class AudioFormat(val label: String, val ext: String, val mime: String) { M4A("M4A (AAC)", "m4a", "audio/mp4"), WAV("WAV (PCM)", "wav", "audio/wav") }

/** V5 Extract audio: M4A (copied untouched when the sound is already AAC) or WAV (decoded to 16-bit PCM). Optional range. */
class ExtractAudioViewModel(application: Application, saved: SavedStateHandle) : VideoToolViewModel(application, saved) {
    override val toolCode = "V5"
    override val requiresVideo get() = false
    private val fmtS = persistedString("ea.fmt", AudioFormat.M4A.name)
    private val trimS = persistedBool("ea.trim", false)
    private val startS = persistedInt("ea.start", 0)
    private val endS = persistedInt("ea.end", 0)
    val format: StateFlow<String> = fmtS.flow
    val trim: StateFlow<Boolean> = trimS.flow
    val startMs: StateFlow<Int> = startS.flow
    val endMs: StateFlow<Int> = endS.flow
    fun setFormat(f: AudioFormat) { fmtS.value = f.name }
    fun setTrim(v: Boolean) { trimS.value = v }
    fun setRange(s: Long, e: Long, duration: Long) { val r = TrimMath.clamp(s, e, duration); startS.value = r.startMs.toInt(); endS.value = r.endMs.toInt() }

    override fun jobLabel() = "Extracting audio"

    override suspend fun onVideoReady(file: PickedFile, info: VideoInfo) {
        if (!info.hasAudio) throw ToolException("This file has no sound track.")
        if (endS.value <= 0 || endS.value > info.durationMs) { startS.value = 0; endS.value = info.durationMs.toInt() }
    }

    fun range(info: VideoInfo): TrimRange? = if (trimS.value) TrimMath.clamp(startS.value.toLong(), endS.value.toLong(), info.durationMs) else null

    override fun buildJob(file: PickedFile, info: VideoInfo): suspend JobContext.() -> ToolOutcome {
        val fmt = AudioFormat.valueOf(fmtS.value)
        val range = range(info)
        val ex = exporter; val dir = workDir; val ctx = app
        return {
            val out = when (fmt) {
                AudioFormat.M4A -> exportAndSave(ex, dir, AudioPlanner.m4aRequest(file.uri, range), "audio", "m4a", fmt.mime, MediaTarget.AUDIO)
                AudioFormat.WAV -> {
                    dir.mkdirs()
                    val tmp = File(dir, "wav-${System.nanoTime()}.wav")
                    try {
                        withContext(Dispatchers.IO) { WavExtractor(ctx).extract(file.uri, range, tmp) { progress(it * 0.95f) } }
                        save("audio", "wav", fmt.mime, MediaTarget.AUDIO) { o -> tmp.inputStream().use { it.copyTo(o) } }
                    } finally { tmp.delete() }
                }
            }
            val lossless = fmt == AudioFormat.M4A && AudioPlanner.m4aIsLossless(info, range)
            ToolOutcome(out, "Audio extracted", when { lossless -> "The sound was copied untouched (lossless)."; fmt == AudioFormat.WAV -> "Uncompressed 16-bit WAV."; else -> "Encoded as AAC." })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractAudioScreen(host: ToolHost, vm: ExtractAudioViewModel = viewModel()) {
    val info by vm.info.collectAsStateWithLifecycle()
    val fmtName by vm.format.collectAsStateWithLifecycle()
    val trim by vm.trim.collectAsStateWithLifecycle()
    val s by vm.startMs.collectAsStateWithLifecycle()
    val e by vm.endMs.collectAsStateWithLifecycle()
    val i = info
    val fmt = AudioFormat.valueOf(fmtName)
    val c = ForgeTheme.colors
    AskNotificationPermission()
    ToolScaffold(
        tool = host.tool, vm = vm, onBack = host.onBack, onOpenTool = host.onOpenTool,
        pick = PickSpec(listOf(FileKind.VIDEO, FileKind.AUDIO), title = "Choose a video", hint = "Get the sound track as M4A or WAV."),
        actionLabel = "Extract as ${fmt.ext.uppercase()}", inputSubtitle = { i?.summary().orEmpty() }, optionsReady = i != null,
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (i != null) {
                LabelText("Format")
                ChipRow { AudioFormat.entries.forEach { f -> OptionChip(f.label, fmt == f, { vm.setFormat(f) }) } }
                when {
                    fmt == AudioFormat.M4A && i.isAac && !trim -> InfoNote("The sound is already AAC, so it is copied untouched: instant and lossless.")
                    fmt == AudioFormat.M4A -> InfoNote("The sound is encoded as AAC.")
                    else -> InfoNote("WAV is uncompressed (about ${com.namanbansal.forge.core.formatBytes(AudioPlanner.wavBytes(if (trim) (e - s).toLong() else i.durationMs, 44100, 2))} for this clip), good for editing.")
                }
                ToggleRow("Only part of it", trim, { vm.setTrim(it) }, subtitle = "Choose a start and end")
                if (trim) {
                    RangeSlider(
                        value = s.toFloat()..e.toFloat(), onValueChange = { vm.setRange(it.start.toLong(), it.endInclusive.toLong(), i.durationMs) },
                        valueRange = 0f..i.durationMs.toFloat(),
                        colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.border),
                    )
                    InfoNote("${TrimMath.clock(s.toLong())} → ${TrimMath.clock(e.toLong())}  ·  ${TrimMath.clock((e - s).toLong())} long", Modifier.fillMaxWidth())
                }
            }
        }
    }
}
