package com.namanbansal.forge.core.pdf

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment
import java.io.ByteArrayOutputStream
import java.util.IdentityHashMap

/** What the Sanitise tool can strip. */
enum class SanitizeKind(val label: String, val explain: String) {
    JAVASCRIPT("JavaScript", "Scripts that run when the PDF opens, or when a button or field is used."),
    EMBEDDED_FILES("Embedded files", "Other files attached inside the PDF."),
    METADATA("Metadata", "Title, author, dates and the hidden XMP packet."),
    HIDDEN_LAYERS("Hidden layers", "Content set to be invisible, which is still stored in the file."),
    THUMBNAILS("Page thumbnails", "Small preview pictures stored for each page."),
    APP_DATA("Private app data", "Extra data other programs left behind (PieceInfo)."),
}

/** Finds and removes things people don't expect to travel with a PDF. */
object PdfSanitizer {

    private val JS = COSName.getPDFName("JavaScript")
    private val PIECE = COSName.getPDFName("PieceInfo")
    private val AA = COSName.getPDFName("AA")

    private fun isJs(a: COSBase?): Boolean {
        var d = a as? COSDictionary ?: return false
        var guard = 0
        while (guard++ < 20) {
            if (d.getNameAsString(COSName.S) == "JavaScript") return true
            d = d.getDictionaryObject(COSName.NEXT) as? COSDictionary ?: return false
        }
        return false
    }

    /** Removes JS actions from [holder]'s /A and /AA; returns how many were removed. */
    private fun stripActions(holder: COSDictionary): Int {
        var n = 0
        if (isJs(holder.getDictionaryObject(COSName.A))) { holder.removeItem(COSName.A); n++ }
        (holder.getDictionaryObject(AA) as? COSDictionary)?.let { aa ->
            for (key in aa.keySet().toList()) if (isJs(aa.getDictionaryObject(key))) { aa.removeItem(key); n++ }
            if (aa.size() == 0) holder.removeItem(AA)
        }
        return n
    }

    private fun countActions(holder: COSDictionary): Int {
        var n = if (isJs(holder.getDictionaryObject(COSName.A))) 1 else 0
        (holder.getDictionaryObject(AA) as? COSDictionary)?.let { aa -> for (key in aa.keySet()) if (isJs(aa.getDictionaryObject(key))) n++ }
        return n
    }

    private fun jsNames(doc: PDDocument): Int = runCatching { doc.documentCatalog.names?.javaScript?.names?.size ?: 0 }.getOrDefault(0)

    private fun embeddedCount(doc: PDDocument): Int {
        var n = runCatching {
            fun count(node: com.tom_roush.pdfbox.pdmodel.common.PDNameTreeNode<*>): Int = (node.names?.size ?: 0) + (node.kids?.sumOf { count(it) } ?: 0)
            doc.documentCatalog.names?.embeddedFiles?.let { count(it) } ?: 0
        }.getOrDefault(0)
        for (p in doc.pages) n += runCatching { p.annotations.count { it is PDAnnotationFileAttachment } }.getOrDefault(0)
        return n
    }

    private fun hiddenGroups(doc: PDDocument): Set<COSDictionary> {
        val out = java.util.Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
        runCatching {
            val props = doc.documentCatalog.ocProperties ?: return@runCatching
            for (g in props.optionalContentGroups) if (!props.isGroupEnabled(g)) out += g.cosObject
        }
        return out
    }

