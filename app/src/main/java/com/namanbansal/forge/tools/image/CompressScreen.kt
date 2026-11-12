package com.namanbansal.forge.tools.image

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.core.formatBytes
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.OptionChip
import com.namanbansal.forge.ui.components.Segmented
import com.namanbansal.forge.ui.components.ToggleRow
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.PickSpec
import com.namanbansal.forge.ui.toolkit.ToolScaffold

@Composable
fun CompressScreen(host: ToolHost, vm: CompressViewModel = viewModel()) {
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val after by vm.after.collectAsStateWithLifecycle()
    val target by vm.target.collectAsStateWithLifecycle()
    val quality by vm.quality.collectAsStateWithLifecycle()
    val kb by vm.targetKb.collectAsStateWithLifecycle()
    val fmtId by vm.format.collectAsStateWithLifecycle()
    val shrink by vm.allowShrink.collectAsStateWithLifecycle()
    val fmt = vm.outputFormat(info)
    val c = ForgeTheme.colors
    val originalBytes = inputs.firstOrNull()?.size ?: -1L

    ToolScaffold(
        tool = host.tool, vm = vm, onBack = host.onBack, onOpenTool = host.onOpenTool,
        pick = PickSpec(listOf(FileKind.IMAGE), multiple = true, title = "Choose pictures to compress", hint = "Pick one to preview, or several to compress together."),
        actionLabel = if (inputs.size > 1) "Compress ${inputs.size} pictures" else "Compress",
        inputSubtitle = { it.subtitle(info) },
        optionsReady = preview != null,
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val b = preview
            val a = after?.after
            if (b != null && a != null && fmt.lossy) SplitCompare(b, a, Modifier.fillMaxWidth().height(240.dp)) else ImagePreview(b, Modifier.fillMaxWidth().height(240.dp))
            after?.let { p ->
                val est = if (fmt.lossy) "≈ ${formatBytes(p.estimatedBytes)}" else ""
                if (fmt.lossy) Text(
                    "$est" + (if (originalBytes > 0) " (was ${formatBytes(originalBytes)})" else "") + if (target) " · quality ${p.quality}" else "",
                    style = ForgeType.Label, color = c.accent,
                )
            }
            LabelText("Format")
            ChipRow {
                listOf("SAME" to "Same as original", "JPG" to "JPG", "WEBP" to "WebP", "PNG" to "PNG (lossless)").forEach { (id, label) -> OptionChip(label, fmtId == id, { vm.setFormat(id) }) }
            }
            if (fmt.lossy) {
                Segmented(listOf("Quality", "Target size"), if (target) 1 else 0, { vm.setTarget(it == 1) })
                if (!target) SliderRow("Quality", quality.toFloat(), 1f..100f, "$quality", { vm.setQuality(it.toInt()) })
                else {
                    ChipRow { listOf(100, 200, 500, 1000, 2000).forEach { v -> OptionChip(if (v >= 1000) "${v / 1000} MB" else "$v KB", kb == v, { vm.setTargetKb(v) }) } }
                    SliderRow("Largest file size", kb.toFloat().coerceIn(20f, 5000f), 20f..5000f, if (kb >= 1000) "%.1f MB".format(kb / 1000f) else "$kb KB", { vm.setTargetKb(it.toInt()) })
                    ToggleRow("Shrink the picture if needed", shrink, vm::setShrink, subtitle = "Only when even the lowest quality is too big.")
                }
            } else InfoNote("PNG is lossless, so Forge removes extra data and re-packs it without changing a pixel. Savings are usually small. For photos, JPG or WebP can be many times smaller.")
        }
    }
}

/** Before / after with a draggable divider: left of the line is the original, right is the compressed result. */
@Composable
fun SplitCompare(before: Bitmap, after: Bitmap, modifier: Modifier = Modifier) {
    var f by rememberSaveable { mutableFloatStateOf(0.5f) }
    val b = remember(before) { before.asImageBitmap() }
    val a = remember(after) { after.asImageBitmap() }
    val accent = ForgeTheme.colors.accent
    BoxWithConstraints(modifier.clip(RoundedCornerShape(14.dp)).semantics { contentDescription = "Before and after comparison. Drag to compare." }) {
        val wPx = with(LocalDensity.current) { maxWidth.toPx() }
        Checkerboard(Modifier.fillMaxSize())
        Image(a, "Compressed", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Image(
            b, "Original", Modifier.fillMaxSize().drawWithContent { clipRect(right = size.width * f) { this@drawWithContent.drawContent() } },
            contentScale = ContentScale.Fit,
        )
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGestures { change, _ -> change.consume(); f = (change.position.x / wPx).coerceIn(0f, 1f) }
            }.pointerInput(Unit) { detectTapGestures { f = (it.x / wPx).coerceIn(0f, 1f) } },
        )
        Box(Modifier.offset(x = maxWidth * f - 1.dp).width(2.dp).fillMaxHeight().background(Color.White))
        Box(Modifier.offset(x = maxWidth * f - 14.dp).align(Alignment.Center).width(28.dp).height(28.dp).clip(RoundedCornerShape(14.dp)).background(accent))
        Text("Original", style = ForgeType.Small, color = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
        Text("Compressed", style = ForgeType.Small, color = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
    }
}
