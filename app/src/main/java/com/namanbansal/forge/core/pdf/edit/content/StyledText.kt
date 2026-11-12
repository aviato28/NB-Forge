package com.namanbansal.forge.core.pdf.edit.content

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import kotlin.math.max

/** Keeps the look of the characters of a block that an edit leaves alone. */
object StyleMap {
    /**
     * [orig] is the block's text with one style per character; [new] is what the user typed. Characters that are still at the start
     * or the end of the text keep their style; typed-in characters take the style of the character just before them (or after, at
     * the very start), like a word processor. Returns one style per character of [new], or null when the block has no style data.
     */
    fun map(orig: String, origStyles: List<RunStyle>, new: String): List<RunStyle>? {
        if (origStyles.size != orig.length || origStyles.isEmpty()) return null
        if (new.isEmpty()) return emptyList()
        var p = 0
        while (p < orig.length && p < new.length && orig[p] == new[p]) p++
        var s = 0
        while (s < orig.length - p && s < new.length - p && orig[orig.length - 1 - s] == new[new.length - 1 - s]) s++
        val out = ArrayList<RunStyle>(new.length)
        for (i in new.indices) {
            out += when {
                i < p -> origStyles[i]
                i >= new.length - s -> origStyles[orig.length - (new.length - i)]
                p > 0 -> origStyles[p - 1]
                s > 0 -> origStyles[orig.length - s]
                else -> origStyles[0]
            }
        }
        return out
    }
}

/** One code point of text and the number of the style it is drawn in. */
class SChar(val text: String, val style: Int)

/** Everything needed to draw an edited block: the laid-out lines and the font / colour for each style. */
class TextPlan(
    val layout: Layout, val size: Float,
    /** One font per style; a plain block has exactly one. */
    val choices: List<FontChoice>,
    /** Fill colour per style (ARGB). */
    val colors: List<Int>,
    val styled: Boolean,
    val notices: List<String>,
) {
    val notice: String? get() = notices.firstOrNull()
    val fontName: String get() = choices.first().name
}

object TextPlanner {
    /**
     * Lays out [text] for [b]. When the block mixes fonts or colours (a bold word, a link) and the caller isn't forcing one look
     * ([bold] / [italic] / [color] null), the unchanged parts keep their own font and colour; otherwise the whole block uses one look.
     */
    fun plan(
        doc: PDDocument, fonts: FontOpener, b: TextBlock, seen: Map<Int, Map<String, ByteArray>>, text: String,
        sizePt: Float, align: Int, bold: Boolean?, italic: Boolean?, color: Int?, noto: MutableMap<String, PDType0Font>,
    ): TextPlan {
        val size = if (sizePt > 0f) sizePt else b.size
        val leading = b.leading * size / b.size
        val al = if (align >= 0) align else b.align
        val styles = if (!b.unreadable && b.mixed && bold == null && italic == null) StyleMap.map(b.text, b.styles, text) else null
        val distinct = styles?.fold(ArrayList<RunStyle>()) { acc, st -> if (acc.none { it.same(st) }) acc += st; acc }.orEmpty()
        if (styles == null || distinct.size < 2) {
            val st = distinct.singleOrNull()
            val choice = if (st != null) TextFonts.chooseFor(doc, fonts, st.font, st.traits, st.fontName, seen[st.fontId].orEmpty(), text, null, null, noto)
            else TextFonts.choose(doc, fonts, b, seen[b.fontId].orEmpty(), text, bold, italic, noto)
            val lay = TextLayout.layout(text, choice.coder, size, leading, al, b.left, b.maxWidth, b.firstBaseline, b.firstIndent)
            return TextPlan(lay, size, listOf(choice), listOf(color ?: st?.color ?: b.color), false, listOfNotNull(choice.notice))
        }
        // one font per style, each checked against only the characters it has to show
        val choices = distinct.map { st ->
            val own = styles.indices.filter { styles[it].same(st) }.joinToString("") { text[it].toString() }
            TextFonts.chooseFor(doc, fonts, st.font, st.traits, st.fontName, seen[st.fontId].orEmpty(), own, null, null, noto)
        }
        val chars = ArrayList<SChar>(); var i = 0
        while (i < text.length) {
            val n = Character.charCount(text.codePointAt(i))
            chars += SChar(text.substring(i, minOf(text.length, i + n)), distinct.indexOfFirst { it.same(styles[i]) })
            i += n
        }
        val lay = TextLayout.layoutStyled(chars, choices.map { it.coder }, size, leading, al, b.left, b.maxWidth, b.firstBaseline, b.firstIndent)
        return TextPlan(lay, size, choices, distinct.map { color ?: it.color }, true, choices.mapNotNull { it.notice }.distinct())
    }
}

