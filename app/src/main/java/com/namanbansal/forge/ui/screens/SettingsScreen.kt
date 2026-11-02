package com.namanbansal.forge.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.namanbansal.forge.BuildConfig
import com.namanbansal.forge.ForgeApp
import com.namanbansal.forge.data.Settings
import com.namanbansal.forge.data.ThemeMode
import com.namanbansal.forge.ui.components.ForgeCard
import com.namanbansal.forge.ui.components.ForgeSwitch
import com.namanbansal.forge.ui.components.GroupLabel
import com.namanbansal.forge.ui.components.ListRow
import com.namanbansal.forge.ui.components.Segmented
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.update.UpdateState
import kotlinx.coroutines.launch

const val WEBSITE_URL = "https://nbansal28.com"
const val SOURCE_URL = "https://github.com/aviato28/NB-Forge"

@Composable
fun SettingsScreen(app: ForgeApp, settings: Settings, onLicenses: () -> Unit) {
    val c = ForgeTheme.colors
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val update by app.updates.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TopBar("Settings")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            item {
                GroupLabel("Theme", Modifier.padding(top = 0.dp))
                Segmented(
                    options = listOf("System", "Light", "Dark"),
                    selected = settings.theme.ordinal,
                    onSelect = { i -> scope.launch { app.settings.setTheme(ThemeMode.entries[i]) } },
                )
            }
            item {
                GroupLabel("Updates")
                ForgeCard(Modifier.fillMaxWidth()) {
                    ListRow("Current version", subtitle = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", icon = ForgeIcons.Info, trailing = {})
                    val status = when (val s = update) {
                        UpdateState.Checking -> "Checking…"
                        UpdateState.UpToDate -> "You're up to date"
                        is UpdateState.Available -> "Forge ${s.info.version} is available"
                        is UpdateState.Downloading -> "Downloading ${s.info.version}…"
                        is UpdateState.NeedsPermission -> "Waiting for install permission"
                        is UpdateState.Installing -> "Installing…"
                        is UpdateState.Failed -> s.message
                        UpdateState.Idle -> null
                    }
                    ListRow(
                        "Check for updates", subtitle = status, icon = ForgeIcons.Refresh,
                        onClick = { app.updates.check(manual = true) },
                        trailing = {
                            if (update == UpdateState.Checking) CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
                            else Icon(ForgeIcons.Chevron, null, tint = c.text2, modifier = Modifier.size(16.dp))
                        },
                    )
                    ListRow("Check automatically", subtitle = "Once a day when Forge opens", icon = ForgeIcons.Clock, trailing = {
                        ForgeSwitch(settings.autoCheckUpdates) { v -> scope.launch { app.settings.setAutoCheck(v) } }
                    })
                    ListRow("Beta updates", subtitle = "Get pre-release builds early", icon = ForgeIcons.Sparkle, showDivider = false, trailing = {
                        ForgeSwitch(settings.betaUpdates) { v -> scope.launch { app.settings.setBeta(v) } }
                    })
                }
            }
            item {
                GroupLabel("About")
                ForgeCard(Modifier.fillMaxWidth()) {
                    ListRow("Forge by Naman Bansal", subtitle = "Every tool. One app.", icon = ForgeIcons.Info, trailing = {})
                    ListRow("Website", subtitle = "nbansal28.com", icon = ForgeIcons.Globe, onClick = { uri.openUri(WEBSITE_URL) }, trailing = {
                        Icon(ForgeIcons.Open, null, tint = c.text2, modifier = Modifier.size(16.dp))
                    })
                    ListRow("Source code", subtitle = "github.com/aviato28/NB-Forge", icon = ForgeIcons.Code, onClick = { uri.openUri(SOURCE_URL) }, trailing = {
                        Icon(ForgeIcons.Open, null, tint = c.text2, modifier = Modifier.size(16.dp))
                    })
                    ListRow("Open-source licences", icon = ForgeIcons.FileText, showDivider = false, onClick = onLicenses)
                }
                Text(
                    "Forge checks GitHub Releases for updates. No account, no tracking.",
                    style = ForgeType.Small, color = c.text2, modifier = Modifier.padding(top = 12.dp, start = 4.dp),
                )
            }
        }
    }
}
