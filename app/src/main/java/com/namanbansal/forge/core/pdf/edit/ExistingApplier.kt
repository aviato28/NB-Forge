package com.namanbansal.forge.core.pdf.edit

import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.pdf.PdfLoader
import com.namanbansal.forge.core.pdf.annot.PdfAnnotIo
import com.namanbansal.forge.core.pdf.edit.content.Aff
import com.namanbansal.forge.core.pdf.edit.content.Box
import com.namanbansal.forge.core.pdf.edit.content.ContentChanges
import com.namanbansal.forge.core.pdf.edit.content.ContentWalker
import com.namanbansal.forge.core.pdf.edit.content.FontOpener
import com.namanbansal.forge.core.pdf.edit.content.ImageOp
import com.namanbansal.forge.core.pdf.edit.content.TextBlock
import com.namanbansal.forge.core.pdf.edit.content.TextBlocks
import com.namanbansal.forge.core.pdf.edit.content.TextFonts
import com.namanbansal.forge.core.pdf.edit.content.TextLayout
import com.namanbansal.forge.core.pdf.edit.content.TextPlan
import com.namanbansal.forge.core.pdf.edit.content.TextPlanner
import com.namanbansal.forge.core.pdf.edit.content.FontChoice
import com.namanbansal.forge.core.pdf.edit.content.Layout
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/** What applying the changes did, for the save message. */
class ExistingReport {
    var texts = 0; var images = 0; var paths = 0
    val notices = LinkedHashSet<String>()
    val summary: String get() {
        val parts = ArrayList<String>()
        if (texts > 0) parts += "$texts text block${if (texts == 1) "" else "s"}"
        if (images > 0) parts += "$images picture${if (images == 1) "" else "s"}"
        if (paths > 0) parts += "$paths drawing${if (paths == 1) "" else "s"}"
        return if (parts.isEmpty()) "" else "Changed " + parts.joinToString(", ") + "."
    }
}

/**
 * Writes Edit existing changes into a PDF: per page it scans the content again, finds each object by its stable index, removes
 * the old characters / paths and re-places pictures with [ContentWalker.rewrite] (nothing else moves), then appends the new
 * text with the block's font, size, colour and alignment, re-wrapped to the block width with real font metrics.
 */
object ExistingApplier {
    fun applyInPlace(file: File, edits: List<ExistingEdit>, fonts: FontOpener, images: (String) -> ByteArray?): ExistingReport {
        val report = ExistingReport()
        if (edits.isEmpty()) return report
        val tmp = File(file.parentFile, file.name + ".existing.tmp")
        try {
            PDDocument.load(file, MemoryUsageSetting.setupMixed(PdfLoader.MIXED_MEMORY_BYTES)).use { doc ->
                apply(doc, edits, fonts, images, report)
                tmp.outputStream().use { doc.save(it) }
            }
            if (!tmp.renameTo(file)) file.writeBytes(tmp.readBytes())
            return report
        } catch (e: ToolException) { throw e
        } catch (e: OutOfMemoryError) { throw ToolException("This page is too large to edit safely on this phone. Nothing was changed.")
        } catch (e: IOException) { throw ToolException("Couldn't write your changes into this PDF: ${e.message ?: "unknown error"}.", e)
        } catch (e: RuntimeException) { throw ToolException("Couldn't change this PDF's own content safely (${e.message ?: e.javaClass.simpleName}). Nothing was changed.", e)
        } finally { tmp.delete() }
    }

    private class Pending(val block: TextBlock, val plan: TextPlan, val skew: Float)

