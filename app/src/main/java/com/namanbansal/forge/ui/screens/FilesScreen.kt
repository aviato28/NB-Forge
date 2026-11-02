package com.namanbansal.forge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.ui.components.EmptyState
import com.namanbansal.forge.ui.icons.ForgeIcons

@Composable
fun FilesScreen() {
    Column(Modifier.fillMaxSize()) {
        TopBar("Files")
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center) {
            EmptyState(ForgeIcons.Folder, "No files yet", "Files you process show up here")
        }
    }
}
