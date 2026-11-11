package com.namanbansal.forge.core.pdf.edit.content

import com.tom_roush.pdfbox.pdmodel.font.PDFont
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object Align { const val LEFT = 0; const val CENTER = 1; const val RIGHT = 2; const val JUSTIFY = 3
    val names = listOf("Left", "Center", "Right", "Justify") }

/** What a font's name and descriptor say about its looks (used to pick a fallback and to show bold / italic state). */
class FontTraits(val bold: Boolean, val italic: Boolean, val serif: Boolean, val mono: Boolean, val family: String) {
    companion object {
        private val BOLD = listOf("bold", "black", "heavy", "semibold", "demibold", "extrabold")
        private val ITALIC = listOf("italic", "oblique", "slanted")
        private val SERIF = listOf("times", "serif", "georgia", "garamond", "palatino", "minion", "cambria", "bookman", "century", "baskerville", "didot", "roman", "caslon", "charter")
        private val MONO = listOf("courier", "mono", "consolas", "menlo", "typewriter", "lucida console", "code")
        private val STYLE_WORDS = Regex("(?i)(bold|italic|oblique|regular|roman|black|heavy|semibold|demibold|extrabold|light|medium|book|normal|mt|ps|std|pro|condensed|narrow)")

        fun stripSubset(name: String) = name.replace(Regex("^[A-Z]{6}\\+"), "")

        fun of(font: PDFont): FontTraits {
            val raw = stripSubset(runCatching { font.name }.getOrNull().orEmpty())
            val n = raw.lowercase()
            val d = runCatching { font.fontDescriptor }.getOrNull()
            val bold = BOLD.any { it in n } || d?.isForceBold == true || (d?.fontWeight ?: 0f) >= 700f
            val italic = ITALIC.any { it in n } || d?.isItalic == true || (d?.italicAngle ?: 0f) != 0f
            val sans = "sans" in n || "arial" in n || "helvetica" in n || "calibri" in n || "verdana" in n || "tahoma" in n
            val serif = !sans && (d?.isSerif == true || SERIF.any { it in n })
            val mono = d?.isFixedPitch == true || MONO.any { it in n }
            val family = raw.replace(Regex("[-,_ ]"), "").replace(STYLE_WORDS, "").lowercase()
            return FontTraits(bold, italic, serif, mono, family)
        }
    }
}

/** The look of a run of characters: which font, in which colour. Two runs are the same style when both match. */
class RunStyle(val font: PDFont, val fontId: Int, val fontName: String, val traits: FontTraits, val color: Int) {
    fun same(o: RunStyle) = fontId == o.fontId && color == o.color
}

/** Characters [start, end) of a block's text share [style]. */
class StyleRun(val start: Int, val end: Int, val style: RunStyle)

/** Groups per-character styles into runs. */
fun runsOf(styles: List<RunStyle>): List<StyleRun> {
    val out = ArrayList<StyleRun>()
    var i = 0
    while (i < styles.size) {
        var j = i + 1
        while (j < styles.size && styles[j].same(styles[i])) j++
        out += StyleRun(i, j, styles[i]); i = j
    }
    return out
}

/** One line of a block: its characters (indices into the page's glyph list) and where it sits, in user space. */
class TextLine(
    val glyphs: List<Glyph>, val text: String, val baseline: Float, val left: Float, val right: Float, val size: Float, val extraIds: List<Int> = emptyList(),
    /** The look of every character of [text] (same length). */
    val styles: List<RunStyle> = emptyList(),
) {
    val width: Float get() = right - left
    val cx: Float get() = (left + right) / 2
}

/**
 * A paragraph (or a single line, heading, label) found on a page: [glyphIds] are the characters to remove when it is edited,
 * [text] what the editor shows (soft-wrapped lines joined by a space, hard breaks as `\n`). Everything is in the page's user space;
 * [box] is the area its characters cover, [maxWidth] how wide the new text may be.
 */
class TextBlock(
    val key: Int, val lines: List<TextLine>, val glyphIds: List<Int>, val text: String, val wrapped: Boolean,
    val font: PDFont, val fontId: Int, val fontName: String, val size: Float, val color: Int, val align: Int,
    val leading: Float, val box: Box, val left: Float, val maxWidth: Float, val firstBaseline: Float, val firstIndent: Float,
    val traits: FontTraits,
    /** The look of every character of [text] (same length), so an edit can keep bold words, links and the like. */
    val styles: List<RunStyle> = emptyList(),
    /** Some characters have no text in the PDF (see [Glyph.unmapped]): the old text can't be shown, only replaced. */
    val unreadable: Boolean = false,
) {
    /** More than one font or colour inside the block. */
    val mixed: Boolean get() = styles.isNotEmpty() && styles.any { !it.same(styles[0]) }
    val lineCount: Int get() = lines.size
    val bottomBaseline: Float get() = lines.last().baseline
}

