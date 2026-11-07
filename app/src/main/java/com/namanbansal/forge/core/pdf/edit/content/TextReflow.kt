package com.namanbansal.forge.core.pdf.edit.content

import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.font.PDVectorFont
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

/** Opens a bundled font file by name (`fonts/NotoSans-Regular.ttf`): the app reads assets, tests read the source tree. */
fun interface FontOpener { fun open(name: String): InputStream }

/** The bundled OFL fallback faces. */
enum class NotoFace(val family: String, val regular: String, val bold: String) {
    SANS("Noto Sans", "fonts/NotoSans-Regular.ttf", "fonts/NotoSans-Bold.ttf"),
    SERIF("Noto Serif", "fonts/NotoSerif-Regular.ttf", "fonts/NotoSerif-Bold.ttf"),
    MONO("Noto Sans Mono", "fonts/NotoSansMono-Regular.ttf", "fonts/NotoSansMono-Bold.ttf");
    fun file(bold: Boolean) = if (bold) this.bold else regular
}

/** Encodes and measures text for one font. Words are encoded one at a time so a missing space glyph never matters. */
interface FontCoder {
    fun canEncode(ch: String): Boolean
    /** Width of [word] in em (1.0 = the font size), or null if a character can't be shown. */
    fun widthEm(word: String): Float?
    /** The TJ items that show [word], or null if a character can't be shown. */
    fun wordItems(word: String): List<COSBase>?
    val spaceEm: Float
    /** The items for one space: the glyph when the font has it, otherwise nothing (the gap is added as a number). */
    val spaceItems: List<COSBase>
}

/** The block's own font. [seen] maps a character to the bytes the page already shows it with (what a subset is known to hold). */
class ExistingCoder(private val font: PDFont, private val seen: Map<String, ByteArray>) : FontCoder {
    private val cache = HashMap<String, ByteArray?>()

    private fun codes(b: ByteArray): List<Int> {
        val inp = ByteArrayInputStream(b); val out = ArrayList<Int>()
        while (inp.available() > 0) out += try { font.readCode(inp) } catch (_: Exception) { break }
        return out
    }

    private fun bytesFor(ch: String): ByteArray? {
        if (cache.containsKey(ch)) return cache[ch]
        val found = seen[ch] ?: if (font is PDType0Font) null else runCatching {
            val b = font.encode(ch)
            val vf = font as? PDVectorFont
            if (vf != null && !codes(b).all { runCatching { vf.hasGlyph(it) }.getOrDefault(false) }) null else b
        }.getOrNull()
        cache[ch] = found
        return found
    }

    override fun canEncode(ch: String) = ch.isBlank() || bytesFor(ch) != null

    private fun chars(s: String): List<String> { val out = ArrayList<String>(); var i = 0; while (i < s.length) { val n = Character.charCount(s.codePointAt(i)); out += s.substring(i, i + n); i += n }; return out }

    override fun widthEm(word: String): Float? {
        var w = 0f
        for (c in chars(word)) { val b = bytesFor(c) ?: return null; w += codes(b).sumOf { runCatching { font.getWidth(it) }.getOrDefault(0f).toDouble() }.toFloat() / 1000f }
        return w
    }

    override fun wordItems(word: String): List<COSBase>? {
        val all = java.io.ByteArrayOutputStream()
        for (c in chars(word)) all.write(bytesFor(c) ?: return null)
        return listOf(COSString(all.toByteArray()))
    }

    private val space = bytesFor(" ")
    override val spaceEm: Float get() = (space?.let { b -> codes(b).sumOf { runCatching { font.getWidth(it) }.getOrDefault(0f).toDouble() }.toFloat() / 1000f }
        ?: runCatching { font.spaceWidth / 1000f }.getOrDefault(0.25f)).let { if (it in 0.1f..0.8f) it else 0.25f }
    override val spaceItems: List<COSBase> get() = space?.let { listOf(COSString(it)) } ?: emptyList()
}

