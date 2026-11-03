package com.namanbansal.forge.core.pdf.form

import com.namanbansal.forge.core.pdf.NormRect

enum class FieldKind { TEXT, MULTILINE, CHECKBOX, RADIO, COMBO, LIST, SIGNATURE }

/** What kind of keyboard suits a text field. */
enum class KeyHint { TEXT, NUMBER, DECIMAL, PHONE, DATE, EMAIL }

/**
 * One tappable widget of a form field, in page fractions. A radio group or a checkbox group is several widgets with the same
 * [name]; [onValue] is the export value that widget stands for. The values the user changes are keyed by [name].
 */
data class FormField(
    val id: String,
    val name: String,
    val kind: FieldKind,
    val page: Int,
    val rect: NormRect,
    val readOnly: Boolean = false,
    val required: Boolean = false,
    val maxLen: Int = -1,
    val options: List<String> = emptyList(),
    val multiSelect: Boolean = false,
    val onValue: String = "Yes",
    /** The value the file has now ("Off" for an unchecked box / radio group without a choice). */
    val value: String = "",
    val hint: KeyHint = KeyHint.TEXT,
    val signed: Boolean = false,
    /** The field's tooltip (/TU), the author's own label for it. */
    val tooltip: String = "",
) {
    /** The title for a sheet: the tooltip when the PDF has one, else the humanised partial name (`form.full_name` -> `Full name`). */
    val title: String get() = tooltip.ifBlank { com.namanbansal.forge.core.pdf.edit.FieldLabels.humanise(name) }
    val isToggle: Boolean get() = kind == FieldKind.CHECKBOX || kind == FieldKind.RADIO
    val isText: Boolean get() = kind == FieldKind.TEXT || kind == FieldKind.MULTILINE
}

/** The form of a document. [xfaOnly] = an XFA form with no AcroForm fields to show (not supported). */
data class FormInfo(
    val fields: List<FormField> = emptyList(),
    val hasAcroForm: Boolean = false,
    val xfaOnly: Boolean = false,
) {
    /** Fields in reading order (page, top to bottom, left to right) that the user can change. */
    val fillable: List<FormField> get() = fields.filter { !it.readOnly && it.kind != FieldKind.SIGNATURE }
    val signatureFields: List<FormField> get() = fields.filter { it.kind == FieldKind.SIGNATURE && !it.signed }
    val hasFields: Boolean get() = fields.isNotEmpty()
}

object FormValues {
    const val OFF = "Off"

    /** Value of [f] after the user's edits in [overrides] (keyed by field name). */
    fun valueOf(f: FormField, overrides: Map<String, String>): String = overrides[f.name] ?: f.value

    fun isOn(f: FormField, overrides: Map<String, String>): Boolean = valueOf(f, overrides).let { it == f.onValue }

    /** The new overrides after tapping [f]: toggles a checkbox, selects a radio. Values equal to the file's are dropped. */
    fun toggled(f: FormField, overrides: Map<String, String>, siblings: List<FormField>): Map<String, String> {
        val now = isOn(f, overrides)
        val next = when (f.kind) {
            FieldKind.CHECKBOX -> if (now) OFF else f.onValue
            else -> if (now) OFF else f.onValue
        }
        return set(f.name, next, siblings.firstOrNull { it.name == f.name } ?: f, overrides)
    }

    fun set(name: String, value: String, f: FormField, overrides: Map<String, String>): Map<String, String> =
        if (value == f.value) overrides - name else overrides + (name to value)

    /** Order for "Next field": page, then top to bottom, then left to right. */
    val readingOrder: Comparator<FormField> = compareBy<FormField>({ it.page }, { Math.round(it.rect.top * 200) }, { it.rect.left })
}
