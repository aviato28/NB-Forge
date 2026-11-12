package com.namanbansal.forge.core.image

import com.namanbansal.forge.core.ToolException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** One archive entry as listed to the user. */
data class ZipItem(val index: Int, val path: String, val size: Long, val compressedSize: Long, val isDir: Boolean, val unsafe: Boolean)

/** Zip create / list / extract with zip-slip protection and a clear refusal for password-protected archives. */
object ZipTools {

    /** Creates a zip from named streams. Duplicate names get " (1)"; directories are not needed. */
    fun create(out: OutputStream, entries: List<Pair<String, () -> InputStream?>>, checkCancelled: () -> Unit = {}, onProgress: (Float) -> Unit = {}) {
        val used = HashSet<String>()
        ZipOutputStream(out).use { zip ->
            entries.forEachIndexed { i, (name, open) ->
                checkCancelled(); onProgress(i / entries.size.toFloat())
                var n = sanitiseName(name)
                var k = 1
                while (!used.add(n)) { n = n.substringBeforeLast('.', n).let { stem -> if ('.' in n) "$stem (${k++}).${n.substringAfterLast('.')}" else "$n (${k++})" } }
                zip.putNextEntry(ZipEntry(n))
                (open() ?: throw ToolException("Couldn't read $name.")).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun sanitiseName(n: String) = n.replace('\\', '/').trimStart('/').ifBlank { "file" }

    /** True if any entry is flagged encrypted (general-purpose bit 0) or uses AES (method 99). Reads the central directory directly. */
    fun isEncrypted(file: File): Boolean = try {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            val tail = ByteArray(minOf(len, 66_000L).toInt())
            raf.seek(len - tail.size); raf.readFully(tail)
            var eocd = -1
            for (i in tail.size - 22 downTo 0) if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4B.toByte() && tail[i + 2] == 5.toByte() && tail[i + 3] == 6.toByte()) { eocd = i; break }
            if (eocd < 0) return false
            fun u16(a: ByteArray, o: Int) = (a[o].toInt() and 0xFF) or ((a[o + 1].toInt() and 0xFF) shl 8)
            fun u32(a: ByteArray, o: Int) = (u16(a, o).toLong() or (u16(a, o + 2).toLong() shl 16))
            val count = u16(tail, eocd + 10)
            val cdOffset = u32(tail, eocd + 16)
            raf.seek(cdOffset)
            var found = false
            val hdr = ByteArray(46)
            for (i in 0 until count) {
                raf.readFully(hdr)
                if (u32(hdr, 0) != 0x02014b50L) break
                if ((u16(hdr, 8) and 1) != 0 || u16(hdr, 10) == 99) { found = true; break }
                raf.skipBytes(u16(hdr, 28) + u16(hdr, 30) + u16(hdr, 32))
            }
            found
        }
    } catch (_: Throwable) { false }

    /** Whether entry [path] would land outside the target folder (`../`, absolute, drive letters). */
    fun isUnsafe(path: String): Boolean {
        val p = path.replace('\\', '/')
        if (p.startsWith("/") || (p.length > 1 && p[1] == ':')) return true
        return p.split('/').any { it == ".." } || p.contains('\u0000')
    }

    fun list(file: File): List<ZipItem> {
        if (isEncrypted(file)) throw ToolException("This zip is password-protected. Forge can't open password-protected zips yet.")
        try {
            ZipFile(file).use { z ->
                return z.entries().asSequence().mapIndexed { i, e -> ZipItem(i, e.name, e.size, e.compressedSize, e.isDirectory, isUnsafe(e.name)) }.toList()
            }
        } catch (e: java.util.zip.ZipException) {
            throw ToolException("That doesn't look like a valid zip file (${e.message ?: "damaged"}).")
        }
    }

    /**
     * Extracts the entries at [selected] indexes (null = all). [sink] gets the safe relative path and returns the stream to write
     * (null to skip). Unsafe entries (zip-slip) are skipped and counted. Returns (extracted, skippedUnsafe).
     * A zip-bomb guard stops at [maxTotalBytes].
     */
    fun extract(
        file: File, selected: Set<Int>?, checkCancelled: () -> Unit = {}, onProgress: (Float) -> Unit = {},
        maxTotalBytes: Long = 8L shl 30, sink: (String, ZipItem) -> OutputStream?,
    ): Pair<Int, Int> {
        var done = 0
        var skipped = 0
        var total = 0L
        ZipFile(file).use { z ->
            val entries = z.entries().asSequence().toList()
            val wanted = entries.withIndex().filter { (i, e) -> !e.isDirectory && (selected == null || i in selected) }
            wanted.forEachIndexed { n, (i, e) ->
                checkCancelled(); onProgress(n / wanted.size.toFloat())
                val item = ZipItem(i, e.name, e.size, e.compressedSize, false, isUnsafe(e.name))
                if (item.unsafe) { skipped++; return@forEachIndexed }
                val out = sink(e.name.replace('\\', '/').trimStart('/'), item) ?: return@forEachIndexed
                out.use { o ->
                    z.getInputStream(e).use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val r = input.read(buf); if (r < 0) break
                            total += r
                            if (total > maxTotalBytes) throw ToolException("This archive expands to more than ${maxTotalBytes shr 30} GB. Forge stopped to protect your storage.")
                            o.write(buf, 0, r)
                        }
                    }
                }
                done++
            }
        }
        return done to skipped
    }
}
