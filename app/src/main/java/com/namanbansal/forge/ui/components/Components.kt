package com.namanbansal.forge.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeShapes
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType

/** Surface with 1dp border and 20dp radius, the base "card" of the design system. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ForgeCard(
    modifier: Modifier = Modifier,
    shape: Shape = ForgeShapes.Card,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = ForgeTheme.colors
    var m = modifier.clip(shape).background(c.surface).border(1.dp, c.border, shape)
    if (onClick != null || onLongClick != null) m = m.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick)
    Column(m, content = content)
}

/** Tinted rounded square holding an ember icon (".ibox" in the mockups). */
@Composable
fun IconBox(icon: ImageVector, size: Dp = 40.dp, iconSize: Dp = 20.dp, shape: Shape = ForgeShapes.IconBox) {
    val c = ForgeTheme.colors
    Box(Modifier.size(size).clip(shape).background(c.tint), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun Tag(text: String, modifier: Modifier = Modifier) {
    val c = ForgeTheme.colors
    Text(
        text, style = ForgeType.Tiny, color = c.text2, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(14.dp)).background(c.surface2).padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, actionMuted: Boolean = false, onAction: (() -> Unit)? = null) {
    val c = ForgeTheme.colors
    Row(modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = ForgeType.Section, color = c.text, modifier = Modifier.weight(1f))
        if (action != null) {
            Text(
                action,
                style = ForgeType.Small.copy(fontWeight = if (actionMuted) FontWeight.Medium else FontWeight.SemiBold),
                color = if (actionMuted) c.text2 else c.accent,
                modifier = if (onAction != null) Modifier.clip(RoundedCornerShape(8.dp)).combinedClickableCompat(onAction).padding(4.dp) else Modifier,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = combinedClickable(onClick = onClick)

/** Small grey label above a group (".lbl"). */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = ForgeType.Small.copy(fontWeight = FontWeight.SemiBold), color = ForgeTheme.colors.text2, modifier = modifier.padding(top = 16.dp, bottom = 8.dp))
}

/** One row of a grouped list card (".grp .r"). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    showDivider: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {
        Icon(ForgeIcons.Chevron, null, tint = ForgeTheme.colors.text2, modifier = Modifier.size(16.dp))
    },
) {
    val c = ForgeTheme.colors
    Column(modifier.fillMaxWidth().let { if (onClick != null || onLongClick != null) it.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick) else it }) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).let { if (subtitle == null) it.height(48.dp) else it.padding(vertical = 10.dp) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null) Icon(icon, null, tint = c.text, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = ForgeType.Item.copy(fontWeight = FontWeight.Medium), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = ForgeType.Small, color = c.text2)
            }
            trailing()
        }
        if (showDivider) HorizontalDivider(Modifier.padding(horizontal = 12.dp), thickness = 1.dp, color = c.border)
    }
}

@Composable
fun ForgeSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val c = ForgeTheme.colors
    Switch(
        checked = checked, onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White, checkedTrackColor = c.accent, checkedBorderColor = c.accent,
            uncheckedThumbColor = Color.White, uncheckedTrackColor = c.border, uncheckedBorderColor = c.border,
        ),
    )
}

/** Segmented control (".seg"). */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = ForgeTheme.colors
    Row(modifier.fillMaxWidth().clip(ForgeShapes.Chip).background(c.surface2).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(11.dp))
                    .background(if (on) c.surface else Color.Transparent)
                    .let { if (on) it.border(1.dp, c.border, RoundedCornerShape(11.dp)) else it }
                    .combinedClickableCompat { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = ForgeType.Label, color = if (on) c.text else c.text2)
            }
        }
    }
}

/** Primary ember button (".btn"), darkens while pressed. */
@Composable
fun ForgeButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, secondary: Boolean = false, enabled: Boolean = true) {
    val c = ForgeTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg = when {
        secondary -> c.surface2
        pressed -> c.accentPressed
        else -> c.accent
    }
    val fg = if (secondary) c.text else Color.White
    Row(
        modifier.height(52.dp).clip(ForgeShapes.Button).background(if (enabled) bg else c.border)
            .let { if (secondary) it.border(1.dp, c.border, ForgeShapes.Button) else it }
            .let { if (enabled) it.clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick) else it }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = ForgeType.BodyMedium.copy(fontWeight = FontWeight.SemiBold), color = fg)
    }
}

/** Small anvil + spark mark (from icon B) used in the wordmark row. */
@Composable
fun AnvilMark(size: Dp = 26.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.22f)).background(com.namanbansal.forge.ui.theme.Charcoal), contentAlignment = Alignment.Center) {
        Icon(ForgeIcons.AnvilGlyph, null, tint = com.namanbansal.forge.ui.theme.Ember, modifier = Modifier.size(size))
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    val c = ForgeTheme.colors
    Column(modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconBox(icon, size = 56.dp, iconSize = 26.dp, shape = RoundedCornerShape(18.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = ForgeType.Item, color = c.text)
        Spacer(Modifier.height(4.dp))
        Text(body, style = ForgeType.Small, color = c.text2)
    }
}
