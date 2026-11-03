package com.namanbansal.forge.core.pdf.edit

import com.namanbansal.forge.core.pdf.NormRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class EditModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun obj(s: EditSession, kind: EditKind = EditKind.RECT, page: Int = 0) =
        EditObject(s.newId(), page, kind, NormRect(0.1f, 0.1f, 0.3f, 0.2f))

    @Test fun undoRedoAcrossObjectsAndMarks() {
        val s = EditSession()
        assertFalse(s.dirty.value)
        val a = obj(s); val b = obj(s, EditKind.TEXT)
        s.add(a); s.add(b)
        s.addMark(RedactMark("m", 0, NormRect(0f, 0f, 0.5f, 0.5f)))
        assertTrue(s.dirty.value)
        s.replace(a.copy(color = EditStyle.RED))
        assertEquals(2, s.current.items.size)
        s.undo(); assertEquals(EditStyle.BLACK, s.find(a.id)!!.color)
        s.undo(); assertTrue(s.current.marks.isEmpty())
        s.redo(); assertEquals(1, s.current.marks.size)
        s.redo(); assertEquals(EditStyle.RED, s.find(a.id)!!.color)
        assertFalse(s.canRedo.value)
        s.remove(b.id); s.undo(); assertEquals(b.id, s.find(b.id)!!.id)
        s.reset(); assertFalse(s.dirty.value); assertFalse(s.canUndo.value)
    }

    @Test fun newEditClearsRedo() {
        val s = EditSession()
        s.add(obj(s)); s.undo(); assertTrue(s.canRedo.value)
        s.add(obj(s)); assertFalse(s.canRedo.value)
    }

    @Test fun bringToFrontAndDuplicate() {
        val s = EditSession()
        val a = obj(s); val b = obj(s)
        s.add(a); s.add(b)
        s.bringToFront(a.id)
        assertEquals(listOf(b.id, a.id), s.current.items.map { it.id })
        s.undo(); assertEquals(listOf(a.id, b.id), s.current.items.map { it.id })
        val copyId = s.duplicate(a.id)!!
        val copy = s.find(copyId)!!
        assertEquals(a.rect.left + 0.03f, copy.rect.left, 1e-5f)
        assertEquals(a.rect.right - a.rect.left, copy.rect.right - copy.rect.left, 1e-5f)
        assertNull(s.duplicate("nope"))
    }

    @Test fun duplicateStaysOnPage() {
        val s = EditSession()
        val a = EditObject(s.newId(), 0, EditKind.RECT, NormRect(0.8f, 0.85f, 1f, 1f))
        s.add(a)
        val c = s.find(s.duplicate(a.id)!!)!!
        assertTrue(c.rect.right <= 1.0001f && c.rect.bottom <= 1.0001f)
    }

    @Test fun jsonRoundTripAndPendingStore() {
        val o = EditObject("x", 2, EditKind.TEXT, NormRect(0.1f, 0.2f, 0.4f, 0.3f), text = "Héllo\nworld", font = EditFont.MONO, sizeFrac = 0.03f, bold = true, align = 2,
            color = EditStyle.BLUE, strokeWidth = 4f, filled = true, fillColor = 0x80112233.toInt(), fillOpacity = 0.4f, imageId = "img1", turns = 3, flip = true, reverse = true)
        val st = EditState(listOf(o), listOf(RedactMark("m1", 1, NormRect(0.1f, 0.1f, 0.2f, 0.2f), RedactMark.ORIGIN_SEARCH)))
        assertEquals(st, EditJson.decode(EditJson.encode(st)))
        assertTrue(EditJson.decode("garbage").isEmpty)
        val store = EditPendingStore(tmp.newFolder("pend"))
        assertNull(store.load("k"))
        store.save("k", st)
        assertEquals(st, store.load("k"))
        store.clear("k")
        assertNull(store.load("k"))
    }

    @Test fun tooltipWinsOverHumanisedName() {
        val f = com.namanbansal.forge.core.pdf.form.FormField("a", "full_name", com.namanbansal.forge.core.pdf.form.FieldKind.TEXT, 0, NormRect(0f, 0f, 1f, 1f))
        assertEquals("Full name", f.title)
        assertEquals("Your legal name", f.copy(tooltip = "Your legal name").title)
    }

    @Test fun fieldLabelsAreHumanised() {
        assertEquals("Full name", FieldLabels.humanise("full_name"))
        assertEquals("Name", FieldLabels.humanise("person.name"))
        assertEquals("Date of birth", FieldLabels.humanise("form1.dateOfBirth".replace("dateOfBirth", "date_of_birth")))
        assertEquals("First name", FieldLabels.humanise("firstName"))
        assertEquals("Postal code", FieldLabels.humanise("address[0].postal-code[1]"))
        assertEquals("Agree", FieldLabels.humanise("agree"))
        assertEquals("X", FieldLabels.humanise("x"))
    }
}
