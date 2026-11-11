package com.namanbansal.forge.tools.device

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.core.device.CameraSession
import com.namanbansal.forge.core.device.ImageFilters
import com.namanbansal.forge.core.device.LocalCameraBackend
import com.namanbansal.forge.core.device.PictureSaver
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.ForgeButton
import com.namanbansal.forge.ui.components.OptionChip
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.Perms
import com.namanbansal.forge.ui.toolkit.PermissionGate
import com.namanbansal.forge.ui.toolkit.ToolFrame
import com.namanbansal.forge.ui.toolkit.rememberHaptics
import com.namanbansal.forge.ui.toolkit.rememberSnack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CONTRASTS = listOf("Normal" to 1f, "High" to 1.6f, "Max" to 2.4f)

/** D7: camera magnifier with pinch / slider zoom, torch, freeze frame, contrast and invert filters, and save-frame. */
@Composable
fun MagnifierScreen(host: ToolHost) {
    ToolFrame(host, scroll = false, padding = false) {
        PermissionGate(
            Perms.CAMERA, ForgeIcons.ZoomIn, "Camera access",
            "The magnifier uses the camera to enlarge small print and details. Nothing is recorded unless you tap Save frame.",
        ) { Magnifier() }
    }
}

@Composable
private fun Magnifier() {
    val c = ForgeTheme.colors
    val backend = LocalCameraBackend.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = rememberSnack()
    val haptics = rememberHaptics()
    var session by remember { mutableStateOf<CameraSession?>(null) }
    var failed by remember { mutableStateOf(false) }
    var zoom by rememberSaveable { mutableFloatStateOf(2f) }
    var torch by rememberSaveable { mutableStateOf(false) }
    var contrastIdx by rememberSaveable { mutableStateOf(0) }
    var invert by rememberSaveable { mutableStateOf(false) }
    var frozen by remember { mutableStateOf<Bitmap?>(null) }
    var saving by remember { mutableStateOf(false) }
    val maxZoom = (session?.maxZoom ?: 8f).coerceIn(2f, 10f)
    val matrix = ImageFilters.matrix(CONTRASTS[contrastIdx].second, invert)

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(20.dp)).background(androidx.compose.ui.graphics.Color.Black)
                .pointerInput(maxZoom) {
                    detectTransformGestures { _, _, gesture, _ ->
                        if (frozen == null) { zoom = (zoom * gesture).coerceIn(1f, maxZoom); session?.setZoom(zoom) }
                    }
                },
        ) {
            backend.Preview(Modifier.fillMaxSize(), null, matrix) { s -> session = s; failed = s == null; s?.setZoom(zoom); s?.setTorch(torch) }
            frozen?.let { bmp ->
                Image(
                    bmp.asImageBitmap(), "Frozen frame", Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black), contentScale = ContentScale.Crop,
                    colorFilter = matrix?.let { ColorFilter.colorMatrix(ColorMatrix(it)) },
                )
            }
            if (failed) Text("Camera could not start. Close other camera apps and try again.", style = ForgeType.Body, color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.padding(24.dp))
            Text(
                "%.1f×".format(java.util.Locale.US, zoom), style = ForgeType.Label, color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.align(androidx.compose.ui.Alignment.TopStart).padding(12.dp).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Slider(
                value = zoom.coerceIn(1f, maxZoom), onValueChange = { zoom = it; session?.setZoom(it) }, valueRange = 1f..maxZoom, enabled = frozen == null,
                colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.border),
            )
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CONTRASTS.forEachIndexed { i, (name, _) -> OptionChip(if (i == 0) "Normal" else "$name contrast", contrastIdx == i, { contrastIdx = i }) }
                OptionChip("Invert", invert, { invert = !invert })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (session?.hasTorch == true) ForgeButton(if (torch) "Torch off" else "Torch", onClick = { torch = !torch; session?.setTorch(torch) }, secondary = !torch, icon = ForgeIcons.Torch, modifier = Modifier.weight(1f))
                ForgeButton(
                    if (frozen == null) "Freeze" else "Resume",
                    onClick = { if (frozen == null) { frozen = session?.grabFrame(); if (frozen != null) haptics.tick() else snack("No picture yet, try again in a moment.") } else frozen = null },
                    secondary = true, icon = if (frozen == null) ForgeIcons.Pin else ForgeIcons.Refresh, modifier = Modifier.weight(1f),
                )
            }
            if (frozen != null) {
                ForgeButton(if (saving) "Saving…" else "Save frame to Pictures/Forge", enabled = !saving, onClick = {
                    val bmp = frozen ?: return@ForgeButton
                    saving = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.Default) { ImageFilters.apply(bmp, matrix) }.let { PictureSaver.save(context, it, "Magnifier") }
                            haptics.confirm(); snack("Saved to Pictures/Forge")
                        } catch (t: Exception) { snack("Could not save: ${t.message ?: "unknown error"}") }
                        saving = false
                    }
                }, icon = ForgeIcons.Download, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
