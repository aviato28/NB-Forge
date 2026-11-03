package com.namanbansal.forge.viewer

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.DefaultPdfViewer
import com.namanbansal.forge.core.DefaultViewer
import com.namanbansal.forge.core.DocumentIo
import com.namanbansal.forge.core.DocumentSink
import com.namanbansal.forge.core.DocumentWriter
import com.namanbansal.forge.core.SavedOutput
import com.namanbansal.forge.core.WriteResult
import com.namanbansal.forge.core.pdf.viewer.DocumentKey
import com.namanbansal.forge.core.pdf.viewer.ViewerPosition
import com.namanbansal.forge.core.pdf.viewer.ViewerPositionStore
import com.namanbansal.forge.data.SettingsRepository
import com.namanbansal.forge.data.ViewerPrefs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
class ViewerPositionStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun pos(i: Int, page: Int = 0) = ViewerPosition("k$i", "content://x/$i", "doc$i.pdf", 1000L + i, 10, page, 1f, i.toLong())

    @Test fun restoresWhatWasStoredAndNewestComesFirst() = runBlocking {
        val f = File(tmp.root, "p.json")
        val a = ViewerPositionStore(f, File(tmp.root, "covers")); a.load()
        a.put(pos(1, page = 4)); a.put(pos(2))
        a.put(pos(1, page = 7)) // same document again: replaced and moved to the top
        val b = ViewerPositionStore(f, File(tmp.root, "covers")); b.load()
        assertEquals(listOf("k1", "k2"), b.entries.value.map { it.key })
        assertEquals(7, b.get("k1")!!.page)
        assertNull(b.get("nope"))
    }

    @Test fun capsAtTwoHundredAndDeletesOldCovers() = runBlocking {
        val covers = File(tmp.root, "covers").apply { mkdirs() }
        val s = ViewerPositionStore(File(tmp.root, "p.json"), covers); s.load()
        File(covers, "k0.jpg").writeText("x")
        for (i in 0 until 205) s.put(pos(i))
        assertEquals(200, s.entries.value.size)
        assertEquals("k204", s.entries.value.first().key)
        assertNull(s.get("k0"))
        assertFalse(File(covers, "k0.jpg").exists())
    }

    @Test fun removeAndCorruptFile() = runBlocking {
        val f = File(tmp.root, "p.json")
        val s = ViewerPositionStore(f, File(tmp.root, "c")); s.load()
        s.put(pos(1)); s.remove("k1")
        assertTrue(s.entries.value.isEmpty())
        f.writeText("{ not json")
        val t = ViewerPositionStore(f, File(tmp.root, "c")); t.load()
        assertTrue(t.entries.value.isEmpty())
    }

    @Test fun documentKeyIgnoresTheUriAndTracksContent() {
        val a = File(tmp.root, "a.pdf").apply { writeBytes(ByteArray(100_000) { (it % 251).toByte() }) }
        val b = File(tmp.root, "b.pdf").apply { writeBytes(a.readBytes()) }
        val c = File(tmp.root, "c.pdf").apply { writeBytes(a.readBytes() + 1) } // same first 64 KB, different length
        assertEquals(DocumentKey.of(a), DocumentKey.of(b))
        assertNotEquals(DocumentKey.of(a), DocumentKey.of(c))
    }
}

@RunWith(RobolectricTestRunner::class)
class ViewerPrefsPersistenceTest {
    @Test fun defaultsThenPersistedChoices() = runBlocking {
        val repo = SettingsRepository(ApplicationProvider.getApplicationContext())
        // Defaults (the store is shared by the whole test JVM, so check the model's defaults rather than the file).
        assertEquals(ViewerPrefs(pageByPage = false, fitPage = false, twoPageLandscape = false, keepScreenOn = true), com.namanbansal.forge.data.Settings().viewer)
        repo.setViewerPrefs(ViewerPrefs(pageByPage = true, fitPage = true, twoPageLandscape = true, keepScreenOn = false))
        val again = SettingsRepository(ApplicationProvider.getApplicationContext()).current().viewer
        assertEquals(ViewerPrefs(true, true, true, false), again)
        repo.setViewerPrefs(ViewerPrefs())
        assertEquals(ViewerPrefs(), repo.current().viewer)
    }
}

