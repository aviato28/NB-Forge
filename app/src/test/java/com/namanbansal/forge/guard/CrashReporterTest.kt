package com.namanbansal.forge.guard

import com.namanbansal.forge.core.CrashReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashReporterTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun writesAndReadsReport() {
        val r = CrashReporter(tmp.root)
        assertNull(r.read()); assertFalse(r.hasUnseen())
        r.record(Thread.currentThread(), IllegalStateException("boom"))
        val text = r.read()!!
        assertTrue(text.contains("IllegalStateException: boom"))
        assertTrue(text.contains("Forge ")); assertTrue(text.contains("Device:")); assertTrue(text.contains("Android:"))
        assertTrue(r.hasUnseen())
        r.markSeen()
        assertFalse(r.hasUnseen()); assertTrue(r.read() != null)
        r.clear(); assertNull(r.read())
    }

    @Test fun unwritableDirectoryNeverThrows() {
        val file = File(tmp.root, "plain").also { it.writeText("x") }
        CrashReporter(File(file, "sub")).record(Thread.currentThread(), RuntimeException("x"))
    }

    @Test fun handlerWritesThenChains() {
        val saved = Thread.getDefaultUncaughtExceptionHandler()
        try {
            var chained: Throwable? = null
            val r = CrashReporter(tmp.root)
            r.install { _, e -> chained = e }
            val boom = RuntimeException("kaboom")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), boom)
            assertEquals(boom, chained)
            assertTrue(r.read()!!.contains("kaboom"))
        } finally { Thread.setDefaultUncaughtExceptionHandler(saved) }
    }
}
