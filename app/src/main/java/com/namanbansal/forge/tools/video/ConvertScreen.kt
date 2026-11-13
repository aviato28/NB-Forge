package com.namanbansal.forge.tools.video

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.ToolOutcome
import com.namanbansal.forge.core.video.ConvertPlanner
import com.namanbansal.forge.core.video.JobContext
import com.namanbansal.forge.core.video.VideoFormat
import com.namanbansal.forge.core.video.VideoInfo
import com.namanbansal.forge.core.video.VideoOps.exportAndSave
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.tools.image.ChipRow
import com.namanbansal.forge.tools.image.LabelText
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.OptionChip
import com.namanbansal.forge.ui.toolkit.PickSpec
import com.namanbansal.forge.ui.toolkit.ToolScaffold
import kotlinx.coroutines.flow.StateFlow

/** V3 Convert: MP4 (H.264 / HEVC) everywhere, WebM only where the device can encode it. Copies instead of re-encoding when it can. */
class ConvertVideoViewModel(application: Application, saved: SavedStateHandle) : VideoToolViewModel(application, saved) {
    override val toolCode = "V3"
    private val formatS = persistedString("vv.fmt", VideoFormat.MP4_H264.name)
    val format: StateFlow<String> = formatS.flow
    fun setFormat(f: VideoFormat) { formatS.value = f.name }
    val caps get() = app.encoderCaps
    val formats: List<VideoFormat> get() = caps.videoFormats()
    fun selected(): VideoFormat = formats.firstOrNull { it.name == formatS.value } ?: VideoFormat.MP4_H264

    override fun jobLabel() = "Converting video"

    override fun buildJob(file: PickedFile, info: VideoInfo): suspend JobContext.() -> ToolOutcome {
        val fmt = selected()
        val request = ConvertPlanner.request(info, file.uri, fmt, app.encoderCaps)
        val remux = ConvertPlanner.canRemux(info, fmt)
        val ex = exporter; val dir = workDir
        return {
            val out = exportAndSave(ex, dir, request, "converted", fmt.ext, fmt.mime)
            ToolOutcome(out, "Converted to ${fmt.label}", if (remux) "Copied without re-encoding: no quality loss." else null)
        }
    }
}

@Composable
fun ConvertVideoScreen(host: ToolHost, vm: ConvertVideoViewModel = viewModel()) {
    val info by vm.info.collectAsStateWithLifecycle()
    val poster by vm.poster.collectAsStateWithLifecycle()
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val name by vm.format.collectAsStateWithLifecycle()
    val i = info
    val fmt = vm.formats.firstOrNull { it.name == name } ?: VideoFormat.MP4_H264
    val player = rememberPreviewPlayer(inputs.firstOrNull()?.uri, exact = false)
    AskNotificationPermission()
    ToolScaffold(
        tool = host.tool, vm = vm, onBack = host.onBack, onOpenTool = host.onOpenTool,
        pick = PickSpec(listOf(FileKind.VIDEO), title = "Choose a video to convert", hint = "MOV, MKV, 3GP, WebM and more. To get just the sound, use Extract audio."),
        actionLabel = "Convert to ${fmt.label}", inputSubtitle = { i?.summary().orEmpty() }, optionsReady = i != null,
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (i != null) {
                VideoPreviewBox(player, poster, aspect = i.width.toFloat() / i.height.coerceAtLeast(1))
                LabelText("Convert to")
                ChipRow { vm.formats.forEach { f -> OptionChip(f.label, fmt == f, { vm.setFormat(f) }) } }
                vm.caps.missingNote()?.let { InfoNote(it, Modifier.fillMaxWidth()) }
                if (ConvertPlanner.canRemux(i, fmt)) InfoNote("This file already uses ${fmt.label} video and compatible sound, so it is copied without re-encoding: fast and lossless.")
                else InfoNote("The video will be re-encoded at about the same quality. Long clips take a while; you can leave Forge and it keeps going.")
                if (i.hdr && fmt == VideoFormat.MP4_H264) InfoNote("This is an HDR video. It is converted to standard colours for H.264. Choose HEVC to keep HDR.")
                if (fmt == VideoFormat.WEBM) InfoNote("WebM is written with VP9 or VP8 video and Opus sound.")
            }
        }
    }
}
