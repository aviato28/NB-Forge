package com.namanbansal.forge.core

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes a ZIP of named streams (Split's optional "also save as ZIP"). Names are de-duplicated inside the archive. */
object ZipWriter {
    fun write(out: OutputStream, entries: List<Pair<String, () -> InputStream?>>, checkCancelled: () -> Unit = {}) {
        val used = HashSet<String>()
        ZipOutputStream(out).use { zip ->
            for ((name, open) in entries) {
                checkCancelled()
                var entryName = name
                var n = 1
                while (!used.add(entryName)) entryName = name.substringBeforeLast('.') + " (${n++})." + name.substringAfterLast('.', "")
                zip.putNextEntry(ZipEntry(entryName))
                (open() ?: throw java.io.FileNotFoundException(name)).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
