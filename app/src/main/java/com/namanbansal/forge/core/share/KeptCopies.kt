package com.namanbansal.forge.core.share

import java.io.File

/**
 * "Keep a copy" for temporary PDFs: a private copy in app storage so a file that arrived from another app still opens from Recent
 * later. Bounded by [capBytes] in total (oldest copies are dropped first) and by [maxFileBytes] per file.
 */
class KeptCopies(private val dir: File, private val capBytes: Long = DEFAULT_CAP, private val maxFileBytes: Long = DEFAULT_MAX_FILE) {

    fun fileFor(key: String): File = File(dir, key.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(80) + ".pdf")

    fun has(key: String): Boolean = fileFor(key).isFile

    /** True when [source] was stored (or already was); false when it is too big or the copy failed. */
    fun keep(key: String, source: File): Boolean {
        if (source.length() > maxFileBytes) return false
        val dst = fileFor(key)
        if (dst.isFile && dst.length() == source.length()) { dst.setLastModified(System.currentTimeMillis()); return true }
        return try {
            dir.mkdirs()
            val tmp = File(dir, dst.name + ".tmp")
            source.copyTo(tmp, overwrite = true)
            if (!tmp.renameTo(dst)) { dst.delete(); tmp.renameTo(dst) }
            trim()
            dst.isFile
        } catch (_: Exception) { false }
    }

    fun delete(key: String) { fileFor(key).delete() }

    fun totalBytes(): Long = dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    /** Drops the least recently written copies until the total fits the cap. */
    fun trim() {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".pdf") }?.sortedBy { it.lastModified() }.orEmpty().toMutableList()
        var total = files.sumOf { it.length() }
        while (total > capBytes && files.size > 1) { val f = files.removeAt(0); total -= f.length(); f.delete() }
    }

    companion object {
        const val DEFAULT_CAP = 200L shl 20
        const val DEFAULT_MAX_FILE = 50L shl 20
    }
}
