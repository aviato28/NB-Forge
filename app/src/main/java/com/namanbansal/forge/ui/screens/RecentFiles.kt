package com.namanbansal.forge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.ForgeApp
import com.namanbansal.forge.core.RecentEntry
import kotlinx.coroutines.launch
import androidx.core.net.toUri
import com.namanbansal.forge.core.FileActions
import com.namanbansal.forge.core.RecentItem
import com.namanbansal.forge.core.formatBytes
import com.namanbansal.forge.core.relativeTime
import com.namanbansal.forge.tools.ToolRegistry
import com.namanbansal.forge.ui.components.IconBox
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.rememberSnack

private val Danger = Color(0xFFE5484D)

/** What the Files / Recent row menu can do beyond opening: rename the file, delete it (the screen asks first). */
class RecentActions(val onRename: (RecentEntry, String) -> Unit, val onDelete: (RecentEntry) -> Unit)

/**
 * One row in Recent: tap opens; the menu has Share, Save as…, Open with…, Rename, Remove from list and Delete file (after a
 * confirmation). Missing files are greyed out and only offer removal.
 */
@Composable
fun RecentRow(item: RecentItem, showDivider: Boolean, onRemove: () -> Unit, actions: RecentActions? = null) {
    val c = ForgeTheme.colors
    val context = LocalContext.current
    val snack = rememberSnack()
    var menu by remember { mutableStateOf(false) }
    val e = item.entry
    val toolLabel = ToolRegistry.get(e.toolCode)?.shortLabel ?: e.toolCode

    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val saveAs = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument(e.mime)) { dest ->
        if (dest != null) scope.launch {
            snack(try { ForgeApp.from(context).outputSaver.copyTo(e.uri.toUri(), dest); "Saved a copy" } catch (_: Exception) { "Couldn't save a copy there" })
        }
    }

    fun open() {
        if (!item.exists) snack("This file no longer exists") else if (!FileActions.open(context, e.uri.toUri(), e.mime)) snack("No app found to open this file")
    }

    Column(Modifier.clickable(onClick = ::open)) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp).alpha(if (item.exists) 1f else 0.5f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconBox(ForgeIcons.FileText, size = 40.dp)
            Column(Modifier.weight(1f)) {
                Text(e.name, style = ForgeType.Item, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sizes = formatBytes(e.afterSize) // the before -> after story lives on the result screen; here the row must fit one line
                Text(
                    if (item.exists) "$toolLabel · $sizes · ${relativeTime(e.timestamp)}" else "No longer on this device · ${relativeTime(e.timestamp)}",
                    style = ForgeType.Small, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).clickable { menu = true },
                    contentAlignment = Alignment.Center,
                ) { Icon(ForgeIcons.More, "More actions for ${e.name}", tint = c.text2, modifier = Modifier.size(20.dp)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.surface) {
                    RecentMenuItems(
                        exists = item.exists, canManage = actions != null,
                        onShare = { menu = false; if (!FileActions.share(context, e.uri.toUri(), e.mime, e.name)) snack("No app available to share this file") },
                        onSaveAs = { menu = false; saveAs.launch(e.name) },
                        onOpenWith = { menu = false; if (!FileActions.openWith(context, e.uri.toUri(), e.mime)) snack("No app found to open this file") },
                        onRename = { menu = false; renaming = true },
                        onRemove = { menu = false; onRemove() },
                        onDelete = { menu = false; deleting = true },
                    )
                }
            }
        }
        if (showDivider) HorizontalDivider(Modifier.padding(horizontal = 12.dp), thickness = 1.dp, color = c.border)
    }
    if (renaming && actions != null) RenameDialog(e.name, onDismiss = { renaming = false }, onRename = { renaming = false; actions.onRename(e, it) })
    if (deleting && actions != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleting = false }, containerColor = c.surface,
            title = { Text("Delete this file?", style = ForgeType.Section, color = c.text) },
            text = { Text("\"${e.name}\" will be deleted from your phone. This can't be undone.", style = ForgeType.Body, color = c.text2) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { deleting = false; actions.onDelete(e) }) { Text("Delete", color = Danger) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { deleting = false }) { Text("Cancel", color = c.text2) } },
        )
    }
}

@Composable
fun RenameDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    val c = ForgeTheme.colors
    var text by remember { mutableStateOf(current) }
    val valid = com.namanbansal.forge.core.OutputNaming.renamed(text) != null
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.surface,
        title = { Text("Rename file", style = ForgeType.Section, color = c.text) },
        text = { com.namanbansal.forge.ui.components.ForgeTextField(text, { text = it }, label = "File name", onDone = { if (valid) onRename(text) }) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onRename(text) }, enabled = valid) { Text("Rename", color = c.accent) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel", color = c.text2) } },
    )
}

/** The rows of a Files / Recent row menu (also shown on their own in screenshots). */
@Composable
fun RecentMenuItems(exists: Boolean, canManage: Boolean, onShare: () -> Unit, onSaveAs: () -> Unit, onOpenWith: () -> Unit, onRename: () -> Unit, onRemove: () -> Unit, onDelete: () -> Unit) {
    @Composable
    fun Row(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color = Color.Unspecified, run: () -> Unit) =
        DropdownMenuItem(text = { Text(text, color = color) }, leadingIcon = { Icon(icon, null, Modifier.size(18.dp), tint = if (color == Color.Unspecified) androidx.compose.material3.LocalContentColor.current else color) }, onClick = run)
    if (exists) {
        Row("Share", ForgeIcons.Share, run = onShare)
        Row("Save as…", ForgeIcons.Download, run = onSaveAs)
        Row("Open with…", ForgeIcons.Open, run = onOpenWith)
        if (canManage) Row("Rename", ForgeIcons.Pencil, run = onRename)
    }
    Row("Remove from list", ForgeIcons.Trash, run = onRemove)
    if (canManage && exists) Row("Delete file…", ForgeIcons.Trash, Danger, onDelete)
}
