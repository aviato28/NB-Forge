package com.namanbansal.forge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeShapes
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType

/** Single-line text field in Forge colours with an error line underneath (range text, page numbers). */
@Composable
fun ForgeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    label: String? = null,
    error: String? = null,
    helper: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    onDone: () -> Unit = {},
) {
    val c = ForgeTheme.colors
    OutlinedTextField(
        value = value, onValueChange = onValueChange, singleLine = true, modifier = modifier.fillMaxWidth(),
        label = label?.let { { Text(it) } },
        placeholder = { Text(placeholder, color = c.text2.copy(alpha = 0.7f)) },
        isError = error != null,
        supportingText = (error ?: helper)?.let { { Text(it, style = ForgeType.Small) } },
        shape = ForgeShapes.Button,
        textStyle = ForgeType.Body,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.accent, unfocusedBorderColor = c.border, cursorColor = c.accent,
            focusedTextColor = c.text, unfocusedTextColor = c.text, focusedLabelColor = c.accent,
            unfocusedContainerColor = c.surface, focusedContainerColor = c.surface,
            unfocusedSupportingTextColor = c.text2, focusedSupportingTextColor = c.text2,
        ),
    )
}

/** "− 4 +" with 48dp touch targets. [label] names the value for accessibility ("Pages per file"). */
@Composable
fun Stepper(value: Int, range: IntRange, onChange: (Int) -> Unit, label: String, modifier: Modifier = Modifier) {
    val c = ForgeTheme.colors
    Row(
        modifier.clip(ForgeShapes.Button).background(c.surface).border(1.dp, c.border, ForgeShapes.Button)
            .semantics { contentDescription = "$label: $value"; stateDescription = "$value" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(ForgeIcons.Minus, "Decrease $label", enabled = value > range.first) { onChange((value - 1).coerceIn(range)) }
        Text("$value", style = ForgeType.BodyMedium, color = c.text, textAlign = TextAlign.Center, modifier = Modifier.width(48.dp))
        StepButton(ForgeIcons.Plus, "Increase $label", enabled = value < range.last) { onChange((value + 1).coerceIn(range)) }
    }
}

@Composable
private fun StepButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val c = ForgeTheme.colors
    Box(
        Modifier.size(48.dp).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (enabled) c.text else c.text2.copy(alpha = 0.4f), modifier = Modifier.size(20.dp)) }
}

/** A card row with a title, optional explanation and a switch. */
@Composable
fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, subtitle: String? = null) {
    val c = ForgeTheme.colors
    ForgeCard(modifier.fillMaxWidth()) {
        Row(
            Modifier.clickable { onChange(!checked) }.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = ForgeType.BodyMedium, color = c.text)
                if (subtitle != null) Text(subtitle, style = ForgeType.Small, color = c.text2)
            }
            ForgeSwitch(checked, onChange)
        }
    }
}

/** Small tinted note with an info icon ("The saved copy keeps the password."). */
@Composable
fun InfoNote(text: String, modifier: Modifier = Modifier, icon: ImageVector = ForgeIcons.Info) {
    val c = ForgeTheme.colors
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.tint).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = c.accent, modifier = Modifier.size(16.dp).padding(top = 1.dp))
        Text(text, style = ForgeType.Small, color = c.text)
    }
}

/** Selectable pill used for options such as page size (".chip" / ".chip.on"). */
@Composable
fun OptionChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = ForgeTheme.colors
    Box(
        modifier.heightIn(min = 40.dp).clip(ForgeShapes.Chip)
            .background(if (selected) c.accent else c.surface2)
            .border(1.dp, if (selected) c.accent else c.border, ForgeShapes.Chip)
            .clickable(onClick = onClick).padding(horizontal = 14.dp)
            .semantics { stateDescription = if (selected) "Selected" else "Not selected" },
        contentAlignment = Alignment.Center,
    ) { Text(text, style = ForgeType.Label, color = if (selected) androidx.compose.ui.graphics.Color.White else c.text) }
}

/** Static info chip (".chip.t") such as a page-range preview. */
@Composable
fun TintChip(text: String, modifier: Modifier = Modifier) {
    val c = ForgeTheme.colors
    Text(
        text, style = ForgeType.Label, color = c.accent,
        modifier = modifier.clip(ForgeShapes.Chip).background(c.tint).padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** Small text button used for "Add files", "All"/"None" (48dp high touch target). */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, danger: Boolean = false) {
    val c = ForgeTheme.colors
    val color = when {
        !enabled -> c.text2.copy(alpha = 0.4f)
        danger -> androidx.compose.ui.graphics.Color(0xFFE5484D)
        else -> c.accent
    }
    Row(
        modifier.heightIn(min = 48.dp).clip(CircleShape).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, style = ForgeType.Label, color = color)
    }
}