/** A font we embedded ourselves (Noto): [PDType0Font] encodes and measures it. */
class EmbeddedCoder(private val font: PDType0Font) : FontCoder {
    override fun canEncode(ch: String) = ch.isBlank() || runCatching { font.encode(ch); true }.getOrDefault(false)
    override fun widthEm(word: String): Float? = runCatching { font.getStringWidth(word) / 1000f }.getOrNull()
    override fun wordItems(word: String): List<COSBase>? = runCatching { listOf<COSBase>(COSString(font.encode(word))) }.getOrNull()
    override val spaceEm: Float get() = runCatching { font.getStringWidth(" ") / 1000f }.getOrDefault(0.26f)
    override val spaceItems: List<COSBase> get() = runCatching { listOf<COSBase>(COSString(font.encode(" "))) }.getOrDefault(emptyList())
}

/** The font a block's new text will be written with, and what to tell the user about it. */
class FontChoice(
    val font: PDFont, val coder: FontCoder, val name: String, val notice: String?,
    val syntheticBold: Boolean, val syntheticItalic: Boolean, val fallback: Boolean, val replacedChars: Boolean,
)

object TextFonts {
    private fun std14(traits: FontTraits, bold: Boolean, italic: Boolean): PDFont = when {
        traits.mono -> if (bold && italic) PDType1Font.COURIER_BOLD_OBLIQUE else if (bold) PDType1Font.COURIER_BOLD else if (italic) PDType1Font.COURIER_OBLIQUE else PDType1Font.COURIER
        traits.serif -> if (bold && italic) PDType1Font.TIMES_BOLD_ITALIC else if (bold) PDType1Font.TIMES_BOLD else if (italic) PDType1Font.TIMES_ITALIC else PDType1Font.TIMES_ROMAN
        else -> if (bold && italic) PDType1Font.HELVETICA_BOLD_OBLIQUE else if (bold) PDType1Font.HELVETICA_BOLD else if (italic) PDType1Font.HELVETICA_OBLIQUE else PDType1Font.HELVETICA
    }

    fun faceFor(t: FontTraits) = when { t.mono -> NotoFace.MONO; t.serif -> NotoFace.SERIF; else -> NotoFace.SANS }

    /** Characters of [text] that need a glyph (no spaces or line breaks). */
    fun needed(text: String): List<String> {
        val out = LinkedHashSet<String>(); var i = 0
        while (i < text.length) { val cp = text.codePointAt(i); val n = Character.charCount(cp); if (!Character.isWhitespace(cp) && !Character.isISOControl(cp)) out += text.substring(i, i + n); i += n }
        return out.toList()
    }

    /** Characters the page already shows with [fontId]: character -> bytes. */
    fun seen(scan: PageScan): Map<Int, Map<String, ByteArray>> {
        val out = HashMap<Int, HashMap<String, ByteArray>>()
        for (g in scan.glyphs) {
            if (g.text.isEmpty() || !g.editable) continue
            val m = out.getOrPut(g.fontId) { HashMap() }
            if (g.text !in m) m[g.text] = when (g.codeLen) { 2 -> byteArrayOf((g.code shr 8).toByte(), g.code.toByte()); 3 -> byteArrayOf((g.code shr 16).toByte(), (g.code shr 8).toByte(), g.code.toByte()); 4 -> byteArrayOf((g.code shr 24).toByte(), (g.code shr 16).toByte(), (g.code shr 8).toByte(), g.code.toByte()); else -> byteArrayOf(g.code.toByte()) }
        }
        return out
    }

