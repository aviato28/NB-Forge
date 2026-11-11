package com.namanbansal.forge.core.pdf.edit.content

import com.tom_roush.pdfbox.pdmodel.font.encoding.GlyphList
import com.tom_roush.fontbox.ttf.TrueTypeFont
import com.tom_roush.pdfbox.pdmodel.font.PDCIDFontType2
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDTrueTypeFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import java.util.IdentityHashMap

/**
 * Finds the text of a character the PDF gives no Unicode mapping for (no /ToUnicode, no usable encoding), which is common in
 * bank and invoice PDFs. The embedded TrueType font is asked instead: code -> glyph, then glyph -> Unicode through the font's own
 * cmap (read backwards) or its PostScript glyph names (Adobe Glyph List). One lookup table per font, built on first use.
 */
class GlyphRecovery {
    private class Table(val toGid: (Int) -> Int, val byGid: Map<Int, String>)
    private val tables = IdentityHashMap<Any, Table?>()

    /** The text for [code] in [font], or null when the font can't tell. */
    fun recover(font: PDFont, code: Int): String? {
        val key = font.cosObject
        val table = if (tables.containsKey(key)) tables[key] else build(font).also { tables[key] = it }
        table ?: return null
        val gid = runCatching { table.toGid(code) }.getOrDefault(0)
        if (gid <= 0) return null
        return table.byGid[gid]
    }

    private fun build(font: PDFont): Table? = runCatching {
        val (ttf, toGid) = when (font) {
            is PDTrueTypeFont -> (font.trueTypeFont ?: return null) to { c: Int -> font.codeToGID(c) }
            is PDType0Font -> {
                val d = font.descendantFont as? PDCIDFontType2 ?: return null
                (d.trueTypeFont ?: return null) to { c: Int -> d.codeToGID(c) }
            }
            else -> return null
        }
        Table(toGid, glyphTexts(ttf))
    }.getOrNull()

    private fun glyphTexts(ttf: TrueTypeFont): Map<Int, String> {
        val out = HashMap<Int, String>()
        // 1. the font's own Unicode cmap, read backwards (subset fonts usually keep it)
        val subtables = runCatching { ttf.cmap?.cmaps?.toList() }.getOrNull().orEmpty()
        val unicode = subtables.filter { it.platformId == 0 || (it.platformId == 3 && (it.platformEncodingId == 1 || it.platformEncodingId == 10)) }
        for (t in unicode) {
            for (cp in 0x21..0xFFFF) {
                if (cp in 0xD800..0xDFFF || cp == 0xA0 || cp == 0xAD) continue
                val g = runCatching { t.getGlyphId(cp) }.getOrDefault(0)
                if (g > 0 && g !in out) out[g] = String(Character.toChars(cp))
            }
        }
        // symbol (3,0) cmaps keep ASCII at 0xF000 + code
        for (t in subtables.filter { it.platformId == 3 && it.platformEncodingId == 0 }) {
            for (cp in 0xF021..0xF0FF) {
                val g = runCatching { t.getGlyphId(cp) }.getOrDefault(0)
                if (g > 0 && g !in out) out[g] = (cp - 0xF000).toChar().toString()
            }
        }
        // 2. PostScript glyph names (a, eacute, uni20AC, ...)
        val names = runCatching { ttf.postScript?.glyphNames }.getOrNull()
        if (names != null) {
            val agl = GlyphList.getAdobeGlyphList()
            for ((gid, n) in names.withIndex()) {
                if (gid == 0 || gid in out || n.isNullOrEmpty() || n == ".notdef" || n.startsWith("glyph") || Regex("g\\d+").matches(n)) continue
                val u = runCatching { agl.toUnicode(n) }.getOrNull()
                if (!u.isNullOrEmpty() && u[0].code > 0x20 && !u[0].isISOControl()) out[gid] = u
            }
        }
        return out
    }
}
