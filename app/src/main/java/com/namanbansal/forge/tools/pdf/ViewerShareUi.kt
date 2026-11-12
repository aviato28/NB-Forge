package com.namanbansal.forge.tools.pdf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.core.pdf.PageRanges
import com.namanbansal.forge.core.pdf.PageSelectionSync
import com.namanbansal.forge.core.pdf.PdfThumbnails
import com.namanbansal.forge.core.share.InfoRow
import com.namanbansal.forge.ui.components.ForgeButton
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.PagePicker

/** What the overflow menu can do with the open file. Null in a callback = that row is hidden. */
class FileMenuActions(
    val onShare: () -> Unit,
    val onSaveToPhone: () -> Unit,
    val onSaveAs: () -> Unit,
    val onPrint: () -> Unit,
    val onOpenWith: () -> Unit,
    val onSharePages: () -> Unit,
    val onInfo: () -> Unit,
)

/** The "File" group of the viewer's overflow menu. */
@Composable
fun FileMenuItems(a: FileMenuActions, onDone: () -> Unit) {
    val c = ForgeTheme.colors
    @Composable
    fun Item(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, run: () -> Unit) =
        DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) }, onClick = { onDone(); run() })
    Text("This file", style = ForgeType.Tiny, color = c.text2, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
    Item("Share", ForgeIcons.Share, a.onShare)
    Item("Share pages…", ForgeIcons.Extract, a.onSharePages)
    Item("Save to phone", ForgeIcons.Download, a.onSaveToPhone)
    Item("Save as…", ForgeIcons.Folder, a.onSaveAs)
    Item("Print", ForgeIcons.Printer, a.onPrint)
    Item("Open with…", ForgeIcons.Open, a.onOpenWith)
    Item("File info", ForgeIcons.Info, a.onInfo)
    HorizontalDivider(color = c.border)
}

/** Slim strip under the top bar for a temporary file. */
@Composable
fun TemporaryBanner(onSave: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val c = ForgeTheme.colors
    Row(
        modifier.fillMaxWidth().background(c.tint).padding(start = 16.dp).semantics { contentDescription = "Not saved on your phone" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ForgeIcons.Info, null, tint = c.accent, modifier = Modifier.size(16.dp))
        Text(
            "Not saved on your phone", style = ForgeType.Small, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp).weight(1f),
        )
        TextButton(onClick = onSave, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save", color = c.accent, style = ForgeType.Label) }
        Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
            Icon(ForgeIcons.Close, "Hide this notice", tint = c.text2, modifier = Modifier.size(16.dp))
        }
    }
}

/** What the file-level action should use when the document has unsaved edits. */
enum class FileAction(val verb: String) { SHARE("Share"), PRINT("Print"), OPEN_WITH("Open"), }

/** "Share with your changes / original" (also used for Print and Open with). */
@Composable
fun ChangesChoiceDialog(action: FileAction, onWithChanges: () -> Unit, onOriginal: () -> Unit, onCancel: () -> Unit) {
    val c = ForgeTheme.colors
    AlertDialog(
        onDismissRequest = onCancel, containerColor = c.surface,
        title = { Text("${action.verb} with your changes?", style = ForgeType.Section, color = c.text) },
        text = { Text("You have edits that aren't saved yet. Choose what to ${action.verb.lowercase()}.", style = ForgeType.Body, color = c.text2) },
        confirmButton = { TextButton(onClick = onWithChanges) { Text("With your changes", color = c.accent) } },
        dismissButton = {
            Row {
                TextButton(onClick = onCancel) { Text("Cancel", color = c.text2) }
                TextButton(onClick = onOriginal) { Text("Original", color = c.text2) }
            }
        },
    )
}

