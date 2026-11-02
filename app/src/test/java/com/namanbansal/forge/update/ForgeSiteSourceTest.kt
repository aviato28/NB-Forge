package com.namanbansal.forge.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForgeSiteSourceTest {
    @Test fun parsesContract() {
        val json = """
            {"version":"0.1.120","versionCode":120,"channel":"beta","notes":"New tools",
             "publishedAt":"2026-12-01T09:00:00Z","apk":"https://forge.nbansal28.com/download/apk",
             "size":8123456,"sha256":"${"0f".repeat(32)}","extra":"ignored"}
        """.trimIndent()
        val info = ForgeSiteSource.parse(json)!!
        assertEquals("0.1.120", info.version)
        assertEquals(120, info.versionCode)
        assertEquals(Channel.Beta, info.channel)
        assertEquals("New tools", info.notes)
        assertEquals("2026-12-01T09:00:00Z", info.publishedAt)
        assertEquals("https://forge.nbansal28.com/download/apk", info.apkUrl)
        assertEquals(8123456L, info.size)
        assertEquals("0f".repeat(32), info.sha256)
    }

    @Test fun nullShaAndStableChannel() {
        val info = ForgeSiteSource.parse(
            """{"version":"0.1.101","versionCode":101,"channel":"stable","notes":"","publishedAt":"","apk":"https://x/y.apk","size":1,"sha256":null}""",
        )!!
        assertEquals(Channel.Stable, info.channel)
        assertNull(info.sha256)
    }

    @Test fun emptyPayloadGivesNull() {
        assertNull(ForgeSiteSource.parse("{}"))
    }
}
