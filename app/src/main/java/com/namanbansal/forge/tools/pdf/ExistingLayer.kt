package com.namanbansal.forge.tools.pdf

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.edit.ExistingEdit
import com.namanbansal.forge.core.pdf.edit.ExistingKind
import com.namanbansal.forge.core.pdf.edit.content.Align
import com.namanbansal.forge.core.pdf.edit.content.ExBlock
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import kotlin.math.max

private val DELETE_RED = Color(0xFFE53935)

/**
 * What Edit existing draws on a page: faint outlines of the text blocks, pictures and drawings (while the tool is active),
 * the pending changes as they will look (the old text hidden, the new text re-wrapped; moved pictures; deleted objects crossed
 * out), the selection with its handles, the lasso, and the inline editor over the block being typed in.
 */
@Composable
fun ExistingLayer(
    controller: EditController, page: Int, widthPx: Int, heightPx: Int, invert: Boolean, active: Boolean, pageWidthPt: Float,
    imageOf: (String) -> ImageBitmap?, existingImage: (Int, Int) -> ImageBitmap?,
) {
    val ex = controller.existing
    val c = ForgeTheme.colors
    val state by controller.session.state.collectAsState()
    val objects by ex.objects.collectAsState()
    val selection by ex.selection.collectAsState()
    val previews by ex.previews.collectAsState()
    val editing by ex.editing.collectAsState()
    val tool by controller.tool.collectAsState()
    val pending = state.existing.filter { it.page == page }
    val outlines = active && tool == EditTool.EXISTING
    LaunchedEffect(page, outlines, pending.isNotEmpty()) { if (outlines || pending.isNotEmpty()) ex.ensure(page) }
    val obj = objects[page]
    val ptScale = widthPx / pageWidthPt.coerceAtLeast(1f)
    val accent = c.accent
    val paper = if (invert) Color(0xFF121212) else Color.White
    val liveLasso = ex.liveLasso?.takeIf { it.first == page }?.second
    val summary = if (outlines && obj != null) "${obj.blocks.size} text block${if (obj.blocks.size == 1) "" else "s"}, ${obj.images.size} picture${if (obj.images.size == 1) "" else "s"}, ${obj.paths.size} drawing${if (obj.paths.size == 1) "" else "s"} you can edit" else null
    Canvas((if (summary != null) Modifier.fillMaxSize().semantics { contentDescription = summary } else Modifier.fillMaxSize())) {
        // pending changes first: they replace what the page shows
        for (e in pending) when (e.kind) {
            ExistingKind.TEXT -> {
                drawRect(paper, px(e.rect, -1.5f), sz(e.rect, 3f))
                val pv = previews[e.id]?.takeIf { it.first == e }?.second
                val block = obj?.blocks?.firstOrNull { it.key == e.key }
                if (!e.delete && pv != null && block != null) drawPreviewText(e, block, pv, ptScale, invert)
                else if (!e.delete && e.text.isNotBlank()) drawRect(accent.copy(alpha = 0.08f), px(e.rect), sz(e.rect))
            }
            ExistingKind.IMAGE -> {
                drawRect(paper, px(e.rect, -1f), sz(e.rect, 2f))
                if (e.delete) drawDeleted(e.rect)
                else {
                    val to = e.newRect ?: e.rect
                    val bmp = (if (e.replaceImageId.isNotEmpty()) imageOf(e.replaceImageId) else null) ?: existingImage(page, e.key)
                    drawPicture(bmp, to, e.turns, accent)
                }
            }
            ExistingKind.PATH -> drawDeleted(e.rect)
        }
        if (outlines && obj != null) {
            val pendingKeys = pending.map { it.kind to it.key }.toSet()
            obj.paths.filter { ExistingKind.PATH to it.key !in pendingKeys }.sortedByDescending { (it.rect.right - it.rect.left) * (it.rect.bottom - it.rect.top) }.take(150).forEach { p ->
                drawRoundRect(accent.copy(alpha = 0.35f), px(p.rect), sz(p.rect), CornerRadius(2.dp.toPx()), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
            }
            obj.images.filter { ExistingKind.IMAGE to it.key !in pendingKeys }.forEach { i ->
                drawRoundRect(accent.copy(alpha = 0.7f), px(i.rect), sz(i.rect), CornerRadius(3.dp.toPx()), style = Stroke(1.6.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 5.dp.toPx()))))
            }
            obj.blocks.forEach { b ->
                drawRoundRect(accent.copy(alpha = 0.07f), px(b.rect, -2f), sz(b.rect, 4f), CornerRadius(3.dp.toPx()))
                drawRoundRect(accent.copy(alpha = 0.55f), px(b.rect, -2f), sz(b.rect, 4f), CornerRadius(3.dp.toPx()), style = Stroke(1.dp.toPx()))
            }
        }
        // selection
        for (r in selection.filter { it.page == page }) {
            val rect = ex.rectOf(r) ?: continue
            drawRoundRect(accent.copy(alpha = 0.12f), px(rect, -2f), sz(rect, 4f), CornerRadius(3.dp.toPx()))
            drawRoundRect(accent, px(rect, -2f), sz(rect, 4f), CornerRadius(3.dp.toPx()), style = Stroke(2.4.dp.toPx()))
            if (r is ExImageRef && selection.size == 1) {
                val x = rect.right * size.width; val y = rect.bottom * size.height
                drawCircle(Color.White, 9.dp.toPx(), Offset(x, y)); drawCircle(accent, 7.dp.toPx(), Offset(x, y))
            }
        }
        if (liveLasso != null) {
            drawRect(accent.copy(alpha = 0.10f), px(liveLasso), sz(liveLasso))
            drawRect(accent, px(liveLasso), sz(liveLasso), style = Stroke(1.6.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx()))))
        }
    }
    val ed = editing?.takeIf { it.page == page }
    val block = ed?.let { ex.blockOf(it) }
    if (ed != null && block != null) InlineEditor(controller, page, block, widthPx, heightPx, ptScale, invert)
}