    fun apply(doc: PDDocument, edits: List<ExistingEdit>, fonts: FontOpener, images: (String) -> ByteArray?, report: ExistingReport = ExistingReport()) {
        val notoCache = HashMap<String, PDType0Font>()
        val imageCache = HashMap<String, PDImageXObject>()
        for ((p, list) in edits.groupBy { it.page }) {
            if (p !in 0 until doc.numberOfPages) continue
            val page = doc.getPage(p)
            val geo = PdfAnnotIo.Geo(page)
            val cb = page.cropBox
            val scan = ContentWalker.scan(doc, page)
            val blocks = TextBlocks.detect(scan, Box(cb.lowerLeftX, cb.lowerLeftY, cb.upperRightX, cb.upperRightY))
            val seen = TextFonts.seen(scan)
            val removeGlyphs = HashSet<Int>(); val removePaths = HashSet<Int>(); val ops = HashMap<Int, ImageOp>()
            val pending = ArrayList<Pending>()
            for (e in list) when (e.kind) {
                ExistingKind.TEXT -> {
                    val b = blocks.firstOrNull { e.key in it.glyphIds } ?: throw ToolException("A paragraph you edited on page ${p + 1} is no longer in this PDF. Nothing was saved.")
                    if (e.origText.isNotBlank() && norm(b.text) != norm(e.origText)) throw ToolException("The text on page ${p + 1} is not what it was when you edited it. Nothing was saved.")
                    removeGlyphs += b.glyphIds
                    report.texts++
                    if (!e.delete && e.text.isNotBlank()) {
                        val bold = e.bold.takeIf { it >= 0 }?.let { it == 1 }; val italic = e.italic.takeIf { it >= 0 }?.let { it == 1 }
                        val plan = TextPlanner.plan(doc, fonts, b, seen, e.text, e.sizePt, e.align, bold, italic, if (e.hasColor) e.color else null, notoCache)
                        plan.notices.forEach { report.notices += it }
                        pending += Pending(b, plan, scan.skew)
                    }
                }
                ExistingKind.PATH -> { if (e.key !in scan.paths.indices) throw ToolException("A drawing you removed on page ${p + 1} is no longer in this PDF. Nothing was saved."); removePaths += e.key; report.paths++ }
                ExistingKind.IMAGE -> {
                    val ref = scan.images.getOrNull(e.key) ?: throw ToolException("A picture you changed on page ${p + 1} is no longer in this PDF. Nothing was saved.")
                    report.images++
                    if (e.delete) { ops[e.key] = ImageOp(delete = true); continue }
                    val dst = e.newRect?.let { userBox(geo, it) } ?: ref.box
                    val k = ((e.turns % 4) + 4) % 4
                    if (e.replaceImageId.isNotEmpty()) {
                        val bytes = images(e.replaceImageId) ?: throw ToolException("The replacement picture is no longer available. Remove the change and pick it again.")
                        val img = imageCache.getOrPut(e.replaceImageId) { PDImageXObject.createFromByteArray(doc, bytes, e.replaceImageId) }
                        val target = fitted(dst, img.width.toFloat() / max(1, img.height), k)
                        ops[e.key] = ImageOp(target = { target }, replacement = { res: PDResources -> res.add(img) })
                    } else {
                        val t = transform(ref.box, dst, k)
                        ops[e.key] = ImageOp(target = { g -> g.then(t) })
                    }
                }
            }
            val changes = ContentChanges(removeGlyphs, removePaths, ops)
            if (!ContentWalker.rewrite(doc, page, changes) && !changes.isEmpty) throw ToolException("Couldn't find what you changed on page ${p + 1}. Nothing was saved.")
            if (pending.isNotEmpty()) writeTexts(doc, page, pending)
        }
    }

    private fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim()

    private fun userBox(geo: PdfAnnotIo.Geo, r: com.namanbansal.forge.core.pdf.NormRect): Box {
        val a = geo.user(r.left, r.top); val b = geo.user(r.right, r.bottom)
        return Box(min(a.first, b.first), min(a.second, b.second), max(a.first, b.first), max(a.second, b.second))
    }

    /** Moves / scales the picture's bounding box [from] onto [to], after [turns] quarter turns clockwise about its centre. */
    internal fun transform(from: Box, to: Box, turns: Int): Aff {
        val odd = turns % 2 == 1
        val wr = if (odd) from.h else from.w; val hr = if (odd) from.w else from.h
        val sx = if (wr > 1e-6f) to.w / wr else 1f; val sy = if (hr > 1e-6f) to.h / hr else 1f
        return Aff.move(-from.cx, -from.cy).then(Aff.rotate(-turns * Math.PI.toFloat() / 2f)).then(Aff.scale(sx, sy)).then(Aff.move(to.cx, to.cy))
    }

