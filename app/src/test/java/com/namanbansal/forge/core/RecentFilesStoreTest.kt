package com.namanbansal.forge.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecentFilesStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun entry(n: Int, uri: String = "content://x/$n") = RecentEntry("f$n.pdf", "P4", 100L * n, 90L * n, n.toLong(), uri, "application/pdf")

    @Test fun newestFirstAndPersists() = runTest {
        val file = File(tmp.root, "recent.json")
        val a = RecentFilesStore(file).also { it.load() }
        a.add(entry(1)); a.add(entry(2)); a.add(entry(3))
        assertEquals(listOf("f3.pdf", "f2.pdf", "f1.pdf"), a.entries.value.map { it.name })

        val b = RecentFilesStore(file).also { it.load() } // simulates app restart
        assertEquals(a.entries.value, b.entries.value)
    }

    @Test fun sameUriReplacesOlderEntry() = runTest {
        val s = RecentFilesStore(File(tmp.root, "r.json")).also { it.load() }
        s.add(entry(1, "content://same")); s.add(entry(2, "content://same"))
        assertEquals(listOf("f2.pdf"), s.entries.value.map { it.name })
    }

    @Test fun capsAtMax() = runTest {
        val s = RecentFilesStore(File(tmp.root, "r.json"), max = 3).also { it.load() }
        (1..10).forEach { s.add(entry(it)) }
        assertEquals(listOf("f10.pdf", "f9.pdf", "f8.pdf"), s.entries.value.map { it.name })
    }

    @Test fun removeAndClear() = runTest {
        val file = File(tmp.root, "r.json")
        val s = RecentFilesStore(file).also { it.load() }
        s.add(entry(1)); s.add(entry(2))
        s.remove("content://x/1")
        assertEquals(listOf("f2.pdf"), RecentFilesStore(file).also { it.load() }.entries.value.map { it.name })
        s.clear()
        assertEquals(emptyList<RecentEntry>(), RecentFilesStore(file).also { it.load() }.entries.value)
    }

    @Test fun corruptFileStartsEmptyAndRecovers() = runTest {
        val file = File(tmp.root, "r.json").apply { writeText("{not json") }
        val s = RecentFilesStore(file).also { it.load() }
        assertEquals(0, s.entries.value.size)
        s.add(entry(1))
        assertEquals(1, RecentFilesStore(file).also { it.load() }.entries.value.size)
    }

    @Test fun ignoresUnknownFieldsFromNewerVersions() = runTest {
        val file = File(tmp.root, "r.json").apply {
            writeText("""{"version":2,"future":true,"entries":[{"name":"a.pdf","toolCode":"P4","beforeSize":1,"afterSize":1,"timestamp":1,"uri":"content://a","mime":"application/pdf","extra":5}]}""")
        }
        val s = RecentFilesStore(file).also { it.load() }
        assertEquals(listOf("a.pdf"), s.entries.value.map { it.name })
    }
}