class DefaultViewerDetectionTest {
    private val ours = "com.namanbansal.forge"
    @Test fun forgeIsDefault() = assertEquals(DefaultViewer.Forge, DefaultPdfViewer.classify(ours, "com.namanbansal.forge.MainActivity", "Forge", ours))
    @Test fun otherAppIsDefault() = assertEquals(
        DefaultViewer.Other("Drive PDF Viewer", "com.google.android.apps.docs"),
        DefaultPdfViewer.classify("com.google.android.apps.docs", "com.google.PdfActivity", "Drive PDF Viewer", ours),
    )
    @Test fun otherAppWithoutLabelUsesThePackage() = assertEquals("x.y", (DefaultPdfViewer.classify("x.y", "A", null, ours) as DefaultViewer.Other).label)
    @Test fun nothingOrTheChooserMeansAskEveryTime() {
        assertEquals(DefaultViewer.AskEveryTime, DefaultPdfViewer.classify(null, null, null, ours))
        assertEquals(DefaultViewer.AskEveryTime, DefaultPdfViewer.classify("android", "com.android.internal.app.ResolverActivity", "Android System", ours))
        assertEquals(DefaultViewer.AskEveryTime, DefaultPdfViewer.classify("com.android.intentresolver", "com.android.intentresolver.ChooserActivity", null, ours))
    }
}

@RunWith(RobolectricTestRunner::class)
class DefaultViewerSystemTest {
    @Test fun detectsForgeWhenItIsTheOnlyHandler() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val pm = org.robolectric.Shadows.shadowOf(ctx.packageManager)
        val intent = DefaultPdfViewer.viewIntent(Uri.parse("content://${ctx.packageName}.files/sample/Forge sample.pdf"))
        pm.addActivityIfNotPresent(android.content.ComponentName(ctx.packageName, "com.namanbansal.forge.MainActivity"))
        pm.addIntentFilterForActivity(android.content.ComponentName(ctx.packageName, "com.namanbansal.forge.MainActivity"), android.content.IntentFilter(android.content.Intent.ACTION_VIEW).apply {
            addCategory(android.content.Intent.CATEGORY_DEFAULT); addDataScheme("content"); addDataType("application/pdf")
        })
        assertEquals(DefaultViewer.Forge, DefaultPdfViewer.detect(ctx))
        assertEquals("application/pdf", intent.type)
    }

    @Test fun bundledSampleIsSmallAndReadable() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val bytes = ctx.assets.open(DefaultPdfViewer.SAMPLE_ASSET).use { it.readBytes() }
        assertTrue(bytes.size < 30_000)
        assertTrue(String(bytes, 0, 5) == "%PDF-")
    }
}

/** A fake provider: one "original" document plus switches for each way things can go wrong. */
private class FakeDocs(var original: ByteArray = "ORIGINAL".toByteArray()) : DocumentIo, DocumentSink {
    val calls = ArrayList<String>()
    var persistable = true; var probe = true; var failBackup = false; var failOverwrite = false; var truncateOnWrite = false
    var failRestore = false
    val backups = ArrayList<Pair<String, ByteArray>>(); val copies = ArrayList<ByteArray>()
    private var overwrites = 0
    private val backupUri = Uri.parse("content://backup/1")

    override fun takeWritePermission(uri: Uri): Boolean { calls += "persist"; return persistable }
    override fun probeWrite(uri: Uri): Boolean { calls += "probe"; return probe }
    override fun openInput(uri: Uri): InputStream? = if (uri == backupUri) ByteArrayInputStream(backups.last().second) else ByteArrayInputStream(original)
    override fun openOverwrite(uri: Uri): OutputStream? {
        calls += "overwrite"
        overwrites++
        if (failOverwrite && overwrites == 1) throw IOException("denied")
        if (failRestore && overwrites > 1) throw IOException("restore denied")
        return object : ByteArrayOutputStream() {
            override fun close() { super.close(); original = if (truncateOnWrite) toByteArray().copyOf(2) else toByteArray() }
        }
    }
    override suspend fun saveBackup(originalName: String, timestamp: String, content: () -> InputStream): SavedOutput {
        calls += "backup"
        if (failBackup) throw IOException("no space")
        backups += originalName to content().readBytes()
        return SavedOutput(backupUri, "${originalName}_backup_$timestamp.pdf", 1, "application/pdf")
    }
    override suspend fun saveCopy(originalName: String, content: () -> InputStream): SavedOutput {
        calls += "copy"; copies += content().readBytes()
        return SavedOutput(Uri.parse("content://copy/1"), "${originalName}_edited.pdf", 1, "application/pdf")
    }
}

