package com.namanbansal.forge.tools.video

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.ToolOutcome
import com.namanbansal.forge.core.formatBytes
import com.namanbansal.forge.core.video.CompressMode
import com.namanbansal.forge.core.video.CompressPlan
import com.namanbansal.forge.core.video.CompressPlanner
import com.namanbansal.forge.core.video.JobContext
import com.namanbansal.forge.core.video.VideoInfo
import com.namanbansal.forge.core.video.VideoOps.exportAndSave
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.tools.image.ChipRow
import com.namanbansal.forge.tools.image.LabelText
import com.namanbansal.forge.tools.image.SliderRow
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.OptionChip
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.PickSpec
import com.namanbansal.forge.ui.toolkit.ToolScaffold
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/** V2 Compress: presets, custom bitrate / resolution or a target file size; H.264 (everywhere) or HEVC (smaller, where an encoder exists). */
class CompressVideoViewModel(application: Application, saved: SavedStateHandle) : VideoToolViewModel(application, saved) {
    override val toolCode = "V2"
    private val modeS = persistedString("vc.mode", CompressMode.MEDIUM.name)
    private val hevcS = persistedBool("vc.hevc", false)
    private val sideS = persistedInt("vc.side", 720)
    private val kbpsS = persistedInt("vc.kbps", 2000)
    private val targetKbS = persistedInt("vc.targetkb", 0)
    val mode: StateFlow<String> = modeS.flow
    val hevc: StateFlow<Boolean> = hevcS.flow
    val shortSide: StateFlow<Int> = sideS.flow
    val kbps: StateFlow<Int> = kbpsS.flow
    val targetKb: StateFlow<Int> = targetKbS.flow
    val hevcAvailable: Boolean get() = app.encoderCaps.hevc

    fun setMode(m: CompressMode) { modeS.value = m.name }
    fun setHevc(v: Boolean) { hevcS.value = v && hevcAvailable }
    fun setShortSide(v: Int) { sideS.value = v }
    fun setKbps(v: Int) { kbpsS.value = v }
    fun setTargetKb(v: Int) { targetKbS.value = v }

    override fun jobLabel() = "Compressing video"

    override suspend fun onVideoReady(file: PickedFile, info: VideoInfo) {
        if (targetKbS.value <= 0) targetKbS.value = (maxOf(file.size, info.sizeBytes) / 1024 / 3).toInt().coerceAtLeast(200)
    }

    fun currentPlan(info: VideoInfo): CompressPlan = CompressPlanner.plan(
        info, CompressMode.valueOf(modeS.value), hevcS.value && hevcAvailable, sideS.value, kbpsS.value, targetKbS.value * 1024L,
    )

    override fun buildJob(file: PickedFile, info: VideoInfo): suspend JobContext.() -> ToolOutcome {
        val plan = currentPlan(info)
        val request = CompressPlanner.request(info, file.uri, plan)
        val ex = exporter; val dir = workDir
        return {
            val out = exportAndSave(ex, dir, request, "compressed", "mp4", "video/mp4")
            val saved = info.sizeBytes.takeIf { it > 0 }?.let { it - out.size }
            val detail = buildString {
                append("${plan.width} × ${plan.height} · ${if (plan.hevc) "HEVC" else "H.264"} · ${"%.1f".format(plan.videoBps / 1_000_000f)} Mbps")
                if (saved != null && saved > 0) append(" · ${(saved * 100f / info.sizeBytes).roundToInt()}% smaller")
            }
            ToolOutcome(out, "Video compressed", detail)
        }
    }
}

