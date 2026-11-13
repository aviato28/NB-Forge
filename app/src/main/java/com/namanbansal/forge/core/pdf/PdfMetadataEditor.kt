package com.namanbansal.forge.core.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** The editable document properties. Dates are shown/typed as `yyyy-MM-dd HH:mm` (blank = none). */
data class PdfMeta(
    val title: String = "", val author: String = "", val subject: String = "", val keywords: String = "",
    val creator: String = "", val producer: String = "", val created: String = "", val modified: String = "",
)

/** Reads and writes the Info dictionary AND the XMP packet together, so viewers that prefer either one agree. */
object PdfMetadataEditor {

    fun read(doc: PDDocument): PdfMeta {
        val i = doc.documentInformation
        return PdfMeta(
            i.title.orEmpty(), i.author.orEmpty(), i.subject.orEmpty(), i.keywords.orEmpty(),
            i.creator.orEmpty(), i.producer.orEmpty(), formatDate(i.creationDate), formatDate(i.modificationDate),
        )
    }

    fun write(doc: PDDocument, m: PdfMeta) {
        val i = doc.documentInformation
        i.title = m.title.ifBlank { null }
        i.author = m.author.ifBlank { null }
        i.subject = m.subject.ifBlank { null }
        i.keywords = m.keywords.ifBlank { null }
        i.creator = m.creator.ifBlank { null }
        i.producer = m.producer.ifBlank { null }
        val created = parseDate(m.created)
        val modified = parseDate(m.modified)
        i.creationDate = created
        i.modificationDate = modified
        doc.documentInformation = i
        val md = PDMetadata(doc)
        md.importXMPMetadata(xmp(m, created, modified).toByteArray(Charsets.UTF_8))
        doc.documentCatalog.metadata = md
    }

    /** Clears Info and removes the XMP packet. */
    fun removeAll(doc: PDDocument) {
        doc.documentInformation = com.tom_roush.pdfbox.pdmodel.PDDocumentInformation()
        doc.documentCatalog.metadata = null
    }

    /** The XMP the document currently carries, as text (null if none). */
    fun readXmp(doc: PDDocument): String? =
        doc.documentCatalog.metadata?.let { runCatching { it.exportXMPMetadata().use { s -> String(s.readBytes(), Charsets.UTF_8) } }.getOrNull() }

    fun parseDate(text: String): Calendar? {
        val t = text.trim()
        if (t.isEmpty()) return null
        for (pattern in listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd")) {
            val f = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
            runCatching { f.parse(t) }.getOrNull()?.let { return Calendar.getInstance().apply { time = it } }
        }
        return null
    }

    fun isValidDate(text: String): Boolean = text.isBlank() || parseDate(text) != null

    fun formatDate(c: Calendar?): String = c?.let { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(it.time) }.orEmpty()

    private fun iso(c: Calendar?): String? = c?.let { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(it.time) }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun xmp(m: PdfMeta, created: Calendar?, modified: Calendar?): String = buildString {
        append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
        append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
        append("<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:pdf=\"http://ns.adobe.com/pdf/1.3/\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\">\n")
        if (m.title.isNotBlank()) append("<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">${esc(m.title)}</rdf:li></rdf:Alt></dc:title>\n")
        if (m.author.isNotBlank()) append("<dc:creator><rdf:Seq><rdf:li>${esc(m.author)}</rdf:li></rdf:Seq></dc:creator>\n")
        if (m.subject.isNotBlank()) append("<dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">${esc(m.subject)}</rdf:li></rdf:Alt></dc:description>\n")
        if (m.keywords.isNotBlank()) append("<pdf:Keywords>${esc(m.keywords)}</pdf:Keywords>\n")
        if (m.producer.isNotBlank()) append("<pdf:Producer>${esc(m.producer)}</pdf:Producer>\n")
        if (m.creator.isNotBlank()) append("<xmp:CreatorTool>${esc(m.creator)}</xmp:CreatorTool>\n")
        iso(created)?.let { append("<xmp:CreateDate>$it</xmp:CreateDate>\n") }
        iso(modified)?.let { append("<xmp:ModifyDate>$it</xmp:ModifyDate>\n") }
        append("</rdf:Description></rdf:RDF></x:xmpmeta>\n<?xpacket end=\"w\"?>")
    }
}
