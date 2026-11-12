package com.namanbansal.forge.ui.toolkit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.tools.ToolDef
import com.namanbansal.forge.tools.ToolRegistry
import com.namanbansal.forge.ui.components.IconBox
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType

/**
 * Shown when another app shares / opens a file with Forge: "What do you want to do with <file>?"
 * Lists only Available tools that accept every incoming file type; scales automatically as tools ship.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenWithSheet(
    files: List<PickedFile>,
    onPick: (ToolDef) -> Unit,
    onDismiss: () -> Unit,
    onSaveToPhone: (() -> Unit)? = null,
    tools: List<ToolDef> = ToolRegistry.availableFor(files.map { it.kind }.toSet())
        .filter { files.size == 1 || it.code in com.namanbansal.forge.core.NextSuggestions.MULTI_INPUT }, // several files: only tools that take several
) {
    val c = ForgeTheme.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val subject = if (files.size == 1) files.first().name else "${files.size} files"
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text("What do you want to do with $subject?", style = ForgeType.Title, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            if (onSaveToPhone != null) {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onSaveToPhone).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    IconBox(ForgeIcons.Download)
                    Column(Modifier.weight(1f)) {
                        Text("Save to phone", style = ForgeType.BodyMedium, color = c.text)
                        Text("Keep ${if (files.size == 1) "it" else "them"} in Downloads/Forge", style = ForgeType.Small, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(ForgeIcons.Chevron, null, tint = c.text2, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.height(4.dp))
            }
            if (tools.isEmpty()) {
                Text("Forge doesn't have a tool for this kind of file yet.", style = ForgeType.Body, color = c.text2)
            } else {
                tools.forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(t) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        IconBox(t.icon)
                        Column(Modifier.weight(1f)) {
                            Text(t.shortLabel, style = ForgeType.BodyMedium, color = c.text)
                            Text(t.description, style = ForgeType.Small, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(ForgeIcons.Chevron, null, tint = c.text2, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