@Composable
fun CompressVideoScreen(host: ToolHost, vm: CompressVideoViewModel = viewModel()) {
    val info by vm.info.collectAsStateWithLifecycle()
    val poster by vm.poster.collectAsStateWithLifecycle()
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val modeName by vm.mode.collectAsStateWithLifecycle()
    val hevc by vm.hevc.collectAsStateWithLifecycle()
    val side by vm.shortSide.collectAsStateWithLifecycle()
    val kbps by vm.kbps.collectAsStateWithLifecycle()
    val targetKb by vm.targetKb.collectAsStateWithLifecycle()
    val i = info
    val mode = CompressMode.valueOf(modeName)
    val plan = remember(i, mode, hevc, side, kbps, targetKb) { i?.let { vm.currentPlan(it) } }
    AskNotificationPermission()
    ToolScaffold(
        tool = host.tool, vm = vm, onBack = host.onBack, onOpenTool = host.onOpenTool,
        pick = PickSpec(listOf(FileKind.VIDEO), title = "Choose a video to compress", hint = "MP4 (H.264 / AAC) comes out of every preset. Files stay on your device."),
        actionLabel = "Compress", inputSubtitle = { i?.summary().orEmpty() }, optionsReady = i != null,
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (i != null && plan != null) {
                EstimateCard(i, plan)
                LabelText("Quality")
                ChipRow {
                    CompressMode.entries.forEach { m ->
                        val label = when (m) { CompressMode.SMALL -> "Small 480p"; CompressMode.MEDIUM -> "Medium 720p"; CompressMode.HIGH -> "High 1080p"; CompressMode.CUSTOM -> "Custom"; CompressMode.TARGET -> "Target size" }
                        OptionChip(label, mode == m, { vm.setMode(m) })
                    }
                }
                when (mode) {
                    CompressMode.CUSTOM -> {
                        LabelText("Resolution (short side)")
                        ChipRow { listOf(0 to "Original", 1080 to "1080p", 720 to "720p", 480 to "480p", 360 to "360p").forEach { (v, l) -> OptionChip(l, side == v, { vm.setShortSide(v) }) } }
                        SliderRow("Video bitrate", kbps.toFloat(), 200f..20000f, if (kbps >= 1000) "%.1f Mbps".format(kbps / 1000f) else "$kbps kbps", { vm.setKbps((it / 50).roundToInt() * 50) })
                    }
                    CompressMode.TARGET -> {
                        val maxKb = maxOf(i.sizeBytes / 1024, 600L).toFloat()
                        SliderRow("Target file size", targetKb.toFloat().coerceAtMost(maxKb), 100f..maxKb, formatBytes(targetKb * 1024L), { vm.setTargetKb(it.roundToInt()) })
                    }
                    else -> {}
                }
                LabelText("Codec")
                ChipRow {
                    OptionChip("H.264 · plays everywhere", !hevc, { vm.setHevc(false) })
                    if (vm.hevcAvailable) OptionChip("HEVC · ~30% smaller", hevc, { vm.setHevc(true) })
                }
                if (!vm.hevcAvailable) InfoNote("HEVC isn't offered: this device has no HEVC encoder.")
                plan.warning?.let { InfoNote(it) }
                if (i.rotation != 0 || i.isPortrait) InfoNote("Orientation is kept: portrait and rotated clips come out the right way up.")
            }
        }
    }
}

@Composable
private fun EstimateCard(info: VideoInfo, plan: CompressPlan) {
    val c = ForgeTheme.colors
    val pct = if (info.sizeBytes > 0) ((1f - plan.estimateBytes.toFloat() / info.sizeBytes) * 100).roundToInt() else null
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.tint).padding(14.dp)) {
        Text("Estimated size", style = ForgeType.Small, color = c.text2)
        Text("≈ ${formatBytes(plan.estimateBytes)}" + if (info.sizeBytes > 0) "  (now ${formatBytes(info.sizeBytes)})" else "", style = ForgeType.Title, color = c.text)
        Text(
            "${plan.width} × ${plan.height} · ${"%.1f".format(plan.videoBps / 1_000_000f)} Mbps video" + (pct?.takeIf { it > 0 }?.let { " · about $it% smaller" } ?: ""),
            style = ForgeType.Small, color = c.text2,
        )
    }
}
