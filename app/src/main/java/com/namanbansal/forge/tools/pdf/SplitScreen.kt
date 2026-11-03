package com.namanbansal.forge.tools.pdf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.namanbansal.forge.core.pdf.PdfThumbnails
import com.namanbansal.forge.core.pdf.SplitGroups
import com.namanbansal.forge.core.pdf.SplitPart
import com.namanbansal.forge.ui.components.TextAction
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeShapes
import com.namanbansal.forge.ui.toolkit.GridGaps
import com.namanbansal.forge.ui.toolkit.GridPage
import com.namanbansal.forge.ui.toolkit.PagePickerGrid
import com.namanbansal.forge.ui.toolkit.fullWidthItem
import com.namanbansal.forge.ui.toolkit.groupColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.core.formatBytes
import com.namanbansal.forge.core.pagesLabel
import com.namanbansal.forge.core.pdf.Bookmark
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.ForgeTextField
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

/** P2 Split: every mode shows its result on the same colour-coded page grid; the default "Cut" mode is edited right on it. */
@Composable
fun SplitScreen(host: ToolHost, vm: SplitViewModel = viewModel()) {
    val source by vm.source.collectAsStateWithLifecycle()
    val prompt by vm.passwordPrompt.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val ranges by vm.rangesText.collectAsStateWithLifecycle()
    val cuts by vm.cuts.collectAsStateWithLifecycle()
    val every by vm.everyN.collectAsStateWithLifecycle()
    val zip by vm.makeZip.collectAsStateWithLifecycle()
    val marks by vm.bookmarks.collectAsStateWithLifecycle()
    val thumbs by vm.thumbnails.collectAsStateWithLifecycle()
    val total = source?.pageCount ?: 0
    val plan = remember(source, mode, cuts, ranges, every, marks) {
        if (source != null) SplitViewModel.buildPlan(mode, cuts, ranges, every, marks, total) else null
    }

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
        val t = thumbs
        if (t != null) SplitOptions(vm, t, mode, ranges, every, zip, cuts, marks, total, plan, source?.protection?.note)
    }
}

@Composable
private fun ColumnScope.SplitOptions(
    vm: SplitViewModel, thumbs: PdfThumbnails, mode: SplitMode, ranges: String, every: Int, zip: Boolean, cuts: Set<Int>,
    marks: List<Bookmark>?, total: Int, plan: SplitPlan?, protectionNote: String?,
) {
    val c = ForgeTheme.colors
    val noBookmarks = marks != null && marks.isEmpty()
    val parts = (plan as? SplitPlan.Valid)?.parts
    val pages = remember(thumbs, total, parts, mode, c.isDark) {
        val owners = parts?.let { SplitGroups.owners(it, total) }
        List(total) { i ->
            val o = owners?.get(i) ?: -1
            GridPage(
                key = "p$i", thumbs = thumbs, page = i, label = "${i + 1}",
                tint = if (o >= 0) groupColor(o, c.isDark) else null,
                badge = if (o >= 0 && parts?.get(o)?.pages?.firstOrNull() == i) "File ${o + 1}" else null,
                dimmed = parts != null && o < 0,
                gapAfter = if (mode == SplitMode.Cut && i < total - 1) i + 1 else null,
            )
        }
    }
    val gaps = if (mode == SplitMode.Cut) remember(cuts, c.accent) {
        GridGaps(
            isActive = { it in cuts }, onToggle = vm::toggleCut, icon = ForgeIcons.Scissors, activeColor = c.accent,
            describe = { id, active -> if (active) "Cut between page $id and page ${id + 1}. Tap to join them again" else "Tap to cut between page $id and page ${id + 1}" },
        )
    } else null
    PagePickerGrid(
        pages, Modifier.weight(1f), gaps = gaps,
        header = {
            fullWidthItem("split-header") {
                Column {
                    Segmented(
                        SplitMode.entries.map { it.label }, mode.ordinal, { vm.setMode(SplitMode.entries[it]) },
                        disabled = if (noBookmarks) setOf(SplitMode.Bookmarks.ordinal) else emptySet(),
                    )
                    if (noBookmarks) Text("Bookmarks is unavailable: this PDF has no bookmarks (outline) to split by.", style = ForgeType.Small, color = c.text2, modifier = Modifier.padding(top = 8.dp))
                    Spacer(Modifier.height(12.dp))
                    when (mode) {
                        SplitMode.Cut -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Tap the gap between two pages to cut there. Long-press a page to preview it.", style = ForgeType.Small, color = c.text2, modifier = Modifier.weight(1f))
                            if (cuts.isNotEmpty()) TextAction("Clear", vm::clearCuts)
                        }
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
                    if (parts != null) {
                        Spacer(Modifier.height(12.dp))
                        Legend(parts, mode, c.isDark)
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        },
        footer = {
            fullWidthItem("split-footer") {
                Column {
                    if (parts != null && parts.size > 100) InfoNote("That is a lot of files. They are all saved to Downloads/Forge; the ZIP option keeps them tidy.", Modifier.padding(top = 4.dp, bottom = 8.dp))
                    ToggleRow("Also save a ZIP", zip, vm::setMakeZip, subtitle = "One archive with all the files, handy for sharing")
                    if (protectionNote != null) InfoNote(protectionNote, Modifier.padding(top = 8.dp))
                    Spacer(Modifier.height(8.dp))
                }
            }
        },
    )
}

/** One chip per resulting file, in the colour its pages have on the grid. */
@Composable
private fun Legend(parts: List<SplitPart>, mode: SplitMode, dark: Boolean) {
    val c = ForgeTheme.colors
    Column {
        Text("${parts.size} ${if (parts.size == 1) "file" else "files"}", style = ForgeType.Small.copy(fontWeight = FontWeight.SemiBold), color = c.text2, modifier = Modifier.padding(bottom = 6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            parts.take(MAX_CHIPS).forEachIndexed { i, part ->
                val color = groupColor(i, dark)
                val title = SplitGroups.title(i, part).let { t ->
                    if (mode == SplitMode.Bookmarks) t.replaceFirst(" · ", " · " + bookmarkTitle(part.label) + " · ") else t
                }
                Row(
                    Modifier.clip(ForgeShapes.Chip).background(color.copy(alpha = if (dark) 0.22f else 0.14f)).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                    Text(title, style = ForgeType.Label, color = c.text, maxLines = 1)
                }
            }
            if (parts.size > MAX_CHIPS) TintChip("+ ${parts.size - MAX_CHIPS} more")
        }
    }
}

private const val MAX_CHIPS = 24

/** Bookmark parts are named `3_Chapter title`; the leading part before the first bookmark is `0_start`. */
private fun bookmarkTitle(label: String): String = if (label.startsWith("0_start")) "Start" else label.substringAfter('_')