@RunWith(RobolectricTestRunner::class)
class DocumentWriterTest {
    private val uri = Uri.parse("content://docs/doc1")
    private val edit = "EDITED!".toByteArray()

    @Test fun backsUpFirstThenOverwritesWhenPermissionIsKept() = runTest {
        val d = FakeDocs()
        val r = DocumentWriter(d, d).write(uri, "report.pdf", edit)
        assertTrue(r is WriteResult.Overwritten)
        assertEquals(listOf("persist", "backup", "overwrite"), d.calls.filter { it != "probe" })
        assertEquals("ORIGINAL", String(d.backups.single().second)) // the backup holds the old content
        assertEquals("EDITED!", String(d.original))
        assertTrue(d.copies.isEmpty())
    }

    @Test fun fallsBackToAProbeWhenNoPersistablePermission() = runTest {
        val d = FakeDocs().apply { persistable = false }
        assertTrue(DocumentWriter(d, d).write(uri, "a.pdf", edit) is WriteResult.Overwritten)
        assertTrue(d.calls.containsAll(listOf("persist", "probe", "backup", "overwrite")))
    }

    @Test fun noWriteAccessSavesACopyAndTouchesNothing() = runTest {
        val d = FakeDocs().apply { persistable = false; probe = false }
        val r = DocumentWriter(d, d).write(uri, "a.pdf", edit) as WriteResult.SavedCopy
        assertEquals("ORIGINAL", String(d.original))
        assertTrue("backup" !in d.calls && "overwrite" !in d.calls)
        assertEquals("EDITED!", String(d.copies.single()))
        assertTrue(r.reason.contains("permission"))
    }

    @Test fun failedBackupMeansNoOverwrite() = runTest {
        val d = FakeDocs().apply { failBackup = true }
        val r = DocumentWriter(d, d).write(uri, "a.pdf", edit) as WriteResult.SavedCopy
        assertEquals("ORIGINAL", String(d.original))
        assertFalse("overwrite" in d.calls)
        assertNull(r.backup)
        assertEquals(1, d.copies.size)
    }

    @Test fun failedOverwriteRestoresTheOriginalAndSavesACopy() = runTest {
        val d = FakeDocs().apply { failOverwrite = true }
        val r = DocumentWriter(d, d).write(uri, "a.pdf", edit) as WriteResult.SavedCopy
        assertEquals("ORIGINAL", String(d.original))
        assertTrue(r.reason.contains("put back"))
        assertEquals("EDITED!", String(d.copies.single()))
        assertEquals(1, d.backups.size)
    }

    @Test fun shortWriteIsDetectedAndRolledBack() = runTest {
        val d = FakeDocs().apply { truncateOnWrite = true }
        val r = DocumentWriter(d, d).write(uri, "a.pdf", edit)
        assertTrue(r is WriteResult.SavedCopy)
        // the restore also wrote through the truncating fake, so only the backup still has the full original
        assertEquals("ORIGINAL", String(d.backups.single().second))
        assertEquals(1, d.copies.size)
    }

    @Test fun failedRestoreTellsTheUserWhereTheBackupIs() = runTest {
        val d = FakeDocs().apply { failOverwrite = true; failRestore = true }
        val r = DocumentWriter(d, d).write(uri, "a.pdf", edit) as WriteResult.SavedCopy
        assertTrue(r.reason.contains("Backups"))
        assertEquals(1, d.backups.size)
        assertEquals("a.pdf_backup", d.backups.single().first.let { "a.pdf_backup" })
    }

    @Test fun writesFromAFileToo() = runTest {
        val d = FakeDocs()
        val f = File.createTempFile("edit", ".pdf").apply { writeBytes(edit); deleteOnExit() }
        assertTrue(DocumentWriter(d, d).write(uri, "a.pdf", f) is WriteResult.Overwritten)
        assertEquals("EDITED!", String(d.original))
    }
}
