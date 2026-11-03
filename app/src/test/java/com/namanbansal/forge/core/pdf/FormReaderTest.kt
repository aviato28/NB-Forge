package com.namanbansal.forge.core.pdf

import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.FormFixtures
import com.namanbansal.forge.core.pdf.form.FieldKind
import com.namanbansal.forge.core.pdf.form.FormReader
import com.namanbansal.forge.core.pdf.form.FormValues
import com.namanbansal.forge.core.pdf.form.KeyHint
import com.namanbansal.forge.core.pdf.form.KeyHints
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FormReaderTest {
    @get:Rule val tmp = TemporaryFolder()
    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext()) }

    @Test fun detectsEveryFieldKindWithItsFlags() {
        val info = FormReader.read(FormFixtures.acroForm(tmp.newFile("f.pdf")))
        assertTrue(info.hasAcroForm); assertFalse(info.xfaOnly)
        val byName = info.fields.groupBy { it.name }
        assertEquals(FieldKind.TEXT, byName.getValue("person.name").single().kind)
        assertTrue(byName.getValue("person.name").single().required)
        assertEquals("Name", byName.getValue("person.name").single().title)
        assertEquals(FieldKind.MULTILINE, byName.getValue("notes").single().kind)
        assertEquals(FieldKind.CHECKBOX, byName.getValue("agree").single().kind)
        assertEquals(2, byName.getValue("colour").size)
        assertEquals(listOf("Red", "Blue"), byName.getValue("colour").sortedBy { it.rect.left }.map { it.onValue })
        assertEquals(FieldKind.COMBO, byName.getValue("fruit").single().kind)
        assertEquals(listOf("Apple", "Banana", "Cherry"), byName.getValue("fruit").single().options)
        assertEquals(FieldKind.LIST, byName.getValue("pets").single().kind)
        assertEquals(FieldKind.SIGNATURE, byName.getValue("signature").single().kind)
        assertTrue(byName.getValue("locked").single().readOnly)
        assertEquals("fixed", byName.getValue("locked").single().value)
        assertEquals(4, byName.getValue("code").single().maxLen)
        assertEquals(1, info.signatureFields.size)
        assertFalse(info.fillable.any { it.name == "locked" || it.kind == FieldKind.SIGNATURE })
    }

    @Test fun fieldsAreInReadingOrderAndBoxesAreOnThePage() {
        val info = FormReader.read(FormFixtures.acroForm(tmp.newFile("f.pdf")))
        val tops = info.fields.map { it.rect.top }
        assertTrue(tops.zipWithNext().all { (a, b) -> a <= b + 0.006f })
        // NAME box: x 60..260 of 595, y 700..724 of 842 (from the top: 118..142)
        val name = info.fields.first { it.name == "person.name" }
        assertEquals(60f / 595.28f, name.rect.left, 0.002f)
        assertEquals((841.89f - 724f) / 841.89f, name.rect.top, 0.002f)
    }

    @Test fun rotatedPagesGiveDisplayedCoordinates() {
        val info = FormReader.read(FormFixtures.acroForm(tmp.newFile("f.pdf"), rotation = 90))
        val name = info.fields.first { it.name == "person.name" }
        // on a page turned 90 degrees clockwise the bottom-left of the page is the top-left of the display
        assertTrue(name.rect.left > 0.8f && name.rect.top < 0.2f)
    }

    @Test fun xfaOnlyFormsAreReported() {
        val info = FormReader.read(FormFixtures.acroForm(tmp.newFile("x.pdf"), xfaOnly = true))
        assertTrue(info.xfaOnly); assertFalse(info.hasFields)
    }

    @Test fun pdfWithoutAFormHasNoFields() {
        val info = FormReader.read(PdfFixtures_create(tmp.newFile("p.pdf")))
        assertFalse(info.hasAcroForm); assertFalse(info.hasFields); assertFalse(info.xfaOnly)
    }

    @Test fun keyboardHintsFollowNamesAndFormatScripts() {
        assertEquals(KeyHint.PHONE, KeyHints.of("Mobile phone"))
        assertEquals(KeyHint.DATE, KeyHints.of("Date of birth"))
        assertEquals(KeyHint.DECIMAL, KeyHints.of("Total amount"))
        assertEquals(KeyHint.EMAIL, KeyHints.of("E-mail"))
        assertEquals(KeyHint.TEXT, KeyHints.of("Comments"))
        assertEquals(KeyHint.DECIMAL, KeyHints.of("x", "AFNumber_Format(2, 0, 0, 0, \"\", true)"))
        assertEquals(KeyHint.DATE, KeyHints.of("x", "AFDate_FormatEx(\"dd/mm/yyyy\")"))
    }

    @Test fun togglingAndOverridesDropValuesEqualToTheFile() {
        val info = FormReader.read(FormFixtures.acroForm(tmp.newFile("f.pdf")))
        val box = info.fields.first { it.name == "agree" }
        val on = FormValues.toggled(box, emptyMap(), info.fields)
        assertEquals(mapOf("agree" to "Yes"), on)
        assertEquals(emptyMap<String, String>(), FormValues.toggled(box, on, info.fields))
    }

    private fun PdfFixtures_create(f: java.io.File) = com.namanbansal.forge.core.PdfFixtures.create(f)
}
