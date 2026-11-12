package com.namanbansal.forge.core.image

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Float rectangle with no Android dependency. */
data class FRect(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w: Float get() = r - l
    val h: Float get() = b - t
    val cx: Float get() = (l + r) / 2
    val cy: Float get() = (t + b) / 2
}

enum class Handle { MOVE, TL, T, TR, R, BR, B, BL, L }

/** User turn / mirror applied on top of the upright (EXIF-corrected) picture: mirror first, then rotate clockwise. */
data class ViewTransform(val rotation: Int = 0, val flipH: Boolean = false) {
    init { require(rotation % 90 == 0) }
    private val quarter: Int get() = ((rotation / 90) % 4 + 4) % 4
    fun swaps(): Boolean = quarter % 2 == 1
    fun size(w: Int, h: Int): Pair<Int, Int> = if (swaps()) h to w else w to h
    fun rotatedRight() = copy(rotation = (quarter + 1) % 4 * 90)
    fun rotatedLeft() = copy(rotation = (quarter + 3) % 4 * 90)
    fun flipped() = copy(flipH = !flipH)
    val normalizedRotation: Int get() = quarter * 90

    /** Maps a point in normalised display space (0..1) back to normalised source space. */
    fun toSource(x: Float, y: Float): Pair<Float, Float> {
        var sx: Float
        var sy: Float
        when (quarter) {
            0 -> { sx = x; sy = y }
            1 -> { sx = y; sy = 1 - x }
            2 -> { sx = 1 - x; sy = 1 - y }
            else -> { sx = 1 - y; sy = x }
        }
        if (flipH) sx = 1 - sx
        return sx to sy
    }
}

object CropMath {
    const val MIN_SIZE = 16f

    /** Source-pixel rect (l, t, r, b) for a crop given in normalised *display* coordinates, honouring rotation and mirror. */
    fun toSourceRect(crop: FRect, t: ViewTransform, srcW: Int, srcH: Int): IntArray {
        val (ax, ay) = t.toSource(crop.l, crop.t)
        val (bx, by) = t.toSource(crop.r, crop.b)
        val l = (min(ax, bx) * srcW).roundToInt().coerceIn(0, srcW - 1)
        val tp = (min(ay, by) * srcH).roundToInt().coerceIn(0, srcH - 1)
        val r = (max(ax, bx) * srcW).roundToInt().coerceIn(l + 1, srcW)
        val b = (max(ay, by) * srcH).roundToInt().coerceIn(tp + 1, srcH)
        return intArrayOf(l, tp, r, b)
    }

    /** The biggest centred rect of [ratio] (w / h) inside a [w] x [h] picture; the whole picture when [ratio] is null. */
    fun initial(w: Float, h: Float, ratio: Float?): FRect {
        if (ratio == null) return FRect(0f, 0f, w, h)
        val cw: Float
        val ch: Float
        if (w / h > ratio) { ch = h; cw = h * ratio } else { cw = w; ch = w / ratio }
        return FRect((w - cw) / 2, (h - ch) / 2, (w + cw) / 2, (h + ch) / 2)
    }

    /** Re-fits [r] to [ratio] around its centre inside the bounds. */
    fun withRatio(r: FRect, ratio: Float?, bw: Float, bh: Float): FRect {
        if (ratio == null) return r
        var w = r.w
        var h = w / ratio
        if (h > bh) { h = bh; w = h * ratio }
        if (w > bw) { w = bw; h = w / ratio }
        return shiftInside(FRect(r.cx - w / 2, r.cy - h / 2, r.cx + w / 2, r.cy + h / 2), bw, bh)
    }

    private fun shiftInside(r: FRect, bw: Float, bh: Float): FRect {
        val dx = if (r.l < 0) -r.l else if (r.r > bw) bw - r.r else 0f
        val dy = if (r.t < 0) -r.t else if (r.b > bh) bh - r.b else 0f
        return FRect(r.l + dx, r.t + dy, r.r + dx, r.b + dy)
    }