    fun scan(doc: PDDocument): Map<SanitizeKind, Int> {
        val out = LinkedHashMap<SanitizeKind, Int>()
        val cat = doc.documentCatalog
        var js = jsNames(doc) + countActions(cat.cosObject)
        if (isJs(cat.cosObject.getDictionaryObject(COSName.getPDFName("OpenAction")))) js++
        for (p in doc.pages) {
            js += countActions(p.cosObject)
            runCatching { p.annotations.forEach { js += countActions(it.cosObject) } }
        }
        runCatching { cat.acroForm?.fieldTree?.forEach { js += countActions(it.cosObject) } }
        out[SanitizeKind.JAVASCRIPT] = js
        out[SanitizeKind.EMBEDDED_FILES] = embeddedCount(doc)
        val info = doc.documentInformation
        val infoFields = listOf(info.title, info.author, info.subject, info.keywords, info.creator, info.producer).count { !it.isNullOrBlank() } +
            (if (info.creationDate != null) 1 else 0) + (if (info.modificationDate != null) 1 else 0)
        out[SanitizeKind.METADATA] = infoFields + if (cat.metadata != null) 1 else 0
        out[SanitizeKind.HIDDEN_LAYERS] = hiddenGroups(doc).size
        out[SanitizeKind.THUMBNAILS] = doc.pages.count { it.cosObject.containsKey(COSName.getPDFName("Thumb")) }
        out[SanitizeKind.APP_DATA] = (if (cat.cosObject.containsKey(PIECE)) 1 else 0) + doc.pages.count { it.cosObject.containsKey(PIECE) }
        return out
    }

    /** Removes the chosen kinds; returns what was removed per kind. */
    fun clean(doc: PDDocument, kinds: Set<SanitizeKind>, ctx: TransformCtx = TransformCtx({}, {})): Map<SanitizeKind, Int> {
        val removed = LinkedHashMap<SanitizeKind, Int>()
        val cat = doc.documentCatalog
        if (SanitizeKind.JAVASCRIPT in kinds) {
            var n = jsNames(doc)
            runCatching { doc.documentCatalog.names?.cosObject?.removeItem(JS) }
            n += stripActions(cat.cosObject)
            val oa = COSName.getPDFName("OpenAction")
            if (isJs(cat.cosObject.getDictionaryObject(oa))) { cat.cosObject.removeItem(oa); n++ }
            for (p in doc.pages) {
                ctx.checkCancelled()
                n += stripActions(p.cosObject)
                runCatching { p.annotations.forEach { n += stripActions(it.cosObject) } }
            }
            runCatching { cat.acroForm?.fieldTree?.forEach { n += stripActions(it.cosObject) } }
            removed[SanitizeKind.JAVASCRIPT] = n
        }
        if (SanitizeKind.EMBEDDED_FILES in kinds) {
            val n = embeddedCount(doc)
            runCatching { doc.documentCatalog.names?.cosObject?.removeItem(COSName.getPDFName("EmbeddedFiles")) }
            cat.cosObject.removeItem(COSName.getPDFName("AF"))
            for (p in doc.pages) runCatching { p.annotations = p.annotations.filterNot { it is PDAnnotationFileAttachment }.toMutableList() }
            removed[SanitizeKind.EMBEDDED_FILES] = n
        }
        if (SanitizeKind.HIDDEN_LAYERS in kinds) {
            val hidden = hiddenGroups(doc)
            if (hidden.isNotEmpty()) {
                val seen = IdentityHashMap<COSStream, Boolean>()
                for (p in doc.pages) {
                    ctx.checkCancelled()
                    val res = p.resources
                    val bytes = p.contents?.use { it.readBytes() }
                    if (bytes != null && bytes.isNotEmpty()) {
                        val out = dropHidden(bytes, res, hidden)
                        if (out != null) { val s = PDStream(doc); s.createOutputStream(COSName.FLATE_DECODE).use { it.write(out) }; p.setContents(s) }
                    }
                    dropHiddenInForms(res, hidden, seen)
                    runCatching { p.annotations = p.annotations.filterNot { hidden.contains(it.cosObject.getDictionaryObject(COSName.OC)) }.toMutableList() }
                }
                // The layers are gone from the pages; drop them from the layer list too.
                cat.cosObject.removeItem(COSName.getPDFName("OCProperties"))
            }
            removed[SanitizeKind.HIDDEN_LAYERS] = hidden.size
        }
        if (SanitizeKind.THUMBNAILS in kinds) {
            var n = 0
            for (p in doc.pages) if (p.cosObject.containsKey(COSName.getPDFName("Thumb"))) { p.cosObject.removeItem(COSName.getPDFName("Thumb")); n++ }
            removed[SanitizeKind.THUMBNAILS] = n
        }
        if (SanitizeKind.APP_DATA in kinds) {
            var n = 0
            if (cat.cosObject.containsKey(PIECE)) { cat.cosObject.removeItem(PIECE); n++ }
            for (p in doc.pages) if (p.cosObject.containsKey(PIECE)) { p.cosObject.removeItem(PIECE); n++ }
            removed[SanitizeKind.APP_DATA] = n
        }
        if (SanitizeKind.METADATA in kinds) {
            removed[SanitizeKind.METADATA] = scan(doc)[SanitizeKind.METADATA] ?: 0
            PdfMetadataEditor.removeAll(doc)
        }
        return removed
    }

