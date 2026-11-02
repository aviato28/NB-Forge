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
import androidx.compose.ui.unit.dp
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

/** One row in Recent: tap opens, the menu has Share / Open / Remove from list. Missing files are greyed out. */
@Composable
fun RecentRow(item: RecentItem, showDivider: Boolean, onRemove: () -> Unit) {
    val c = ForgeTheme.colors
    val context = LocalContext.current
    val snack = rememberSnack()
    var menu by remember { mutableStateOf(false) }
    val e = item.entry
    val toolLabel = ToolRegistry.get(e.toolCode)?.shortLabel ?: e.toolCode

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
                val sizes = if (e.beforeSize >= 0 && e.afterSize >= 0 && e.beforeSize != e.afterSize) "${formatBytes(e.beforeSize)} → ${formatBytes(e.afterSize)}" else formatBytes(e.afterSize)
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
                    if (item.exists) {
                        DropdownMenuItem(
                            text = { Text("Share") },
                            leadingIcon = { Icon(ForgeIcons.Share, null, Modifier.size(18.dp)) },
                            onClick = { menu = false; if (!FileActions.share(context, e.uri.toUri(), e.mime, e.name)) snack("No app available to share this file") },
                        )
                        DropdownMenuItem(
                            text = { Text("Open") },
                            leadingIcon = { Icon(ForgeIcons.Open, null, Modifier.size(18.dp)) },
                            onClick = { menu = false; open() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Remove from list") },
                        leadingIcon = { Icon(ForgeIcons.Trash, null, Modifier.size(18.dp)) },
                        onClick = { menu = false; onRemove() },
                    )
                }
            }
        }
        if (showDivider) HorizontalDivider(Modifier.padding(horizontal = 12.dp), thickness = 1.dp, color = c.border)
    }
}