object TextBlocks {
    private fun same(a: Float, b: Float, tol: Float) = abs(a - b) <= tol

    private class Frag(
        val glyphs: List<Glyph>, val text: String, val baseline: Float, val left: Float, val right: Float, val size: Float, val color: Int,
        val family: String, val extra: List<Int>, val styles: List<RunStyle>, val families: Set<String>, val colors: Set<Int>,
    ) {
        val cx get() = (left + right) / 2
        fun toLine() = TextLine(glyphs, text, baseline, left, right, size, extra, styles)
    }

    private class Build(first: Frag) {
        val frags = arrayListOf(first)
        val last get() = frags.last()
        val left get() = frags.minOf { it.left }
        val right get() = frags.maxOf { it.right }
        var leading = 0f
    }

    /** Finds the text blocks of a page; [pageBox] is the page's crop box in user space. */
    fun detect(scan: PageScan, pageBox: Box): List<TextBlock> {
        val frags = fragments(scan.editableGlyphs)
        val sorted = frags.sortedWith(compareByDescending<Frag> { it.baseline }.thenBy { it.left })
        val builds = ArrayList<Build>()
        for (f in sorted) {
            var best: Build? = null; var bestD = Float.MAX_VALUE
            for (b in builds) {
                val l = b.last
                val d = l.baseline - f.baseline
                if (d <= 0f || d >= bestD || !joins(b, f, d)) continue
                best = b; bestD = d
            }
            if (best != null) {
                if (best.frags.size == 1) best.leading = bestD else best.leading = (best.leading * (best.frags.size - 1) + bestD) / best.frags.size
                best.frags += f
            } else builds += Build(f)
        }
        val blocks = builds.map { toBlock(it, pageBox) }
        return widen(blocks, pageBox).sortedWith(compareByDescending<TextBlock> { it.box.t }.thenBy { it.box.l })
    }

    private fun joins(b: Build, f: Frag, d: Float): Boolean {
        val l = b.last
        val s = max(f.size, l.size)
        if (abs(f.size - l.size) > 0.12f * l.size) return false
        // a bold word, a code snippet or a coloured link may change the look inside a paragraph: lines still belong together when
        // they share a font family, or the paragraph's rhythm (the spacing) says so
        val sharesFamily = f.families.any { it in l.families }
        val sharesColor = f.colors.any { it in l.colors }
        if (!sharesFamily && !(b.frags.size >= 2 && abs(d - b.leading) <= 0.12f * b.leading)) return false
        if (!sharesColor && d > 1.6f * s) return false
        if (d < 0.8f * s || d > 2.3f * s) return false
        if (b.frags.size >= 2 && abs(d - b.leading) > 0.22f * b.leading) return false
        if (f.left >= b.right || f.right <= b.left) return false
        val tol = 0.6f * s
        val leftOk = same(f.left, b.left, tol) || same(f.left, l.left, tol) || (b.frags.size == 1 && f.left < l.left && l.left - f.left <= 3f * s)
        val rightOk = same(f.right, l.right, tol) && b.frags.size >= 1 && (b.frags.size == 1 || b.frags.all { same(it.right, l.right, tol) })
        val centreOk = same(f.cx, l.cx, tol) && (b.frags.size == 1 || b.frags.all { same(it.cx, l.cx, tol) })
        if (!(leftOk || rightOk || centreOk)) return false
        // a short last line ends a left-aligned paragraph: the next line starts a new one
        if (b.frags.size >= 2 && leftOk && !centreOk && !rightOk) {
            val widest = b.frags.maxOf { it.right - it.left }
            if (l.right - l.left < 0.55f * widest) return false
        }
        return true
    }