/** Where to put an edit that can't replace the original: Downloads/Forge (default) or anywhere via the system picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveWhereSheet(fileName: String, temporary: Boolean, onDownloads: () -> Unit, onSaveAs: () -> Unit, onCancel: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = ForgeTheme.colors.surface) {
        SaveWhereContent(fileName, temporary, onDownloads, onSaveAs, onCancel)
    }
}

@Composable
fun SaveWhereContent(fileName: String, temporary: Boolean, onDownloads: () -> Unit, onSaveAs: () -> Unit, onCancel: () -> Unit) {
    val c = ForgeTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp).navigationBarsPadding().semantics { contentDescription = "Where to save" },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Where do you want to save it?", style = ForgeType.Title, color = c.text)
        Text(
            if (temporary) "\"$fileName\" came from another app, so Forge can't change it in place. Your edit is saved as a new file and the original stays as it was."
            else "Android didn't let Forge change \"$fileName\" in place. Your edit is saved as a new file and the original stays as it was.",
            style = ForgeType.Small, color = c.text2,
        )
        ForgeButton("Save to Downloads/Forge", onDownloads, Modifier.fillMaxWidth(), icon = ForgeIcons.Download)
        ForgeButton("Save as… (choose folder and name)", onSaveAs, Modifier.fillMaxWidth(), icon = ForgeIcons.Folder, secondary = true)
        Row(Modifier.fillMaxWidth()) { Spacer(Modifier.weight(1f)); TextButton(onClick = onCancel) { Text("Cancel", color = c.text2) } }
    }
}

/** File info: name, size, pages, where it came from and is saved, PDF title / author / producer, password. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileInfoSheet(rows: List<InfoRow>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = ForgeTheme.colors.surface) {
        FileInfoContent(rows)
    }
}

@Composable
fun FileInfoContent(rows: List<InfoRow>) {
    val c = ForgeTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding().semantics { contentDescription = "File info" }) {
        Text("File info", style = ForgeType.Title, color = c.text, modifier = Modifier.padding(bottom = 8.dp))
        rows.forEachIndexed { i, r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(r.label, style = ForgeType.Small, color = c.text2, modifier = Modifier.weight(0.38f))
                Text(r.value, style = ForgeType.Body, color = c.text, modifier = Modifier.weight(0.62f))
            }
            if (i < rows.lastIndex) HorizontalDivider(color = c.border)
        }
    }
}

/** Pick pages, then share them as a PDF; or share one page as a picture. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharePagesSheet(thumbs: PdfThumbnails, pageCount: Int, initialPage: Int, onSharePdf: (List<Int>) -> Unit, onShareImage: (Int) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = ForgeTheme.colors.surface) {
        SharePagesContent(thumbs, pageCount, initialPage, onSharePdf, onShareImage)
    }
}

@Composable
fun SharePagesContent(thumbs: PdfThumbnails, pageCount: Int, initialPage: Int, onSharePdf: (List<Int>) -> Unit, onShareImage: (Int) -> Unit) {
    val c = ForgeTheme.colors
    var selected by remember { mutableStateOf(setOf(initialPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))) }
    var range by remember { mutableStateOf(PageRanges.format(selected)) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding().semantics { contentDescription = "Share pages" }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Share pages", style = ForgeType.Title, color = c.text)
        PagePicker(
            pageCount = pageCount, thumbnails = thumbs, selected = selected, onSelectionChange = { selected = it; range = PageRanges.format(it) },
            modifier = Modifier.fillMaxWidth().height(380.dp), rangeText = range,
            onRangeTextChange = { t -> range = t; selected = PageSelectionSync.fromText(t, pageCount, selected).selection },
        )
        val n = selected.size
        ForgeButton(
            if (n == 0) "Select pages to share" else if (n == 1) "Share page ${selected.first() + 1} as PDF" else "Share $n pages as PDF",
            { onSharePdf(selected.sorted()) }, Modifier.fillMaxWidth(), icon = ForgeIcons.Share, enabled = n > 0,
        )
        ForgeButton("Share page as image", { onShareImage(selected.first()) }, Modifier.fillMaxWidth(), icon = ForgeIcons.Image, secondary = true, enabled = n == 1)
        Spacer(Modifier.height(8.dp))
    }
}

/** "Why can't I edit this?": the specific reason for the spot that was tapped, with a button when another tool can handle it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhyLockedSheet(why: WhyLocked, onRoute: (ExRoute) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = ForgeTheme.colors.surface) {
        WhyLockedContent(why, onRoute, onDismiss)
    }
}

@Composable
fun WhyLockedContent(why: WhyLocked, onRoute: (ExRoute) -> Unit, onDismiss: () -> Unit) {
    val c = ForgeTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp).navigationBarsPadding().semantics { contentDescription = "Why this can't be edited" },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Why can't I edit this?", style = ForgeType.Small, color = c.text2)
        Text(why.title, style = ForgeType.Title, color = c.text)
        Text(why.detail, style = ForgeType.Body, color = c.text2)
        why.route?.let { r -> ForgeButton(why.routeLabel, { onRoute(r) }, Modifier.fillMaxWidth()) }
        Row(Modifier.fillMaxWidth()) { Spacer(Modifier.weight(1f)); TextButton(onClick = onDismiss) { Text("Got it", color = c.text2) } }
    }
}
