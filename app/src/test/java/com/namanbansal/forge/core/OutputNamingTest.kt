package com.namanbansal.forge.core

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputNamingTest {
    @Test fun basicName() {
        assertEquals("invoice_rotated.pdf", OutputNaming.build("invoice.pdf", "rotated", "pdf") { false })
    }

    @Test fun dedupesWithCounter() {
        val taken = setOf("a_rotated.pdf", "a_rotated (1).pdf", "a_rotated (2).pdf")
        assertEquals("a_rotated (3).pdf", OutputNaming.build("a.pdf", "rotated", "pdf") { it in taken })
    }

    @Test fun doesNotRepeatActionSuffix() {
        assertEquals("a_rotated (1).pdf", OutputNaming.build("a_rotated.pdf", "rotated", "pdf") { it == "a_rotated.pdf" })
    }

    @Test fun stripsOnlyLastExtensionAndKeepsDots() {
        assertEquals("report.v2_rotated.pdf", OutputNaming.build("report.v2.pdf", "rotated", "pdf") { false })
    }

    @Test fun sanitisesIllegalCharacters() {
        assertEquals("a_b_c_rotated.pdf", OutputNaming.build("a/b:c.pdf", "rotated", "pdf") { false })
    }

    @Test fun handlesNoExtensionAndBlankNames() {
        assertEquals("notes_rotated.pdf", OutputNaming.build("notes", "rotated", "pdf") { false })
        assertEquals("file_rotated.pdf", OutputNaming.build("", "rotated", "pdf") { false })
        assertEquals("file_rotated.pdf", OutputNaming.build("...", "rotated", "pdf") { false })
    }

    @Test fun extensionMayCarryLeadingDot() {
        assertEquals("a_x.jpg", OutputNaming.build("a.png", "x", ".jpg") { false })
    }

    @Test fun truncatesVeryLongNames() {
        val name = OutputNaming.build("x".repeat(400) + ".pdf", "rotated", "pdf") { false }
        assertEquals(100 + "_rotated.pdf".length, name.length)
    }

    @Test fun formatBytesAndRelativeTime() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("4.2 MB", formatBytes((4.2 * 1024 * 1024).toLong()))
        assertEquals("Unknown size", formatBytes(-1))
        assertEquals("just now", relativeTime(1000, now = 30_000))
        assertEquals("5 min ago", relativeTime(0, now = 5 * 60_000))
        assertEquals("2 h ago", relativeTime(0, now = 2 * 3_600_000L))
    }
}