    /**
     * Picks the font for [text] in [block]: the block's own font when it can show every character (checked glyph by glyph, so a
     * subset that lacks a letter doesn't count), a standard-14 relative for bold / italic of a standard font, else the closest
     * bundled Noto face (loaded into [doc], subset when the document is saved). [bold] / [italic] are the requested style or null to keep.
     */
    fun choose(doc: PDDocument, fonts: FontOpener, block: TextBlock, seen: Map<String, ByteArray>, text: String, bold: Boolean?, italic: Boolean?, cache: MutableMap<String, PDType0Font>? = null): FontChoice {
        val t = block.traits
        val wantBold = bold ?: t.bold; val wantItalic = italic ?: t.italic
        val need = needed(text)
        val own = ExistingCoder(block.font, seen)
        val glyphsOk = need.all { own.canEncode(it) }
        val unboldable = !wantBold && t.bold; val unitalicable = !wantItalic && t.italic
        val styleOk = !unboldable && !unitalicable
        val name = TextBlockInfo.displayName(block.fontName)
        if (glyphsOk && wantBold == t.bold && wantItalic == t.italic) return FontChoice(block.font, own, name, null, false, false, false, false)
        if (block.font.isStandard14 && (wantBold != t.bold || wantItalic != t.italic)) {
            val v = std14(t, wantBold, wantItalic); val c = ExistingCoder(v, emptyMap())
            if (need.all { c.canEncode(it) }) return FontChoice(v, c, TextBlockInfo.displayName(v.name), null, false, false, false, false)
        }
        if (glyphsOk && styleOk) return FontChoice(block.font, own, name, null, wantBold && !t.bold, wantItalic && !t.italic, false, false)
        val face = faceFor(t)
        val key = face.file(wantBold)
        val f = cache?.get(key) ?: fonts.open(key).use { PDType0Font.load(doc, it, true) }.also { cache?.put(key, it) }
        val coder = EmbeddedCoder(f)
        val replaced = need.any { !coder.canEncode(it) }
        val why = if (!glyphsOk) "the original font doesn't include some of these letters" else "the original font has no ${if (unboldable) "regular" else "upright"} style"
        val notice = "Used ${face.family} — $why." + if (replaced) " A few characters aren't in any bundled font and became “?”." else ""
        return FontChoice(f, coder, face.family + if (wantBold) " Bold" else "", notice, false, wantItalic, true, replaced)
    }
}

object TextBlockInfo {
    fun displayName(raw: String): String = FontTraits.stripSubset(raw).replace('-', ' ').replace('_', ' ').trim().ifEmpty { "font" }
}

/** One line of the new text, placed in user space. [gap] is the extra space added at each blank (justified lines). */
class LaidLine(val text: String, val x: Float, val baseline: Float, val width: Float, val gap: Float)

class Layout(val lines: List<LaidLine>, val size: Float, val leading: Float, val box: Box, val replaced: Boolean)

object TextLayout {
    /** Replaces non-breaking spaces and tabs, drops control characters, and turns characters [coder] can't show into "?". */
    fun clean(text: String, coder: FontCoder): Pair<String, Boolean> {
        var replaced = false
        val sb = StringBuilder(); var i = 0
        val t = text.replace("\r\n", "\n").replace('\r', '\n')
        while (i < t.length) {
            val cp = t.codePointAt(i); val n = Character.charCount(cp); val s = t.substring(i, i + n); i += n
            when {
                cp == '\n'.code -> sb.append('\n')
                cp == 0xA0 || cp == '\t'.code || cp == 0x2009 || cp == 0x202F -> sb.append(' ')
                Character.isISOControl(cp) || cp == 0xAD || cp == 0x200B -> Unit
                Character.isWhitespace(cp) -> sb.append(' ')
                coder.canEncode(s) -> sb.append(s)
                else -> { sb.append('?'); replaced = true }
            }
        }
        return sb.toString() to replaced
    }