    private fun fragments(glyphs: List<Glyph>): List<Frag> {
        val lines = ArrayList<MutableList<Glyph>>()
        val refs = ArrayList<Pair<Float, Float>>() // baseline, size
        for (g in glyphs.sortedWith(compareByDescending<Glyph> { it.baseY }.thenBy { it.baseX })) {
            var hit = -1
            for (i in lines.lastIndex downTo max(0, lines.size - 10)) {
                if (abs(refs[i].first - g.baseY) <= 0.3f * min(refs[i].second, g.size)) { hit = i; break }
            }
            if (hit < 0) { lines += mutableListOf(g); refs += g.baseY to g.size } else lines[hit] += g
        }
        val out = ArrayList<Frag>()
        val styleCache = HashMap<Long, RunStyle>()
        for (line in lines) {
            val gs = line.sortedBy { it.baseX }
            var cur = ArrayList<Glyph>()
            fun flush() { if (cur.isNotEmpty()) { frag(cur, styleCache)?.let { out += it }; cur = ArrayList() } }
            for (g in gs) {
                val prev = cur.lastOrNull()
                if (prev != null) {
                    val gap = g.box.l - prev.box.r
                    val split = gap > 2.2f * max(g.size, prev.size) || gap < -3f * g.size && false ||
                        abs(g.size - prev.size) > 0.15f * prev.size && g.text.isNotBlank() && prev.text.isNotBlank()
                    if (split) flush()
                }
                cur += g
            }
            flush()
        }
        return out
    }

    private val traitCache = java.util.IdentityHashMap<Any, FontTraits>()
    private fun traitsOf(f: PDFont): FontTraits = traitCache.getOrPut(f.cosObject) { FontTraits.of(f) }
    private fun styleOf(g: Glyph, cache: HashMap<Long, RunStyle>): RunStyle = cache.getOrPut(g.fontId.toLong() * 0x1_0000_0000L + (g.color.toLong() and 0xFFFFFFFFL)) { RunStyle(g.font, g.fontId, g.fontName, traitsOf(g.font), g.color) }

    private fun frag(glyphs: List<Glyph>, cache: HashMap<Long, RunStyle>): Frag? {
        val ink = glyphs.filter { it.text.isNotBlank() }
        if (ink.isEmpty()) return null
        val sb = StringBuilder(); val st = ArrayList<RunStyle>(); val extra = ArrayList<Int>()
        fun add(t: String, style: RunStyle) { sb.append(t); repeat(t.length) { st += style } }
        var prev: Glyph? = null
        for (g in glyphs) {
            val p = prev
            if (p != null && g.text == p.text && abs(g.baseX - p.baseX) < 0.08f * g.size && abs(g.baseY - p.baseY) < 0.2f * g.size) { extra += g.index; continue } // drawn twice (fake bold, shadow)
            if (p != null && g.text.isNotBlank() && p.text.isNotBlank() && g.box.l - p.box.r > 0.17f * g.size) add(" ", styleOf(p, cache))
            add(g.text, styleOf(g, cache))
            prev = g
        }
        // trim, and collapse runs of blanks into one, keeping every character's style
        val text = StringBuilder(); val styles = ArrayList<RunStyle>()
        for (i in 0 until sb.length) {
            val c = sb[i]
            if (c.isWhitespace()) { if (text.isEmpty() || text.last() == ' ') continue; text.append(' '); styles += st[i] } else { text.append(c); styles += st[i] }
        }
        while (text.isNotEmpty() && text.last() == ' ') { text.setLength(text.length - 1); styles.removeAt(styles.size - 1) }
        val fam = traitsOf(dominant(ink) { it.fontId }.font).family
        val sizeBucket = dominant(ink) { Math.round(it.size * 10f) }.size
        val col = dominant(ink) { it.color }.color
        return Frag(
            glyphs.map { it }, text.toString(), ink.map { it.baseY }.average().toFloat(), ink.minOf { it.box.l }, ink.maxOf { it.box.r }, sizeBucket, col, fam, extra,
            styles, ink.map { traitsOf(it.font).family }.toSet(), ink.map { it.color }.toSet(),
        )
    }

    private fun <K> dominant(gs: List<Glyph>, key: (Glyph) -> K): Glyph = gs.groupBy(key).maxByOrNull { it.value.size }!!.value.first()

