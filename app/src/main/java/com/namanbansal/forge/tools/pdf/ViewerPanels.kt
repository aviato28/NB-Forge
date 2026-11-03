package com.namanbansal.forge.tools.pdf

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.core.pdf.PdfThumbnails
import com.namanbansal.forge.core.pdf.viewer.OutlineItem
import com.namanbansal.forge.core.pdf.viewer.PdfOutline
import com.namanbansal.forge.ui.components.EmptyState
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.PageThumb

/** Contents of the outline panel: a Bookmarks tab (expand / collapse tree) and a Pages tab (thumbnails). */
@Composable
fun OutlinePanel(
    outline: List<OutlineItem>?, thumbs: PdfThumbnails?, pageCount: Int, currentPage: Int,
    onJump: (Int) -> Unit, modifier: Modifier = Modifier,
) {
    val c = ForgeTheme.colors
    var tab by rememberSaveable { mutableStateOf(-1) } // -1 = pick a default once the outline is known
    val effective = if (tab >= 0) tab else if (outline.isNullOrEmpty()) 1 else 0
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelTab("Bookmarks", effective == 0, Modifier.weight(1f)) { tab = 0 }
            PanelTab("Pages", effective == 1, Modifier.weight(1f)) { tab = 1 }
        }
        if (effective == 0) BookmarksTab(outline, onJump) else PagesTab(thumbs, pageCount, currentPage, onJump)
    }
}

@Composable
private fun PanelTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = ForgeTheme.colors
    Box(
        modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(if (selected) c.tint else c.surface2)
            .border(1.dp, if (selected) c.accent else c.border, RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .semantics { stateDescription = if (selected) "Selected" else "Not selected" },
        contentAlignment = Alignment.Center,
    ) { Text(label, style = ForgeType.Label, color = if (selected) c.accent else c.text) }
}

@Composable
private fun BookmarksTab(outline: List<OutlineItem>?, onJump: (Int) -> Unit) {
    val c = ForgeTheme.colors
    if (outline == null) {
        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text("Reading bookmarks…", style = ForgeType.Small, color = c.text2) }
        return
    }
    if (outline.isEmpty()) {
        EmptyState(ForgeIcons.Bookmark, "No bookmarks in this PDF", "Use the Pages tab to jump around by thumbnail.", Modifier.padding(horizontal = 24.dp))
        return
    }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    val rows = remember(outline, expanded) { PdfOutline.flatten(outline, expanded) }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
        items(rows, key = { it.id }) { r ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = r.page != null) { r.page?.let(onJump) }
                    .padding(start = (16 + 18 * r.depth).dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(r.title, style = ForgeType.Body, color = if (r.page != null) c.text else c.text2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                if (r.page != null) Text("${r.page + 1}", style = ForgeType.Small, color = c.text2, modifier = Modifier.padding(horizontal = 8.dp))
                if (r.hasChildren) Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable { expanded = if (r.id in expanded) expanded - r.id else expanded + r.id }
                        .semantics { contentDescription = if (r.expanded) "Collapse ${r.title}" else "Expand ${r.title}" },
                    contentAlignment = Alignment.Center,
                ) { Icon(ForgeIcons.ChevronDown, null, tint = c.text2, modifier = Modifier.size(18.dp).rotate(if (r.expanded) 180f else 0f)) }
                else Spacer(Modifier.width(44.dp))
            }
        }
    }
}

@Composable
private fun PagesTab(thumbs: PdfThumbnails?, pageCount: Int, currentPage: Int, onJump: (Int) -> Unit) {
    LazyVerticalGrid(
        GridCells.Adaptive(92.dp), Modifier.fillMaxWidth().heightIn(max = 560.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items((0 until pageCount).toList(), key = { it }) { i ->
            PageThumb(i, "${i + 1}", thumbs, selected = i == currentPage, onClick = { onJump(i) }, description = "Go to page ${i + 1}")
        }
    }
}

/** Portrait: a bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutlineSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = ForgeTheme.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text("Contents", style = ForgeType.Title, color = c.text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            content()
        }
    }
}

/** Landscape: a side panel that sits beside the pages instead of covering them. */
@Composable
fun OutlineSidePanel(onClose: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = ForgeTheme.colors
    Column(modifier.width(340.dp).fillMaxHeight().background(c.surface).border(1.dp, c.border)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Contents", style = ForgeType.Title, color = c.text, modifier = Modifier.weight(1f))
            Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClose).semantics { contentDescription = "Close contents" }, contentAlignment = Alignment.Center) {
                Icon(ForgeIcons.Close, null, tint = c.text, modifier = Modifier.size(18.dp))
            }
        }
        content()
    }
}
