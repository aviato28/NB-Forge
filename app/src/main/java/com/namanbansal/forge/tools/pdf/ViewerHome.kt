package com.namanbansal.forge.tools.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.core.pdf.viewer.ViewerPosition
import com.namanbansal.forge.core.relativeTime
import com.namanbansal.forge.ui.components.EmptyState
import com.namanbansal.forge.ui.components.ForgeButton
import com.namanbansal.forge.ui.components.GroupLabel
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.screens.TopBar
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Viewer start screen: "Open PDF" plus the recent documents with a thumbnail, where you stopped and when.
 * [error] is shown when the last file could not be opened, with a Report button that copies diagnostics.
 */
@Composable
fun ViewerHome(
    title: String,
    recents: List<ViewerPosition>,
    loading: Boolean,
    error: ViewerViewModel.OpenError?,
    coverFile: (ViewerPosition) -> File?,
    onOpenPdf: () -> Unit,
    onOpenRecent: (ViewerPosition) -> Unit,
    onRemoveRecent: (ViewerPosition) -> Unit,
    onReport: (String) -> Unit,
    onDismissError: () -> Unit,
    onBack: () -> Unit,
    now: Long = System.currentTimeMillis(),
) {
    val c = ForgeTheme.colors
    Column(Modifier.fillMaxSize()) {
        TopBar(title, onBack = onBack)
        if (loading) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = c.accent, trackColor = c.surface2, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(16.dp))
                Text("Opening…", style = ForgeType.Item, color = c.text)
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 4.dp, 16.dp, 24.dp)) {
            if (error != null) item("error") {
                Column(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(16.dp)).background(c.tint).border(1.dp, c.accent.copy(alpha = 0.4f), RoundedCornerShape(16.dp)).padding(16.dp),
                ) {
                    Text("This PDF couldn't be opened", style = ForgeType.Section, color = c.text)
                    Spacer(Modifier.height(4.dp))
                    Text(error.message, style = ForgeType.Body, color = c.text2)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ForgeButton("Report", { onReport(error.report) }, Modifier.weight(1f), icon = ForgeIcons.Copy, secondary = true)
                        ForgeButton("Dismiss", onDismissError, Modifier.weight(1f), secondary = true)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Report copies technical details (no file contents) so you can paste them in a message.", style = ForgeType.Tiny, color = c.text2)
                }
            }
            item("open") { ForgeButton("Open PDF", onOpenPdf, Modifier.fillMaxWidth(), icon = ForgeIcons.Folder) }
            item("label") { GroupLabel("Recent") }
            if (recents.isEmpty()) item("empty") {
                EmptyState(ForgeIcons.FileText, "No recent PDFs yet", "PDFs you read here, or open from other apps, show up in this list.")
            }
            items(recents, key = { it.key }) { p ->
                RecentRow(p, coverFile(p), now, onOpenRecent, onRemoveRecent)
            }
        }
    }
}

@Composable
private fun RecentRow(p: ViewerPosition, cover: File?, now: Long, onOpen: (ViewerPosition) -> Unit, onRemove: (ViewerPosition) -> Unit) {
    val c = ForgeTheme.colors
    val bmp by produceState<Bitmap?>(null, cover) {
        value = if (cover == null) null else withContext(Dispatchers.IO) { runCatching { if (cover.exists()) BitmapFactory.decodeFile(cover.path) else null }.getOrNull() }
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).clickable { onOpen(p) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(46.dp).height(60.dp).clip(RoundedCornerShape(6.dp)).background(c.surface2).border(1.dp, c.border, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            val b = bmp
            if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(ForgeIcons.FileText, null, tint = c.text2, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(p.name, style = ForgeType.BodyMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Page ${p.page + 1} of ${p.pageCount}  ·  ${relativeTime(p.lastOpened, now)}", style = ForgeType.Small, color = c.text2, maxLines = 1)
        }
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable { onRemove(p) }.semantics { contentDescription = "Remove ${p.name} from recent" },
            contentAlignment = Alignment.Center,
        ) { Icon(ForgeIcons.Close, null, tint = c.text2, modifier = Modifier.size(16.dp)) }
    }
}