private fun DrawScope.px(r: NormRect, pad: Float = 0f) = Offset(r.left * size.width + pad.dp.toPx(), r.top * size.height + pad.dp.toPx())
private fun DrawScope.sz(r: NormRect, pad: Float = 0f) = Size((r.right - r.left) * size.width + pad.dp.toPx(), (r.bottom - r.top) * size.height + pad.dp.toPx())

private fun DrawScope.drawDeleted(r: NormRect) {
    drawRect(DELETE_RED.copy(alpha = 0.10f), px(r), sz(r))
    drawRect(DELETE_RED, px(r), sz(r), style = Stroke(1.6.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
    drawLine(DELETE_RED, Offset(r.left * size.width, r.top * size.height), Offset(r.right * size.width, r.bottom * size.height), 1.4.dp.toPx())
    drawLine(DELETE_RED, Offset(r.left * size.width, r.bottom * size.height), Offset(r.right * size.width, r.top * size.height), 1.4.dp.toPx())
}

private fun DrawScope.drawPicture(bmp: ImageBitmap?, to: NormRect, turns: Int, accent: Color) {
    val x = to.left * size.width; val y = to.top * size.height
    val w = (to.right - to.left) * size.width; val h = (to.bottom - to.top) * size.height
    if (bmp == null) { drawRect(accent.copy(alpha = 0.18f), Offset(x, y), Size(w, h)); return }
    val t = ((turns % 4) + 4) % 4
    val dw = if (t % 2 == 0) w else h; val dh = if (t % 2 == 0) h else w
    rotate(t * 90f, pivot = Offset(x + w / 2, y + h / 2)) {
        drawImage(bmp, dstOffset = IntOffset((x + w / 2 - dw / 2).toInt(), (y + h / 2 - dh / 2).toInt()), dstSize = IntSize(dw.toInt().coerceAtLeast(1), dh.toInt().coerceAtLeast(1)),
            filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium)
    }
}

private fun typefaceFor(serif: Boolean, mono: Boolean, bold: Boolean, italic: Boolean): Typeface {
    val base = when { mono -> Typeface.MONOSPACE; serif -> Typeface.SERIF; else -> Typeface.SANS_SERIF }
    val style = (if (bold) Typeface.BOLD else 0) or (if (italic) Typeface.ITALIC else 0)
    return Typeface.create(base, style)
}

/** Draws the re-wrapped lines of a pending text edit; each line is stretched to the width the PDF will have (justified, other font). */
private fun DrawScope.drawPreviewText(e: ExistingEdit, block: ExBlock, pv: com.namanbansal.forge.core.pdf.edit.content.TextPreview, ptScale: Float, invert: Boolean) {
    val t = block.raw.traits
    val bold = e.bold.takeIf { it >= 0 }?.let { it == 1 } ?: t.bold
    val italic = e.italic.takeIf { it >= 0 }?.let { it == 1 } ?: t.italic
    val col = if (e.hasColor) e.color else block.raw.color
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color(col).let { if (invert && col == 0xFF000000.toInt()) Color(0xFFEDEDED) else it }.toArgb()
        textSize = pv.sizePt * ptScale; typeface = typefaceFor(t.serif, t.mono, bold, italic)
    }
    val canvas = drawContext.canvas.nativeCanvas
    for (l in pv.lines) {
        if (l.text.isEmpty()) continue
        val x = l.x * size.width; val y = l.baseline * size.height; val target = l.width * size.width
        val words = l.text.split(' ').filter { it.isNotEmpty() }
        val natural = paint.measureText(l.text)
        if (words.size > 1 && kotlin.math.abs(natural - target) > 1.5f) {
            val total = words.sumOf { paint.measureText(it).toDouble() }.toFloat()
            val gap = ((target - total) / (words.size - 1)).coerceIn(0f, pv.sizePt * ptScale * 2f)
            var cx = x
            for (w in words) { canvas.drawText(w, cx, y, paint); cx += paint.measureText(w) + gap }
        } else canvas.drawText(l.text, x, y, paint)
    }
}

@Composable
private fun InlineEditor(controller: EditController, page: Int, block: ExBlock, widthPx: Int, heightPx: Int, ptScale: Float, invert: Boolean) {
    val ex = controller.existing
    val c = ForgeTheme.colors
    val density = LocalDensity.current
    val raw = block.raw
    val look = ex.lookOf(ExBlockRef(page, block.key))
    val sizePx = (look?.sizePt ?: raw.size) * ptScale
    val leadingPx = raw.leading * ((look?.sizePt ?: raw.size) / raw.size) * ptScale
    val x = block.rect.left * widthPx; val y = block.rect.top * heightPx
    val boxW = max(raw.maxWidth * ptScale, 80f)
    val family = when { raw.traits.mono -> FontFamily.Monospace; raw.traits.serif -> FontFamily.Serif; else -> FontFamily.SansSerif }
    val textColor = Color(look?.color ?: raw.color).let { if (invert && it.toArgb() == 0xFF000000.toInt()) Color(0xFFEDEDED) else it }
    val align = when (look?.align ?: raw.align) { Align.CENTER -> TextAlign.Center; Align.RIGHT -> TextAlign.Right; Align.JUSTIFY -> TextAlign.Justify; else -> TextAlign.Left }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val paper = if (invert) Color(0xFF121212) else Color.White
    with(density) {
        Box(Modifier.offset { IntOffset((x - 4.dp.toPx()).toInt(), (y - 4.dp.toPx()).toInt()) }) {
            BasicTextField(
                value = ex.draft, onValueChange = { ex.draft = it },
                textStyle = TextStyle(
                    fontFamily = family, fontSize = sizePx.toSp(), lineHeight = leadingPx.toSp(), color = textColor, textAlign = align,
                    fontWeight = if (look?.bold == true) FontWeight.Bold else FontWeight.Normal, fontStyle = if (look?.italic == true) FontStyle.Italic else FontStyle.Normal,
                ),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.width((boxW + 8.dp.toPx()).toDp()).heightIn(min = (block.rect.bottom * heightPx - y + 8.dp.toPx()).toDp())
                    .background(paper).border(1.5.dp, c.accent, RoundedCornerShape(3.dp)).padding(4.dp)
                    .focusRequester(focus).testTag("existing-editor").semantics { contentDescription = "Edit paragraph text" },
            )
        }
        Row(
            Modifier.offset { IntOffset((x - 4.dp.toPx()).toInt(), (block.rect.bottom * heightPx + 8.dp.toPx()).toInt()) },
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            EditorChip("Done", true, "existing-editor-done") { ex.closeEditor(true) }
            EditorChip("Cancel", false, "existing-editor-cancel") { ex.closeEditor(false) }
        }
    }
}

@Composable
private fun EditorChip(label: String, primary: Boolean, tag: String, onClick: () -> Unit) {
    val c = ForgeTheme.colors
    Box(
        Modifier.heightIn(min = 48.dp).widthIn(min = 72.dp).clip(RoundedCornerShape(24.dp)).background(if (primary) c.accent else c.surface).border(1.dp, if (primary) c.accent else c.border, RoundedCornerShape(24.dp))
            .clickable(onClick = onClick).padding(horizontal = 16.dp).testTag(tag).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Text(label, style = ForgeType.Label, color = if (primary) Color.White else c.text) }
}
