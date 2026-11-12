package com.namanbansal.forge.image

import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.image.ZipTools
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipToolsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zipOf(vararg e: Pair<String, ByteArray>): File {
        val f = tmp.newFile()
        ZipOutputStream(f.outputStream()).use { z -> e.forEach { (n, d) -> z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } }
        return f
    }

    @Test fun createThenExtractRoundTrip() {
        val out = ByteArrayOutputStream()
        val a = "alpha".toByteArray(); val b = ByteArray(5000) { it.toByte() }
        ZipTools.create(out, listOf("a.txt" to { ByteArrayInputStream(a) }, "sub/b.bin" to { ByteArrayInputStream(b) }, "a.txt" to { ByteArrayInputStream(a) }))
        val f = tmp.newFile().also { it.writeBytes(out.toByteArray()) }
        val items = ZipTools.list(f)
        assertEquals(listOf("a.txt", "sub/b.bin", "a (1).txt"), items.map { it.path })
        assertEquals(5000L, items[1].size)
        val got = HashMap<String, ByteArray>()
        val (n, skipped) = ZipTools.extract(f, null) { path, _ -> object : ByteArrayOutputStream() { override fun close() { got[path] = toByteArray() } } }
        assertEquals(3 to 0, n to skipped)
        assertArrayEquals(a, got["a.txt"]); assertArrayEquals(b, got["sub/b.bin"])
        // only selected
        val one = HashMap<String, ByteArray>()
        ZipTools.extract(f, setOf(1)) { path, _ -> object : ByteArrayOutputStream() { override fun close() { one[path] = toByteArray() } } }
        assertEquals(setOf("sub/b.bin"), one.keys)
    }

    @Test fun zipSlipEntriesAreRejected() {
        assertTrue(ZipTools.isUnsafe("../evil.txt")); assertTrue(ZipTools.isUnsafe("a/../../evil")); assertTrue(ZipTools.isUnsafe("/etc/passwd"))
        assertTrue(ZipTools.isUnsafe("..\\evil")); assertTrue(ZipTools.isUnsafe("C:\\x"))
        assertFalse(ZipTools.isUnsafe("dir/file..txt")); assertFalse(ZipTools.isUnsafe("ok/file.txt"))
        val f = zipOf("../evil.txt" to "x".toByteArray(), "good.txt" to "y".toByteArray())
        val items = ZipTools.list(f)
        assertTrue(items[0].unsafe); assertFalse(items[1].unsafe)
        val written = ArrayList<String>()
        val (n, skipped) = ZipTools.extract(f, null) { path, _ -> written += path; ByteArrayOutputStream() }
        assertEquals(listOf("good.txt"), written); assertEquals(1 to 1, n to skipped)
    }

    @Test fun passwordProtectedZipIsReportedClearly() {
        val f = zipOf("secret.txt" to "x".toByteArray())
        assertFalse(ZipTools.isEncrypted(f))
        // flip the "encrypted" bit (bit 0 of the general-purpose flag) in both local and central headers
        val b = f.readBytes()
        for (i in 0 until b.size - 4) {
            if (b[i] == 0x50.toByte() && b[i + 1] == 0x4B.toByte() && b[i + 2] == 3.toByte() && b[i + 3] == 4.toByte()) b[i + 6] = (b[i + 6].toInt() or 1).toByte()
            if (b[i] == 0x50.toByte() && b[i + 1] == 0x4B.toByte() && b[i + 2] == 1.toByte() && b[i + 3] == 2.toByte()) b[i + 8] = (b[i + 8].toInt() or 1).toByte()
        }
        f.writeBytes(b)
        assertTrue(ZipTools.isEncrypted(f))
        val e = assertThrows(ToolException::class.java) { ZipTools.list(f) }
        assertTrue(e.message!!.contains("password"))
    }

    @Test fun corruptZipGivesFriendlyError() {
        val f = tmp.newFile().also { it.writeBytes(ByteArray(100) { 7 }) }
        assertThrows(ToolException::class.java) { ZipTools.list(f) }
    }

    @Test fun zipBombGuard() {
        val f = zipOf("big.bin" to ByteArray(100_000))
        assertThrows(ToolException::class.java) { ZipTools.extract(f, null, maxTotalBytes = 1000) { _, _ -> ByteArrayOutputStream() } }
    }
}
