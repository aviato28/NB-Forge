package com.namanbansal.forge.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.namanbansal.forge.ui.components.ForgeButton
import com.namanbansal.forge.ui.components.IconBox
import com.namanbansal.forge.ui.components.Tag
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.update.Channel
import com.namanbansal.forge.update.UpdateInfo
import com.namanbansal.forge.update.UpdateManager
import com.namanbansal.forge.update.UpdateState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheet(manager: UpdateManager) {
    val visible by manager.sheetVisible.collectAsStateWithLifecycle()
    val state by manager.state.collectAsStateWithLifecycle()
    if (!visible) return
    val c = ForgeTheme.colors
    val context = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val info: UpdateInfo? = when (val s = state) {
        is UpdateState.Available -> s.info
        is UpdateState.Downloading -> s.info
        is UpdateState.NeedsPermission -> s.info
        is UpdateState.Installing -> s.info
        is UpdateState.Failed -> s.info
        else -> null
    }
    if (info == null) return

    ModalBottomSheet(
        onDismissRequest = { manager.dismiss() },
        sheetState = sheet,
        containerColor = c.surface,
        contentColor = c.text,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBox(ForgeIcons.Download, size = 44.dp)
                Column(Modifier.weight(1f)) {
                    Text("Update available", style = ForgeType.Title, color = c.text)
                    Text(
                        "Forge ${info.version}" + if (info.size > 0) " · ${Formatter.formatShortFileSize(context, info.size)}" else "",
                        style = ForgeType.Small, color = c.text2,
                    )
                }
                if (info.channel == Channel.Beta) Tag("Beta")
            }
            Spacer(Modifier.height(16.dp))
            Text("What's new", style = ForgeType.Label, color = c.text2)
            Spacer(Modifier.height(6.dp))
            Text(
                info.notes.ifBlank { "Bug fixes and improvements." },
                style = ForgeType.Body, color = c.text,
                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
            )
            Spacer(Modifier.height(20.dp))
            when (val s = state) {
                is UpdateState.Downloading -> {
                    Text(if (s.progress >= 0) "Downloading… ${(s.progress * 100).toInt()}%" else "Downloading…", style = ForgeType.Small, color = c.text2)
                    Spacer(Modifier.height(8.dp))
                    if (s.progress >= 0) {
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().height(8.dp), color = c.accent, trackColor = c.surface2, drawStopIndicator = {})
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp), color = c.accent, trackColor = c.surface2)
                    }
                    Spacer(Modifier.height(16.dp))
                    ForgeButton("Cancel", onClick = { manager.dismiss() }, secondary = true, modifier = Modifier.fillMaxWidth())
                }
                is UpdateState.NeedsPermission -> {
                    Text(
                        "To install updates, allow Forge to install apps. Android will open the setting for Forge — turn on \"Allow from this source\", then come back.",
                        style = ForgeType.Small, color = c.text2,
                    )
                    Spacer(Modifier.height(16.dp))
                    ForgeButton("Open settings", onClick = { context.startActivity(manager.unknownSourcesIntent()) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    ForgeButton("Later", onClick = { manager.dismiss() }, secondary = true, modifier = Modifier.fillMaxWidth())
                }
                is UpdateState.Installing -> {
                    Text("Waiting for Android to confirm the install…", style = ForgeType.Small, color = c.text2)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp), color = c.accent, trackColor = c.surface2)
                }
                is UpdateState.Failed -> {
                    Text(s.message, style = ForgeType.Small, color = c.accent)
                    Spacer(Modifier.height(16.dp))
                    UpdateButtons(onUpdate = { manager.download(info) }, onLater = { manager.dismiss() }, updateLabel = "Try again")
                }
                else -> UpdateButtons(onUpdate = { manager.download(info) }, onLater = { manager.dismiss() })
            }
        }
    }
}

@Composable
private fun UpdateButtons(onUpdate: () -> Unit, onLater: () -> Unit, updateLabel: String = "Update") {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ForgeButton("Later", onClick = onLater, secondary = true, modifier = Modifier.weight(1f))
        ForgeButton(updateLabel, onClick = onUpdate, modifier = Modifier.weight(1f))
    }
}
