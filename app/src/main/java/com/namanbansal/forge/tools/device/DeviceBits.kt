package com.namanbansal.forge.tools.device

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.ui.components.ForgeCard
import com.namanbansal.forge.ui.components.IconBox
import com.namanbansal.forge.ui.components.SectionHeader
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import androidx.compose.ui.graphics.vector.ImageVector

/** Shown when the phone lacks the sensor a tool needs. */
@Composable
fun SensorMissing(what: String, modifier: Modifier = Modifier, icon: ImageVector = ForgeIcons.Info) {
    val c = ForgeTheme.colors
    ForgeCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            IconBox(icon, size = 56.dp, iconSize = 26.dp)
            Text("Sensor not available", style = ForgeType.Section, color = c.text)
            Text("This phone has no $what, so this tool can't take readings here.", style = ForgeType.Small, color = c.text2)
        }
    }
}

/** A titled card holding a list of "value  label" reference rows; the row at [highlight] is tinted. */
@Composable
fun ReferenceChart(title: String, rows: List<Pair<String, String>>, highlight: Int = -1, modifier: Modifier = Modifier) {
    val c = ForgeTheme.colors
    Column(modifier.fillMaxWidth()) {
        SectionHeader(title)
        ForgeCard(Modifier.fillMaxWidth()) {
            rows.forEachIndexed { i, (v, label) ->
                Row(
                    Modifier.fillMaxWidth().let { if (i == highlight) it.background(c.tint) else it }.padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(v, style = ForgeType.Item, color = if (i == highlight) c.accent else c.text, modifier = Modifier.size(width = 76.dp, height = 18.dp))
                    Text(label, style = ForgeType.Small, color = c.text2)
                }
            }
        }
    }
}
