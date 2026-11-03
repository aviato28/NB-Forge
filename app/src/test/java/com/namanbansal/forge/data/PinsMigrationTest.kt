package com.namanbansal.forge.data

import com.namanbansal.forge.tools.ToolRegistry
import com.namanbansal.forge.tools.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinsMigrationTest {
    @Test fun newDefaultsAreMergeSplitViewerPageManager() {
        assertEquals(listOf("P1", "P2", "P40", "P3"), ToolRegistry.DEFAULT_PINS)
        assertTrue(ToolRegistry.DEFAULT_PINS.all { ToolRegistry.get(it)!!.status == ToolStatus.Available })
    }

    @Test fun usersStillOnTheOldDefaultsGetTheNewOnes() {
        assertEquals(ToolRegistry.DEFAULT_PINS, Pins.migrate(listOf("P9", "P1", "P43", "D1")))
    }

    @Test fun customPinsAreLeftAlone() {
        for (custom in listOf(
            listOf("P1", "P9", "P43", "D1"), // same set, different order
            listOf("P9", "P1", "P43"), // one removed
            listOf("P9", "P1", "P43", "D1", "P4"), // one added
            emptyList(), // the user cleared everything
            listOf("P1", "P2", "P40", "P3"),
        )) assertEquals(custom, Pins.migrate(custom))
    }

    @Test fun nothingStoredStaysNothingSoTheDefaultsApply() {
        assertNull(Pins.migrate(null))
    }

    @Test fun comingSoonPinsStillResolveSoTheirTilesStayTappable() {
        // Old pins such as P9 (Compress) are not built yet; the registry still knows them and shows "Coming soon" when opened.
        val old = ToolRegistry.get("P9")!!
        assertEquals(ToolStatus.ComingSoon, old.status)
        assertEquals(listOf("P9"), Pins.toggle(emptyList(), "P9"))
    }
}
