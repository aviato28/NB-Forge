package com.namanbansal.forge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.namanbansal.forge.core.DefaultPdfViewer
import com.namanbansal.forge.core.DefaultViewer
import com.namanbansal.forge.ui.components.ForgeButton
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
import com.namanbansal.forge.ui.components.NbMark
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
                GroupLabel("PDF viewer")
                val context = LocalContext.current
                var state by remember { mutableStateOf(DefaultPdfViewer.detect(context)) }
                LifecycleResumeEffect(Unit) { state = DefaultPdfViewer.detect(context); onPauseOrDispose {} } // coming back from App info / the chooser
                PdfViewerSettings(
                    state = state, keepScreenOn = settings.viewer.keepScreenOn,
                    onKeepScreenOn = { v -> scope.launch { app.settings.setViewerPrefs(settings.viewer.copy(keepScreenOn = v)) } },
                    onOpenAppInfo = { pkg -> runCatching { context.startActivity(DefaultPdfViewer.appInfoIntent(pkg)) } },
                    onTest = { runCatching { context.startActivity(DefaultPdfViewer.testIntent(context)) } },
                    keepCopies = settings.keepCopies, onKeepCopies = { v -> scope.launch { app.settings.setKeepCopies(v) } },
                )
            }
            item {
                GroupLabel("Developer")
                ForgeCard(Modifier.fillMaxWidth()) {
                    ListRow(
                        "Show which text can be edited", subtitle = "In Edit existing, tints editable text green and locked text red, to find out why a PDF can't be edited",
                        icon = ForgeIcons.Code, showDivider = false, trailing = { ForgeSwitch(settings.debugOverlay) { v -> scope.launch { app.settings.setDebugOverlay(v) } } },
                    )
                }
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
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        NbMark(44.dp)
                        Column {
                            Text("Forge by Naman Bansal", style = ForgeType.Item.copy(fontWeight = FontWeight.Medium), color = c.text)
                            Text("Every tool. One app.", style = ForgeType.Small, color = c.text2)
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 12.dp), thickness = 1.dp, color = c.border)
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

/**
 * "Make Forge your PDF viewer": a check when Forge already opens PDFs, otherwise two short steps with buttons for each
 * (App info of the current default app > Open by default > Clear defaults, then Test it and pick Forge > Always).
 */
@Composable
fun PdfViewerSettings(
    state: DefaultViewer,
    keepScreenOn: Boolean,
    onKeepScreenOn: (Boolean) -> Unit,
    onOpenAppInfo: (String) -> Unit,
    onTest: () -> Unit,
    keepCopies: Boolean = true,
    onKeepCopies: (Boolean) -> Unit = {},
) {
    val c = ForgeTheme.colors
    ForgeCard(Modifier.fillMaxWidth()) {
        when (state) {
            DefaultViewer.Forge -> ListRow(
                "Forge is your PDF viewer", subtitle = "PDFs you tap in other apps open here.", icon = ForgeIcons.Check,
                trailing = {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                        Icon(ForgeIcons.Check, "Default", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                },
            )
            else -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Make Forge your PDF viewer", style = ForgeType.Section, color = c.text)
                Text(
                    if (state is DefaultViewer.Other) "PDFs currently open in ${state.label}. Switch in two steps:" else "Android asks which app to use each time. Fix that in one step:",
                    style = ForgeType.Small, color = c.text2,
                )
                if (state is DefaultViewer.Other) {
                    Step("1", "Open App info, tap Open by default, then Clear defaults.")
                    Step("2", "Come back and tap Test it. Choose Forge, then Always.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ForgeButton("Open App info", { onOpenAppInfo(state.packageName) }, Modifier.weight(1f), secondary = true)
                        ForgeButton("Test it", onTest, Modifier.weight(1f))
                    }
                } else {
                    Step("1", "Tap Test it. Choose Forge, then Always.")
                    ForgeButton("Test it", onTest, Modifier.fillMaxWidth())
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 12.dp), thickness = 1.dp, color = c.border)
        ListRow("Keep screen on while reading", subtitle = "Stops the screen from sleeping in the viewer", icon = ForgeIcons.Sun, trailing = {
            ForgeSwitch(keepScreenOn, onKeepScreenOn)
        })
        ListRow("Keep a copy of PDFs from other apps", subtitle = "So a PDF from email or a shopping app still opens from Recent later", icon = ForgeIcons.Copy, showDivider = false, trailing = {
            ForgeSwitch(keepCopies, onKeepCopies)
        })
    }
}

@Composable
private fun Step(n: String, text: String) {
    val c = ForgeTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(c.tint), contentAlignment = Alignment.Center) { Text(n, style = ForgeType.Tiny, color = c.accent) }
        Text(text, style = ForgeType.Small, color = c.text, modifier = Modifier.weight(1f))
    }
}
