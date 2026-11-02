package com.namanbansal.forge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.tools.SearchHit
import com.namanbansal.forge.tools.ToolSearch
import com.namanbansal.forge.ui.components.EmptyState
import com.namanbansal.forge.ui.components.ForgeCard
import com.namanbansal.forge.ui.components.GroupLabel
import com.namanbansal.forge.ui.components.IconBox
import com.namanbansal.forge.ui.components.ListRow
import com.namanbansal.forge.ui.components.Tag
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeShapes
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType

@Composable
fun SearchScreen(
    recentSearches: List<String>,
    onBack: () -> Unit,
    onTool: (code: String, query: String) -> Unit,
    onSubmit: (String) -> Unit,
    onClearRecent: () -> Unit,
    onTogglePin: (String) -> Unit,
) {
    val c = ForgeTheme.colors
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(query) { ToolSearch.search(query) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).imePadding().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().height(52.dp).clip(ForgeShapes.Search).background(c.surface)
                .border(1.5.dp, c.accent, ForgeShapes.Search).padding(start = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(ForgeIcons.Back, "Back", tint = c.text2, modifier = Modifier.size(20.dp))
            }
            Box(Modifier.weight(1f).padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text("Search 80+ tools…", style = ForgeType.Body, color = c.text2)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = ForgeType.BodyMedium.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit(query); keyboard?.hide() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (query.isNotEmpty()) {
                Box(Modifier.size(36.dp).clip(CircleShape).clickable { query = "" }, contentAlignment = Alignment.Center) {
                    Icon(ForgeIcons.Close, "Clear", tint = c.text2, modifier = Modifier.size(18.dp))
                }
            }
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (query.isBlank()) {
                if (recentSearches.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GroupLabel("Recent searches", Modifier.weight(1f).padding(top = 4.dp))
                            Text(
                                "Clear", style = ForgeType.Small.copy(fontWeight = FontWeight.SemiBold), color = c.accent,
                                modifier = Modifier.clip(CircleShape).clickable(onClick = onClearRecent).padding(8.dp),
                            )
                        }
                        ForgeCard(Modifier.fillMaxWidth()) {
                            recentSearches.forEachIndexed { i, q ->
                                ListRow(q, icon = ForgeIcons.Clock, showDivider = i < recentSearches.lastIndex, onClick = { query = q })
                            }
                        }
                    }
                } else {
                    item { EmptyState(ForgeIcons.Search, "Find any tool", "Try \"compress\", \"qr\" or \"P9\"", Modifier.padding(top = 32.dp)) }
                }
            } else {
                item { GroupLabel(if (results.size == 1) "1 result" else "${results.size} results", Modifier.padding(top = 8.dp)) }
                if (results.isEmpty()) {
                    item { EmptyState(ForgeIcons.Search, "No matching tools", "Try a different word") }
                }
                items(results, key = { it.tool.code }) { hit ->
                    ResultCard(hit, onClick = { onTool(hit.tool.code, query) }, onLongClick = { onTogglePin(hit.tool.code) })
                }
            }
        }
    }
}

@Composable
private fun ResultCard(hit: SearchHit, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = ForgeTheme.colors
    val name = buildAnnotatedString {
        hit.tool.name.forEachIndexed { i, ch ->
            if (i in hit.nameMatches) withStyle(SpanStyle(color = c.accent, fontWeight = FontWeight.Bold)) { append(ch) } else append(ch)
        }
    }
    ForgeCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), onClick = onClick, onLongClick = onLongClick) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBox(hit.tool.icon)
            Column(Modifier.weight(1f)) {
                Text(name, style = ForgeType.BodyMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(hit.tool.description, style = ForgeType.Small, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Tag(hit.tool.category.shortTitle())
        }
    }
}