    private fun dropHiddenInForms(res: PDResources?, hidden: Set<COSDictionary>, seen: IdentityHashMap<COSStream, Boolean>) {
        res ?: return
        for (name in res.xObjectNames.toList()) {
            val xo = runCatching { res.getXObject(name) }.getOrNull() as? PDFormXObject ?: continue
            if (seen.put(xo.cosObject, true) != null) continue
            val raw = xo.cosObject.createInputStream().use { it.readBytes() }
            val out = dropHidden(raw, xo.resources ?: res, hidden)
            if (out != null) xo.cosObject.createOutputStream(COSName.FLATE_DECODE).use { it.write(out) }
            dropHiddenInForms(xo.resources, hidden, seen)
        }
    }

    /** Removes `/OC /name BDC ... EMC` blocks of hidden layers and `Do`s of hidden XObjects. Returns new bytes, or null if nothing changed. */
    fun dropHidden(content: ByteArray, res: PDResources?, hidden: Set<COSDictionary>): ByteArray? {
        val tokens = PDFStreamParser(content).apply { parse() }.tokens
        val props = res?.cosObject?.getDictionaryObject(COSName.PROPERTIES) as? COSDictionary
        val xobjs = res?.cosObject?.getDictionaryObject(COSName.XOBJECT) as? COSDictionary
        val out = ArrayList<Any>(tokens.size)
        val operands = ArrayList<Any>()
        var skipDepth = 0
        var changed = false
        var depth = 0
        for (t in tokens) {
            if (t !is Operator) { operands += t; continue }
            when (t.name) {
                "BDC", "BMC" -> {
                    depth++
                    val hide = skipDepth == 0 && t.name == "BDC" && operands.size == 2 && operands[0] == COSName.OC &&
                        (operands[1] as? COSName)?.let { n -> hidden.contains(props?.getDictionaryObject(n)) } == true
                    if (skipDepth > 0) skipDepth++ else if (hide) { skipDepth = 1; changed = true } else { out.addAll(operands); out.add(t) }
                    operands.clear()
                }
                "EMC" -> {
                    depth--
                    if (skipDepth > 0) skipDepth-- else { out.addAll(operands); out.add(t) }
                    operands.clear()
                }
                "Do" -> {
                    val hide = (operands.firstOrNull() as? COSName)?.let { n -> (xobjs?.getDictionaryObject(n) as? COSDictionary)?.getDictionaryObject(COSName.OC)?.let { hidden.contains(it) } } == true
                    if (skipDepth == 0 && !hide) { out.addAll(operands); out.add(t) } else if (hide) changed = true
                    operands.clear()
                }
                else -> { if (skipDepth == 0) { out.addAll(operands); out.add(t) }; operands.clear() }
            }
        }
        if (!changed) return null
        val bos = ByteArrayOutputStream()
        ContentStreamWriter(bos).writeTokens(out)
        return bos.toByteArray()
    }
}
