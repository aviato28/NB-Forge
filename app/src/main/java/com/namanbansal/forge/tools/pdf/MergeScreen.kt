package com.namanbansal.forge.tools.pdf

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.core.formatBytes
import com.namanbansal.forge.core.pagesLabel
import com.namanbansal.forge.core.pdf.PdfProtection
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.ForgeCard
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.TextAction
import com.namanbansal.forge.ui.components.ToggleRow
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.PasswordDialog
import com.namanbansal.forge.ui.toolkit.PickSpec
import com.namanbansal.forge.ui.toolkit.ReorderableColumn
import com.namanbansal.forge.ui.toolkit.ToolScaffold
import com.namanbansal.forge.ui.toolkit.rememberFilePicker

/** P1 Merge. */
@Composable
fun MergeScreen(host: ToolHost, vm: MergeViewModel = viewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    val blank by vm.blankBetween.collectAsStateWithLifecycle()
    val prompt by vm.passwordPrompt.collectAsStateWithLifecycle()
    val addFiles = rememberFilePicker(FileKind.pickerMimes(FileKind.PDF), multiple = true) { vm.addUris(it) }
    val blocker = vm.blocker(items)

    ToolScaffold(
        tool = host.tool, vm = vm, onBack = host.onBack, onOpenTool = host.onOpenTool,
        pick = PickSpec(listOf(FileKind.PDF), multiple = true, title = "Choose PDFs to merge", hint = "Pick two or more. You can add more and reorder them next."),
        actionLabel = "Merge",
        actionEnabled = blocker == null,
        actionHint = blocker,
        showInputCard = false,
        dialogs = { prompt?.let { PasswordDialog(it, onSubmit = vm::submitPassword, onCancel = vm::skipLocked) } },
    ) {
        MergeOptions(items, blank, vm, onAdd = addFiles)
    }
}

@Composable
private fun ColumnScope.MergeOptions(items: List<MergeItem>, blank: Boolean, vm: MergeViewModel, onAdd: () -> Unit) {
    val c = ForgeTheme.colors
    val ready = items.filter { it.status == MergeStatus.Ready }
    val totalPages = ready.sumOf { it.pages }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${items.size} ${if (items.size == 1) "file" else "files"}", style = ForgeType.Section, color = c.text)
            Text(
                if (ready.isEmpty()) "Drag the handle to change the order" else "${pagesLabel(totalPages)} · drag the handle to change the order",
                style = ForgeType.Small, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        TextAction("Add files", onAdd, icon = ForgeIcons.Plus)
    }
    Spacer(Modifier.height(4.dp))
    ReorderableColumn(
        items = items, key = { it.id }, modifier = Modifier.weight(1f).fillMaxWidth(),
        onMove = vm::move, onDragStart = {}, onDragEnd = vm::dragFinished,
    ) { item, index, dragging, handle ->
        MergeRow(item, index + 1, dragging, handle, onRemove = { vm.remove(item.id) })
    }
    val protection = PdfProtection.forAll(ready.mapNotNull { it.source })
    val anyLocked = ready.any { it.source?.wasEncrypted == true }
    if (anyLocked) {
        InfoNote(
            if (protection != null) "Every file uses the same password, so the merged PDF keeps it."
            else "Password-protected files are unlocked in the merged PDF. Use Protect afterwards to add a password.",
            Modifier.padding(bottom = 8.dp),
        )
    }
    ToggleRow("Add a blank page between files", blank, vm::setBlankBetween, Modifier.padding(bottom = 4.dp), subtitle = "Useful for double-sided printing")
}

@Composable
private fun MergeRow(item: MergeItem, number: Int, dragging: Boolean, handle: Modifier, onRemove: () -> Unit) {
    val c = ForgeTheme.colors
    val failed = item.status is MergeStatus.Failed
    ForgeCard(Modifier.fillMaxWidth().let { if (dragging) it.border(1.5.dp, c.accent, com.namanbansal.forge.ui.theme.ForgeShapes.Card) else it }) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                handle.size(48.dp).semantics { contentDescription = "Drag to reorder ${item.file.name}" },
                contentAlignment = Alignment.Center,
            ) { Icon(ForgeIcons.Grip, null, tint = c.text2, modifier = Modifier.size(22.dp)) }
            Cover(item)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text("$number · ${item.file.name}", style = ForgeType.Item, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val (text, color) = when (val s = item.status) {
                    MergeStatus.Opening -> "Opening…" to c.text2
                    MergeStatus.Ready -> "${pagesLabel(item.pages)} · ${formatBytes(item.file.size)}" to c.text2
                    is MergeStatus.Locked -> (if (s.wrong) "Wrong password, try again" else "Password needed") to c.accent
                    is MergeStatus.Failed -> s.reason to androidx.compose.ui.graphics.Color(0xFFE5484D)
                }
                Text(text, style = ForgeType.Small, color = color, maxLines = if (failed) 3 else 1, overflow = TextOverflow.Ellipsis)
            }
            Box(
                Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable(onClick = onRemove)
                    .semantics { contentDescription = "Remove ${item.file.name}" },
                contentAlignment = Alignment.Center,
            ) { Icon(ForgeIcons.Close, null, tint = c.text2, modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun Cover(item: MergeItem) {
    val c = ForgeTheme.colors
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.size(width = 40.dp, height = 54.dp).clip(shape).background(c.surface2).border(1.dp, c.border, shape),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = item.cover
        when {
            bmp != null -> {
                val image = remember(bmp) { bmp.asImageBitmap() }
                Image(image, null, Modifier.fillMaxWidth().height(54.dp), contentScale = ContentScale.Fit)
            }
            item.status == MergeStatus.Opening -> CircularProgressIndicator(color = c.accent, trackColor = c.surface2, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            item.status is MergeStatus.Locked -> Icon(ForgeIcons.Lock, null, tint = c.accent, modifier = Modifier.size(20.dp))
            else -> Icon(ForgeIcons.FileText, null, tint = c.text2, modifier = Modifier.size(20.dp))
        }
    }
}