    /** Unit square -> a picture of aspect [aspect] (w / h) fitted inside [to], turned [turns] quarter turns clockwise. */
    internal fun fitted(to: Box, aspect: Float, turns: Int): Aff {
        val odd = turns % 2 == 1
        // rotated size is (h, w) when odd: pick w so that both fit
        val w = if (odd) min(to.h, to.w * aspect) else min(to.w, to.h * aspect)
        val h = w / aspect
        return Aff.scale(w, h).then(Aff.move(-w / 2, -h / 2)).then(Aff.rotate(-turns * Math.PI.toFloat() / 2f)).then(Aff.move(to.cx, to.cy))
    }

    private fun writeTexts(doc: PDDocument, page: PDPage, items: List<Pending>) {
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            val res = page.resources ?: PDResources().also { page.resources = it }
            val tokens = ArrayList<Any>()
            fun op(n: String) = Operator.getOperator(n)
            /** One run of text: font, size, fill colour, and a text matrix that puts ([x], [y]) of the working frame on the page, turned by [skew]. */
            fun emit(choice: FontChoice, size: Float, c: Int, x: Float, y: Float, skew: Float, tj: List<COSBase>) {
                val name = res.add(choice.font)
                val r = ((c shr 16) and 0xFF) / 255f; val g = ((c shr 8) and 0xFF) / 255f; val b = (c and 0xFF) / 255f
                tokens += op("q"); tokens += op("BT")
                tokens += name; tokens += COSFloat(size); tokens += op("Tf")
                tokens += COSFloat(r); tokens += COSFloat(g); tokens += COSFloat(b); tokens += op("rg")
                if (choice.syntheticBold) {
                    tokens += COSInteger.get(2); tokens += op("Tr")
                    tokens += COSFloat(r); tokens += COSFloat(g); tokens += COSFloat(b); tokens += op("RG")
                    tokens += COSFloat(size * 0.03f); tokens += op("w")
                }
                val shear = if (choice.syntheticItalic) 0.2f else 0f
                val m = if (skew == 0f) Aff(1f, 0f, shear, 1f, x, y)
                else Aff(1f, 0f, shear, 1f, 0f, 0f).then(Aff.rotate(skew)).let { t -> val px = Aff.rotate(skew).x(x, y); val py = Aff.rotate(skew).y(x, y); Aff(t.a, t.b, t.c, t.d, px, py) }
                listOf(m.a, m.b, m.c, m.d, m.e, m.f).forEach { tokens += COSFloat(it) }; tokens += op("Tm")
                tokens.add(COSArray().also { a -> tj.forEach { a.add(it) } }); tokens += op("TJ")
                tokens += op("ET"); tokens += op("Q")
            }
            for (p in items) {
                val plan = p.plan
                for (line in p.plan.layout.lines) {
                    if (line.text.isEmpty()) continue
                    val segs = line.segs
                    if (plan.styled && segs != null) {
                        for (sg in segs) {
                            val ch = plan.choices[sg.style]
                            emit(ch, plan.size, plan.colors[sg.style], sg.x, line.baseline, p.skew, ch.coder.wordItems(sg.text) ?: ch.coder.wordItems(sg.text.map { if (ch.coder.canEncode(it.toString())) it else '?' }.joinToString("")).orEmpty())
                        }
                    } else {
                        val ch = plan.choices[0]
                        emit(ch, plan.size, plan.colors[0], line.x, line.baseline, p.skew, TextLayout.items(line, ch.coder, plan.size))
                    }
                }
            }
            val bytes = ByteArrayOutputStream().also { ContentStreamWriter(it).writeTokens(tokens) }.toByteArray()
            cs.appendRawCommands(bytes)
        }
    }
}
