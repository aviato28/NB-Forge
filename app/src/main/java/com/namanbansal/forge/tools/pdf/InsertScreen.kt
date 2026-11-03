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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.core.formatBytes
import com.namanbansal.forge.core.pagesLabel
import com.namanbansal.forge.core.pdf.BlankSize
import com.namanbansal.forge.core.pdf.InsertPosition
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.ForgeButton
import com.namanbansal.forge.ui.components.ForgeCard
import com.namanbansal.forge.ui.components.ForgeTextField
import com.namanbansal.forge.ui.components.IconBox
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.OptionChip
import com.namanbansal.forge.ui.components.Segmented
import com.namanbansal.forge.ui.components.Stepper
import com.namanbansal.forge.ui.components.TextAction
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.PasswordDialog
import com.namanbansal.forge.ui.toolkit.PickSpec
import com.namanbansal.forge.ui.toolkit.ToolScaffold
import com.namanbansal.forge.ui.toolkit.rememberFilePicker

/** P6 Insert pages. */
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
    val other by vm.other.collectAsStateWithLifecycle()
    val otherLoading by vm.otherLoading.collectAsStateWithLifecycle()
    val pickOther = rememberFilePicker(FileKind.pickerMimes(FileKind.PDF), multiple = false) { it.firstOrNull()?.let(vm::pickOther) }
    val total = source?.pageCount ?: 0
    val blocker = if (fromPdf) vm.otherPages(range, other).exceptionOrNull()?.message else null
    val adding = if (fromPdf) vm.otherPages(range, other).getOrNull()?.size ?: 0 else count

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
        InsertOptions(vm, total, fromPdf, count, size, position, page, range, other, otherLoading, blocker, adding, pickOther, source?.protection?.note)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.InsertOptions(
    vm: InsertViewModel, total: Int, fromPdf: Boolean, count: Int, size: BlankSize, position: InsertPosition, page: Int, range: String,
    other: InsertSource?, otherLoading: Boolean, blocker: String?, adding: Int, pickOther: () -> Unit, protectionNote: String?,
) {
    val c = ForgeTheme.colors
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Segmented(listOf("Blank pages", "From another PDF"), if (fromPdf) 1 else 0, { vm.setFromPdf(it == 1) })
        Spacer(Modifier.height(16.dp))
        if (!fromPdf) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Number of pages", style = ForgeType.BodyMedium, color = c.text, modifier = Modifier.weight(1f))
                Stepper(count, 1..InsertViewModel.MAX_BLANK, vm::setBlankCount, "Number of blank pages")
            }
            Text("Page size", style = ForgeType.Small.copy(fontWeight = FontWeight.SemiBold), color = c.text2, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BlankSize.entries.forEach { OptionChip(it.label, it == size, { vm.setBlankSize(it) }) }
            }
        } else {
            if (other == null) {
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
                ForgeTextField(
                    range, vm::setOtherRange, placeholder = "All pages", label = "Pages to insert",
                    error = blocker?.takeIf { range.isNotBlank() },
                    helper = "Leave empty for all ${pagesLabel(other.source.pageCount)}, or type 1-3, 5.",
                )
            }
        }
        Text("Insert", style = ForgeType.Small.copy(fontWeight = FontWeight.SemiBold), color = c.text2, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        Segmented(InsertPosition.entries.map { it.label }, position.ordinal, { vm.setPosition(InsertPosition.entries[it]) })
        if (position == InsertPosition.BeforePage || position == InsertPosition.AfterPage) {
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Page number (1-$total)", style = ForgeType.BodyMedium, color = c.text, modifier = Modifier.weight(1f))
                Stepper(page.coerceIn(1, total.coerceAtLeast(1)), 1..total.coerceAtLeast(1), vm::setPositionPage, "Page number")
            }
        }
        if (adding > 0 && blocker == null) {
            val where = when (position) {
                InsertPosition.Start -> "at the start"
                InsertPosition.End -> "at the end"
                InsertPosition.BeforePage -> "before page $page"
                InsertPosition.AfterPage -> "after page $page"
            }
            Text(
                "Adds ${pagesLabel(adding)} $where. The new PDF will have ${pagesLabel(total + adding)}.",
                style = ForgeType.Small, color = c.text2, modifier = Modifier.padding(top = 16.dp),
            )
        }
        if (protectionNote != null) InfoNote(protectionNote, Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(8.dp))
    }
}