    private fun toBlock(b: Build, page: Box): TextBlock {
        val lines = b.frags.map { it.toLine() }
        val all = lines.flatMap { it.glyphs }
        val ink = all.filter { it.text.isNotBlank() }
        val n = lines.size
        val size = dominant(ink) { Math.round(it.size * 10f) }.size
        val domFont = dominant(ink) { it.fontId }
        val color = dominant(ink) { it.color }.color
        val s = size
        val tol = 0.6f * s
        val lefts = lines.map { it.left }; val rights = lines.map { it.right }
        val widest = lines.maxOf { it.width }
        val indented = n >= 2 && lines[0].left - lines[1].left > 0.5f * s && lines.drop(1).let { rest -> rest.maxOf { it.left } - rest.minOf { it.left } <= tol }
        val bodyLefts = if (indented) lefts.drop(1) else lefts
        val leftAligned = bodyLefts.max() - bodyLefts.min() <= tol
        val align = when {
            n == 1 -> single(lines[0], page)
            leftAligned -> {
                val inner = rights.dropLast(1)
                if (n >= 3 && inner.max() - inner.min() <= tol && inner.all { it - bodyLefts.min() > 0.7f * page.w * 0.5f }) Align.JUSTIFY else Align.LEFT
            }
            lines.all { same(it.cx, lines[0].cx, tol) } -> Align.CENTER
            rights.max() - rights.min() <= tol -> Align.RIGHT
            else -> Align.LEFT
        }
        val blockLeft = if (align == Align.LEFT || align == Align.JUSTIFY) (bodyLefts.min()) else lefts.min()
        val wrapped = n > 1 && lines.dropLast(1).all { it.width >= 0.88f * widest }
        val sep = if (wrapped) " " else "\n"
        val text = lines.joinToString(sep) { it.text }
        val styles = ArrayList<RunStyle>(text.length)
        lines.forEachIndexed { i, ln -> if (i > 0) styles += (lines[i - 1].styles.lastOrNull() ?: ln.styles.firstOrNull() ?: return@forEachIndexed).let { it }; styles += ln.styles }
        val leading = if (n >= 2) lines.zipWithNext { a, c -> a.baseline - c.baseline }.sorted().let { it[it.size / 2] } else s * 1.2f
        val box = all.map { it.box }.reduce { a, c -> a.union(c) }
        val ids = (all.map { it.index } + lines.flatMap { it.extraIds }).distinct().sorted()
        val rightEdge = rights.max()
        val width = when (align) {
            Align.CENTER -> max(widest, (rights.max() - lefts.min()))
            else -> rightEdge - blockLeft
        }
        return TextBlock(
            key = ids.first(), lines = lines, glyphIds = ids, text = text, wrapped = wrapped, font = domFont.font, fontId = domFont.fontId,
            fontName = domFont.fontName, size = size, color = color, align = align, leading = leading, box = box, left = blockLeft,
            maxWidth = max(width, widest), firstBaseline = lines[0].baseline, firstIndent = if (indented && align == Align.LEFT) lines[0].left - blockLeft else 0f,
            traits = traitsOf(domFont.font), styles = if (styles.size == text.length) styles else emptyList(), unreadable = all.any { it.unmapped },
        )
    }

    private fun single(l: TextLine, page: Box): Int {
        val centred = same(l.cx, page.cx, 0.02f * page.w) && l.width < 0.9f * page.w && l.left - page.l > 0.08f * page.w
        if (centred) return Align.CENTER
        val rightEdge = page.r - l.right
        if (rightEdge < 0.07f * page.w && l.left - page.l > 0.35f * page.w) return Align.RIGHT
        return Align.LEFT
    }

    /** Single-line blocks may grow sideways up to the page margin or the next thing on the same line. */
    private fun widen(blocks: List<TextBlock>, page: Box): List<TextBlock> = blocks.map { b ->
        if (b.lineCount != 1) return@map b
        val l = b.lines[0]
        val band = Box(page.l, l.baseline - 0.3f * b.size, page.r, l.baseline + b.size * 0.9f)
        val others = blocks.filter { it !== b && it.box.intersects(band) }
        val rightWall = others.filter { it.box.l >= l.right - 0.01f }.minOfOrNull { it.box.l } ?: page.r
        val leftWall = others.filter { it.box.r <= l.left + 0.01f }.maxOfOrNull { it.box.r } ?: page.l
        val margin = 0.5f * min(l.left - page.l, 40f)
        val room = when (b.align) {
            Align.CENTER -> 2f * (min(l.cx - leftWall, rightWall - l.cx) - margin)
            Align.RIGHT -> l.right - leftWall - margin
            else -> rightWall - l.left - margin
        }
        val width = max(l.width, room)
        val newLeft = when (b.align) { Align.CENTER -> l.cx - width / 2; Align.RIGHT -> l.right - width; else -> b.left }
        TextBlock(b.key, b.lines, b.glyphIds, b.text, b.wrapped, b.font, b.fontId, b.fontName, b.size, b.color, b.align, b.leading, b.box, newLeft, width, b.firstBaseline, b.firstIndent, b.traits, b.styles, b.unreadable)
    }
}
