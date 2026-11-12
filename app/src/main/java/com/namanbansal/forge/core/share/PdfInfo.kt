package com.namanbansal.forge.core.share

import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File

/** What the file-info sheet shows about the PDF itself. Empty strings = not set in the file. */
data class PdfInfo(
    val pages: Int,
    val title: String,
    val author: String,
    val producer: String,
    val creator: String,
    val version: String,
    val encrypted: Boolean,
) {
    companion object {
        /** Reads the document info of [file] (a plain working copy). [wasEncrypted]: the original needed a password. Never throws. */
        fun read(file: File, wasEncrypted: Boolean): PdfInfo = try {
            PDDocument.load(file).use { d ->
                val i = d.documentInformation
                PdfInfo(
                    pages = d.numberOfPages,
                    title = i?.title.orEmpty().trim(), author = i?.author.orEmpty().trim(),
                    producer = i?.producer.orEmpty().trim(), creator = i?.creator.orEmpty().trim(),
                    version = "%.1f".format(java.util.Locale.US, d.version),
                    encrypted = wasEncrypted || d.isEncrypted,
                )
            }
        } catch (_: Exception) {
            PdfInfo(0, "", "", "", "", "", wasEncrypted)
        }
    }
}

/** One labelled row of the info sheet. */
data class InfoRow(val label: String, val value: String)

/** Lays the facts out as rows, skipping what is unknown. */
object FileInfoRows {
    fun build(name: String, sizeBytes: Long, origin: FileOrigin, savedWhere: String, info: PdfInfo?): List<InfoRow> = buildList {
        add(InfoRow("Name", name))
        if (sizeBytes >= 0) add(InfoRow("Size", com.namanbansal.forge.core.formatBytes(sizeBytes)))
        if (info != null && info.pages > 0) add(InfoRow("Pages", info.pages.toString()))
        add(InfoRow("Came from", if (origin.temporary) origin.from else origin.where))
        add(InfoRow("Saved", savedWhere))
        if (info != null) {
            if (info.title.isNotEmpty()) add(InfoRow("Title", info.title))
            if (info.author.isNotEmpty()) add(InfoRow("Author", info.author))
            val maker = info.producer.ifEmpty { info.creator }
            if (maker.isNotEmpty()) add(InfoRow("Made with", maker))
            if (info.version.isNotEmpty()) add(InfoRow("PDF version", info.version))
            add(InfoRow("Password", if (info.encrypted) "Yes, protected" else "No"))
        }
    }

    /** "Saved" row text for an origin. */
    fun savedWhere(origin: FileOrigin): String = if (origin.temporary) "Not saved on your phone" else origin.where
}
