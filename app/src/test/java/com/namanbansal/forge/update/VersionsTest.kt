package com.namanbansal.forge.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {
    @Test fun tagToVersionCode() {
        assertEquals(101, Versions.versionCodeFromTag("v0.1.101"))
        assertEquals(7, Versions.versionCodeFromTag("0.1.7"))
        assertEquals(12345, Versions.versionCodeFromTag(" v0.1.12345 "))
        assertEquals(3, Versions.versionCodeFromTag("v3"))
    }

    @Test fun invalidTagsGiveNull() {
        assertNull(Versions.versionCodeFromTag(""))
        assertNull(Versions.versionCodeFromTag("v0.1.x"))
        assertNull(Versions.versionCodeFromTag("v0.1.5-beta"))
        assertNull(Versions.versionCodeFromTag("release"))
        assertNull(Versions.versionCodeFromTag("v0.1."))
    }

    @Test fun versionName() {
        assertEquals("0.1.101", Versions.versionNameFromTag("v0.1.101"))
    }

    @Test fun comparison() {
        val info = UpdateInfo("0.1.105", 105, Channel.Stable, "", "", "https://x/a.apk", 1, null)
        assertTrue(Versions.isNewer(info, 104))
        assertFalse(Versions.isNewer(info, 105))
        assertFalse(Versions.isNewer(info, 200))
        assertFalse(Versions.isNewer(null, 1))
        assertTrue(Versions.isNewer(info, 1)) // local dev builds are versionCode 1
    }

    @Test fun channelParam() {
        assertEquals(Channel.Beta, Channel.fromParam("beta"))
        assertEquals(Channel.Stable, Channel.fromParam("STABLE"))
        assertEquals(Channel.Stable, Channel.fromParam(null))
    }
}
