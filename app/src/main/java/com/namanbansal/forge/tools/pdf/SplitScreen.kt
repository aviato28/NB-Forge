package com.namanbansal.forge.tools.pdf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.namanbansal.forge.core.pdf.Bookmark
import com.namanbansal.forge.core.pdf.PageRanges
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.ForgeTextField
import com.namanbansal.forge.ui.components.GroupLabel
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.Segmented
import com.namanbansal.forge.ui.components.Stepper
import com.namanbansal.forge.ui.components.TintChip
import com.namanbansal.forge.ui.components.ToggleRow
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.PasswordDialog
import com.namanbansal.forge.ui.toolkit.PickSpec
import com.namanbansal.forge.ui.toolkit.ToolScaffold

/** P2 Split. */
@Composable
fun SplitScreen(host: ToolHost, vm: SplitViewModel = viewModel()) {
    val source by vm.source.collectAsStateWithLifecycle()
    val prompt by vm.passwordPrompt.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val ranges by vm.rangesText.collectAsStateWithLifecycle()
    val every by vm.everyN.collectAsStateWithLifecycle()
    val zip by vm.makeZip.collectAsStateWithLifecycle()
    val marks by vm.bookmarks.collectAsStateWithLifecycle()
    val thumbs by vm.thumbnails.collectAsStateWithLifecycle()
    val total = source?.pageCount ?: 0
    val plan = if (source != null) SplitViewModel.buildPlan(mode, ranges, every, marks, total) else null

    ToolScaffold(
        tool = host.tool, vm = vm, onBack = host.onBack, onOpenTool = host.onOpenTool,
        pick = PickSpec(listOf(FileKind.PDF), title = "Choose a PDF to split"),
        actionLabel = when (val p = plan) { is SplitPlan.Valid -> "Split into ${p.parts.size} ${if (p.parts.size == 1) "file" else "files"}"; else -> "Split" },
        actionEnabled = plan is SplitPlan.Valid,
        actionHint = (plan as? SplitPlan.Invalid)?.message?.takeIf { mode != SplitMode.Ranges },
        inputSubtitle = { files -> source?.let { "${pagesLabel(it.pageCount)} · ${formatBytes(files.firstOrNull()?.size ?: -1)}" } ?: "Opening…" },
        optionsReady = source != null && thumbs != null,
        dialogs = { prompt?.let { PasswordDialog(it, onSubmit = vm::submitPassword, onCancel = vm::cancelPassword) } },
    ) {
        SplitOptions(vm, mode, ranges, every, zip, marks, total, plan, source?.protection?.note)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.SplitOptions(
    vm: SplitViewModel, mode: SplitMode, ranges: String, every: Int, zip: Boolean, marks: List<Bookmark>?,
    total: Int, plan: SplitPlan?, protectionNote: String?,
) {
    val c = ForgeTheme.colors
    val noBookmarks = marks != null && marks.isEmpty()
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Text("Split by", style = ForgeType.Small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = c.text2, modifier = Modifier.padding(bottom = 8.dp))
        Segmented(
            SplitMode.entries.map { it.label }, mode.ordinal, { vm.setMode(SplitMode.entries[it]) },
            disabled = if (noBookmarks) setOf(SplitMode.Bookmarks.ordinal) else emptySet(),
        )
        if (noBookmarks) Text("Bookmarks is unavailable: this PDF has no bookmarks (outline) to split by.", style = ForgeType.Small, color = c.text2, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(16.dp))
        when (mode) {
            SplitMode.Ranges -> {
                val error = (plan as? SplitPlan.Invalid)?.message?.takeIf { ranges.isNotBlank() }
                ForgeTextField(
                    ranges, vm::setRanges, placeholder = "1-3, 5, 8-10", label = "Pages for each file", error = error,
                    helper = "Each comma-separated range becomes its own file. This PDF has ${pagesLabel(total)}.",
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
                )
            }
            SplitMode.EveryN -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Pages per file", style = ForgeType.BodyMedium, color = c.text, modifier = Modifier.weight(1f))
                Stepper(every.coerceIn(1, total.coerceAtLeast(1)), 1..total.coerceAtLeast(1), vm::setEveryN, "Pages per file")
            }
            SplitMode.Single -> Text("Every page becomes its own PDF.", style = ForgeType.Body, color = c.text2)
            SplitMode.Bookmarks -> if (marks == null) Text("Reading bookmarks…", style = ForgeType.Body, color = c.text2)
            else Text("One file for each top-level bookmark, from its page to the next bookmark.", style = ForgeType.Body, color = c.text2)
        }
        if (plan is SplitPlan.Valid) {
            GroupLabel("Preview · ${plan.parts.size} ${if (plan.parts.size == 1) "file" else "files"}")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                plan.parts.take(MAX_CHIPS).forEach { part ->
                    val n = part.pages.size
                    TintChip("${chipTitle(mode, part.label, part.summary)} · ${if (n == 1) "1 page" else "$n pages"}")
                }
                if (plan.parts.size > MAX_CHIPS) TintChip("+ ${plan.parts.size - MAX_CHIPS} more")
            }
            if (plan.parts.size > 100) InfoNote("That is a lot of files. They are all saved to Downloads/Forge; the ZIP option keeps them tidy.", Modifier.padding(top = 12.dp))
        }
        Spacer(Modifier.height(16.dp))
        ToggleRow("Also save a ZIP", zip, vm::setMakeZip, subtitle = "One archive with all the files, handy for sharing")
        if (protectionNote != null) InfoNote(protectionNote, Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(8.dp))
    }
}

private const val MAX_CHIPS = 24

/** Chip text: the page range for range-like modes, the bookmark title for bookmark parts. */
private fun chipTitle(mode: SplitMode, label: String, summary: String): String =
    if (mode == SplitMode.Bookmarks) label.substringAfter('_').let { if (label.startsWith("0_start")) "Start" else it } + " (p" + summary + ")" else "p$summary"
