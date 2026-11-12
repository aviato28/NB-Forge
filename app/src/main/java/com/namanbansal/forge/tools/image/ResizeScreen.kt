package com.namanbansal.forge.tools.image

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.core.image.BitmapOps
import com.namanbansal.forge.core.image.FRect
import com.namanbansal.forge.core.image.ImgFormat
import com.namanbansal.forge.core.image.ResizeMath
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.ForgeTextField
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.OptionChip
import com.namanbansal.forge.ui.components.Segmented
import com.namanbansal.forge.ui.components.TextAction
import com.namanbansal.forge.ui.components.ToggleRow
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.PickSpec
import com.namanbansal.forge.ui.toolkit.ToolScaffold

@Composable
fun ResizeScreen(host: ToolHost, vm: ResizeViewModel = viewModel()) {
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val modeName by vm.mode.collectAsStateWithLifecycle()
    val w by vm.width.collectAsStateWithLifecycle()
    val h by vm.height.collectAsStateWithLifecycle()
    val lock by vm.lock.collectAsStateWithLifecycle()
    val pct by vm.percent.collectAsStateWithLifecycle()
    val presetId by vm.presetId.collectAsStateWithLifecycle()
    val rot by vm.rotation.collectAsStateWithLifecycle()
    val flip by vm.flipH.collectAsStateWithLifecycle()
    val ratioIdx by vm.ratioIndex.collectAsStateWithLifecycle()
    val crop by vm.crop.collectAsStateWithLifecycle()
    val fmt by vm.format.collectAsStateWithLifecycle()
    val quality by vm.quality.collectAsStateWithLifecycle()
    val mode = ResizeMode.entries.firstOrNull { it.name == modeName } ?: ResizeMode.PIXELS
    val batch = inputs.size > 1
    val c = ForgeTheme.colors

    ToolScaffold(
        tool = host.tool, vm = vm, onBack = host.onBack, onOpenTool = host.onOpenTool,
        pick = PickSpec(listOf(FileKind.IMAGE), multiple = true, title = "Choose pictures to resize or crop", hint = "Pick one to crop, or several to resize together."),
        actionLabel = if (mode == ResizeMode.CROP) "Crop" else if (batch) "Resize ${inputs.size} pictures" else "Resize",
        inputSubtitle = { it.subtitle(info) },
        optionsReady = preview != null,
    ) {
        val shown = remember(preview, rot, flip) { preview?.let { BitmapOps.transform(it, vm.transform) } }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Segmented(ResizeMode.entries.map { it.label }, mode.ordinal, { vm.setMode(ResizeMode.entries[it]) }, disabled = if (batch) setOf(ResizeMode.CROP.ordinal) else emptySet())
            if (mode == ResizeMode.CROP && shown != null) {
                CropEditor(
                    shown, crop.let { FRect(it[0] * shown.width, it[1] * shown.height, it[2] * shown.width, it[3] * shown.height) },
                    ratio = ResizeViewModel.RATIOS.getOrNull(ratioIdx)?.second?.takeIf { it > 0 },
                    onRect = { r -> vm.setCrop(r.l / shown.width, r.t / shown.height, r.r / shown.width, r.b / shown.height) },
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                )
            } else ImagePreview(shown, Modifier.fillMaxWidth().height(200.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextAction("Left", { vm.rotate(false) }, icon = ForgeIcons.RotateLeft)
                TextAction("Right", { vm.rotate(true) }, icon = ForgeIcons.Rotate)
                TextAction("Flip", { vm.flip() }, icon = ForgeIcons.Swap)
            }
            when (mode) {
                ResizeMode.PIXELS -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField("Width (px)", w, { vm.setWidth(it) }, Modifier.weight(1f))
                        NumberField("Height (px)", h, { vm.setHeight(it) }, Modifier.weight(1f), enabled = !batch)
                    }
                    ToggleRow("Keep proportions", lock || batch, { vm.setLock(it) }, subtitle = if (batch) "Each picture keeps its own shape; the width is applied to all." else null)
                }
                ResizeMode.PERCENT -> {
                    SliderRow("Scale", pct.toFloat(), 5f..200f, "$pct %", { vm.setPercent(it.toInt()) })
                    ChipRow { listOf(25, 50, 75, 100, 150, 200).forEach { p -> OptionChip("$p %", pct == p, { vm.setPercent(p) }) } }
                }
                ResizeMode.PRESET -> ChipRow {
                    ResizeMath.PRESETS.forEach { p -> OptionChip("${p.label} · ${p.note}", presetId == p.id, { vm.setPreset(p.id) }) }
                }
                ResizeMode.CROP -> {
                    ChipRow { ResizeViewModel.RATIOS.forEachIndexed { i, (label, _) -> OptionChip(label, ratioIdx == i, { vm.setRatio(i) }) } }
                }
            }
            vm.outputSize()?.let { (ow, oh) -> Text("Result: $ow × $oh px", style = ForgeType.Label, color = c.accent, modifier = Modifier.padding(top = 4.dp)) }
            LabelText("Save as")
            ChipRow {
                listOf("SAME" to "Same as original", "JPG" to "JPG", "PNG" to "PNG", "WEBP" to "WebP").forEach { (id, label) -> OptionChip(label, fmt == id, { vm.setFormat(id) }) }
            }
            if (vm.outputFormat(info).lossy) SliderRow("Quality", quality.toFloat(), 40f..100f, "$quality", { vm.setQuality(it.toInt()) })
            if (mode == ResizeMode.PIXELS && !lock && !batch) InfoNote("Width and height are set freely, so the picture may look stretched.")
        }
    }
}

@Composable
fun NumberField(label: String, value: Int, onValue: (Int) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var text by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value) { if (text.toIntOrNull() != value) text = value.toString() }
    ForgeTextField(
        value = text, label = label, keyboardType = KeyboardType.Number, modifier = modifier,
        onValueChange = { t -> if (enabled) { text = t.filter { it.isDigit() }.take(6); text.toIntOrNull()?.takeIf { it > 0 }?.let(onValue) } },
    )
}
