package com.namanbansal.forge.tools.pdf

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.core.formatBytes
import com.namanbansal.forge.core.pagesLabel
import com.namanbansal.forge.core.pdf.PdfThumbnails
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.PasswordDialog
import com.namanbansal.forge.ui.toolkit.PickSpec
import com.namanbansal.forge.ui.toolkit.ToolScaffold
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** P4 Rotate: the reference tool built on [ToolScaffold]. */
@Composable
fun RotateScreen(host: ToolHost, vm: RotateViewModel = viewModel()) {
    val source by vm.source.collectAsStateWithLifecycle()
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val prompt by vm.passwordPrompt.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val rotations by vm.rotations.collectAsStateWithLifecycle()
    val thumbs by vm.thumbnails.collectAsStateWithLifecycle()
    val changed = vm.changedCount(rotations)

    ToolScaffold(
        tool = host.tool,
        vm = vm,
        pick = PickSpec(listOf(FileKind.PDF), title = "Choose a PDF to rotate"),
        actionLabel = "Save",
        onBack = host.onBack,
        onOpenTool = host.onOpenTool,
        actionEnabled = changed > 0,
        actionHint = if (changed == 0) "Rotate at least one page to save" else null,
        inputSubtitle = { files ->
            source?.let { "${pagesLabel(it.pageCount)} · ${formatBytes(files.firstOrNull()?.size ?: -1)}" } ?: "Opening…"
        },
        optionsReady = source != null && thumbs != null,
        dialogs = {
            prompt?.let { PasswordDialog(it, onSubmit = vm::submitPassword, onCancel = vm::cancelPassword) }
        },
    ) {
        val t = thumbs
        val s = source
        if (t != null && s != null) {
            RotateOptions(
                pageCount = s.pageCount,
                thumbnails = t,
                selected = selected,
                rotations = rotations,
                encryptedNote = s.wasEncrypted,
                onToggle = vm::togglePage,
                onAll = vm::selectAll,
                onNone = vm::selectNone,
                onRotate = vm::rotateSelected,
                onReset = vm::resetRotations,
                hasInputs = inputs.isNotEmpty(),
            )
        }
    }
}

@Composable
private fun ColumnScope.RotateOptions(
    pageCount: Int,
    thumbnails: PdfThumbnails,
    selected: Set<Int>,
    rotations: Map<Int, Int>,
    encryptedNote: Boolean,
    onToggle: (Int) -> Unit,
    onAll: () -> Unit,
    onNone: () -> Unit,
    onRotate: (Int) -> Unit,
    onReset: () -> Unit,
    hasInputs: Boolean,
) {
    val c = ForgeTheme.colors
    val allSelected = selected.size == pageCount
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${selected.size} of $pageCount selected",
            style = ForgeType.Small, color = c.text2, modifier = Modifier.weight(1f),
        )
        SelectChip(if (allSelected) "None" else "All", onClick = if (allSelected) onNone else onAll)
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.weight(1f).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        items((0 until pageCount).toList(), key = { it }) { index ->
            PageCell(index, thumbnails, selected = index in selected, rotation = rotations[index] ?: 0, onClick = { onToggle(index) })
        }
    }
    if (encryptedNote) {
        Text("The saved copy won't have the password.", style = ForgeType.Small, color = c.text2, modifier = Modifier.padding(vertical = 4.dp))
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(20.dp)).padding(vertical = 4.dp),
    ) {
        val enabled = selected.isNotEmpty() && hasInputs
        RotateAction(ForgeIcons.RotateLeft, "Left 90°", "Rotate selected pages left", enabled, Modifier.weight(1f)) { onRotate(-90) }
        RotateAction(ForgeIcons.Rotate, "Right 90°", "Rotate selected pages right", enabled, Modifier.weight(1f)) { onRotate(90) }
        RotateAction(ForgeIcons.Rotate180, "180°", "Rotate selected pages 180 degrees", enabled, Modifier.weight(1f)) { onRotate(180) }
        RotateAction(ForgeIcons.Refresh, "Reset", "Undo all rotations", rotations.isNotEmpty(), Modifier.weight(1f)) { onReset() }
    }
}

@Composable
private fun SelectChip(text: String, onClick: () -> Unit) {
    val c = ForgeTheme.colors
    Box(Modifier.heightIn(min = 48.dp).clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(text, style = ForgeType.Label, color = c.accent)
    }
}

@Composable
private fun RotateAction(icon: ImageVector, label: String, description: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = ForgeTheme.colors
    val tint = if (enabled) c.text else c.text2.copy(alpha = 0.4f)
    Column(
        modifier.heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { contentDescription = description }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, style = ForgeType.Tiny, color = tint)
    }
}

/** One page tile: thumbnail (rotated live), selection ring + check, page number. */
@Composable
private fun PageCell(index: Int, thumbnails: PdfThumbnails, selected: Boolean, rotation: Int, onClick: () -> Unit) {
    val c = ForgeTheme.colors
    val bitmap by produceState(initialValue = thumbnails.peek(index), index, thumbnails) {
        if (value == null) value = thumbnails.get(index)
    }
    val angle by animateFloatAsState(rotation.toFloat(), tween(220), label = "pageRotation")
    val turns = ((rotation % 360) + 360) % 360
    Column(
        Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).semantics {
            contentDescription = "Page ${index + 1}" + if (turns != 0) ", rotated $turns degrees" else ""
            stateDescription = if (selected) "Selected" else "Not selected"
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(8.dp)).background(c.surface2)
                .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.border, RoundedCornerShape(8.dp)),
        ) {
            val bmp = bitmap
            if (bmp != null) {
                val image = remember(bmp) { bmp.asImageBitmap() }
                Canvas(Modifier.fillMaxSize().padding(3.dp)) {
                    val rad = Math.toRadians(angle.toDouble())
                    val ac = abs(cos(rad)).toFloat()
                    val asn = abs(sin(rad)).toFloat()
                    val bw = image.width.toFloat()
                    val bh = image.height.toFloat()
                    val fit = min(size.width / (bw * ac + bh * asn), size.height / (bw * asn + bh * ac))
                    withTransform({
                        translate(size.width / 2f, size.height / 2f)
                        rotate(angle, Offset.Zero)
                        scale(fit, fit, Offset.Zero)
                    }) {
                        drawImage(image, topLeft = Offset(-bw / 2f, -bh / 2f))
                    }
                }
            }
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(5.dp).size(20.dp).clip(CircleShape).background(c.accent),
                    contentAlignment = Alignment.Center,
                ) { Icon(ForgeIcons.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
            }
        }
        Text("${index + 1}", style = ForgeType.Tiny, color = c.text2)
    }
}
