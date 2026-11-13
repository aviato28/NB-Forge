package com.namanbansal.forge.ui.toolkit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.core.formatBytes
import com.namanbansal.forge.core.pagesLabel
import com.namanbansal.forge.core.pdf.PdfSource
import com.namanbansal.forge.core.pdf.PdfThumbnails
import com.namanbansal.forge.core.pdf.PdfToolViewModel
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.Stepper
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import kotlinx.coroutines.delay

/**
 * [ToolScaffold] pre-wired for a one-PDF tool: password dialog, subtitle, "opening" state and the protection note.
 * [options] gets the open source and its thumbnails and is scrollable unless [scroll] is false.
 */
@Composable
fun PdfToolScreen(
    host: ToolHost,
    vm: PdfToolViewModel,
    pickTitle: String,
    actionLabel: String,
    modifier: Modifier = Modifier,
    actionEnabled: Boolean = true,
    actionHint: String? = null,
    scroll: Boolean = true,
    showProtectionNote: Boolean = true,
    options: @Composable ColumnScope.(PdfSource, PdfThumbnails) -> Unit,
) {
    val source by vm.source.collectAsStateWithLifecycle()
    val prompt by vm.passwordPrompt.collectAsStateWithLifecycle()
    val thumbs by vm.thumbnails.collectAsStateWithLifecycle()
    ToolScaffold(
        tool = host.tool, vm = vm, onBack = host.onBack, onOpenTool = host.onOpenTool, modifier = modifier,
        pick = PickSpec(listOf(FileKind.PDF), title = pickTitle),
        actionLabel = actionLabel, actionEnabled = actionEnabled, actionHint = actionHint,
        inputSubtitle = { files -> source?.let { "${pagesLabel(it.pageCount)} · ${formatBytes(files.firstOrNull()?.size ?: -1)}" } ?: "Opening…" },
        optionsReady = source != null && thumbs != null,
        dialogs = { prompt?.let { PasswordDialog(it, onSubmit = vm::submitPassword, onCancel = vm::cancelPassword) } },
    ) {
        val s = source
        val t = thumbs
        if (s != null && t != null) {
            Column(Modifier.weight(1f).fillMaxWidth().then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
                options(s, t)
            }
            if (showProtectionNote) s.protection?.let { InfoNote(it.note, Modifier.padding(vertical = 8.dp)) }
        }
    }
}

/**
 * A live preview picture: [render] is re-run (after a short pause, so typing doesn't thrash) whenever [key] changes.
 * Shows a spinner while working and [emptyText] if the page could not be rendered.
 */
@Composable
fun LivePreview(
    key: Any,
    modifier: Modifier = Modifier,
    description: String = "Preview",
    emptyText: String = "Preview unavailable",
    render: suspend () -> android.graphics.Bitmap?,
) {
    val c = ForgeTheme.colors
    var done by remember { mutableStateOf(false) }
    val image by produceState<ImageBitmap?>(null, key) {
        done = false
        delay(250)
        value = render()?.asImageBitmap()
        done = true
    }
    Box(
        modifier.fillMaxWidth().heightIn(min = 160.dp).clip(RoundedCornerShape(16.dp)).background(c.surface2)
            .border(1.dp, c.border, RoundedCornerShape(16.dp)).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        val img = image
        if (img != null) {
            Image(img, description, Modifier.fillMaxWidth().heightIn(max = 340.dp).padding(8.dp), contentScale = ContentScale.Fit)
            if (!done) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = c.accent)
        } else if (!done) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = c.accent)
        else Text(emptyText, style = ForgeType.Small, color = c.text2, modifier = Modifier.padding(24.dp))
    }
}

/** "Preview page [- 3 +] of N" row for choosing which page a live preview shows. */
@Composable
fun PreviewPageRow(page: Int, pageCount: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier, label: String = "Preview page") {
    val c = ForgeTheme.colors
    Row(modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = ForgeType.Small, color = c.text2, modifier = Modifier.weight(1f))
        Stepper(page + 1, 1..pageCount.coerceAtLeast(1), { onChange(it - 1) }, label)
        Text("of $pageCount", style = ForgeType.Small, color = c.text2)
    }
}

/** One-line "All pages / 3 of 10 pages" row that opens the thumbnail [PagePicker] in a sheet. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PagesChooser(
    pageCount: Int, thumbnails: PdfThumbnails, selected: Set<Int>, rangeText: String,
    onSelection: (Set<Int>) -> Unit, onText: (String) -> Unit, modifier: Modifier = Modifier,
) {
    val c = ForgeTheme.colors
    var open by remember { mutableStateOf(false) }
    val all = selected.size == pageCount
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Pages", style = ForgeType.Label, color = c.text2)
            Text(if (all) "All $pageCount pages" else "${selected.size} of $pageCount pages", style = ForgeType.BodyMedium, color = c.text)
        }
        com.namanbansal.forge.ui.components.TextAction("Choose pages", { open = true })
    }
    if (open) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = c.surface,
        ) {
            Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(horizontal = 16.dp)) {
                PagePicker(pageCount, thumbnails, selected, onSelection, Modifier.weight(1f, fill = false), rangeText = rangeText, onRangeTextChange = onText)
                com.namanbansal.forge.ui.components.ForgeButton("Done", { open = false }, Modifier.fillMaxWidth().padding(vertical = 12.dp), enabled = selected.isNotEmpty())
            }
        }
    }
}

/** A row of colour dots; [colors] are ARGB ints. */
@Composable
fun ColorDots(colors: List<Int>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = ForgeTheme.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEachIndexed { i, col ->
            Box(
                Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape).background(androidx.compose.ui.graphics.Color(col))
                    .border(if (i == selected) 3.dp else 1.dp, if (i == selected) c.accent else c.border, androidx.compose.foundation.shape.CircleShape)
                    .clickable { onSelect(i) }.semantics { contentDescription = "Colour ${i + 1}"; this.selected = i == selected },
            )
        }
    }
}

/** 3 x 3 position picker (0 = top-left ... 8 = bottom-right), optionally with a "Tiled" choice (index [tiled]). */
@Composable
fun PositionGrid(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, tiled: Int? = null, rows: Int = 3) {
    val c = ForgeTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (r in 0 until rows) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (col in 0 until 3) {
                val idx = r * 3 + col
                val on = idx == selected
                Box(
                    Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(10.dp)).background(if (on) c.accent else c.surface2)
                        .border(1.dp, if (on) c.accent else c.border, RoundedCornerShape(10.dp)).clickable { onSelect(idx) }
                        .semantics { contentDescription = "Position ${idx + 1}"; this.selected = on },
                )
            }
        }
        if (tiled != null) {
            val on = selected == tiled
            Box(
                Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(10.dp)).background(if (on) c.accent else c.surface2)
                    .border(1.dp, if (on) c.accent else c.border, RoundedCornerShape(10.dp)).clickable { onSelect(tiled) },
                contentAlignment = Alignment.Center,
            ) { Text("Tiled across the page", style = ForgeType.Label, color = if (on) androidx.compose.ui.graphics.Color.White else c.text) }
        }
    }
}
