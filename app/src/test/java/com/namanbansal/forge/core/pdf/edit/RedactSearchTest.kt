package com.namanbansal.forge.core.pdf.edit

import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.viewer.PageText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactSearchTest {
    private fun found(text: String, p: RedactPreset) = RedactSearch.find(text, p).map { text.substring(it.first, it.last + 1) }

    @Test fun emails() {
        assertEquals(listOf("jane.doe+x@mail.example.co.uk", "a@b.io"), found("Write jane.doe+x@mail.example.co.uk or a@b.io. Thanks", RedactPreset.EMAIL))
        assertTrue(found("no at-sign here, just @handle", RedactPreset.EMAIL).isEmpty())
    }

    @Test fun phoneNumbers() {
        val t = "Call +1 (555) 123-4567, or 555-123-4567; UK +44 20 7946 0958; mobile 98765 43210 today"
        val f = found(t, RedactPreset.PHONE)
        assertTrue(f.toString(), f.any { it.contains("(555) 123-4567") })
        assertTrue(f.toString(), f.any { it == "555-123-4567" })
        assertTrue(f.toString(), f.any { it.startsWith("+44 20 7946 0958") })
        assertTrue(f.toString(), f.any { it.contains("98765 43210") })
        // dates and plain short numbers are not phone numbers
        assertTrue(found("on 2024-01-15 and 12/05/2024, qty 12 345", RedactPreset.PHONE).isEmpty())
    }

    @Test fun longNumbersAndCards() {
        val f = found("Account 123456789012 card 4111 1111 1111 1111 and 4111-1111-1111-1111 short 12345 ref 12345678", RedactPreset.LONG_NUMBER)
        assertEquals(listOf("123456789012", "4111 1111 1111 1111", "4111-1111-1111-1111"), f)
    }

    @Test fun dates() {
        val t = "Born 1990-03-04, paid 04/03/1990 and 4.3.90? Due 3 March 2025, signed Sept. 9, 2024, 1st Jan 2020."
        val f = found(t, RedactPreset.DATE)
        assertTrue(f.toString(), "1990-03-04" in f && "04/03/1990" in f && "4.3.90" in f)
        assertTrue(f.toString(), "3 March 2025" in f && "Sept. 9, 2024" in f && "1st Jan 2020" in f)
    }

    @Test fun termSearchIsCaseInsensitiveLiteral() {
        assertEquals(2, RedactSearch.find("Secret secret SECRETS", "secret").size - 1)
        assertEquals(listOf(0..5, 7..12, 14..19), RedactSearch.find("Secret secret SECRET", "SECRET"))
        assertTrue(RedactSearch.find("abc", "  ").isEmpty())
        assertEquals(1, RedactSearch.find("a.b (c)", "(c)").size)
    }

    @Test fun hitsCarryBoxesAndContextAndBecomeMarks() {
        val text = "Mail me at jane@x.org please"
        val rects = text.indices.map { i -> NormRect(0.01f * i, 0.1f, 0.01f * i + 0.009f, 0.12f) }
        val pt = PageText(text, rects)
        val hits = RedactSearch.hitsOn(2, pt, RedactPreset.EMAIL, null)
        assertEquals(1, hits.size)
        val h = hits.single()
        assertEquals("jane@x.org", h.text); assertEquals("Mail me at", h.before.trim()); assertEquals("please", h.after.trim()); assertEquals(2, h.page)
        var n = 0
        val marks = RedactSearch.marksFor(hits) { "m${n++}" }
        assertEquals(1, marks.size)
        assertEquals(RedactMark.ORIGIN_SEARCH, marks[0].origin)
        assertTrue(marks[0].rect.left < h.rects[0].left && marks[0].rect.right > h.rects[0].right)
    }
}
