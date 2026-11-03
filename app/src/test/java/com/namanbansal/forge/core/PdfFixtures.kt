package com.namanbansal.forge.core

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.io.File

/** Generates fixture PDFs in test code (no binaries committed). Requires PDFBoxResourceLoader.init first. */
object PdfFixtures {
    fun create(file: File, pages: Int = 1, sizes: List<PDRectangle> = listOf(PDRectangle.A4), rotations: Map<Int, Int> = emptyMap(), userPassword: String? = null): File {
        PDDocument().use { doc ->
            repeat(pages) { i ->
                val page = PDPage(sizes[i % sizes.size])
                rotations[i]?.let { page.rotation = it }
                doc.addPage(page)
            }
            if (userPassword != null) {
                val policy = StandardProtectionPolicy("owner-$userPassword", userPassword, AccessPermission())
                policy.encryptionKeyLength = 128
                doc.protect(policy)
            }
            doc.save(file)
        }
        return file
    }

    /**
     * A readable document: page i (1-based) has a big "Page i" heading, a title line and [lines] of body text.
     * Page heights differ per page (300 + i) so tests can identify a page by its media box after reordering.
     */
    fun withText(
        file: File,
        pages: Int,
        title: String = "Forge fixture",
        body: (Int) -> List<String> = { listOf("Quarterly report line one", "Totals and figures", "Signed and approved") },
        userPassword: String? = null,
        uniqueHeights: Boolean = true,
    ): File {
        PDDocument().use { doc ->
            for (i in 1..pages) {
                val height = if (uniqueHeights) 300f + i else PDRectangle.A4.height
                val page = PDPage(PDRectangle(PDRectangle.A4.width, height))
                doc.addPage(page)
                val top = height - 70f // text sits near the top of the page whatever its height
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA_BOLD, 28f); cs.newLineAtOffset(48f, top)
                    cs.showText("Page $i"); cs.endText()
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 12f); cs.newLineAtOffset(48f, top - 24f); cs.showText(title); cs.endText()
                    var y = top - 50f
                    for (line in body(i)) {
                        cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 11f); cs.newLineAtOffset(48f, y); cs.showText(line); cs.endText()
                        y -= 18f
                    }
                    cs.setNonStrokingColor(0.85f, 0.85f, 0.88f)
                    cs.addRect(48f, 40f, 200f, 8f); cs.fill()
                }
            }
            if (userPassword != null) {
                val policy = StandardProtectionPolicy("owner-$userPassword", userPassword, AccessPermission())
                policy.encryptionKeyLength = 128
                doc.protect(policy)
            }
            doc.save(file)
        }
        return file
    }

    /** Media-box heights per page, in order (pairs with [withText]'s unique heights). */
    fun pageHeights(file: File, password: String = ""): List<Int> =
        PDDocument.load(file, password).use { d -> d.pages.map { it.mediaBox.height.toInt() } }

    fun corrupt(file: File): File {
        file.writeBytes(ByteArray(2048) { (it * 31 + 7).toByte() })
        return file
    }

    fun pageRotations(file: File): List<Int> = PDDocument.load(file).use { d -> d.pages.map { it.rotation } }
}
