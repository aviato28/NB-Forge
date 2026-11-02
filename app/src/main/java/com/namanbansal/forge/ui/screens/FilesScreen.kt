package com.namanbansal.forge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.namanbansal.forge.core.OutputSaver
import com.namanbansal.forge.core.RecentItem
import com.namanbansal.forge.core.RecentEntry
import com.namanbansal.forge.ui.components.EmptyState
import com.namanbansal.forge.ui.components.ForgeCard
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType

@Composable
fun FilesScreen(items: List<RecentItem>, onRefresh: () -> Unit, onRemove: (RecentEntry) -> Unit) {
    val c = ForgeTheme.colors
    LaunchedEffect(Unit) { onRefresh() }
    Column(Modifier.fillMaxSize()) {
        TopBar("Files")
        if (items.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center) {
                EmptyState(ForgeIcons.Folder, "No files yet", "Files you process show up here")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                item {
                    Text(
                        "Saved in ${OutputSaver.FOLDER_LABEL}. Removing a row here never deletes the file.",
                        style = ForgeType.Small, color = c.text2, modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                item {
                    ForgeCard(Modifier.fillMaxWidth()) {
                        items.forEachIndexed { i, item ->
                            RecentRow(item, showDivider = i < items.lastIndex, onRemove = { onRemove(item.entry) })
                        }
                    }
                }
            }
        }
    }
}
