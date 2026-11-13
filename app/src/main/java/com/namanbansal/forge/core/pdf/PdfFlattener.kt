package com.namanbansal.forge.core.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.util.Matrix

/** Bakes form fields and annotations into the page content so they can't be edited any more. Links are left clickable. */
object PdfFlattener {

    class Counts(val fields: Int, val annotations: Int)
    class Report(val fields: Int, val annotations: Int, val dropped: Int)

    private fun isFlattenable(a: PDAnnotation) = a !is PDAnnotationLink && a !is PDAnnotationWidget

    fun count(doc: PDDocument): Counts {
        val fields = doc.documentCatalog.acroForm?.fieldTree?.count { it is PDTerminalField } ?: 0
        var annots = 0
        for (p in doc.pages) annots += runCatching { p.annotations.count { isFlattenable(it) } }.getOrDefault(0)
        return Counts(fields, annots)
    }

    fun flatten(doc: PDDocument, forms: Boolean, annots: Boolean, ctx: TransformCtx = TransformCtx({}, {})): Report {
        var fieldCount = 0
        if (forms) {
            doc.documentCatalog.acroForm?.let { form ->
                fieldCount = form.fieldTree.count { it is PDTerminalField }
                if (form.needAppearances) runCatching { form.refreshAppearances() }
                form.flatten()
            }
        }
        var done = 0
        var dropped = 0
        if (annots) {
            val total = doc.numberOfPages
            var idx = 0
            for (page in doc.pages) {
                ctx.checkCancelled()
                val keep = ArrayList<PDAnnotation>()
                val bake = ArrayList<PDAnnotation>()
                for (a in page.annotations) if (isFlattenable(a)) bake += a else keep += a
                if (bake.isNotEmpty()) {
                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        for (a in bake) if (draw(cs, a)) done++ else dropped++
                    }
                    page.annotations = keep
                }
                idx++
                ctx.onProgress(idx.toFloat() / total * 0.9f)
            }
        }
        return Report(fieldCount, done, dropped)
    }

    /** Draws [a]'s normal appearance onto the page, mapped into the annotation rectangle (PDF 32000 section 12.5.5). */
    private fun draw(cs: PDPageContentStream, a: PDAnnotation): Boolean {
        if (a.isHidden || a.isNoView) return false
        val normal = a.appearance?.normalAppearance ?: return false
        val form = when {
            normal.isStream -> normal.appearanceStream
            else -> a.appearanceState?.let { normal.subDictionary[it] }
        } ?: return false
        val rect = a.rectangle ?: return false
        val bbox = form.bBox ?: return false
        val m = form.matrix
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        for ((x, y) in listOf(bbox.lowerLeftX to bbox.lowerLeftY, bbox.upperRightX to bbox.lowerLeftY, bbox.lowerLeftX to bbox.upperRightY, bbox.upperRightX to bbox.upperRightY)) {
            xs += m.getValue(0, 0) * x + m.getValue(1, 0) * y + m.getValue(2, 0)
            ys += m.getValue(0, 1) * x + m.getValue(1, 1) * y + m.getValue(2, 1)
        }
        val minX = xs.min(); val minY = ys.min()
        val w = xs.max() - minX; val h = ys.max() - minY
        if (w <= 0f || h <= 0f) return false
        val sx = rect.width / w; val sy = rect.height / h
        cs.saveGraphicsState()
        cs.transform(Matrix(sx, 0f, 0f, sy, rect.lowerLeftX - minX * sx, rect.lowerLeftY - minY * sy))
        cs.drawForm(form)
        cs.restoreGraphicsState()
        return true
    }
}
