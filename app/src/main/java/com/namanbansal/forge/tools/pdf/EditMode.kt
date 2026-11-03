package com.namanbansal.forge.tools.pdf

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.annot.UndoRedo
import com.namanbansal.forge.core.pdf.edit.EditFont
import com.namanbansal.forge.core.pdf.edit.EditIo
import com.namanbansal.forge.core.pdf.edit.EditKind
import com.namanbansal.forge.core.pdf.edit.EditObject
import com.namanbansal.forge.core.pdf.edit.EditStyle
import com.namanbansal.forge.ui.theme.ForgeTheme
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The Edit [ViewerMode]: text boxes, pictures, shapes and whiteouts drawn on the pages as they will be written, plus the red
 * redaction marks. Drawn in every mode so unsaved edits never disappear; selection handles only while it is the active mode.
 */
class EditMode(
    val controller: EditController,
    private val interactive: Boolean,
    private val pageWidthPt: (Int) -> Float = { 595f },
    private val imageOf: (String) -> ImageBitmap? = { null },
    private val hasTextSelection: Boolean = false,
    private val onMarkSelection: () -> Unit = {},
    private val onPickImage: () -> Unit = {},
    private val onTakePhoto: () -> Unit = {},
) : ViewerMode {
    override val id = ViewerModeId.EDIT
    override val undoRedo: UndoRedo get() = controller.session
    override val dirty: StateFlow<Boolean> get() = controller.session.dirty
    override val input: PageInput? get() = if (interactive) controller else null

    @Composable
    override fun PageOverlay(page: Int, widthPx: Int, heightPx: Int, invert: Boolean) {
        val c = ForgeTheme.colors
        val state by controller.session.state.collectAsState()
        val selectedId by controller.selectedId.collectAsState()
        val selectedMark by controller.selectedMark.collectAsState()
        val live = controller.live
        val liveMark = controller.liveMark
        val items = state.items.filter { it.page == page }.map { if (live?.id == it.id) live else it }
        val drawingNew = live?.takeIf { l -> l.page == page && items.none { it.id == l.id } }
        val marks = state.marks.filter { it.page == page }
        val accent = c.accent
        val ptScale = widthPx / pageWidthPt(page).coerceAtLeast(1f)
        Canvas(Modifier.fillMaxSize()) {
            for (o in items) drawObject(o, invert, ptScale)
            if (drawingNew != null) drawObject(drawingNew, invert, ptScale)
            val sel = items.firstOrNull { it.id == selectedId }
            if (interactive && sel != null) drawSelection(sel, accent)
            for (m in marks) drawMark(m.rect, m.id == selectedMark, interactive)
            if (liveMark != null && liveMark.page == page) drawMark(liveMark.rect, true, true)
        }
    }

    private fun DrawScope.px(r: NormRect) = Offset(r.left * size.width, r.top * size.height)
    private fun DrawScope.sz(r: NormRect) = Size((r.right - r.left) * size.width, (r.bottom - r.top) * size.height)

    private fun DrawScope.drawMark(r: NormRect, strong: Boolean, show: Boolean) {
        val red = Color(0xFFE53935)
        drawRect(red.copy(alpha = if (strong) 0.22f else 0.12f), px(r), sz(r))
        drawRect(red, px(r), sz(r), style = Stroke(if (strong) 2.5.dp.toPx() else 1.6.dp.toPx()))
        if (strong && show) { // a small cross corner shows which one is selected
            val x = r.right * size.width; val y = r.top * size.height
            drawCircle(Color.White, 9.dp.toPx(), Offset(x, y)); drawCircle(red, 7.dp.toPx(), Offset(x, y))
        }
    }

    private fun DrawScope.drawSelection(o: EditObject, accent: Color) {
        val r = o.rect
        drawRoundRect(accent, px(r), sz(r), CornerRadius(3.dp.toPx()), style = Stroke(1.6.dp.toPx()))
        val x = r.right * size.width; val y = r.bottom * size.height
        drawCircle(Color.White, 9.dp.toPx(), Offset(x, y)); drawCircle(accent, 7.dp.toPx(), Offset(x, y))
    }

    private fun tone(color: Int, invert: Boolean): Color = Color(color).let { if (invert && color == EditStyle.BLACK) Color(0xFFEDEDED) else it }

    private fun DrawScope.drawObject(o: EditObject, invert: Boolean, ptScale: Float) {
        val x = o.rect.left * size.width; val y = o.rect.top * size.height
        val w = (o.rect.right - o.rect.left) * size.width; val h = (o.rect.bottom - o.rect.top) * size.height
        when (o.kind) {
            EditKind.TEXT -> drawText(o, x, y, w, h, tone(o.color, invert))
            EditKind.WHITEOUT -> {
                drawRect(Color(o.fillColor).let { if (invert) it.copy(alpha = 1f) else it }, Offset(x, y), Size(w, h))
                if (o.text.isNotBlank()) drawText(o, x, y, w, h, tone(o.color, invert))
            }
            EditKind.IMAGE -> {
                val img = imageOf(o.imageId)
                if (img == null) drawRect(Color(0x33888888), Offset(x, y), Size(w, h))
                else {
                    val t = ((o.turns % 4) + 4) % 4
                    val dw = if (t % 2 == 0) w else h; val dh = if (t % 2 == 0) h else w
                    rotate(t * 90f, pivot = Offset(x + w / 2, y + h / 2)) {
                        drawImage(img, dstOffset = IntOffset((x + w / 2 - dw / 2).toInt(), (y + h / 2 - dh / 2).toInt()), dstSize = IntSize(dw.toInt().coerceAtLeast(1), dh.toInt().coerceAtLeast(1)),
                            filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium)
                    }
                }
            }
            EditKind.RECT, EditKind.ELLIPSE -> {
                val sw = max(0.5f, o.strokeWidth * ptScale); val inset = sw / 2
                val tl = Offset(x + inset, y + inset); val s = Size(max(1f, w - sw), max(1f, h - sw))
                if (o.filled) {
                    val f = Color(o.fillColor).copy(alpha = o.fillOpacity)
                    if (o.kind == EditKind.RECT) drawRect(f, tl, s) else drawOval(f, tl, s)
                }
                val sc = tone(o.color, invert)
                if (o.kind == EditKind.RECT) drawRect(sc, tl, s, style = Stroke(sw)) else drawOval(sc, tl, s, style = Stroke(sw))
            }
            EditKind.LINE, EditKind.ARROW -> {
                val sw = max(0.5f, o.strokeWidth * ptScale)
                var a = if (o.flip) Offset(x, y + h) else Offset(x, y)
                var b = if (o.flip) Offset(x + w, y) else Offset(x + w, y + h)
                if (o.reverse) { val t = a; a = b; b = t }
                val col = tone(o.color, invert)
                val head = max(8f, o.strokeWidth * 4f) * ptScale
                val ang = atan2(b.y - a.y, b.x - a.x)
                val end = if (o.kind == EditKind.ARROW) Offset(b.x - cos(ang) * head * 0.6f, b.y - sin(ang) * head * 0.6f) else b
                drawLine(col, a, end, sw, cap = StrokeCap.Round)
                if (o.kind == EditKind.ARROW) {
                    val k = 0.45f
                    val p = Path().apply {
                        moveTo(b.x, b.y)
                        lineTo(b.x - head * cos(ang - k), b.y - head * sin(ang - k))
                        lineTo(b.x - head * cos(ang + k), b.y - head * sin(ang + k)); close()
                    }
                    drawPath(p, col)
                }
            }
        }
    }

    private fun DrawScope.drawText(o: EditObject, x: Float, y: Float, w: Float, h: Float, col: Color) {
        val sizePx = (o.sizeFrac * size.width).coerceAtLeast(2f)
        val pad = sizePx * 0.25f
        val typeface = when (o.font) {
            EditFont.SANS -> Typeface.create(Typeface.SANS_SERIF, if (o.bold) Typeface.BOLD else Typeface.NORMAL)
            EditFont.SERIF -> Typeface.create(Typeface.SERIF, if (o.bold) Typeface.BOLD else Typeface.NORMAL)
            EditFont.MONO -> Typeface.create(Typeface.MONOSPACE, if (o.bold) Typeface.BOLD else Typeface.NORMAL)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col.toArgb(); textSize = sizePx; this.typeface = typeface }
        val pdfFont = runCatching { EditIo.fontFor(o.font, o.bold) }.getOrNull()
        val lines = if (pdfFont != null) runCatching { EditIo.wrap(pdfFont, o.text, w - 2 * pad, sizePx) }.getOrNull() else null
        val shown = lines ?: o.text.split('\n')
        val canvas = drawContext.canvas.nativeCanvas
        canvas.save(); canvas.clipRect(x, y, x + w, y + h)
        var by = y + pad + sizePx * 0.8f
        for (l in shown) {
            if (l.isNotEmpty()) {
                val lw = runCatching { pdfFont!!.getStringWidth(l) / 1000f * sizePx }.getOrElse { paint.measureText(l) }
                val lx = when (o.align) { 1 -> x + (w - lw) / 2; 2 -> x + w - pad - lw; else -> x + pad }
                canvas.drawText(l, lx, by, paint)
            }
            by += sizePx * 1.2f
        }
        canvas.restore()
    }

    @Composable
    override fun Toolbar() = EditToolbar(controller, hasTextSelection, onMarkSelection, onPickImage, onTakePhoto)
}

