package com.namanbansal.forge.core.pdf

import com.namanbansal.forge.core.ToolException
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition

/** A link as listed in the editor. [index] is its position among the page's annotations. [uri] null = an internal jump or other action. */
data class LinkInfo(val page: Int, val index: Int, val uri: String?, val destPage: Int?, val left: Float, val top: Float, val right: Float, val bottom: Float)

/** A link to add: a rectangle as fractions of the displayed page. */
data class NewLink(val page: Int, val left: Float, val top: Float, val right: Float, val bottom: Float, val uri: String)

/** The edits the person has queued; applied together when saving. */
class LinkEdits(
    val urlChanges: Map<Pair<Int, Int>, String> = emptyMap(),
    val deletes: Set<Pair<Int, Int>> = emptySet(),
    val additions: List<NewLink> = emptyList(),
    val autoDetect: Boolean = false,
)

object PdfLinks {
    private val ALLOWED = setOf("http", "https", "mailto", "tel", "ftp")
    private val URL = Regex("""(?i)\b((?:https?://|www\.)[^\s<>"'`]+|[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,})""")

    /** Cleans up what the person typed: adds https://, rejects script and file schemes. Null = unusable. */
    fun normalizeUrl(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        val scheme = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):").find(t)?.groupValues?.get(1)?.lowercase()
        if (scheme != null && scheme !in ALLOWED) return null
        if (scheme != null) return t
        if (t.contains('@') && !t.contains('/')) return "mailto:$t"
        return if (t.contains('.')) "https://$t" else null
    }

    fun list(doc: PDDocument): List<LinkInfo> {
        val out = ArrayList<LinkInfo>()
        for ((pi, page) in doc.pages.withIndex()) {
            val annots = runCatching { page.annotations }.getOrNull() ?: continue
            val (vw, vh) = PageGeometry.viewSize(page)
            for ((ai, a) in annots.withIndex()) {
                if (a !is PDAnnotationLink) continue
                val act = runCatching { a.action }.getOrNull()
                val uri = (act as? PDActionURI)?.uri
                val dest = if (uri == null) runCatching { com.namanbansal.forge.core.pdf.viewer.PdfOutline.pageOfDestination(doc, a.destination ?: (act as? PDActionGoTo)?.destination) }.getOrNull() else null
                val r = a.rectangle ?: continue
                val f = userToFractions(page, r, vw, vh)
                out += LinkInfo(pi, ai, uri, dest, f[0], f[1], f[2], f[3])
            }
        }
        return out
    }

    /** User-space rectangle to (left, top, right, bottom) fractions of the displayed page. */
    fun userToFractions(page: PDPage, r: PDRectangle, vw: Float, vh: Float): FloatArray {
        val m = PageGeometry.viewToUser(page)
        // invert the (rotation + translation) matrix by sampling the corners
        val a = m.getValue(0, 0); val b = m.getValue(0, 1); val c = m.getValue(1, 0); val d = m.getValue(1, 1); val e = m.getValue(2, 0); val f = m.getValue(2, 1)
        val det = a * d - b * c
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        for ((ux, uy) in listOf(r.lowerLeftX to r.lowerLeftY, r.upperRightX to r.lowerLeftY, r.lowerLeftX to r.upperRightY, r.upperRightX to r.upperRightY)) {
            val px = ux - e; val py = uy - f
            xs += (d * px - c * py) / det; ys += (-b * px + a * py) / det
        }
        return floatArrayOf((xs.min() / vw).coerceIn(0f, 1f), (1 - ys.max() / vh).coerceIn(0f, 1f), (xs.max() / vw).coerceIn(0f, 1f), (1 - ys.min() / vh).coerceIn(0f, 1f))
    }

    private fun newAnnotation(page: PDPage, l: Float, t: Float, r: Float, b: Float, uri: String): PDAnnotationLink {
        val link = PDAnnotationLink()
        link.rectangle = PageGeometry.fractionsToUser(page, l, t, r, b)
        val border = PDBorderStyleDictionary(); border.width = 0f
        link.borderStyle = border
        link.action = PDActionURI().also { it.uri = uri }
        return link
    }

    /** Applies queued edits. @return (changed, removed, added) */
    fun apply(doc: PDDocument, edits: LinkEdits, ctx: TransformCtx = TransformCtx({}, {})): Triple<Int, Int, Int> {
        var changed = 0; var removed = 0; var added = 0
        val before = HashMap<Int, List<com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation>>()
        for ((pi, page) in doc.pages.withIndex()) {
            ctx.checkCancelled()
            val annots = runCatching { page.annotations }.getOrNull() ?: continue
            val keep = ArrayList<com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation>()
            var touched = false
            for ((ai, a) in annots.withIndex()) {
                val key = pi to ai
                if (a is PDAnnotationLink && key in edits.deletes) { removed++; touched = true; continue }
                val url = edits.urlChanges[key]
                if (a is PDAnnotationLink && url != null) {
                    val u = normalizeUrl(url) ?: throw ToolException("\"$url\" isn't a link address Forge can use.")
                    a.action = PDActionURI().also { it.uri = u }; changed++; touched = true
                }
                keep += a
            }
            if (touched) page.annotations = keep
        }
        for (n in edits.additions) {
            val u = normalizeUrl(n.uri) ?: throw ToolException("\"${n.uri}\" isn't a link address Forge can use.")
            val page = doc.getPage(n.page)
            page.annotations = (runCatching { page.annotations }.getOrNull() ?: mutableListOf()).toMutableList().apply { add(newAnnotation(page, n.left, n.top, n.right, n.bottom, u)) }
            added++
        }
        if (edits.autoDetect) added += autoLink(doc, ctx)
        return Triple(changed, removed, added)
    }

    /** A plain-text address found in the page text, with the box it occupies (fractions of the displayed page). */
    class Found(val page: Int, val url: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

    fun detect(doc: PDDocument, ctx: TransformCtx = TransformCtx({}, {})): List<Found> {
        val out = ArrayList<Found>()
        val sizes = doc.pages.map { PageGeometry.viewSize(it) }
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                // map each character of [text] to the glyph that drew it
                val idx = ArrayList<Int>(text.length)
                for ((pi, tp) in textPositions.withIndex()) repeat(tp.unicode?.length ?: 1) { idx += pi }
                if (idx.size != text.length) return
                val (vw, vh) = sizes.getOrNull(currentPageNo - 1) ?: return
                for (m in URL.findAll(text)) {
                    var raw = m.value.trimEnd('.', ',', ';', ':', ')', ']', '!', '?')
                    if (raw.length < 4) continue
                    val from = m.range.first; val to = from + raw.length - 1
                    val ps = textPositions.subList(idx[from], idx[to] + 1)
                    val l = ps.minOf { it.xDirAdj }; val r = ps.maxOf { it.xDirAdj + it.widthDirAdj }
                    val b = ps.maxOf { it.yDirAdj }; val t = ps.minOf { it.yDirAdj - it.heightDir }
                    val url = normalizeUrl(raw) ?: continue
                    out += Found(currentPageNo - 1, url, (l / vw).coerceIn(0f, 1f), (t / vh).coerceIn(0f, 1f), (r / vw).coerceIn(0f, 1f), (b / vh).coerceIn(0f, 1f))
                }
            }
        }
        val total = doc.numberOfPages
        for (p in 1..total) { ctx.checkCancelled(); stripper.startPage = p; stripper.endPage = p; stripper.getText(doc); ctx.onProgress(p.toFloat() / total * 0.5f) }
        return out
    }

    /** Makes plain-text addresses clickable; skips ones already covered by a link. @return links added */
    fun autoLink(doc: PDDocument, ctx: TransformCtx = TransformCtx({}, {})): Int {
        val existing = list(doc)
        var n = 0
        for (f in detect(doc, ctx)) {
            val covered = existing.any { it.page == f.page && it.left < f.right && it.right > f.left && it.top < f.bottom && it.bottom > f.top }
            if (covered) continue
            val page = doc.getPage(f.page)
            page.annotations = (runCatching { page.annotations }.getOrNull() ?: mutableListOf()).toMutableList().apply { add(newAnnotation(page, f.left, f.top, f.right, f.bottom, f.url)) }
            n++
        }
        return n
    }

    // ---- state encoding (survives process death) ----
    fun encodeEdits(e: LinkEdits): String = buildString {
        for ((k, v) in e.urlChanges) append("U\t${k.first}\t${k.second}\t${v.replace("\n", " ")}\n")
        for (k in e.deletes) append("D\t${k.first}\t${k.second}\n")
        for (a in e.additions) append("A\t${a.page}\t${a.left}\t${a.top}\t${a.right}\t${a.bottom}\t${a.uri.replace("\n", " ")}\n")
        if (e.autoDetect) append("T\n")
    }

    fun decodeEdits(text: String): LinkEdits {
        val u = LinkedHashMap<Pair<Int, Int>, String>(); val d = LinkedHashSet<Pair<Int, Int>>(); val a = ArrayList<NewLink>(); var auto = false
        for (line in text.split("\n")) {
            val p = line.split("\t")
            runCatching {
                when (p[0]) {
                    "U" -> u[p[1].toInt() to p[2].toInt()] = p[3]
                    "D" -> d += p[1].toInt() to p[2].toInt()
                    "A" -> a += NewLink(p[1].toInt(), p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p[5].toFloat(), p[6])
                    "T" -> auto = true
                }
            }
        }
        return LinkEdits(u, d, a, auto)
    }
}
