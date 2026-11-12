package com.namanbansal.forge.core.image

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.namanbansal.forge.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Where a box is pinned: free boxes use their centre, meme boxes hug the top or bottom edge. */
enum class TextAnchor { CENTER, TOP, BOTTOM }

/**
 * One piece of text on a picture. All sizes are fractions of the picture, so the same box renders identically at preview and full resolution.
 * [size] = font size / picture height; [stroke] = outline width / font size; (cx, cy) = centre (or the pinned edge for TOP / BOTTOM).
 */
@Serializable
data class TextBox(
    val id: Int,
    val text: String = "",
    val cx: Float = 0.5f,
    val cy: Float = 0.5f,
    val size: Float = 0.08f,
    val colour: Int = 0xFFFFFF,
    val strokeColour: Int = 0x000000,
    val stroke: Float = 0f,
    val shadow: Boolean = false,
    val font: String = "Sans",
    val anchor: TextAnchor = TextAnchor.CENTER,
    val uppercase: Boolean = false,
    /** Meme style: shrink the text until it fits inside the width. */
    val autoFit: Boolean = false,
)

object TextBoxes {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(list: List<TextBox>): String = json.encodeToString(list)
    fun decode(s: String): List<TextBox> = runCatching { json.decodeFromString<List<TextBox>>(s) }.getOrDefault(emptyList())
    val FONTS = listOf("Anton", "Sans", "Serif", "Mono", "Handwritten")

    fun meme(id: Int, top: Boolean, text: String = "") = TextBox(
        id = id, text = text, cx = 0.5f, cy = if (top) 0.03f else 0.97f, size = 0.11f, stroke = 0.12f, font = "Anton",
        anchor = if (top) TextAnchor.TOP else TextAnchor.BOTTOM, uppercase = true, autoFit = true,
    )
}

object TextFonts {
    private val cache = HashMap<String, Typeface>()
    @Synchronized fun typeface(ctx: Context, name: String): Typeface = cache.getOrPut(name) {
        try {
            when (name) {
                "Anton" -> Typeface.createFromAsset(ctx.assets, "fonts/Anton-Regular.ttf")
                "Serif" -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
                "Mono" -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                "Handwritten" -> ResourcesCompat.getFont(ctx, R.font.sig_caveat) ?: Typeface.DEFAULT_BOLD
                else -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
        } catch (_: Throwable) { Typeface.DEFAULT_BOLD }
    }
}

object TextOverlayRenderer {
    private class Laid(val layout: StaticLayout, val paintFill: TextPaint, val x: Float, val y: Float, val w: Float, val h: Float)

    private fun lay(b: TextBox, w: Int, h: Int, font: Typeface): Laid? {
        val text = (if (b.uppercase) b.text.uppercase() else b.text).trim('\n')
        if (text.isBlank()) return null
        val maxW = (w * 0.94f).toInt().coerceAtLeast(10)
        var px = (b.size * h).coerceAtLeast(6f)
        fun build(size: Float): StaticLayout {
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font; textSize = size; color = 0xFF000000.toInt() or b.colour }
            return StaticLayout.Builder.obtain(text, 0, text.length, p, maxW).setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
        }
        var layout = build(px)
        if (b.autoFit) {
            var guard = 0
            while ((layout.height > h * 0.3f || (0 until layout.lineCount).any { layout.getLineWidth(it) > maxW - 2 }) && px > 8f && guard++ < 40) { px *= 0.93f; layout = build(px) }
        }
        val tw = (0 until layout.lineCount).maxOf { layout.getLineWidth(it) }
        val th = layout.height.toFloat()
        val cx = b.cx * w
        val left = cx - maxW / 2f
        val top = when (b.anchor) { TextAnchor.TOP -> b.cy * h; TextAnchor.BOTTOM -> b.cy * h - th; TextAnchor.CENTER -> b.cy * h - th / 2f }
        return Laid(layout, layout.paint as TextPaint, left, top, tw, th)
    }

    /** Draws every box onto [canvas] sized [w] x [h]. */
    fun draw(canvas: Canvas, w: Int, h: Int, boxes: List<TextBox>, font: (String) -> Typeface) {
        for (b in boxes) {
            val laid = lay(b, w, h, font(b.font)) ?: continue
            val sw = if (b.stroke > 0f) b.stroke * laid.paintFill.textSize else 0f
            canvas.save()
            canvas.translate(laid.x, laid.y)
            if (sw > 0f) {
                // Outline first (wider stroke underneath), then the fill on top.
                val outline = TextPaint(laid.paintFill).apply { style = Paint.Style.STROKE; strokeWidth = sw; strokeJoin = Paint.Join.ROUND; color = 0xFF000000.toInt() or b.strokeColour }
                val text = laid.layout.text
                StaticLayout.Builder.obtain(text, 0, text.length, outline, laid.layout.width).setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build().draw(canvas)
            }
            if (b.shadow) laid.paintFill.setShadowLayer(laid.paintFill.textSize * 0.08f, laid.paintFill.textSize * 0.05f, laid.paintFill.textSize * 0.06f, 0xAA000000.toInt())
            laid.layout.draw(canvas)
            canvas.restore()
        }
    }

    /** Bounds (pixels) of the drawn text of [b], for tapping / selecting; null when empty. */
    fun bounds(b: TextBox, w: Int, h: Int, font: Typeface): FRect? {
        val l = lay(b, w, h, font) ?: return null
        return FRect(b.cx * w - l.w / 2f, l.y, b.cx * w + l.w / 2f, l.y + l.h)
    }
}
