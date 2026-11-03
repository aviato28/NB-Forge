package com.namanbansal.forge.core.pdf.viewer

import com.namanbansal.forge.core.pdf.NormRect

/** A tappable area on a page: [uri] for web / mail links, [page] (0-based) for jumps inside the document. */
data class PageLink(val rect: NormRect, val uri: String?, val page: Int?)

object LinkGeometry {
    /**
     * Turns a PDF user-space rectangle (lower-left origin) into fractions of the displayed page (top-left origin) for a page
     * whose crop box is ([boxX], [boxY], [boxW] x [boxH]) turned clockwise by [rotation] degrees.
     */
    fun normalize(llx: Float, lly: Float, urx: Float, ury: Float, boxX: Float, boxY: Float, boxW: Float, boxH: Float, rotation: Int): NormRect {
        fun fx(x: Float) = ((x - boxX) / boxW).coerceIn(0f, 1f)
        fun fy(y: Float) = (1f - (y - boxY) / boxH).coerceIn(0f, 1f)
        val xs = floatArrayOf(fx(llx), fx(urx)); val ys = floatArrayOf(fy(lly), fy(ury))
        val us = ArrayList<Float>(2); val vs = ArrayList<Float>(2)
        for (i in 0..1) {
            val (u, v) = when (((rotation % 360) + 360) % 360) {
                90 -> (1f - ys[i]) to xs[i]
                180 -> (1f - xs[i]) to (1f - ys[i])
                270 -> ys[i] to (1f - xs[i])
                else -> xs[i] to ys[i]
            }
            us += u; vs += v
        }
        return NormRect(us.min(), vs.min(), us.max(), vs.max())
    }

    /** The link under ([nx], [ny]); the smallest one wins when links overlap. Taps within [slop] of an edge still count. */
    fun hit(links: List<PageLink>, nx: Float, ny: Float, slop: Float = 0.004f): PageLink? =
        links.filter { nx >= it.rect.left - slop && nx <= it.rect.right + slop && ny >= it.rect.top - slop && ny <= it.rect.bottom + slop }
            .minByOrNull { (it.rect.right - it.rect.left) * (it.rect.bottom - it.rect.top) }
}
