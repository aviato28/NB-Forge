package com.namanbansal.forge.core

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
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

    fun corrupt(file: File): File {
        file.writeBytes(ByteArray(2048) { (it * 31 + 7).toByte() })
        return file
    }

    fun pageRotations(file: File): List<Int> = PDDocument.load(file).use { d -> d.pages.map { it.rotation } }
}
