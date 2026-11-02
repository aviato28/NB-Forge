package com.namanbansal.forge.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.tools.Category
import com.namanbansal.forge.tools.ToolRegistry
import com.namanbansal.forge.ui.components.ForgeCard
import com.namanbansal.forge.ui.components.ListRow
import com.namanbansal.forge.ui.components.SectionHeader
import com.namanbansal.forge.ui.components.Tag
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme

@Composable
fun CategoryScreen(
    category: Category,
    pins: List<String>,
    onBack: () -> Unit,
    onTool: (String) -> Unit,
    onTogglePin: (String) -> Unit,
) {
    val c = ForgeTheme.colors
    val groups = ToolRegistry.grouped(category)
    Column(Modifier.fillMaxSize()) {
        TopBar(category.title, onBack = onBack) { Tag(toolCount(ToolRegistry.count(category)), Modifier.padding(end = 8.dp)) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            groups.forEachIndexed { gi, (group, tools) ->
                item(key = group) {
                    SectionHeader(group, Modifier.padding(top = if (gi == 0) 0.dp else 4.dp), action = toolCount(tools.size), actionMuted = true)
                    ForgeCard(Modifier.fillMaxWidth()) {
                        tools.forEachIndexed { i, tool ->
                            ListRow(
                                tool.name,
                                icon = tool.icon,
                                showDivider = i < tools.lastIndex,
                                onClick = { onTool(tool.code) },
                                onLongClick = { onTogglePin(tool.code) },
                                trailing = {
                                    if (tool.code in pins) Icon(ForgeIcons.Pin, "Pinned", tint = c.accent, modifier = Modifier.size(16.dp))
                                    Icon(ForgeIcons.Chevron, null, tint = c.text2, modifier = Modifier.size(16.dp))
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
