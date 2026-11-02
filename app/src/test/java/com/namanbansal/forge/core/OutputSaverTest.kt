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
}
