package com.namanbansal.forge.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.namanbansal.forge.tools.Category
import com.namanbansal.forge.tools.ToolDef
import com.namanbansal.forge.tools.ToolRegistry
import com.namanbansal.forge.ui.components.AnvilMark
import com.namanbansal.forge.ui.components.EmptyState
import com.namanbansal.forge.ui.components.ForgeCard
import com.namanbansal.forge.ui.components.IconBox
import com.namanbansal.forge.ui.components.SectionHeader
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeShapes
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType

fun Category.icon(): ImageVector = when (this) {
    Category.PDF -> ForgeIcons.FileText
    Category.IMAGE -> ForgeIcons.Image
    Category.VIDEO -> ForgeIcons.Video
    Category.DEVICE -> ForgeIcons.Phone
    Category.UTILITIES -> ForgeIcons.Calc
    Category.FILES -> ForgeIcons.Folder
}

/** Short label used on the home grid (mockup uses "Video"). */
fun Category.shortTitle(): String = if (this == Category.VIDEO) "Video" else title

fun toolCount(n: Int) = if (n == 1) "1 tool" else "$n tools"

@Composable
fun HomeScreen(
    pins: List<String>,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onTool: (String) -> Unit,
    onCategory: (Category) -> Unit,
    onTogglePin: (String) -> Unit,
) {
    val c = ForgeTheme.colors
    var editing by rememberSaveable { mutableStateOf(false) }
    val pinned = pins.mapNotNull { ToolRegistry.get(it) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
        item {
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                AnvilMark(26.dp)
                Spacer(Modifier.width(8.dp))
                Text("FORGE", style = ForgeType.Wordmark, color = c.text, modifier = Modifier.weight(1f))
                RoundIconButton(ForgeIcons.Settings, "Settings", onSettings)
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().height(52.dp).clip(ForgeShapes.Search).background(c.surface)
                    .border(1.dp, c.border, ForgeShapes.Search).clickable(onClick = onSearch).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(ForgeIcons.Search, null, tint = c.text2, modifier = Modifier.size(20.dp))
                Text("Search 80+ tools…", style = ForgeType.Body, color = c.text2)
            }
        }
        item {
            SectionHeader(
                "Pinned",
                action = if (pinned.isEmpty()) null else if (editing) "Done" else "Edit",
                onAction = { editing = !editing },
            )
            if (pinned.isEmpty()) {
                Text("Long-press any tool to pin it here.", style = ForgeType.Small, color = c.text2)
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(pinned, key = { it.code }) { tool ->
                        PinTile(tool, editing, onClick = { if (editing) onTogglePin(tool.code) else onTool(tool.code) }, onLongClick = { editing = !editing })
                    }
                }
            }
        }
        item {
            SectionHeader("Recent")
            ForgeCard(Modifier.fillMaxWidth()) {
                EmptyState(ForgeIcons.Clock, "Nothing here yet", "Files you process show up here")
            }
        }
        item { SectionHeader("All tools", Modifier.padding(top = 0.dp)) }
        val cats = Category.entries.chunked(2)
        items(cats.size) { i ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                cats[i].forEach { cat -> CategoryCard(cat, Modifier.weight(1f)) { onCategory(cat) } }
                if (cats[i].size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PinTile(tool: ToolDef, editing: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = ForgeTheme.colors
    val haptics = LocalHapticFeedback.current
    Box {
        Column(
            Modifier.size(76.dp).clip(ForgeShapes.Card).background(c.surface).border(1.dp, c.border, ForgeShapes.Card)
                .combinedClickable(onClick = onClick, onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() })
                .padding(top = 12.dp, start = 4.dp, end = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(tool.icon, null, tint = c.text, modifier = Modifier.size(24.dp))
            Text(
                tool.name, style = ForgeType.Tiny.copy(fontSize = 10.5.sp, lineHeight = 12.sp), color = c.text,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        if (editing) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).size(20.dp).clip(CircleShape).background(c.accent),
                contentAlignment = Alignment.Center,
            ) { Icon(ForgeIcons.Close, "Unpin ${tool.name}", tint = Color.White, modifier = Modifier.size(12.dp)) }
        }
    }
}

@Composable
private fun CategoryCard(cat: Category, modifier: Modifier, onClick: () -> Unit) {
    val c = ForgeTheme.colors
    ForgeCard(modifier.height(56.dp), onClick = onClick) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconBox(cat.icon(), size = 36.dp, shape = ForgeShapes.IconBoxSmall)
            Column {
                Text(cat.shortTitle(), style = ForgeType.Item, color = c.text)
                Text(toolCount(ToolRegistry.count(cat)), style = ForgeType.Small.copy(fontWeight = FontWeight.Normal), color = c.text2)
            }
        }
    }
}
