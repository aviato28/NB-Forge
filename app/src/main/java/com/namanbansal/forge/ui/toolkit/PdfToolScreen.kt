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
            Image(img, description, Modifier.fillMaxWidth().padding(8.dp), contentScale = ContentScale.FillWidth)
            if (!done) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = c.accent)
        } else if (!done) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = c.accent)
        else Text(emptyText, style = ForgeType.Small, color = c.text2, modifier = Modifier.padding(24.dp))
    }
}

/** "Preview page [- 3 +] of N" row for choosing which page a live preview shows. */
@Composable
fun PreviewPageRow(page: Int, pageCount: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = ForgeTheme.colors
    Row(modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Preview page", style = ForgeType.Small, color = c.text2, modifier = Modifier.weight(1f))
        Stepper(page + 1, 1..pageCount.coerceAtLeast(1), { onChange(it - 1) }, "Preview page")
        Text("of $pageCount", style = ForgeType.Small, color = c.text2)
    }
}
