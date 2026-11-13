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
import com.namanbansal.forge.core.video.ExportRequest
import com.namanbansal.forge.core.video.JobContext
import com.namanbansal.forge.core.video.VideoInfo
import com.namanbansal.forge.core.video.VideoOps.exportAndSave
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.toolkit.PickSpec
import com.namanbansal.forge.ui.toolkit.ToolScaffold

/** V9 Mute: drops the audio track; the picture is copied as is (no re-encode), so it is fast and lossless. */
class MuteViewModel(application: Application, saved: SavedStateHandle) : VideoToolViewModel(application, saved) {
    override val toolCode = "V9"
    override fun jobLabel() = "Removing audio"

    override fun buildJob(file: PickedFile, info: VideoInfo): suspend JobContext.() -> ToolOutcome {
        val ex = exporter; val dir = workDir
        return {
            val out = exportAndSave(ex, dir, ExportRequest(input = file.uri, removeAudio = true), "muted", "mp4", "video/mp4")
            ToolOutcome(out, "Audio removed", "The picture was copied without re-encoding.")
        }
    }
}

@Composable
fun MuteScreen(host: ToolHost, vm: MuteViewModel = viewModel()) {
    val info by vm.info.collectAsStateWithLifecycle()
    val poster by vm.poster.collectAsStateWithLifecycle()
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val i = info
    val player = rememberPreviewPlayer(inputs.firstOrNull()?.uri, exact = false)
    AskNotificationPermission()
    ToolScaffold(
        tool = host.tool, vm = vm, onBack = host.onBack, onOpenTool = host.onOpenTool,
        pick = PickSpec(listOf(FileKind.VIDEO), title = "Choose a video to mute", hint = "The picture is copied untouched. Files stay on your device."),
        actionLabel = "Remove audio", actionEnabled = i?.hasAudio == true,
        actionHint = if (i != null && !i.hasAudio) "This video has no sound." else null,
        inputSubtitle = { i?.summary().orEmpty() }, optionsReady = i != null,
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            VideoPreviewBox(player, poster, aspect = i?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: 1.78f)
            if (i != null && i.hasAudio) InfoNote("The ${i.codecLabel()?.substringAfter(" / ", "audio") ?: "audio"} track will be removed. Nothing is re-encoded, so quality is unchanged.", Modifier.fillMaxWidth())
        }
    }
}