    /** Drags [handle] by (dx, dy) pixels; keeps the rect inside [bw] x [bh], at least [MIN_SIZE] big, and at [ratio] when given. */
    fun drag(r: FRect, handle: Handle, dx: Float, dy: Float, bw: Float, bh: Float, ratio: Float?): FRect {
        if (handle == Handle.MOVE) return shiftInside(FRect(r.l + dx, r.t + dy, r.r + dx, r.b + dy), bw, bh).let {
            FRect(it.l.coerceIn(0f, bw - r.w), it.t.coerceIn(0f, bh - r.h), it.l.coerceIn(0f, bw - r.w) + r.w, it.t.coerceIn(0f, bh - r.h) + r.h)
        }
        var l = r.l; var t = r.t; var rr = r.r; var b = r.b
        val movesL = handle == Handle.TL || handle == Handle.L || handle == Handle.BL
        val movesR = handle == Handle.TR || handle == Handle.R || handle == Handle.BR
        val movesT = handle == Handle.TL || handle == Handle.T || handle == Handle.TR
        val movesB = handle == Handle.BL || handle == Handle.B || handle == Handle.BR
        if (movesL) l = (l + dx).coerceIn(0f, rr - MIN_SIZE)
        if (movesR) rr = (rr + dx).coerceIn(l + MIN_SIZE, bw)
        if (movesT) t = (t + dy).coerceIn(0f, b - MIN_SIZE)
        if (movesB) b = (b + dy).coerceIn(t + MIN_SIZE, bh)
        if (ratio == null) return FRect(l, t, rr, b)
        // Locked ratio: the dragged side(s) decide the size, the opposite side / centre stays put.
        val corner = (movesL || movesR) && (movesT || movesB)
        var w: Float
        var h: Float
        if (corner) {
            // Anchor is the opposite corner; use whichever axis the finger moved more.
            val aw = rr - l
            val ah = b - t
            if (aw / ratio >= ah) { w = aw; h = aw / ratio } else { h = ah; w = ah * ratio }
            val ax = if (movesL) r.r else r.l
            val ay = if (movesT) r.b else r.t
            val maxW = if (movesL) ax else bw - ax
            val maxH = if (movesT) ay else bh - ay
            if (w > maxW) { w = maxW; h = w / ratio }
            if (h > maxH) { h = maxH; w = h * ratio }
            w = max(w, MIN_SIZE); h = w / ratio
            val nl = if (movesL) ax - w else ax
            val nt = if (movesT) ay - h else ay
            return FRect(nl, nt, nl + w, nt + h)
        }
        if (movesL || movesR) { w = rr - l; h = w / ratio; if (h > bh) { h = bh; w = h * ratio } } else { h = b - t; w = h * ratio; if (w > bw) { w = bw; h = w / ratio } }
        val out = if (movesL || movesR) {
            val nl = if (movesL) rr.coerceAtLeast(l + w) - w else l
            FRect(nl, r.cy - h / 2, nl + w, r.cy + h / 2)
        } else {
            val nt = if (movesT) b - h else t
            FRect(r.cx - w / 2, nt, r.cx + w / 2, nt + h)
        }
        return shiftInside(out, bw, bh)
    }

    /** Which handle (if any) a touch at ([x], [y]) hits, with [slop] px tolerance; inside the rect = MOVE. */
    fun hit(r: FRect, x: Float, y: Float, slop: Float): Handle? {
        val nl = abs(x - r.l) <= slop
        val nr = abs(x - r.r) <= slop
        val nt = abs(y - r.t) <= slop
        val nb = abs(y - r.b) <= slop
        val inX = x in (r.l - slop)..(r.r + slop)
        val inY = y in (r.t - slop)..(r.b + slop)
        return when {
            nl && nt -> Handle.TL; nr && nt -> Handle.TR; nl && nb -> Handle.BL; nr && nb -> Handle.BR
            nt && inX -> Handle.T; nb && inX -> Handle.B; nl && inY -> Handle.L; nr && inY -> Handle.R
            x in r.l..r.r && y in r.t..r.b -> Handle.MOVE
            else -> null
        }
    }
}
