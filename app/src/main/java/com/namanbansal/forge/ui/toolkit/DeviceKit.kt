package com.namanbansal.forge.ui.toolkit

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.ForgeButton
import com.namanbansal.forge.ui.components.IconBox
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.screens.TopBar
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Standard screen for the Device and Utilities tools: top bar with back, optional trailing actions and a padded
 * column (scrollable unless [scroll] is false, e.g. a full-bleed camera view).
 */
@Composable
fun ToolFrame(
    host: ToolHost,
    modifier: Modifier = Modifier,
    scroll: Boolean = true,
    padding: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
        TopBar(host.tool.name, onBack = host.onBack, trailing = actions)
        var body = Modifier.weight(1f).fillMaxWidth()
        if (scroll) body = body.verticalScroll(rememberScrollState())
        if (padding) body = body.padding(horizontal = 16.dp, vertical = 4.dp)
        Column(body, verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** Keeps the display awake while this composable is part of the screen (level, sound meter, ...). */
@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
}

/** Light tick feedback; a no-op where the device does not support it. */
class Haptics(private val view: View) {
    fun tick() { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    fun confirm() { view.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }
    fun heavy() { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

/** State of one runtime permission: [granted] is re-read whenever the screen resumes (e.g. back from Settings). */
class PermissionState(val permission: String, granted: Boolean, private val launch: () -> Unit, val permanentlyDenied: Boolean, private val openSettings: () -> Unit) {
    val granted: Boolean = granted
    fun request() = launch()
    fun settings() = openSettings()
}

@Composable
fun rememberPermission(permission: String): PermissionState {
    val context = LocalContext.current
    fun check() = ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(check()) }
    var asked by rememberSaveable(permission) { mutableStateOf(false) }
    var permanent by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        asked = true
        val activity = context as? Activity
        permanent = !ok && activity != null && !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = check() }
    return PermissionState(
        permission, granted, { launcher.launch(permission) }, permanent && asked,
        { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
    )
}

/**
 * Shows [content] once [permission] is granted; until then a clear rationale with an Allow button, and an
 * "Open settings" button after the user chose "Don't ask again".
 */
@Composable
fun PermissionGate(
    permission: String,
    icon: ImageVector,
    title: String,
    rationale: String,
    modifier: Modifier = Modifier,
    extra: @Composable ColumnScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    val state = rememberPermission(permission)
    if (state.granted) {
        content()
        return
    }
    val c = ForgeTheme.colors
    Column(modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        IconBox(icon, size = 72.dp, iconSize = 32.dp, shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = ForgeType.Section, color = c.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(rationale, style = ForgeType.Body, color = c.text2, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        if (state.permanentlyDenied) {
            Text("Permission is blocked. Turn it on in the app settings to use this tool.", style = ForgeType.Small, color = c.text2, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            ForgeButton("Open settings", onClick = state::settings, icon = ForgeIcons.Settings, modifier = Modifier.fillMaxWidth())
        } else {
            ForgeButton("Allow", onClick = state::request, icon = ForgeIcons.Check, modifier = Modifier.fillMaxWidth())
        }
        extra()
    }
}

object Perms {
    const val CAMERA = Manifest.permission.CAMERA
    const val MIC = Manifest.permission.RECORD_AUDIO
    const val COARSE_LOCATION = Manifest.permission.ACCESS_COARSE_LOCATION
    const val NOTIFICATIONS = Manifest.permission.POST_NOTIFICATIONS
}

/** Big tabular number with a small caption underneath (readouts on the sensor tools). */
@Composable
fun Readout(value: String, caption: String, modifier: Modifier = Modifier, size: Int = 56, emphasised: Boolean = false, fill: Boolean = true) {
    val c = ForgeTheme.colors
    Column(if (fill) modifier.fillMaxWidth() else modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = ForgeType.Display.copy(fontSize = size.sp, lineHeight = (size * 1.1f).sp, fontFeatureSettings = "tnum"),
            color = if (emphasised) c.accent else c.text, maxLines = 1,
        )
        Text(caption, style = ForgeType.Small, color = c.text2)
    }
}

/** Row of equal-weight children (stat triplets such as min / avg / max). */
@Composable
fun StatRow(vararg stats: Pair<String, String>, modifier: Modifier = Modifier) {
    val c = ForgeTheme.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        stats.forEach { (label, value) ->
            com.namanbansal.forge.ui.components.ForgeCard(Modifier.weight(1f)) {
                Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(value, style = ForgeType.Section, color = c.text, maxLines = 1)
                    Text(label, style = ForgeType.Small, color = c.text2)
                }
            }
        }
    }
}
