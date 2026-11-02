package com.namanbansal.forge.tools.pdf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.core.formatBytes
import com.namanbansal.forge.core.pagesLabel
import com.namanbansal.forge.core.pdf.PageRanges
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.ForgeTextField
import com.namanbansal.forge.ui.components.GroupLabel
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.Segmented
import com.namanbansal.forge.ui.components.TextAction
import com.namanbansal.forge.ui.components.TintChip
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.PageSelectGrid
import com.namanbansal.forge.ui.toolkit.PasswordDialog
import com.namanbansal.forge.ui.toolkit.PickSpec
import com.namanbansal.forge.ui.toolkit.ToolScaffold

/** P5 Extract pages: pick pages on the grid or type ranges, save them as a new PDF. */
@Composable
fun ExtractScreen(host: ToolHost, vm: ExtractViewModel = viewModel()) {
    val source by vm.source.collectAsStateWithLifecycle()
    val prompt by vm.passwordPrompt.collectAsStateWithLifecycle()
    val thumbs by vm.thumbnails.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val text by vm.rangeText.collectAsStateWithLifecycle()
    val view by vm.view.collectAsStateWithLifecycle()
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
            Segmented(listOf("Pick pages", "Page ranges"), view, vm::setView)
            Spacer(Modifier.height(12.dp))
            if (view == 0) {
                val all = selected.size == total
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${selected.size} of $total selected", style = ForgeType.Small, color = ForgeTheme.colors.text2, modifier = Modifier.weight(1f))
                    TextAction(if (all) "None" else "All", if (all) vm::selectNone else vm::selectAll)
                }
                PageSelectGrid(total, t, selected, vm::toggle, Modifier.weight(1f))
            } else {
                RangeEntry(vm, text, selected, total)
            }
            source?.protection?.let { InfoNote(it.note, Modifier.padding(vertical = 8.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.RangeEntry(vm: ExtractViewModel, text: String, selected: Set<Int>, total: Int) {
    Column2(Modifier.weight(1f)) {
        ForgeTextField(
            text, vm::setText, placeholder = "1-3, 5, 8-10", label = "Pages to extract", error = vm.textError(text),
            helper = "This PDF has ${pagesLabel(total)}. Pages keep their original order.",
        )
        if (selected.isNotEmpty()) {
            GroupLabel("Selected · ${pagesLabel(selected.size)}")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PageRanges.format(selected).split(", ").take(30).forEach { TintChip("p$it") }
            }
        }
    }
}

@Composable
private fun Column2(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) =
    androidx.compose.foundation.layout.Column(modifier.verticalScroll(rememberScrollState()), content = content)
