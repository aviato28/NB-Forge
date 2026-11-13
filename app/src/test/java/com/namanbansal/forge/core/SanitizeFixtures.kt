package com.namanbansal.forge.core

import com.namanbansal.forge.core.pdf.PdfMeta
import com.namanbansal.forge.core.pdf.PdfMetadataEditor
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import com.tom_roush.pdfbox.pdmodel.PDJavascriptNameTreeNode
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup
import com.tom_roush.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentProperties
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionJavaScript
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import java.io.File

object SanitizeFixtures {
    /** A PDF carrying every kind of baggage the sanitiser looks for. */
    fun dirty(f: File): File {
        PDDocument().use { doc ->
            val page = com.tom_roush.pdfbox.pdmodel.PDPage(PDRectangle.A4); doc.addPage(page)
            val ocg = PDOptionalContentGroup("Secret")
            val props = PDOptionalContentProperties(); props.addGroup(ocg); props.setGroupEnabled(ocg, false)
            doc.documentCatalog.ocProperties = props
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 14f); cs.newLineAtOffset(50f, 700f); cs.showText("PUBLIC TEXT"); cs.endText()
                cs.beginMarkedContent(COSName.OC, ocg)
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 14f); cs.newLineAtOffset(50f, 650f); cs.showText("SECRET TEXT"); cs.endText()
                cs.endMarkedContent()
            }
            doc.documentCatalog.openAction = PDActionJavaScript("app.alert('open')")
            val names = PDDocumentNameDictionary(doc.documentCatalog)
            val jsTree = PDJavascriptNameTreeNode(); jsTree.names = mapOf("init" to PDActionJavaScript("app.alert('names')"))
            names.setJavascript(jsTree)
            val spec = PDComplexFileSpecification(); spec.file = "note.txt"
            spec.embeddedFile = PDEmbeddedFile(doc, "hello".byteInputStream())
            val ef = PDEmbeddedFilesNameTreeNode(); ef.names = mapOf("note.txt" to spec)
            names.setEmbeddedFiles(ef)
            doc.documentCatalog.names = names
            val link = PDAnnotationLink(); link.rectangle = PDRectangle(50f, 50f, 100f, 20f); link.action = PDActionJavaScript("app.alert('link')")
            page.annotations = mutableListOf(link)
            page.cosObject.setItem(COSName.getPDFName("Thumb"), doc.document.createCOSStream())
            page.cosObject.setItem(COSName.getPDFName("PieceInfo"), com.tom_roush.pdfbox.cos.COSDictionary())
            doc.documentInformation.title = "Secret plan"; doc.documentInformation.author = "Ana"
            PdfMetadataEditor.write(doc, PdfMeta(title = "Secret plan", author = "Ana"))
            doc.save(f)
        }
        return f
    }
}
