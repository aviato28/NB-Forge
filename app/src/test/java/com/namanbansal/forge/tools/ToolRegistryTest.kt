package com.namanbansal.forge.tools

import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.core.NextSuggestions
import com.namanbansal.forge.data.Pins
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {
    @Test fun shortLabelsAreUnique() {
        val dupes = ToolRegistry.all.groupBy { it.shortLabel.lowercase() }.filterValues { it.size > 1 }
        assertTrue("Ambiguous short labels: ${dupes.mapValues { e -> e.value.map { it.code } }}", dupes.isEmpty())
    }

    @Test fun ambiguousNamesGetQualifiedLabels() {
        assertEquals("Compress PDF", ToolRegistry.get("P9")!!.shortLabel)
        assertEquals("Compress image", ToolRegistry.get("I2")!!.shortLabel)
        assertEquals("Compress video", ToolRegistry.get("V2")!!.shortLabel)
        assertEquals("Convert video", ToolRegistry.get("V3")!!.shortLabel)
        assertEquals("Trim video", ToolRegistry.get("V1")!!.shortLabel)
    }

    @Test fun defaultPinsAreUnambiguous() {
        ToolRegistry.DEFAULT_PINS.forEach { code ->
            val t = ToolRegistry.get(code)!!
            assertTrue("$code label '${t.shortLabel}' should mention what it works on or be self-evident", t.shortLabel.isNotBlank())
        }
        assertEquals(ToolRegistry.DEFAULT_PINS.size, ToolRegistry.DEFAULT_PINS.map { ToolRegistry.get(it)!!.shortLabel }.toSet().size)
    }

    @Test fun availableToolsAreTheOrganiseSetPlusAnnotateAndViewer() {
        val available = ToolRegistry.all.filter { it.status == ToolStatus.Available }
        assertEquals(listOf("P1", "P2", "P3", "P4", "P5", "P6", "P17", "P40"), available.map { it.code })
        assertEquals(listOf("P1", "P2", "P3", "P4", "P5", "P6", "P17", "P40"), ToolRegistry.availableFor(setOf(FileKind.PDF)).map { it.code })
        assertTrue(ToolRegistry.availableFor(setOf(FileKind.IMAGE)).isEmpty())
        assertTrue(ToolRegistry.availableFor(emptySet()).isEmpty())
        assertTrue(ToolRegistry.availableFor(setOf(FileKind.PDF, FileKind.IMAGE)).isEmpty()) // mixed: no tool takes both
    }

    @Test fun nextSuggestionsAreScalableAndExcludeCurrentTool() {
        val pdfAvail = ToolRegistry.all.filter { it.category == Category.PDF }.take(6).mapIndexed { i, t ->
            t.copy(status = if (i < 4) ToolStatus.Available else ToolStatus.ComingSoon)
        }
        val next = NextSuggestions.forOutput(FileKind.PDF, currentToolCode = pdfAvail[0].code, tools = pdfAvail)
        assertEquals(pdfAvail.subList(1, 4).map { it.code }, next.map { it.code })
        assertTrue(NextSuggestions.forOutput(FileKind.IMAGE, "x", pdfAvail).isEmpty())
    }

    @Test fun nextSuggestionsFollowThePreferredFlowsAndLightUpAsToolsShip() {
        fun codes(from: String) = NextSuggestions.forOutput(FileKind.PDF, from).map { it.code }
        // Merge: Compress / Watermark / Protect do not exist yet, so the next best available steps show.
        assertEquals(listOf("P40", "P2", "P3", "P4"), codes("P1"))
        assertTrue("P1" in codes("P2"))                       // Split -> Merge
        assertEquals("P1", codes("P2").first())
        assertTrue(codes("P4").first() == "P3")               // Rotate -> Page manager
        assertTrue(codes("P40").containsAll(listOf("P3", "P1")))
        assertTrue("P4" !in codes("P4"))
        assertTrue(codes("P5").first() == "P1")
        // With Compress / Watermark / Protect available, Merge suggests them first.
        val all = ToolRegistry.all.map { if (it.code in setOf("P9", "P20", "P29")) it.copy(status = ToolStatus.Available) else it }
        assertEquals(listOf("P9", "P20", "P29"), NextSuggestions.forOutput(FileKind.PDF, "P1", all).take(3).map { it.code })
    }

    @Test fun toolCountMatchesRegistry() {
        assertTrue(ToolRegistry.all.size > 80)
        assertEquals(ToolRegistry.all.size, ToolRegistry.all.map { it.code }.toSet().size)
    }

    @Test fun pinRules() {
        assertEquals(listOf("a", "b", "c"), Pins.toggle(listOf("a", "b"), "c"))
        assertEquals(listOf("a"), Pins.toggle(listOf("a", "b"), "b"))
        val full = (1..ToolRegistry.MAX_PINS).map { "P$it" }
        assertNull("9th pin must be refused", Pins.toggle(full, "P20"))
        assertEquals(full - "P3", Pins.toggle(full, "P3"))
    }

    @Test fun pinMove() {
        val l = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), Pins.move(l, 0, 2))
        assertEquals(listOf("d", "a", "b", "c"), Pins.move(l, 3, 0))
        assertEquals(l, Pins.move(l, 1, 1))
        assertEquals(l, Pins.move(l, 9, 0))
        assertEquals(listOf("b", "c", "d", "a"), Pins.move(l, 0, 99))
        assertFalse(Pins.move(l, 0, 3) === l)
    }
}
