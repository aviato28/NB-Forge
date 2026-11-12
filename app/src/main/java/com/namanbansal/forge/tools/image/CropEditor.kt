package com.namanbansal.forge.tools.image

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.core.image.CropMath
import com.namanbansal.forge.core.image.FRect
import com.namanbansal.forge.core.image.Handle
import com.namanbansal.forge.ui.theme.ForgeTheme
import kotlin.math.min

/** Picture with draggable crop handles (free or aspect-locked). [rect] and the callback are in [bitmap] pixels. */
@Composable
fun CropEditor(bitmap: Bitmap, rect: FRect, ratio: Float?, onRect: (FRect) -> Unit, modifier: Modifier = Modifier) {
    val accent = ForgeTheme.colors.accent
    val img = remember(bitmap) { bitmap.asImageBitmap() }
    val bw = bitmap.width.toFloat()
    val bh = bitmap.height.toFloat()
    val currentRect by rememberUpdatedState(rect)
    val currentRatio by rememberUpdatedState(ratio)
    BoxWithConstraints(modifier.semantics { contentDescription = "Crop area. Drag the corners or edges." }) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val maxW = with(density) { maxWidth.toPx() }
        val maxH = with(density) { maxHeight.toPx() }
        val s = min(maxW / bw, maxH / bh)
        val ox = (maxW - bw * s) / 2
        val oy = (maxH - bh * s) / 2
        val slop = with(density) { 28.dp.toPx() } / s
        var active by remember { mutableStateOf<Handle?>(null) }
        Canvas(
            Modifier.fillMaxSize().pointerInput(bitmap) {
                detectDragGestures(
                    onDragStart = { p -> active = CropMath.hit(currentRect, (p.x - ox) / s, (p.y - oy) / s, slop) },
                    onDragEnd = { active = null }, onDragCancel = { active = null },
                    onDrag = { change, drag ->
                        val h = active ?: return@detectDragGestures
                        change.consume()
                        onRect(CropMath.drag(currentRect, h, drag.x / s, drag.y / s, bw, bh, currentRatio))
                    },
                )
            },
        ) {
            drawImage(img, dstOffset = IntOffset(ox.toInt(), oy.toInt()), dstSize = IntSize((bw * s).toInt(), (bh * s).toInt()))
            val l = ox + rect.l * s; val t = oy + rect.t * s; val r = ox + rect.r * s; val b = oy + rect.b * s
            val dim = Color.Black.copy(alpha = 0.5f)
            drawRect(dim, Offset(ox, oy), Size(bw * s, t - oy))
            drawRect(dim, Offset(ox, b), Size(bw * s, oy + bh * s - b))
            drawRect(dim, Offset(ox, t), Size(l - ox, b - t))
            drawRect(dim, Offset(r, t), Size(ox + bw * s - r, b - t))
            drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(2.dp.toPx()))
            val third = Color.White.copy(alpha = 0.45f)
            for (i in 1..2) {
                drawLine(third, Offset(l + (r - l) * i / 3, t), Offset(l + (r - l) * i / 3, b), 1.dp.toPx())
                drawLine(third, Offset(l, t + (b - t) * i / 3), Offset(r, t + (b - t) * i / 3), 1.dp.toPx())
            }
            val hs = 7.dp.toPx()
            listOf(l to t, r to t, l to b, r to b, (l + r) / 2 to t, (l + r) / 2 to b, l to (t + b) / 2, r to (t + b) / 2).forEach { (x, y) ->
                drawRect(accent, Offset(x - hs, y - hs), Size(hs * 2, hs * 2))
                drawRect(Color.White, Offset(x - hs, y - hs), Size(hs * 2, hs * 2), style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}
