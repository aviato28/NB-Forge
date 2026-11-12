package com.namanbansal.forge.tools.pdf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.namanbansal.forge.ui.toolkit.GridPage
import com.namanbansal.forge.ui.toolkit.PagePeek
import com.namanbansal.forge.ui.toolkit.SelectionBar
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.core.formatBytes
import com.namanbansal.forge.core.pagesLabel
import com.namanbansal.forge.core.pdf.EditorState
import com.namanbansal.forge.core.pdf.PdfThumbnails
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.PageThumb
import com.namanbansal.forge.ui.toolkit.PasswordDialog
import com.namanbansal.forge.ui.toolkit.PickSpec
import com.namanbansal.forge.ui.toolkit.ReorderableGrid
import com.namanbansal.forge.ui.toolkit.ToolScaffold

private val Danger = Color(0xFFE5484D)

/** P3 Page manager (mockup 05b): thumbnail grid, multi-select, drag to reorder, rotate / delete / duplicate / extract. */
@Composable
fun PageManagerScreen(host: ToolHost, vm: PageManagerViewModel = viewModel()) {
    val source by vm.source.collectAsStateWithLifecycle()
    val prompt by vm.passwordPrompt.collectAsStateWithLifecycle()
    val thumbs by vm.thumbnails.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()

    ToolScaffold(
        tool = host.tool, vm = vm, onBack = host.onBack, onOpenTool = host.onOpenTool,
        pick = PickSpec(listOf(FileKind.PDF), title = "Choose a PDF to organise"),
        actionLabel = "Save PDF",
        actionEnabled = state.dirty,
        actionHint = if (!state.dirty) "Make a change to save" else null,
        inputSubtitle = { files -> source?.let { "${pagesLabel(it.pageCount)} · ${formatBytes(files.firstOrNull()?.size ?: -1)}" } ?: "Opening…" },
        optionsReady = source != null && thumbs != null && state.pages.isNotEmpty(),
        dialogs = { prompt?.let { PasswordDialog(it, onSubmit = vm::submitPassword, onCancel = vm::cancelPassword) } },
    ) {
        val t = thumbs
        if (t != null) PageManagerOptions(vm, state, t, source?.protection?.note)
    }
}

@Composable
private fun ColumnScope.PageManagerOptions(vm: PageManagerViewModel, state: EditorState, thumbs: PdfThumbnails, protectionNote: String?) {
    val c = ForgeTheme.colors
    val selectedCount = state.selected.size
    var peek by rememberSaveable { mutableIntStateOf(-1) }
    var shareChoice by rememberSaveable { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val snack = com.namanbansal.forge.ui.toolkit.rememberSnack()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(pagesLabel(state.pages.size), style = ForgeType.Small, color = c.text2, modifier = Modifier.weight(1f))
        IconAction(ForgeIcons.Undo, "Undo", state.canUndo, vm::undo)
        IconAction(ForgeIcons.Redo, "Redo", state.canRedo, vm::redo)
    }
    SelectionBar(selectedCount, state.pages.size, vm::selectAll, vm::selectNone, vm::invertSelection, Modifier.padding(bottom = 4.dp))
    ReorderableGrid(
        items = state.pages, key = { it.id }, columns = 3,
        onMove = { from, to -> vm.dragMove(from, to) }, onDragStart = vm::dragStart, onDragEnd = vm::dragEnd,
        modifier = Modifier.weight(1f),
    ) { page, index, dragging ->
        PageThumb(
            sourcePage = page.source, label = "${index + 1}", thumbnails = thumbs, selected = page.id in state.selected,
            rotation = page.rotation, onClick = { vm.toggle(page.id) },
            description = "Page ${index + 1}, from original page ${page.source + 1}",
        )
    }
    if (protectionNote != null) InfoNote(protectionNote, Modifier.padding(bottom = 8.dp))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(20.dp)).padding(vertical = 4.dp),
    ) {
        val has = selectedCount > 0
        ToolbarAction(ForgeIcons.Rotate, "Rotate", "Rotate selected pages right", has, Modifier.weight(1f), onClick = vm::rotateSelected)
        ToolbarAction(ForgeIcons.Trash, "Delete", "Delete selected pages", has && selectedCount < state.pages.size, Modifier.weight(1f), danger = true, onClick = vm::deleteSelected)
        ToolbarAction(ForgeIcons.Copy, "Duplicate", "Duplicate selected pages", has, Modifier.weight(1f), onClick = vm::duplicateSelected)
        ToolbarAction(ForgeIcons.Extract, "Extract", "Save selected pages as a new PDF", has, Modifier.weight(1f), onClick = vm::extractSelected)
        ToolbarAction(ForgeIcons.Share, "Share", "Share the selected pages", has, Modifier.weight(1f), onClick = { shareChoice = true })
        ToolbarAction(ForgeIcons.Eye, "Preview", "Preview the selected pages full screen", has, Modifier.weight(1f), onClick = {
            peek = state.pages.indexOfFirst { it.id in state.selected }.coerceAtLeast(0)
        })
    }
    if (shareChoice) {
        fun share(asImage: Boolean) {
            shareChoice = false
            scope.launch {
                val p = vm.stageSelected(asImage) ?: return@launch
                val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", p.file)
                if (!com.namanbansal.forge.core.FileActions.share(context, uri, p.mime, p.name)) snack("No app available to share this.")
            }
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { shareChoice = false }, containerColor = c.surface,
            title = { Text("Share ${if (selectedCount == 1) "this page" else "$selectedCount pages"}", style = ForgeType.Section, color = c.text) },
            text = { Text("Pages are shared as they look here, with your turns and order.", style = ForgeType.Body, color = c.text2) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { share(false) }) { Text("As PDF", color = c.accent) } },
            dismissButton = {
                Row {
                    androidx.compose.material3.TextButton(onClick = { shareChoice = false }) { Text("Cancel", color = c.text2) }
                    if (selectedCount == 1) androidx.compose.material3.TextButton(onClick = { share(true) }) { Text("As image", color = c.text2) }
                }
            },
        )
    }
    if (peek in state.pages.indices) {
        val pages = remember(state.pages, thumbs) {
            state.pages.mapIndexed { i, p -> GridPage("m${p.id}", thumbs, p.source, "${i + 1}", rotation = p.rotation, description = "Page ${i + 1}, from original page ${p.source + 1}") }
        }
        PagePeek(pages, peek, onIndexChange = { peek = it }, onClose = { peek = -1 })
    }
}

@Composable
private fun IconAction(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val c = ForgeTheme.colors
    Box(
        Modifier.size(48.dp).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (enabled) c.text else c.text2.copy(alpha = 0.4f), modifier = Modifier.size(20.dp)) }
}

@Composable
private fun ToolbarAction(icon: ImageVector, label: String, description: String, enabled: Boolean, modifier: Modifier, danger: Boolean = false, onClick: () -> Unit) {
    val c = ForgeTheme.colors
    val tint = when {
        !enabled -> c.text2.copy(alpha = 0.4f)
        danger -> Danger
        else -> c.text
    }
    Column(
        modifier.heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp)).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { contentDescription = description }.padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, style = ForgeType.Tiny, color = tint)
    }
}
