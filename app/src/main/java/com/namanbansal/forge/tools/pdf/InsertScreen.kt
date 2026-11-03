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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.core.formatBytes
import com.namanbansal.forge.core.pagesLabel
import com.namanbansal.forge.core.pdf.BlankSize
import com.namanbansal.forge.core.pdf.InsertPreview
import com.namanbansal.forge.core.pdf.PdfThumbnails
import com.namanbansal.forge.core.pdf.PreviewKind
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.ForgeButton
import com.namanbansal.forge.ui.components.ForgeCard
import com.namanbansal.forge.ui.components.IconBox
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.OptionChip
import com.namanbansal.forge.ui.components.Segmented
import com.namanbansal.forge.ui.components.Stepper
import com.namanbansal.forge.ui.components.TextAction
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.GridGaps
import com.namanbansal.forge.ui.toolkit.GridPage
import com.namanbansal.forge.ui.toolkit.PagePicker
import com.namanbansal.forge.ui.toolkit.PagePickerGrid
import com.namanbansal.forge.ui.toolkit.PasswordDialog
import com.namanbansal.forge.ui.toolkit.PickSpec
import com.namanbansal.forge.ui.toolkit.ToolScaffold
import com.namanbansal.forge.ui.toolkit.fullWidthItem
import com.namanbansal.forge.ui.toolkit.groupColor
import com.namanbansal.forge.ui.toolkit.rememberFilePicker

/** P6 Insert pages: tap a gap in the document's page grid to choose the place, with the new pages previewed in place. */
@Composable
fun InsertScreen(host: ToolHost, vm: InsertViewModel = viewModel()) {
    val source by vm.source.collectAsStateWithLifecycle()
    val prompt by vm.passwordPrompt.collectAsStateWithLifecycle()
    val otherPrompt by vm.otherPrompt.collectAsStateWithLifecycle()
    val thumbs by vm.thumbnails.collectAsStateWithLifecycle()
    val fromPdf by vm.fromPdf.collectAsStateWithLifecycle()
    val count by vm.blankCount.collectAsStateWithLifecycle()
    val size by vm.blankSize.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()
    val page by vm.positionPage.collectAsStateWithLifecycle()
    val range by vm.otherRange.collectAsStateWithLifecycle()
    val otherSel by vm.otherSelection.collectAsStateWithLifecycle()
    val step by vm.step.collectAsStateWithLifecycle()
    val other by vm.other.collectAsStateWithLifecycle()
    val otherLoading by vm.otherLoading.collectAsStateWithLifecycle()
    val pickOther = rememberFilePicker(FileKind.pickerMimes(FileKind.PDF), multiple = false) { it.firstOrNull()?.let { u -> vm.pickOther(u) } }
    val total = source?.pageCount ?: 0
    val gap = remember(position, page, total) { vm.insertGap() }
    val otherPages = remember(fromPdf, other, otherSel, range) { vm.otherPages() }
    val blocker = if (fromPdf) otherPages.exceptionOrNull()?.message else null
    val adding = if (fromPdf) otherPages.getOrNull()?.size ?: 0 else count

    ToolScaffold(
        tool = host.tool, vm = vm, onBack = host.onBack, onOpenTool = host.onOpenTool,
        pick = PickSpec(listOf(FileKind.PDF), title = "Choose the PDF to add pages to"),
        actionLabel = if (adding > 0 && blocker == null) "Insert ${pagesLabel(adding)}" else "Insert",
        actionEnabled = blocker == null && adding > 0,
        actionHint = if (fromPdf && other == null) "Choose the PDF to take pages from" else null,
        inputSubtitle = { files -> source?.let { "${pagesLabel(it.pageCount)} · ${formatBytes(files.firstOrNull()?.size ?: -1)}" } ?: "Opening…" },
        optionsReady = source != null && thumbs != null,
        dialogs = {
            prompt?.let { PasswordDialog(it, onSubmit = vm::submitPassword, onCancel = vm::cancelPassword) }
                ?: otherPrompt?.let { PasswordDialog(it, onSubmit = vm::submitOtherPassword, onCancel = vm::cancelOtherPassword) }
        },
    ) {
        val t = thumbs
        if (t != null) {
            val head: @Composable () -> Unit = {
                InsertHeader(vm, fromPdf, count, size, step, other, otherLoading, pickOther, gap, total, adding, blocker, source?.protection?.note)
            }
            val o = other
            if (fromPdf && o != null && step == 0) {
                // Step 1 of "from another PDF": choose which of its pages to insert.
                val ot = o.thumbnails
                if (ot != null) {
                    PagePicker(
                        o.source.pageCount, ot, otherSel, vm::setOtherSelection, Modifier.weight(1f),
                        rangeText = range, onRangeTextChange = vm::setOtherRange, selectedNoun = "will be inserted", header = head,
                    )
                } else Column(Modifier.weight(1f)) { head(); InfoNote("This PDF's pages can't be previewed on your device, but you can still type the pages to insert.") }
            } else {
                InsertGrid(vm, t, total, fromPdf, count, other, otherPages.getOrDefault(emptyList()), gap, head)
            }
        }
    }
}

