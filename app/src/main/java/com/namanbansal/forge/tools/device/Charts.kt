package com.namanbansal.forge.tools.device

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.ui.theme.ForgeTheme

/** Live line graph in a bordered card. [values] oldest first; [version] only forces a redraw when the array is reused. */
@Composable
fun LineGraph(
    values: FloatArray,
    min: Float,
    max: Float,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
    capacity: Int = values.size,
    centerLine: Boolean = false,
    version: Int = 0,
    lineColor: Color = ForgeTheme.colors.accent,
) {
    val c = ForgeTheme.colors
    val shape = RoundedCornerShape(16.dp)
    Box(modifier.fillMaxWidth().height(height).clip(shape).background(c.surface).border(1.dp, c.border, shape)) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            @Suppress("UNUSED_VARIABLE") val v = version
            val w = size.width; val h = size.height
            val range = (max - min).coerceAtLeast(1e-6f)
            for (i in 1..3) {
                val y = h * i / 4f
                drawLine(c.border, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }
            if (centerLine) drawLine(c.text2.copy(alpha = 0.5f), Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = 1.5f)
            if (values.size < 2) return@Canvas
            val step = w / (capacity - 1).coerceAtLeast(1)
            val x0 = w - step * (values.size - 1)
            val path = Path()
            values.forEachIndexed { i, y0 ->
                val x = x0 + i * step
                val y = h - ((y0 - min) / range).coerceIn(0f, 1f) * (h - 8f) - 4f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, lineColor, style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
