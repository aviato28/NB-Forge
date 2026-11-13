package com.namanbansal.forge.core.pdf

import com.namanbansal.forge.core.ToolException
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File

/**
 * Repair = load leniently (PDFBox rebuilds a missing or broken cross-reference table by scanning the file for objects) and
 * write the document back out, which produces a fresh, consistent file. Pages whose content can't be read are dropped and reported.
 */
object PdfRepair {

    class Diagnosis(val missingEof: Boolean, val missingStartXref: Boolean)

    /** What is visibly wrong with the raw bytes (cheap tail check; the file can still be loadable). */
    fun diagnose(file: File): Diagnosis {
        val len = file.length()
        val tailLen = minOf(len, 2048L).toInt()
        val buf = ByteArray(tailLen)
        file.inputStream().use { it.skip(len - tailLen); var n = 0; while (n < tailLen) { val r = it.read(buf, n, tailLen - n); if (r < 0) break; n += r } }
        val tail = String(buf, Charsets.ISO_8859_1)
        return Diagnosis(!tail.contains("%%EOF"), !tail.contains("startxref"))
    }

    /** Loads [doc]'s file leniently; throws [ToolException] when nothing usable is left. */
    fun open(file: File): PDDocument {
        val doc = try { PdfOps.load(file) } catch (t: Throwable) {
            throw ToolException("This PDF is too damaged to repair: no pages could be recovered.", t)
        }
        if (doc.numberOfPages <= 0) { doc.close(); throw ToolException("This PDF is too damaged to repair: no pages could be recovered.") }
        return doc
    }

    /** Fixes [doc] in place (drops unreadable pages) and returns the human report. */
    fun fix(doc: PDDocument, file: File, ctx: TransformCtx = TransformCtx({}, {})): String {
        val diag = diagnose(file)
        val fixes = ArrayList<String>()
        if (diag.missingEof || diag.missingStartXref) fixes += "rebuilt the cross-reference table (the file was cut short or its index was damaged)"
        val total = doc.numberOfPages
        var removed = 0
        for (i in total - 1 downTo 0) {
            ctx.checkCancelled()
            val ok = runCatching {
                val p = doc.getPage(i)
                p.mediaBox
                p.contents?.use { it.readBytes() }
            }.isSuccess
            if (!ok) { doc.removePage(i); removed++ }
            ctx.onProgress((total - i).toFloat() / total * 0.9f)
        }
        if (removed > 0) fixes += "removed $removed unreadable ${if (removed == 1) "page" else "pages"}"
        if (doc.numberOfPages <= 0) throw ToolException("This PDF is too damaged to repair: none of its pages could be read.")
        fixes += "rewrote the file with a clean structure"
        return "Recovered ${doc.numberOfPages} of $total pages; " + fixes.joinToString("; ")
    }
}
