package com.namanbansal.forge.tools.pdf

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.annot.Annot
import com.namanbansal.forge.core.pdf.annot.AnnotHit
import com.namanbansal.forge.core.pdf.annot.AnnotPalette
import com.namanbansal.forge.core.pdf.annot.AnnotType
import com.namanbansal.forge.core.pdf.annot.NormPt

/** Drawing of annotations on a page canvas of the page's size. Shared by the saved-annotation layer and the live stroke. */
internal object AnnotDraw {
    /** Quadratic smoothing through the midpoints of the sampled points (the same curve [com.namanbansal.forge.core.pdf.annot.StrokeMath.smoothed] writes to the PDF). */
    fun inkPath(points: List<NormPt>, w: Float, h: Float): Path {
        val p = Path()
        if (points.isEmpty()) return p
        p.moveTo(points[0].x * w, points[0].y * h)
        when (points.size) {
            1 -> p.lineTo(points[0].x * w + 0.01f, points[0].y * h)
            2 -> p.lineTo(points[1].x * w, points[1].y * h)
            else -> {
                for (i in 1 until points.size - 1) {
                    val c = points[i]; val n = points[i + 1]
                    val ex = if (i == points.size - 2) n.x else (c.x + n.x) / 2
                    val ey = if (i == points.size - 2) n.y else (c.y + n.y) / 2
                    p.quadraticTo(c.x * w, c.y * h, ex * w, ey * h)
                }
            }
        }
        return p
    }

    fun DrawScope.markup(type: AnnotType, quads: List<NormRect>, color: Int, alpha: Float, invert: Boolean) {
        val col = Color(color)
        for (q in quads) {
            val l = q.left * size.width; val t = q.top * size.height; val r = q.right * size.width; val b = q.bottom * size.height
            when (type) {
                AnnotType.HIGHLIGHT ->
                    if (invert) drawRect(col.copy(alpha = 0.5f * alpha), Offset(l, t), Size(r - l, b - t))
                    else drawRect(col.copy(alpha = alpha), Offset(l, t), Size(r - l, b - t), blendMode = BlendMode.Multiply)
                AnnotType.UNDERLINE -> {
                    val th = ((b - t) * 0.09f).coerceAtLeast(1.5f)
                    drawLine(col.copy(alpha = alpha), Offset(l, b - th), Offset(r, b - th), th, StrokeCap.Butt)
                }
                AnnotType.STRIKEOUT -> {
                    val th = ((b - t) * 0.08f).coerceAtLeast(1.5f)
                    drawLine(col.copy(alpha = alpha), Offset(l, (t + b) / 2), Offset(r, (t + b) / 2), th, StrokeCap.Butt)
                }
                else -> Unit
            }
        }
    }

    fun DrawScope.ink(path: Path, color: Int, opacity: Float, width: Float) {
        drawPath(path, Color(color).copy(alpha = opacity), style = Stroke(width = width.coerceAtLeast(1f), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    fun DrawScope.note(a: Annot, dim: Boolean) {
        val r = AnnotHit.noteRect(a) ?: return
        val s = AnnotPalette.NOTE_SIZE * size.width
        val x = r.left * size.width; val y = r.top * size.height
        val fill = Color(a.color)
        val a0 = if (dim) 0.35f else 1f
        drawRoundRect(fill.copy(alpha = a0), Offset(x, y), Size(s, s), CornerRadius(s * 0.18f))
        drawRoundRect(Color(0x66000000).copy(alpha = 0.4f * a0), Offset(x, y), Size(s, s), CornerRadius(s * 0.18f), style = Stroke(width = (s * 0.05f).coerceAtLeast(1f)))
        val ink = Color(0xCC111111).copy(alpha = a0)
        for (i in 0..2) {
            val ly = y + s * (0.3f + i * 0.2f)
            drawLine(ink, Offset(x + s * 0.2f, ly), Offset(x + s * (if (i == 2) 0.55f else 0.8f), ly), (s * 0.07f).coerceAtLeast(1f), StrokeCap.Round)
        }
    }

    fun DrawScope.outline(bounds: NormRect, color: Color, pad: Float) {
        val l = bounds.left * size.width - pad; val t = bounds.top * size.height - pad
        val w = (bounds.right - bounds.left) * size.width + 2 * pad; val h = (bounds.bottom - bounds.top) * size.height + 2 * pad
        drawRoundRect(color, Offset(l, t), Size(w, h), CornerRadius(pad), style = Stroke(width = pad * 0.45f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(pad * 1.6f, pad)), cap = StrokeCap.Round))
    }
}
