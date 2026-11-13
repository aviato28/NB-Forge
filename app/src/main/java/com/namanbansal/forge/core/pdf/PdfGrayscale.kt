package com.namanbansal.forge.core.pdf

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import java.io.ByteArrayOutputStream
import java.util.IdentityHashMap

/**
 * Colour -> gray, for pictures (via [PdfImagePass]) and for colour operators in content streams:
 * rg/RG, k/K and sc/scn/SC/SCN under an RGB or CMYK colour space become gray. Patterns and shadings are left as they are.
 */
object PdfGrayscale {

    fun lum(r: Float, g: Float, b: Float) = (0.299f * r + 0.587f * g + 0.114f * b).coerceIn(0f, 1f)
    fun lumCmyk(c: Float, m: Float, y: Float, k: Float): Float = lum((1 - minOf(1f, c + k)), (1 - minOf(1f, m + k)), (1 - minOf(1f, y + k)))

    /** Converts [doc] (all pages, or only [pages]). Returns the number of colour operators converted. */
    fun convert(doc: PDDocument, quality: Int = 88, ctx: TransformCtx = TransformCtx({}, {}), pages: Set<Int>? = null): Pair<ImageStats, Int> {
        val stats = PdfImagePass.run(doc, ImageOptions(targetDpi = null, quality = quality, gray = true, onlyIfSmaller = false, reencodeLossless = false), TransformCtx(ctx.checkCancelled) { ctx.onProgress(it * 0.6f) }, pages)
        var ops = 0
        val forms = IdentityHashMap<COSStream, Boolean>()
        val total = doc.numberOfPages.coerceAtLeast(1)
        for ((i, page) in doc.pages.withIndex()) {
            if (pages != null && i !in pages) continue
            ctx.checkCancelled()
            val res = page.resources
            val bytes = page.contents?.use { it.readBytes() }
            if (bytes != null && bytes.isNotEmpty()) {
                val (out, n) = rewrite(bytes, res)
                if (n > 0) {
                    val s = PDStream(doc)
                    s.createOutputStream(COSName.FLATE_DECODE).use { it.write(out) }
                    page.setContents(s)
                    ops += n
                }
            }
            ops += convertForms(res, forms)
            ctx.onProgress(0.6f + 0.35f * (i + 1) / total)
        }
        return stats to ops
    }

    private fun convertForms(res: PDResources?, seen: IdentityHashMap<COSStream, Boolean>): Int {
        res ?: return 0
        var ops = 0
        for (name in res.xObjectNames) {
            val xo = runCatching { res.getXObject(name) }.getOrNull() as? PDFormXObject ?: continue
            if (seen.put(xo.cosObject, true) != null) continue
            val raw = xo.cosObject.createInputStream().use { it.readBytes() }
            val (out, n) = rewrite(raw, xo.resources ?: res)
            if (n > 0) { xo.cosObject.createOutputStream(COSName.FLATE_DECODE).use { it.write(out) }; ops += n }
            ops += convertForms(xo.resources, seen)
        }
        return ops
    }

    private class State(var fill: Int = 0, var stroke: Int = 0) // components converted for the current colour space (0 = leave alone)

    private fun componentsToConvert(name: COSName, res: PDResources?): Int = when (name.name) {
        "DeviceRGB", "CalRGB" -> 3
        "DeviceCMYK" -> 4
        "DeviceGray", "CalGray", "Pattern" -> 0
        else -> runCatching {
            val cs = res?.getColorSpace(name)
            when (cs?.name) { "ICCBased", "CalRGB" -> cs.numberOfComponents.takeIf { it == 3 || it == 4 } ?: 0; else -> 0 }
        }.getOrDefault(0)
    }

    private fun num(o: Any?): Float? = (o as? COSNumber)?.floatValue()
    private fun f(v: Float) = COSFloat(v)

    /** Rewrites colour operators in [content]; returns the new bytes and how many operators changed. */
    fun rewrite(content: ByteArray, res: PDResources?): Pair<ByteArray, Int> {
        val tokens = PDFStreamParser(content).apply { parse() }.tokens
        val out = ArrayList<Any>(tokens.size)
        val stack = ArrayList<State>()
        var st = State()
        var changed = 0
        val operands = ArrayList<Any>()
        fun passThrough(op: Operator) { out.addAll(operands); out.add(op); operands.clear() }
        for (t in tokens) {
            if (t !is Operator) { operands += t; continue }
            when (t.name) {
                "q" -> { stack += State(st.fill, st.stroke); passThrough(t) }
                "Q" -> { if (stack.isNotEmpty()) st = stack.removeAt(stack.size - 1); passThrough(t) }
                "rg", "RG" -> {
                    val v = operands.mapNotNull { num(it) }
                    if (v.size == 3) { out += f(lum(v[0], v[1], v[2])); out += Operator.getOperator(if (t.name == "rg") "g" else "G"); operands.clear(); changed++ } else passThrough(t)
                }
                "k", "K" -> {
                    val v = operands.mapNotNull { num(it) }
                    if (v.size == 4) { out += f(lumCmyk(v[0], v[1], v[2], v[3])); out += Operator.getOperator(if (t.name == "k") "g" else "G"); operands.clear(); changed++ } else passThrough(t)
                }
                "g", "G" -> { if (t.name == "g") st.fill = 0 else st.stroke = 0; passThrough(t) }
                "cs", "CS" -> {
                    val n = (operands.lastOrNull() as? COSName)?.let { componentsToConvert(it, res) } ?: 0
                    if (t.name == "cs") st.fill = n else st.stroke = n
                    if (n > 0) { out += COSName.getPDFName("DeviceGray"); out += t; operands.clear(); changed++ } else passThrough(t)
                }
                "sc", "scn", "SC", "SCN" -> {
                    val n = if (t.name[0] == 's') st.fill else st.stroke
                    val v = operands.mapNotNull { num(it) }
                    if (n > 0 && v.size == n && operands.size == n) {
                        val g = if (n == 3) lum(v[0], v[1], v[2]) else lumCmyk(v[0], v[1], v[2], v[3])
                        out += f(g); out += t; operands.clear(); changed++
                    } else passThrough(t)
                }
                else -> passThrough(t)
            }
        }
        out.addAll(operands)
        if (changed == 0) return content to 0
        val bos = ByteArrayOutputStream()
        ContentStreamWriter(bos).writeTokens(out)
        return bos.toByteArray() to changed
    }
}