/** Word-wrapping for text with several styles; the one-style [TextLayout.layout] stays as it is. */
fun TextLayout.layoutStyled(
    input: List<SChar>, coders: List<FontCoder>, size: Float, leading: Float, align: Int, left: Float, width: Float, firstBaseline: Float, firstIndent: Float,
): Layout {
    // clean like TextLayout.clean: blanks, control characters, what the style's font can't show
    var replaced = false
    val chars = ArrayList<SChar>()
    var k = 0
    while (k < input.size) {
        val c = input[k++]
        val cp = c.text.codePointAt(0)
        when {
            c.text == "\r" -> { if (k < input.size && input[k].text == "\n") k++; chars += SChar("\n", c.style) }
            cp == '\n'.code -> chars += SChar("\n", c.style)
            cp == 0xA0 || cp == '\t'.code || cp == 0x2009 || cp == 0x202F -> chars += SChar(" ", c.style)
            Character.isISOControl(cp) || cp == 0xAD || cp == 0x200B -> Unit
            Character.isWhitespace(cp) -> chars += SChar(" ", c.style)
            coders[c.style].canEncode(c.text) -> chars += c
            else -> { chars += SChar("?", c.style); replaced = true }
        }
    }
    fun wEm(word: List<SChar>): Float {
        var w = 0f; var i = 0
        while (i < word.size) {
            var j = i; val sb = StringBuilder()
            while (j < word.size && word[j].style == word[i].style) { sb.append(word[j].text); j++ }
            w += coders[word[i].style].widthEm(sb.toString()) ?: 0f
            i = j
        }
        return w
    }
    fun w(word: List<SChar>) = wEm(word) * size
    fun spaceAfter(word: List<SChar>) = coders[word.lastOrNull()?.style ?: 0].spaceEm * size
    class Raw(val words: List<List<SChar>>, val w: Float, val last: Boolean)
    val raws = ArrayList<Raw>()
    val paras = ArrayList<ArrayList<SChar>>().also { it.add(ArrayList()) }
    for (c in chars) if (c.text == "\n") paras.add(ArrayList()) else paras.last().add(c)
    for (para in paras) {
        val words = ArrayList<List<SChar>>(); var cur = ArrayList<SChar>()
        for (c in para) { if (c.text == " ") { if (cur.isNotEmpty()) { words.add(cur); cur = ArrayList() } } else cur += c }
        if (cur.isNotEmpty()) words.add(cur)
        if (words.isEmpty()) { raws += Raw(emptyList(), 0f, true); continue }
        var line = ArrayList<List<SChar>>(); var lineW = 0f
        fun avail() = width - if (raws.isEmpty()) firstIndent else 0f
        fun flush(last: Boolean) { raws += Raw(line, lineW, last); line = ArrayList(); lineW = 0f }
        for (word in words) {
            var piece = word
            while (w(piece) > width && piece.size > 1) {
                var n = piece.size - 1
                while (n > 1 && w(piece.subList(0, n)) > width - (if (line.isEmpty()) 0f else lineW + spaceAfter(line.last()))) n--
                if (line.isNotEmpty() && w(piece.subList(0, n)) + lineW + spaceAfter(line.last()) > avail()) { flush(false); continue }
                line.add(piece.subList(0, n)); lineW += w(piece.subList(0, n)); flush(false); piece = piece.subList(n, piece.size)
            }
            val ww = w(piece)
            if (line.isEmpty()) { line.add(piece); lineW = ww }
            else if (lineW + spaceAfter(line.last()) + ww <= avail()) { lineW += spaceAfter(line.last()) + ww; line.add(piece) }
            else { flush(false); line.add(piece); lineW = ww }
        }
        flush(true)
    }
    val lines = ArrayList<LaidLine>()
    var box: Box? = null
    raws.forEachIndexed { i, r ->
        val indent = if (i == 0) firstIndent else 0f
        val spaces = r.words.size - 1
        val gap = if (align == Align.JUSTIFY && !r.last && spaces > 0) (((width - indent - r.w) / spaces).takeIf { it <= 1.5f * coders[0].spaceEm * size } ?: 0f).coerceAtLeast(0f) else 0f
        val lineW = r.w + gap * maxOf(spaces, 0)
        val x0 = when (align) { Align.CENTER -> left + (width - r.w) / 2; Align.RIGHT -> left + width - r.w; else -> left + indent }
        val y = firstBaseline - i * leading
        val segs = ArrayList<LaidSeg>(); var x = x0
        r.words.forEachIndexed { wi, word ->
            if (wi > 0) x += spaceAfter(r.words[wi - 1]) + gap
            var a = 0
            while (a < word.size) {
                var e = a; val sb = StringBuilder()
                while (e < word.size && word[e].style == word[a].style) { sb.append(word[e].text); e++ }
                segs += LaidSeg(sb.toString(), word[a].style, x)
                x += (coders[word[a].style].widthEm(sb.toString()) ?: 0f) * size
                a = e
            }
        }
        val text = r.words.joinToString(" ") { wd -> wd.joinToString("") { it.text } }
        lines += LaidLine(text, x0, y, lineW, gap, segs)
        if (text.isNotEmpty()) { val b = Box(x0, y - 0.22f * size, x0 + lineW, y + 0.8f * size); box = box?.union(b) ?: b }
    }
    return Layout(lines, size, leading, box ?: Box(left, firstBaseline, left, firstBaseline), replaced)
}
