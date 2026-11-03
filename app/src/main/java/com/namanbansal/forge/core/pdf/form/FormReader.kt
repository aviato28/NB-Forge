package com.namanbansal.forge.core.pdf.form

import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.PdfLoader
import com.namanbansal.forge.core.pdf.annot.PdfAnnotIo
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDListBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import java.io.File

/** Finds the AcroForm fields of a PDF (every widget with its page and box) and tells whether the form is XFA-only. */
object FormReader {
    fun read(file: File): FormInfo =
        PDDocument.load(file, MemoryUsageSetting.setupMixed(PdfLoader.MIXED_MEMORY_BYTES)).use { read(it) }

    fun read(doc: PDDocument): FormInfo {
        val form = doc.documentCatalog.acroForm ?: return FormInfo()
        val out = ArrayList<FormField>()
        // widget dictionary -> (page, geometry): widgets name their page only sometimes, the page's /Annots always.
        val widgetPage = HashMap<COSDictionary, Int>()
        for (p in 0 until doc.numberOfPages) {
            val arr = doc.getPage(p).cosObject.getDictionaryObject(COSName.ANNOTS) as? com.tom_roush.pdfbox.cos.COSArray ?: continue
            for (i in 0 until arr.size()) (arr.getObject(i) as? COSDictionary)?.let { widgetPage[it] = p }
        }
        var n = 0
        val tree = try { form.fieldTree.toList() } catch (_: Exception) { emptyList() }
        for (field in tree) {
            if (field !is com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField) continue
            val widgets = try { field.widgets } catch (_: Exception) { continue }
            for (w in widgets) {
                try { build(doc, form, field, w, widgetPage, "f${n++}")?.let { out += it } } catch (_: Exception) { /* a damaged widget is skipped */ }
            }
        }
        out.sortWith(FormValues.readingOrder)
        val xfa = try { form.hasXFA() } catch (_: Exception) { false }
        return FormInfo(out, hasAcroForm = true, xfaOnly = xfa && out.none { it.kind != FieldKind.SIGNATURE })
    }

    private fun build(doc: PDDocument, form: PDAcroForm, field: PDField, w: PDAnnotationWidget, pages: Map<COSDictionary, Int>, id: String): FormField? {
        val page = pages[w.cosObject] ?: w.page?.let { pg -> (0 until doc.numberOfPages).firstOrNull { doc.getPage(it).cosObject === pg.cosObject } } ?: return null
        val flags = w.cosObject.getInt(COSName.F, 0)
        if (flags and (2 or 32) != 0) return null // hidden / no view
        val r = w.rectangle ?: return null
        val geo = PdfAnnotIo.Geo(doc.getPage(page))
        val rect = geo.norm(r.lowerLeftX, r.lowerLeftY, r.upperRightX, r.upperRightY)
        if (rect.right - rect.left < 0.002f || rect.bottom - rect.top < 0.002f) return null
        val name = field.fullyQualifiedName ?: field.partialName ?: id
        val ro = field.isReadOnly
        val req = field.isRequired
        val tip = runCatching { field.alternateFieldName }.getOrNull().orEmpty().trim()
        return when (field) {
            is PDTextField -> {
                val multi = field.isMultiline
                FormField(id, name, if (multi) FieldKind.MULTILINE else FieldKind.TEXT, page, rect, ro || field.isFileSelect, req,
                    maxLen = field.maxLen, value = field.valueAsString.orEmpty(), hint = if (multi || field.isPassword) KeyHint.TEXT else KeyHints.of(name, formatScript(field)))
            }
            is PDCheckBox -> FormField(id, name, FieldKind.CHECKBOX, page, rect, ro, req, onValue = field.onValue.ifEmpty { "Yes" }, value = if (field.isChecked) field.onValue.ifEmpty { "Yes" } else FormValues.OFF)
            is PDRadioButton -> {
                val on = onValueOf(field, w)
                FormField(id, name, FieldKind.RADIO, page, rect, ro, req, onValue = on, value = field.value.orEmpty().ifEmpty { FormValues.OFF })
            }
            is PDComboBox -> FormField(id, name, FieldKind.COMBO, page, rect, ro, req, options = displayOptions(field), value = field.value.firstOrNull().orEmpty())
            is PDListBox -> FormField(id, name, FieldKind.LIST, page, rect, ro, req, options = displayOptions(field), multiSelect = field.isMultiSelect, value = field.value.joinToString("\n"))
            is PDSignatureField -> FormField(id, name, FieldKind.SIGNATURE, page, rect, ro, req, signed = field.signature != null)
            else -> null // push buttons and unknown types are left alone
        }?.copy(tooltip = tip)
    }

    private fun displayOptions(c: PDChoice): List<String> = try { c.options } catch (_: Exception) { emptyList() }

    private fun onValueOf(field: PDRadioButton, w: PDAnnotationWidget): String {
        val n = w.appearance?.normalAppearance?.subDictionary?.keys?.map { it.name }?.firstOrNull { it != "Off" }
        if (n != null) return n
        return field.onValues.firstOrNull() ?: "Yes"
    }

    private fun formatScript(f: PDTextField): String = try {
        val aa = f.actions
        listOfNotNull(aa?.f, aa?.k, aa?.v).joinToString(" ") { act ->
            ((act.cosObject.getDictionaryObject(COSName.getPDFName("JS"))) as? com.tom_roush.pdfbox.cos.COSString)?.string.orEmpty()
        }
    } catch (_: Exception) { "" }
}

/** Picks a keyboard from the field's name or its format script (AFNumber_Format, AFDate_Format, AFSpecial_Format ...). */
object KeyHints {
    fun of(name: String, script: String = ""): KeyHint {
        val n = name.lowercase()
        val s = script
        return when {
            s.contains("AFDate") || s.contains("AFTime") -> KeyHint.DATE
            s.contains("AFPercent_") || s.contains("AFNumber_") -> KeyHint.DECIMAL
            s.contains("AFSpecial_Format(2") || s.contains("AFSpecial_Format(3") -> KeyHint.PHONE
            s.contains("AFSpecial_Format(0") || s.contains("AFSpecial_Format(1") -> KeyHint.NUMBER
            Regex("e-?mail").containsMatchIn(n) -> KeyHint.EMAIL
            Regex("phone|mobile|tel\\b|fax|cell").containsMatchIn(n) -> KeyHint.PHONE
            Regex("date|dob|birth|\\bday\\b|expiry|expires").containsMatchIn(n) -> KeyHint.DATE
            Regex("amount|total|price|salary|income|balance|sum|payment|fee|cost|tax|\\bqty\\b|quantity|\\bage\\b|zip|postcode|postal|number|\\bno\\b|\\bssn\\b|pin\\b").containsMatchIn(n) -> KeyHint.DECIMAL
            else -> KeyHint.TEXT
        }
    }
}
