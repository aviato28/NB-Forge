package com.namanbansal.forge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.tools.ToolDef
import com.namanbansal.forge.ui.components.ForgeButton
import com.namanbansal.forge.ui.components.IconBox
import com.namanbansal.forge.ui.components.Tag
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType

/** Shared placeholder for every tool whose status is ComingSoon. */
@Composable
fun ComingSoonScreen(tool: ToolDef?, pinned: Boolean, onBack: () -> Unit, onTogglePin: () -> Unit) {
    val c = ForgeTheme.colors
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
        TopBar(tool?.name ?: "Unknown tool", onBack = onBack) {
            if (tool != null) {
                RoundIconButton(ForgeIcons.Pin, if (pinned) "Unpin" else "Pin", onTogglePin, active = pinned)
                Spacer(Modifier.padding(end = 8.dp))
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (tool == null) {
                Text("This tool doesn't exist.", style = ForgeType.Body, color = c.text2)
                return@Column
            }
            IconBox(tool.icon, size = 88.dp, iconSize = 40.dp, shape = RoundedCornerShape(28.dp))
            Spacer(Modifier.height(20.dp))
            Text(tool.name, style = ForgeType.Title, color = c.text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag(tool.category.title)
                if (tool.group != tool.category.title) Tag(tool.group)
                Tag(tool.code)
            }
            Spacer(Modifier.height(16.dp))
            Text(tool.description, style = ForgeType.Body, color = c.text2, textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            Text("COMING SOON", style = ForgeType.Label, color = c.accent)
            Spacer(Modifier.height(6.dp))
            Text(
                "This tool is on the way. Pin it and it'll be one tap away when it lands in an update.",
                style = ForgeType.Small, color = c.text2, textAlign = TextAlign.Center,
            )
        }
        if (tool != null) {
            ForgeButton(
                text = if (pinned) "Unpin from Home" else "Pin to Home",
                onClick = onTogglePin,
                icon = ForgeIcons.Pin,
                secondary = pinned,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
    }
}
