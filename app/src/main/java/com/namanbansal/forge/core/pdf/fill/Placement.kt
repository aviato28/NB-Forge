package com.namanbansal.forge.core.pdf.fill

import com.namanbansal.forge.core.pdf.NormRect
import kotlin.math.max
import kotlin.math.min

/**
 * Where a placed signature or text box goes, as pure geometry in page fractions. [pageAspect] is the displayed page's
 * width / height; because fractions of width and height are different lengths, every size change goes through it so the
 * picture keeps its shape.
 */
object Placement {
    const val DEFAULT_WIDTH = 0.34f
    const val MIN_WIDTH = 0.06f

    private fun clampInto(w: Float, h: Float, cx: Float, cy: Float): NormRect {
        val l = (cx - w / 2).coerceIn(0f, max(0f, 1f - w)); val t = (cy - h / 2).coerceIn(0f, max(0f, 1f - h))
        return NormRect(l, t, l + w, t + h)
    }

    /** Height (page fraction) of an item of [wFrac] page widths whose picture is [aspect] (w/h) on a page of [pageAspect]. */
    fun heightFor(wFrac: Float, aspect: Float, pageAspect: Float): Float = wFrac * pageAspect / aspect

    /** A new signature centred on ([cx], [cy]) (the middle of the visible page), [DEFAULT_WIDTH] wide, shrunk to fit the page. */
    fun centered(aspect: Float, pageAspect: Float, cx: Float = 0.5f, cy: Float = 0.5f, widthFrac: Float = DEFAULT_WIDTH): NormRect {
        var w = widthFrac; var h = heightFor(w, aspect, pageAspect)
        if (h > 0.5f) { h = 0.5f; w = h * aspect / pageAspect }
        return clampInto(w, h, cx, cy)
    }

    /** The biggest rectangle of picture shape [aspect] that fits [field] (with [margin] of the field kept free), centred in it. */
    fun fitInto(field: NormRect, aspect: Float, pageAspect: Float, margin: Float = 0.06f): NormRect {
        val fw = (field.right - field.left) * (1 - 2 * margin); val fh = (field.bottom - field.top) * (1 - 2 * margin)
        // compare in page-height units: width in height units = wFrac * pageAspect
        var w = fw; var h = heightFor(w, aspect, pageAspect)
        if (h > fh) { h = fh; w = h * aspect / pageAspect }
        return clampInto(w, h, (field.left + field.right) / 2, (field.top + field.bottom) / 2)
    }

    /** Turns the rectangle 90 degrees about its centre (its width and height swap in real length). */
    fun rotated(r: NormRect, pageAspect: Float): NormRect {
        val w = r.right - r.left; val h = r.bottom - r.top
        var nw = h / pageAspect; var nh = w * pageAspect
        if (nh > 1f) { val k = 1f / nh; nw *= k; nh = 1f }
        if (nw > 1f) { val k = 1f / nw; nh *= k; nw = 1f }
        return clampInto(nw, nh, (r.left + r.right) / 2, (r.top + r.bottom) / 2)
    }

    /** Scales by [factor] keeping the aspect ratio and the top-left corner; never smaller than [MIN_WIDTH], never off the page. */
    fun scaled(r: NormRect, factor: Float): NormRect {
        val w = r.right - r.left; val h = r.bottom - r.top
        var k = factor
        k = max(k, MIN_WIDTH / max(w, 0.0001f))
        k = min(k, min((1f - r.left) / w, (1f - r.top) / h).coerceAtLeast(0.01f))
        return NormRect(r.left, r.top, r.left + w * k, r.top + h * k)
    }

    /** Drags the bottom-right handle to ([x], [y]) keeping the shape. */
    fun resizeTo(r: NormRect, x: Float, y: Float, pageAspect: Float): NormRect {
        val w0 = r.right - r.left; val h0 = r.bottom - r.top
        val k = max((x - r.left) / w0, (y - r.top) / h0)
        return scaled(r, k.coerceAtLeast(0.01f))
    }

    fun moved(r: NormRect, dx: Float, dy: Float): NormRect {
        val w = r.right - r.left; val h = r.bottom - r.top
        val l = (r.left + dx).coerceIn(0f, max(0f, 1f - w)); val t = (r.top + dy).coerceIn(0f, max(0f, 1f - h))
        return NormRect(l, t, l + w, t + h)
    }

    fun contains(r: NormRect, x: Float, y: Float, slop: Float = 0f) = x >= r.left - slop && x <= r.right + slop && y >= r.top - slop && y <= r.bottom + slop
}
