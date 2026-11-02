package com.namanbansal.forge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType

/** Screen title bar (".tbar"): optional back, title, trailing slot. */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    val c = ForgeTheme.colors
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(ForgeIcons.Back, contentDescription = "Back", tint = c.text, modifier = Modifier.size(22.dp))
            }
        } else {
            Box(Modifier.size(8.dp))
        }
        Text(
            title, style = ForgeType.Section.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold), color = c.text,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        trailing()
    }
}

/** 36dp round outlined icon button (".ib"). */
@Composable
fun RoundIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, active: Boolean = false) {
    val c = ForgeTheme.colors
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(if (active) c.tint else c.surface)
            .border(1.dp, if (active) c.accent else c.border, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = if (active) c.accent else c.text, modifier = Modifier.size(18.dp))
    }
}
