package com.namanbansal.forge.core

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceEntry
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDListBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import java.io.File

/** AcroForm fixtures generated in test code: one field of every kind, on A4, optionally rotated, encrypted, or XFA-only. */
object FormFixtures {
    // widget boxes (user space), also used by tests to know where things are
    val NAME = PDRectangle(60f, 700f, 200f, 24f)
    val NOTES = PDRectangle(60f, 600f, 240f, 70f)
    val AGREE = PDRectangle(60f, 560f, 16f, 16f)
    val RED = PDRectangle(60f, 520f, 16f, 16f)
    val BLUE = PDRectangle(120f, 520f, 16f, 16f)
    val COLOR = PDRectangle(60f, 470f, 140f, 22f)
    val LIST = PDRectangle(60f, 380f, 140f, 60f)
    val SIGN = PDRectangle(60f, 280f, 220f, 60f)
    val LOCKED = PDRectangle(300f, 700f, 150f, 24f)
    val CODE = PDRectangle(300f, 650f, 100f, 24f)

    fun acroForm(file: File, rotation: Int = 0, xfaOnly: Boolean = false, userPassword: String? = null, permissions: com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission? = null): File {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            page.rotation = rotation
            doc.addPage(page)
            val form = PDAcroForm(doc)
            doc.documentCatalog.acroForm = form
            form.defaultResources = PDResources().also { it.put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
            form.defaultAppearance = "/Helv 0 Tf 0 g"
            if (xfaOnly) {
                form.cosObject.setItem(COSName.getPDFName("XFA"), doc.document.createCOSStream())
            } else {
                text(doc, form, page, "person.name", NAME) { it.defaultAppearance = "/Helv 12 Tf 0 g"; it.isRequired = true }
                text(doc, form, page, "notes", NOTES) { it.defaultAppearance = "/Helv 10 Tf 0 g"; it.isMultiline = true }
                text(doc, form, page, "locked", LOCKED) { it.defaultAppearance = "/Helv 12 Tf 0 g"; it.isReadOnly = true; it.setValue("fixed") }
                text(doc, form, page, "code", CODE) { it.defaultAppearance = "/Helv 12 Tf 0 g"; it.maxLen = 4 }
                val box = PDCheckBox(form); box.partialName = "agree"
                widget(page, AGREE, onNames = listOf("Yes")).also { box.widgets = listOf(it); form.fields.add(box) }
                val radio = PDRadioButton(form); radio.partialName = "colour"
                val w1 = widget(page, RED, listOf("Red")); val w2 = widget(page, BLUE, listOf("Blue"))
                radio.widgets = listOf(w1, w2); form.fields.add(radio)
                val combo = PDComboBox(form); combo.partialName = "fruit"; combo.options = listOf("Apple", "Banana", "Cherry"); combo.defaultAppearance = "/Helv 12 Tf 0 g"
                combo.widgets = listOf(widget(page, COLOR)); form.fields.add(combo)
                val list = PDListBox(form); list.partialName = "pets"; list.options = listOf("Cat", "Dog", "Fish"); list.defaultAppearance = "/Helv 12 Tf 0 g"
                list.widgets = listOf(widget(page, LIST)); form.fields.add(list)
                val sig = PDSignatureField(form); sig.partialName = "signature"
                sig.widgets = listOf(widget(page, SIGN)); form.fields.add(sig)
            }
            if (userPassword != null || permissions != null) {
                val policy = com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner-x", userPassword.orEmpty(), permissions ?: com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission())
                policy.encryptionKeyLength = 128
                doc.protect(policy)
            }
            doc.save(file)
        }
        return file
    }

    private fun text(doc: PDDocument, form: PDAcroForm, page: PDPage, name: String, r: PDRectangle, setup: (PDTextField) -> Unit) {
        val f = PDTextField(form)
        f.partialName = name.substringAfterLast('.')
        setup(f)
        f.widgets = listOf(widget(page, r))
        if ('.' in name) {
            // a nested field: parent "person" holds "name"
            val parent = com.tom_roush.pdfbox.pdmodel.interactive.form.PDNonTerminalField(form)
            parent.partialName = name.substringBefore('.')
            parent.children = listOf(f)
            form.fields.add(parent)
        } else form.fields.add(f)
    }

    private fun widget(page: PDPage, r: PDRectangle, onNames: List<String> = emptyList()): PDAnnotationWidget {
        val w = PDAnnotationWidget()
        w.rectangle = r
        w.page = page
        w.isPrinted = true
        if (onNames.isNotEmpty()) {
            val n = COSDictionary()
            val doc = PDDocument()
            for (name in onNames + "Off") n.setItem(COSName.getPDFName(name), PDAppearanceStream(doc).cosObject)
            val ap = PDAppearanceDictionary()
            ap.normalAppearance = PDAppearanceEntry(n)
            w.appearance = ap
        }
        page.annotations = page.annotations + w
        return w
    }
}
