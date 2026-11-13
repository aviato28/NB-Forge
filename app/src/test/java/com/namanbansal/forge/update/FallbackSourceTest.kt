package com.namanbansal.forge.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FallbackSourceTest {
    private fun info(code: Int) = UpdateInfo(
        version = "0.1.$code", versionCode = code, channel = Channel.fromParam("stable"),
        notes = "", publishedAt = "", apkUrl = "https://x/$code.apk", size = 1, sha256 = null,
    )
    private fun src(block: () -> UpdateInfo?) = object : UpdateSource {
        override suspend fun latest(channel: Channel) = block()
    }
    private val stable = Channel.fromParam("stable")

    @Test fun usesPrimaryWhenItAnswers() = runBlocking {
        assertEquals(1, FallbackSource(src { info(1) }, src { info(2) }).latest(stable)?.versionCode)
    }

    @Test fun fallsBackWhenPrimaryThrows() = runBlocking {
        assertEquals(2, FallbackSource(src { throw UpdateException(UpdateException.Kind.Parse, "x", null) }, src { info(2) }).latest(stable)?.versionCode)
    }

    @Test fun fallsBackWhenPrimaryEmpty() = runBlocking {
        assertEquals(2, FallbackSource(src { null }, src { info(2) }).latest(stable)?.versionCode)
    }

    @Test fun nullWhenBothEmpty() = runBlocking {
        assertNull(FallbackSource(src { null }, src { null }).latest(stable))
    }
}
