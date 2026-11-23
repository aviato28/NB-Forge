package com.namanbansal.forge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.namanbansal.forge.core.CrashReporter
import com.namanbansal.forge.ui.components.ForgeButton
import com.namanbansal.forge.ui.components.ForgeCard
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType

/** Shown on the next launch after a crash: calm, with Share / Copy / Dismiss. The report stays in Settings > About. */
@Composable
fun CrashNotice(reporter: CrashReporter) {
    val context = LocalContext.current
    var show by remember { mutableStateOf(reporter.hasUnseen()) }
    val text = remember { reporter.read() }
    if (!show || text == null) return
    val c = ForgeTheme.colors
    ForgeCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Forge closed unexpectedly last time", style = ForgeType.Item, color = c.text)
            Text("Sorry about that. A short report was saved on this phone (nothing was sent anywhere). Sharing it helps get the problem fixed.", style = ForgeType.Small, color = c.text2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ForgeButton("Share report", onClick = { CrashReporter.share(context, text) }, modifier = Modifier.weight(1f))
                ForgeButton("Copy", onClick = { CrashReporter.copy(context, text) }, secondary = true, modifier = Modifier.weight(1f))
            }
            ForgeButton("Dismiss", onClick = { reporter.markSeen(); show = false }, secondary = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Settings > About > Last crash report. */
@Composable
fun CrashReportScreen(reporter: CrashReporter, onBack: () -> Unit) {
    val c = ForgeTheme.colors
    val context = LocalContext.current
    var text by remember { mutableStateOf(reporter.read()) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Last crash report", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val t = text
            if (t == null) {
                Text("No crash has been recorded on this phone.", style = ForgeType.Body, color = c.text2)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ForgeButton("Share report", onClick = { CrashReporter.share(context, t) }, modifier = Modifier.weight(1f))
                    ForgeButton("Copy", onClick = { CrashReporter.copy(context, t) }, secondary = true, modifier = Modifier.weight(1f))
                }
                ForgeButton("Clear report", onClick = { reporter.clear(); text = null }, secondary = true, modifier = Modifier.fillMaxWidth())
                ForgeCard(Modifier.fillMaxWidth()) {
                    Text(t, style = ForgeType.Small.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp), color = c.text2, modifier = Modifier.padding(12.dp))
                }
            }
        }
    }
}