    /** Wraps [text] into lines no wider than [width] (points) with real font metrics and places them like the original block. */
    fun layout(text: String, coder: FontCoder, size: Float, leading: Float, align: Int, left: Float, width: Float, firstBaseline: Float, firstIndent: Float): Layout {
        val (clean, replaced) = clean(text, coder)
        val spaceW = coder.spaceEm * size
        fun w(word: String) = (coder.widthEm(word) ?: coder.widthEm(word.map { if (coder.canEncode(it.toString())) it else '?' }.joinToString("")) ?: 0f) * size
        class Raw(val text: String, val w: Float, val spaces: Int, val last: Boolean)
        val raws = ArrayList<Raw>()
        for (para in clean.split('\n')) {
            val words = para.split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty()) { raws += Raw("", 0f, 0, true); continue }
            var cur = ArrayList<String>(); var curW = 0f
            fun avail() = width - if (raws.isEmpty()) firstIndent else 0f
            fun flush(last: Boolean) { raws += Raw(cur.joinToString(" "), curW, cur.size - 1, last); cur = ArrayList(); curW = 0f }
            for (word in words) {
                var piece = word
                // a word wider than the box is broken between characters
                while (w(piece) > width && piece.length > 1) {
                    var k = piece.length - 1
                    while (k > 1 && w(piece.substring(0, k)) > width - (if (cur.isEmpty()) 0f else curW + spaceW)) k--
                    if (cur.isNotEmpty() && w(piece.substring(0, k)) + curW + spaceW > avail()) { flush(false); continue }
                    cur += piece.substring(0, k); curW += w(piece.substring(0, k)); flush(false); piece = piece.substring(k)
                }
                val ww = w(piece)
                if (cur.isEmpty()) { cur += piece; curW = ww }
                else if (curW + spaceW + ww <= avail()) { cur += piece; curW += spaceW + ww }
                else { flush(false); cur += piece; curW = ww }
            }
            flush(true)
        }
        val lines = ArrayList<LaidLine>()
        var box: Box? = null
        raws.forEachIndexed { i, r ->
            val indent = if (i == 0) firstIndent else 0f
            val natural = r.w
            val gap = if (align == Align.JUSTIFY && !r.last && r.spaces > 0) ((width - indent - natural) / r.spaces).coerceIn(0f, size * 1.5f) else 0f
            val lineW = natural + gap * r.spaces
            val x = when (align) { Align.CENTER -> left + (width - natural) / 2; Align.RIGHT -> left + width - natural; else -> left + indent }
            val y = firstBaseline - i * leading
            lines += LaidLine(r.text, x, y, lineW, gap)
            if (r.text.isNotEmpty()) { val b = Box(x, y - 0.22f * size, x + lineW, y + 0.8f * size); box = box?.union(b) ?: b }
        }
        return Layout(lines, size, leading, box ?: Box(left, firstBaseline, left, firstBaseline), replaced)
    }

    /** The first block below [block] (or beside it) that the new text would run into, or null. */
    fun overlap(block: TextBlock, layout: Layout, others: List<TextBlock>): TextBlock? {
        val nb = layout.box
        if (layout.lines.all { it.text.isEmpty() }) return null
        val grown = nb.b < block.box.b - 0.5f
        val wider = nb.l < block.box.l - 0.5f || nb.r > block.box.r + 0.5f
        if (!grown && !wider) return null
        return others.firstOrNull { o ->
            o !== block && !o.box.intersects(block.box) && o.box.intersects(Box(nb.l, nb.b, nb.r, nb.t)) &&
                o.box.l < nb.r - 0.5f && o.box.r > nb.l + 0.5f && min(o.box.t, nb.t) - max(o.box.b, nb.b) > 0.5f
        }
    }

    /** TJ items for one laid line. */
    fun items(line: LaidLine, coder: FontCoder, size: Float): List<COSBase> {
        val out = ArrayList<COSBase>()
        val words = line.text.split(' ').filter { it.isNotEmpty() }
        val spaceGlyph = coder.spaceItems
        val gapPt = (if (spaceGlyph.isEmpty()) coder.spaceEm * size else 0f) + line.gap
        val kern: COSBase? = if (gapPt != 0f) COSFloat(-gapPt * 1000f / size) else null
        words.forEachIndexed { i, wd ->
            if (i > 0) { out.addAll(spaceGlyph); kern?.let { out += it } }
            val safe = coder.wordItems(wd) ?: coder.wordItems(wd.map { if (coder.canEncode(it.toString())) it else '?' }.joinToString(""))
            if (safe != null) out.addAll(safe)
        }
        return out
    }
}