@Composable
private fun ColumnScope.InsertGrid(
    vm: InsertViewModel, thumbs: PdfThumbnails, total: Int, fromPdf: Boolean, blankCount: Int, other: InsertSource?, otherPages: List<Int>, gap: Int,
    head: @Composable () -> Unit,
) {
    val c = ForgeTheme.colors
    val inserted = remember(fromPdf, other, otherPages, blankCount) {
        if (fromPdf) (if (other != null) InsertPreview.otherEntries(otherPages) else emptyList()) else InsertPreview.blankEntries(minOf(blankCount, MAX_BLANK_PREVIEW))
    }
    val green = groupColor(1, c.isDark)
    val pages = remember(thumbs, total, gap, inserted, other, blankCount, c.isDark) {
        var k = 0
        InsertPreview.entries(total, gap, inserted).map { e ->
            when (e.kind) {
                PreviewKind.Base -> GridPage("b${e.page}", thumbs, e.page, "${e.page + 1}", gapBefore = e.gapBefore, gapAfter = e.gapAfter)
                PreviewKind.Other -> {
                    val n = k++
                    GridPage(
                        "o$n", other?.thumbnails, e.page, "new ${n + 1}", tint = green, badge = if (n == 0) "New" else null, gapBefore = e.gapBefore, gapAfter = e.gapAfter,
                        description = "Inserted page ${e.page + 1} of ${other?.file?.name ?: "the second PDF"}",
                    )
                }
                PreviewKind.Blank -> {
                    val n = k++
                    GridPage(
                        "x$n", null, 0, "blank", tint = green, badge = if (n == 0) (if (blankCount > MAX_BLANK_PREVIEW) "$blankCount blank" else "Blank") else null,
                        gapBefore = e.gapBefore, gapAfter = e.gapAfter, description = "Inserted blank page",
                    )
                }
            }
        }
    }
    val gaps = remember(gap, total, c.accent) {
        GridGaps(
            isActive = { it == gap }, onToggle = vm::setInsertGap, icon = ForgeIcons.Plus, activeColor = c.accent,
            describe = { id, active -> (if (active) "Insert here: " else "Insert ") + gapWhere(id, total) },
        )
    }
    PagePickerGrid(pages, Modifier.weight(1f), gaps = gaps, header = { fullWidthItem("insert-header") { head() } })
}

private const val MAX_BLANK_PREVIEW = 4

private fun gapWhere(gap: Int, total: Int) = when {
    gap <= 0 -> "at the start"
    gap >= total -> "at the end"
    else -> "between page $gap and page ${gap + 1}"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InsertHeader(
    vm: InsertViewModel, fromPdf: Boolean, count: Int, size: BlankSize, step: Int, other: InsertSource?, otherLoading: Boolean,
    pickOther: () -> Unit, gap: Int, total: Int, adding: Int, blocker: String?, protectionNote: String?,
) {
    val c = ForgeTheme.colors
    Column(Modifier.padding(bottom = 12.dp)) {
        Segmented(listOf("Blank pages", "From another PDF"), if (fromPdf) 1 else 0, { vm.setFromPdf(it == 1) })
        Spacer(Modifier.height(12.dp))
        if (!fromPdf) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Number of pages", style = ForgeType.BodyMedium, color = c.text, modifier = Modifier.weight(1f))
                Stepper(count, 1..InsertViewModel.MAX_BLANK, vm::setBlankCount, "Number of blank pages")
            }
            Text("Page size", style = ForgeType.Small.copy(fontWeight = FontWeight.SemiBold), color = c.text2, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BlankSize.entries.forEach { OptionChip(it.label, it == size, { vm.setBlankSize(it) }) }
            }
        } else if (other == null) {
            ForgeButton(if (otherLoading) "Opening…" else "Choose PDF to take pages from", onClick = pickOther, icon = ForgeIcons.Folder, secondary = true, enabled = !otherLoading, modifier = Modifier.fillMaxWidth())
        } else {
            ForgeCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconBox(ForgeIcons.FileText)
                    Column(Modifier.weight(1f)) {
                        Text(other.file.name, style = ForgeType.Item, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${pagesLabel(other.source.pageCount)} · ${formatBytes(other.file.size)}", style = ForgeType.Small, color = c.text2)
                    }
                    TextAction("Change", pickOther)
                }
            }
            Spacer(Modifier.height(12.dp))
            Segmented(listOf("1 · Choose pages", "2 · Choose place"), step, vm::setStep)
        }
        if (blocker != null && fromPdf && other != null) {
            Text(blocker, style = ForgeType.Small, color = c.accent, modifier = Modifier.padding(top = 8.dp))
        }
        val showsPlace = !fromPdf || (other != null && step == 1)
        if (showsPlace) {
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Insert at", style = ForgeType.Small.copy(fontWeight = FontWeight.SemiBold), color = c.text2)
                OptionChip("Start", gap == 0, { vm.setInsertGap(0) })
                OptionChip("End", gap == total, { vm.setInsertGap(total) })
            }
            if (adding > 0 && blocker == null) {
                val where = when {
                    gap <= 0 -> "at the start"
                    gap >= total -> "at the end"
                    else -> "before page ${gap + 1}"
                }
                Text(
                    "Adds ${pagesLabel(adding)} $where. The new PDF will have ${pagesLabel(total + adding)}.",
                    style = ForgeType.Small, color = c.text2, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        if (protectionNote != null) InfoNote(protectionNote, Modifier.padding(top = 12.dp))
    }
}
