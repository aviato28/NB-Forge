package com.namanbansal.forge.core.pdf.annot

import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.viewer.PageText
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Hit-testing for the eraser and for tapping an annotation. All coordinates are page fractions; [pageAspect] = width / height. */
object AnnotHit {
    /** Distance in page-width fractions the finger may miss by (about 4 mm on a phone page). */
    const val DEFAULT_SLOP = 0.02f

    fun noteRect(a: Annot): NormRect? {
        val p = a.anchor ?: return null
        return NormRect(p.x, p.y, p.x + AnnotPalette.NOTE_SIZE, p.y + AnnotPalette.NOTE_SIZE)
    }

    /** Bounding box of [a] in page fractions (with ink width included). */
    fun bounds(a: Annot, pageAspect: Float): NormRect? = when (a.type) {
        AnnotType.HIGHLIGHT, AnnotType.UNDERLINE, AnnotType.STRIKEOUT ->
            if (a.quads.isEmpty()) null else NormRect(a.quads.minOf { it.left }, a.quads.minOf { it.top }, a.quads.maxOf { it.right }, a.quads.maxOf { it.bottom })
        AnnotType.INK -> {
            val pts = a.strokes.flatten()
            if (pts.isEmpty()) null else {
                val hw = a.width / 2; val hh = a.width / 2 * pageAspect
                NormRect(pts.minOf { it.x } - hw, pts.minOf { it.y } - hh, pts.maxOf { it.x } + hw, pts.maxOf { it.y } + hh)
            }
        }
        AnnotType.NOTE -> noteRect(a)
    }

    fun hits(a: Annot, nx: Float, ny: Float, pageAspect: Float, slop: Float = DEFAULT_SLOP): Boolean {
        when (a.type) {
            AnnotType.HIGHLIGHT, AnnotType.UNDERLINE, AnnotType.STRIKEOUT -> {
                val sy = slop * pageAspect
                return a.quads.any { nx >= it.left - slop / 2 && nx <= it.right + slop / 2 && ny >= it.top - sy / 2 && ny <= it.bottom + sy / 2 }
            }
            AnnotType.NOTE -> {
                val r = noteRect(a) ?: return false
                val sy = slop * pageAspect
                return nx >= r.left - slop / 2 && nx <= r.right + slop / 2 && ny >= r.top - sy / 2 && ny <= r.bottom + sy / 2
            }
            AnnotType.INK -> {
                // measure in page-width units, so y is scaled by 1 / aspect (height fraction -> width fraction)
                val reach = a.width / 2 + slop
                val ky = 1f / pageAspect
                for (stroke in a.strokes) {
                    if (stroke.size == 1) { if (hypot(nx - stroke[0].x, (ny - stroke[0].y) * ky) <= reach) return true; continue }
                    for (i in 0 until stroke.size - 1) {
                        if (segmentDistance(nx, ny * ky, stroke[i].x, stroke[i].y * ky, stroke[i + 1].x, stroke[i + 1].y * ky) <= reach) return true
                    }
                }
                return false
            }
        }
    }

    /** The top-most annotation (last in z-order) of [page] under the point, or null. */
    fun topmost(list: List<Annot>, page: Int, nx: Float, ny: Float, pageAspect: Float, slop: Float = DEFAULT_SLOP): Annot? =
        list.lastOrNull { it.page == page && hits(it, nx, ny, pageAspect, slop) }

    /** Ids of everything on [page] that the eraser segment (x0,y0)-(x1,y1) touches. */
    fun erased(list: List<Annot>, page: Int, x0: Float, y0: Float, x1: Float, y1: Float, pageAspect: Float): List<String> {
        val out = ArrayList<String>()
        val steps = max(1, (hypot(x1 - x0, (y1 - y0) / pageAspect) / 0.005f).toInt())
        for (a in list) {
            if (a.page != page) continue
            for (s in 0..steps) {
                val t = s.toFloat() / steps
                if (hits(a, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, pageAspect, slop = 0.012f)) { out += a.id; break }
            }
        }
        return out
    }

    private fun segmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}

/** Smoothing and thinning of a freehand stroke. */
object StrokeMath {
    /** Drops points closer than [minStep] (page-width units) to the previous kept one; always keeps the last. */
    fun thin(points: List<NormPt>, pageAspect: Float, minStep: Float = 0.0015f): List<NormPt> {
        if (points.size < 3) return points
        val out = ArrayList<NormPt>(points.size)
        out += points.first()
        for (i in 1 until points.size - 1) {
            val p = out.last(); val q = points[i]
            if (hypot(q.x - p.x, (q.y - p.y) / pageAspect) >= minStep) out += q
        }
        out += points.last()
        return out
    }

    /**
     * The polyline of the quadratic-curve smoothing the screen draws (curve through the midpoints, control points at the
     * samples), sampled [perSegment] times per segment: this is what is written to the PDF so other viewers look the same.
     */
    fun smoothed(points: List<NormPt>, perSegment: Int = 4): List<NormPt> {
        if (points.size < 3) return points
        val out = ArrayList<NormPt>()
        out += points.first()
        var start = points.first()
        for (i in 1 until points.size - 1) {
            val c = points[i]; val n = points[i + 1]
            val end = if (i == points.size - 2) n else NormPt((c.x + n.x) / 2, (c.y + n.y) / 2)
            for (s in 1..perSegment) {
                val t = s.toFloat() / perSegment; val u = 1 - t
                out += NormPt(u * u * start.x + 2 * u * t * c.x + t * t * end.x, u * u * start.y + 2 * u * t * c.y + t * t * end.y)
            }
            start = end
        }
        return out
    }
}

/** Turns a drag over text into highlight boxes. */
object TextSnap {
    /** Character range [first, last+1) between the characters at the two drag ends; null when the drag never touched text. */
    fun range(a: Int, b: Int): IntRange? = if (a < 0 || b < 0) null else min(a, b)..max(a, b)

    /** One box per line for the characters of [r] (spaces and line breaks have no box and are skipped). */
    fun quads(text: PageText, r: IntRange): List<NormRect> = text.lineRects(r.first, r.last + 1)

    /** The same, from the two drag points; null if nothing sits under them. */
    fun quadsBetween(text: PageText, x0: Float, y0: Float, x1: Float, y1: Float): List<NormRect>? {
        val a = text.charAt(x0, y0, slop = 0.03f)
        val b = text.charAt(x1, y1, slop = 0.03f)
        val r = range(a, b) ?: return null
        return quads(text, r).takeIf { it.isNotEmpty() }
    }
}
