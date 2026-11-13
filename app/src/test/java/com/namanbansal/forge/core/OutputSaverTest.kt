package com.namanbansal.forge.core

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import java.io.File
import java.io.IOException

/** Minimal in-memory stand-in for the MediaStore "media" provider (Downloads collection). */
class FakeMediaProvider : ContentProvider() {
    class Row(val values: ContentValues, val file: File)
    val rows = LinkedHashMap<Long, Row>()
    private var next = 1L
    lateinit var dir: File

    override fun onCreate() = true

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val id = next++
        rows[id] = Row(ContentValues(values), File(dir, "row$id"))
        return Uri.withAppendedPath(uri, id.toString())
    }

    private fun idOf(uri: Uri) = uri.lastPathSegment!!.toLong()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        rows[idOf(uri)]?.values?.putAll(values!!) ?: return 0
        return 1
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        if (rows.remove(idOf(uri)) != null) 1 else 0

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val c = MatrixCursor(arrayOf(MediaStore.MediaColumns._ID))
        rows.forEach { (id, r) ->
            if (selectionArgs != null && r.values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME) == selectionArgs[0] &&
                r.values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH) == selectionArgs[1]
            ) c.addRow(arrayOf(id))
        }
        return c
    }

    override fun getType(uri: Uri): String? = null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val row = rows[idOf(uri)] ?: throw java.io.FileNotFoundException("gone")
        val writing = mode.contains('w')
        return ParcelFileDescriptor.open(
            row.file,
            if (writing) ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE else ParcelFileDescriptor.MODE_READ_ONLY,
        )
    }
}

@RunWith(RobolectricTestRunner::class)
class OutputSaverTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var provider: FakeMediaProvider
    private lateinit var saver: OutputSaver

    @Before fun setUp() {
        provider = Robolectric.setupContentProvider(FakeMediaProvider::class.java, "media")
        provider.dir = tmp.newFolder()
        saver = OutputSaver(ApplicationProvider.getApplicationContext())
    }

    @Test fun savesIntoDownloadsForgeAndFinalisesPending() = runTest {
        val out = saver.save("invoice.pdf", "rotated", "pdf", "application/pdf") { it.write("PDF-DATA".toByteArray()) }
        assertEquals("invoice_rotated.pdf", out.displayName)
        assertEquals(8L, out.size)
        val row = provider.rows.values.single().values
        assertEquals("Download/Forge/", row.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals("application/pdf", row.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals(0, row.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertTrue(saver.isReadable(out.uri))
    }

    @Test fun secondSaveGetsDedupedNameAndFirstIsUntouched() = runTest {
        val a = saver.save("invoice.pdf", "rotated", "pdf", "application/pdf") { it.write(1) }
        val b = saver.save("invoice.pdf", "rotated", "pdf", "application/pdf") { it.write(2) }
        val c = saver.save("invoice.pdf", "rotated", "pdf", "application/pdf") { it.write(3) }
        assertEquals(listOf("invoice_rotated.pdf", "invoice_rotated (1).pdf", "invoice_rotated (2).pdf"), listOf(a, b, c).map { it.displayName })
        assertEquals(3, provider.rows.size)
    }

    @Test fun failedWriteLeavesNoFileBehind() = runTest {
        try {
            saver.save("x.pdf", "rotated", "pdf", "application/pdf") { it.write(1); throw IOException("disk full") }
            fail()
        } catch (_: IOException) {
        }
        assertTrue("half-written entry must be deleted", provider.rows.isEmpty())
    }

    @Test fun missingFileIsNotReadable() = runTest {
        val out = saver.save("x.pdf", "rotated", "pdf", "application/pdf") { it.write(1) }
        provider.rows.clear()
        assertFalse(saver.isReadable(out.uri))
    }

    @Test fun videoGoesToMoviesForge() = runTest {
        val out = saver.save("clip.mp4", "trimmed", "mp4", "video/mp4", target = MediaTarget.VIDEO) { it.write(byteArrayOf(1, 2, 3)) }
        assertEquals("clip_trimmed.mp4", out.displayName)
        assertEquals("Movies/Forge", out.folder)
        val row = provider.rows.values.single().values
        assertEquals("Movies/Forge/", row.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals("video/mp4", row.getAsString(MediaStore.MediaColumns.MIME_TYPE))
    }

    @Test fun audioGoesToMusicForgeAndDedupesPerFolder() = runTest {
        val a = saver.save("song.mp4", "audio", "m4a", "audio/mp4", target = MediaTarget.AUDIO) { it.write(1) }
        val b = saver.save("song.mp4", "audio", "m4a", "audio/mp4", target = MediaTarget.AUDIO) { it.write(1) }
        // Same name in another folder is not a clash.
        val c = saver.save("song.mp4", "audio", "m4a", "audio/mp4") { it.write(1) }
        assertEquals(listOf("song_audio.m4a", "song_audio (1).m4a", "song_audio.m4a"), listOf(a, b, c).map { it.displayName })
        assertEquals("Music/Forge", a.folder)
        assertEquals("Downloads/Forge", c.folder)
    }

    @Test fun imagesFlagStillMapsToPictures() = runTest {
        val out = saver.save("a.png", "x", "png", "image/png", images = true) { it.write(1) }
        assertEquals("Pictures/Forge", out.folder)
        assertEquals("Pictures/Forge/", provider.rows.values.single().values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
    }

    @Test fun mediaTargetCollections() {
        assertEquals(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaTarget.VIDEO.collection)
        assertEquals(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, MediaTarget.AUDIO.collection)
        assertEquals(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaTarget.IMAGES.collection)
        assertEquals(MediaStore.Downloads.EXTERNAL_CONTENT_URI, MediaTarget.DOWNLOADS.collection)
    }
}
