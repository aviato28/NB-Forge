package com.namanbansal.forge.tools.pdf

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.core.formatBytes
import com.namanbansal.forge.core.pagesLabel
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.toolkit.PagePicker
import com.namanbansal.forge.ui.toolkit.PasswordDialog
import com.namanbansal.forge.ui.toolkit.PickSpec
import com.namanbansal.forge.ui.toolkit.ToolScaffold

/** P5 Extract pages: pick pages on the grid (tap, drag across, long-press to peek); the range field is the secondary way in. */
@Composable
fun ExtractScreen(host: ToolHost, vm: ExtractViewModel = viewModel()) {
    val source by vm.source.collectAsStateWithLifecycle()
    val prompt by vm.passwordPrompt.collectAsStateWithLifecycle()
    val thumbs by vm.thumbnails.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val text by vm.rangeText.collectAsStateWithLifecycle()
    val total = source?.pageCount ?: 0

    ToolScaffold(
        tool = host.tool, vm = vm, onBack = host.onBack, onOpenTool = host.onOpenTool,
        pick = PickSpec(listOf(FileKind.PDF), title = "Choose a PDF to extract pages from"),
        actionLabel = if (selected.isEmpty()) "Extract" else "Extract ${pagesLabel(selected.size)}",
        actionEnabled = selected.isNotEmpty(),
        actionHint = if (selected.isEmpty()) "Select at least one page" else null,
        inputSubtitle = { files -> source?.let { "${pagesLabel(it.pageCount)} · ${formatBytes(files.firstOrNull()?.size ?: -1)}" } ?: "Opening…" },
        optionsReady = source != null && thumbs != null,
        dialogs = { prompt?.let { PasswordDialog(it, onSubmit = vm::submitPassword, onCancel = vm::cancelPassword) } },
    ) {
        val t = thumbs
        if (t != null) {
            PagePicker(
                total, t, selected, vm::setSelection, Modifier.weight(1f),
                rangeText = text, onRangeTextChange = vm::setText,
            )
            source?.protection?.let { InfoNote(it.note, Modifier.padding(vertical = 8.dp)) }
        }
    }
}
