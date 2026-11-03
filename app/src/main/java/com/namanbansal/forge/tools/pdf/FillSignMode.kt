package com.namanbansal.forge.tools.pdf

import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
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
import com.namanbansal.forge.core.pdf.fill.FillItem
import com.namanbansal.forge.core.pdf.fill.FillPalette
import com.namanbansal.forge.core.pdf.fill.ItemKind
import com.namanbansal.forge.core.pdf.form.FieldKind
import com.namanbansal.forge.core.pdf.form.FormField
import com.namanbansal.forge.core.pdf.form.FormValues
import com.namanbansal.forge.ui.theme.ForgeTheme
import kotlinx.coroutines.flow.StateFlow

/**
 * The Fill & Sign [ViewerMode]: form hotspots with the values typed so far, text boxes, check marks and signatures. The overlay
 * is drawn in every mode (so unsaved changes never disappear); hotspots, selection and handles only while it is the active mode
 * ([interactive]). Touch input goes to the [controller].
 */
class FillSignMode(
    val controller: FillSignController,
    private val interactive: Boolean,
    private val notice: String? = null,
    private val sigBitmap: (String) -> ImageBitmap? = { null },
) : ViewerMode {
    override val id = ViewerModeId.FILL_SIGN
    override val undoRedo: UndoRedo get() = controller.session
    override val dirty: StateFlow<Boolean> get() = controller.session.dirty
    override val input: PageInput? get() = if (interactive) controller else null

    @Composable
    override fun PageOverlay(page: Int, widthPx: Int, heightPx: Int, invert: Boolean) {
        val c = ForgeTheme.colors
        val state by controller.session.state.collectAsState()
        val form by controller.form.collectAsState()
        val focus by controller.focusId.collectAsState()
        val selectedId by controller.selectedId.collectAsState()
        val live = controller.live
        val items = state.items.filter { it.page == page }.map { if (live?.id == it.id) live else it }
        val fields = form.fields.filter { it.page == page }
        val accent = c.accent
        Canvas(Modifier.fillMaxSize()) {
            for (f in fields) {
                val v = FormValues.valueOf(f, state.overrides)
                val edited = state.overrides.containsKey(f.name)
                val r = f.rect
                if (interactive && !f.readOnly) {
                    drawRect(accent.copy(alpha = if (f.id == focus) 0.22f else 0.10f), topLeft(r), sizeOf(r))
                    drawRect(accent.copy(alpha = if (f.id == focus) 0.95f else if (f.required) 0.75f else 0.35f), topLeft(r), sizeOf(r), style = Stroke(if (f.id == focus || f.required) 1.6.dp.toPx() else 1.dp.toPx()))
                    if (f.required && f.kind != FieldKind.SIGNATURE && v.isBlank() || f.required && f.kind == FieldKind.SIGNATURE) star(r, accent)
                }
                if (edited) drawValue(f, v, c.text, invert)
            }
            for (it in items) drawItem(it, invert)
            val sel = items.firstOrNull { it.id == selectedId }
            if (interactive && sel != null) drawSelection(sel, accent)
        }
    }

    private fun DrawScope.topLeft(r: NormRect) = Offset(r.left * size.width, r.top * size.height)
    private fun DrawScope.sizeOf(r: NormRect) = Size((r.right - r.left) * size.width, (r.bottom - r.top) * size.height)

    private fun DrawScope.star(r: NormRect, color: Color) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toArgbCompat(); textSize = 13.dp.toPx(); typeface = Typeface.DEFAULT_BOLD }
        drawContext.canvas.nativeCanvas.drawText("*", r.right * size.width - 9.dp.toPx(), r.top * size.height + 13.dp.toPx(), p)
    }

    private fun DrawScope.drawValue(f: FormField, v: String, ink: Color, invert: Boolean) {
        val r = f.rect
        val paper = if (invert) Color(0xFF16161A) else Color.White
        val inkC = if (invert) Color(0xFFEDEDED) else Color(0xFF111111)
        when (f.kind) {
            FieldKind.CHECKBOX, FieldKind.RADIO -> {
                drawRect(paper, topLeft(r), sizeOf(r))
                val on = v == f.onValue
                val w = (r.right - r.left) * size.width; val h = (r.bottom - r.top) * size.height
                if (f.kind == FieldKind.RADIO) {
                    drawCircle(inkC, radius = minOf(w, h) / 2 * 0.8f, center = Offset(r.left * size.width + w / 2, r.top * size.height + h / 2), style = Stroke(1.2.dp.toPx()))
                    if (on) drawCircle(inkC, radius = minOf(w, h) / 2 * 0.42f, center = Offset(r.left * size.width + w / 2, r.top * size.height + h / 2))
                } else {
                    drawRect(inkC, topLeft(r), sizeOf(r), style = Stroke(1.dp.toPx()))
                    if (on) {
                        val x0 = r.left * size.width; val y0 = r.top * size.height
                        drawLine(inkC, Offset(x0 + w * 0.18f, y0 + h * 0.52f), Offset(x0 + w * 0.42f, y0 + h * 0.78f), 2.dp.toPx())
                        drawLine(inkC, Offset(x0 + w * 0.42f, y0 + h * 0.78f), Offset(x0 + w * 0.84f, y0 + h * 0.22f), 2.dp.toPx())
                    }
                }
            }
            else -> {
                drawRect(paper, topLeft(r), sizeOf(r))
                val text = v.replace('\n', ' ').takeIf { f.kind != FieldKind.MULTILINE } ?: v
                val h = (r.bottom - r.top) * size.height
                val px = (if (f.kind == FieldKind.MULTILINE) 13.dp.toPx() else (h * 0.62f).coerceIn(8.dp.toPx(), 16.dp.toPx()))
                val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = inkC.toArgbCompat(); textSize = px; typeface = Typeface.SANS_SERIF }
                val w = ((r.right - r.left) * size.width - 6.dp.toPx()).toInt().coerceAtLeast(8)
                val canvas = drawContext.canvas.nativeCanvas
                canvas.save()
                canvas.clipRect(r.left * size.width, r.top * size.height, r.right * size.width, r.bottom * size.height)
                if (f.kind == FieldKind.MULTILINE) {
                    canvas.translate(r.left * size.width + 3.dp.toPx(), r.top * size.height + 3.dp.toPx())
                    StaticLayout.Builder.obtain(text, 0, text.length, tp, w).setAlignment(Layout.Alignment.ALIGN_NORMAL).build().draw(canvas)
                } else {
                    val base = r.top * size.height + h / 2 - (tp.ascent() + tp.descent()) / 2
                    canvas.drawText(text, r.left * size.width + 3.dp.toPx(), base, tp)
                }
                canvas.restore()
            }
        }
    }

    private fun DrawScope.drawItem(it: FillItem, invert: Boolean) {
        val r = it.rect
        val x = r.left * size.width; val y = r.top * size.height
        val w = (r.right - r.left) * size.width; val h = (r.bottom - r.top) * size.height
        val col = Color(it.color).let { c -> if (invert && it.color == FillPalette.BLACK) Color(0xFFEDEDED) else c }
        when (it.kind) {
            ItemKind.TEXT -> {
                val px = FillPalette.sizes[it.size.coerceIn(0, 2)] * size.width
                val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = col.toArgbCompat(); textSize = px; typeface = Typeface.SANS_SERIF }
                val pad = px * 0.25f
                val canvas = drawContext.canvas.nativeCanvas
                canvas.save(); canvas.clipRect(x, y, x + w, y + h); canvas.translate(x + pad, y + pad)
                val text = it.text.ifEmpty { " " }
                StaticLayout.Builder.obtain(text, 0, text.length, tp, (w - 2 * pad).toInt().coerceAtLeast(4)).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.0f).build().draw(canvas)
                canvas.restore()
            }
            ItemKind.CHECK -> {
                drawLine(col, Offset(x + w * 0.14f, y + h * 0.52f), Offset(x + w * 0.40f, y + h * 0.82f), h * 0.11f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(col, Offset(x + w * 0.40f, y + h * 0.82f), Offset(x + w * 0.86f, y + h * 0.16f), h * 0.11f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
            ItemKind.CROSS -> {
                drawLine(col, Offset(x + w * 0.16f, y + h * 0.16f), Offset(x + w * 0.84f, y + h * 0.84f), h * 0.11f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(col, Offset(x + w * 0.16f, y + h * 0.84f), Offset(x + w * 0.84f, y + h * 0.16f), h * 0.11f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
            ItemKind.SIGNATURE -> {
                val img = sigBitmap(it.sigId)
                if (img == null) drawRect(Color(0x33888888), Offset(x, y), Size(w, h))
                else {
                    val t = ((it.turns % 4) + 4) % 4
                    val dw = if (t % 2 == 0) w else h; val dh = if (t % 2 == 0) h else w
                    rotate(t * 90f, pivot = Offset(x + w / 2, y + h / 2)) {
                        drawImage(img, dstOffset = IntOffset((x + w / 2 - dw / 2).toInt(), (y + h / 2 - dh / 2).toInt()), dstSize = IntSize(dw.toInt().coerceAtLeast(1), dh.toInt().coerceAtLeast(1)), filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
                            colorFilter = if (invert) androidx.compose.ui.graphics.ColorFilter.tint(Color(0xFFEDEDED), androidx.compose.ui.graphics.BlendMode.SrcIn) else null)
                    }
                }
            }
        }
    }

    private fun DrawScope.drawSelection(it: FillItem, accent: Color) {
        val r = it.rect
        val x = r.left * size.width; val y = r.top * size.height
        val w = (r.right - r.left) * size.width; val h = (r.bottom - r.top) * size.height
        drawRoundRect(accent, Offset(x, y), Size(w, h), CornerRadius(3.dp.toPx()), style = Stroke(1.6.dp.toPx()))
        drawCircle(Color.White, 9.dp.toPx(), Offset(x + w, y + h))
        drawCircle(accent, 7.dp.toPx(), Offset(x + w, y + h))
    }

    @Composable
    override fun Toolbar() = FillSignToolbar(controller, notice)
}

internal fun Color.toArgbCompat(): Int = this.toArgb()
