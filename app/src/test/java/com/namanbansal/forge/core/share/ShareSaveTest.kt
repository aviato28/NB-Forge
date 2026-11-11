package com.namanbansal.forge.core.share

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.FakeMediaProvider
import com.namanbansal.forge.core.FileActions
import com.namanbansal.forge.core.OutputNaming
import com.namanbansal.forge.core.OutputSaver
import com.namanbansal.forge.core.PdfBoxRasterizer
import com.namanbansal.forge.core.PdfFixtures
import com.namanbansal.forge.core.pdf.PageRasterizers
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ShareSaveTest {
    @get:Rule val tmp = TemporaryFolder()
    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private lateinit var provider: FakeMediaProvider
    private lateinit var saver: OutputSaver
    private val originalFactory = PageRasterizers.factory

    @Before fun setUp() {
        FileProviderReset.clear()
        provider = Robolectric.setupContentProvider(FakeMediaProvider::class.java, "media")
        provider.dir = tmp.newFolder()
        saver = OutputSaver(app)
        PageRasterizers.factory = { PdfBoxRasterizer(it) }
    }

    @After fun tearDown() { PageRasterizers.factory = originalFactory }

    // ---- temporary-file detection -------------------------------------------------------------------------------------

    @Test fun contentFromAnotherAppsFileProviderIsTemporary() {
        val gmail = FileOrigins.classify("content://com.google.android.gm.fileprovider/attachments/1".toUri())
        assertTrue(gmail.temporary); assertEquals("Gmail", gmail.from)
        assertTrue(FileOrigins.classify("content://in.amazon.mShop.android.shopping.fileprovider/docs/invoice.pdf".toUri()).temporary)
        assertTrue(FileOrigins.classify("content://com.mybank.app.provider/statement".toUri(), viaView = true).temporary)
    }

    @Test fun mediaStoreDownloadsAndFilesAppAreSaved() {
        assertFalse(FileOrigins.classify("content://media/external/downloads/42".toUri()).temporary)
        assertFalse(FileOrigins.classify("content://com.android.externalstorage.documents/document/primary%3ADocuments%2Fa.pdf".toUri()).temporary)
        assertFalse(FileOrigins.classify("content://com.android.providers.downloads.documents/document/7".toUri()).temporary)
        assertFalse(FileOrigins.classify("content://com.android.providers.media.documents/document/pdf%3A1".toUri()).temporary)
    }

    @Test fun pickedDocumentWithLastingGrantIsSavedButViewHandOverIsNot() {
        val drive = "content://com.example.cloud.documents/document/9".toUri()
        assertTrue("no lasting grant: temporary", FileOrigins.classify(drive).temporary)
        assertFalse("picked in the system picker with a lasting grant", FileOrigins.classify(drive, hasPersistedPermission = true).temporary)
        assertTrue("same uri handed over by ACTION_VIEW", FileOrigins.classify(drive, hasPersistedPermission = true, viaView = true).temporary)
    }

    @Test fun fileUrisAreTemporaryOnlyInPrivateStorage() {
        assertFalse(FileOrigins.classify("file:///storage/emulated/0/Download/a.pdf".toUri()).temporary)
        assertTrue(FileOrigins.classify("file:///data/user/0/com.namanbansal.forge/files/kept/a.pdf".toUri()).temporary)
    }

    // ---- save / rename / dedupe ---------------------------------------------------------------------------------------

    @Test fun keepNameDedupesWithoutActionSuffix() {
        val taken = setOf("invoice.pdf", "invoice (1).pdf")
        assertEquals("invoice (2).pdf", OutputNaming.keep("invoice.pdf") { it in taken })
        assertEquals("a_b.pdf", OutputNaming.keep("a/b.pdf") { false })
        assertEquals("noext", OutputNaming.keep("noext") { false })
    }

    @Test fun renameKeepsPdfExtensionAndRejectsEmpty() {
        assertEquals("Tax 2025.pdf", OutputNaming.renamed("Tax 2025"))
        assertEquals("Tax 2025.pdf", OutputNaming.renamed("  Tax 2025.pdf "))
        assertEquals("a_b.pdf", OutputNaming.renamed("a/b"))
        assertNull(OutputNaming.renamed("   "))
    }

    @Test fun saveToPhoneKeepsTheRealNameAndDedupes() = runTest {
        val a = saver.saveKeepingName("Amazon invoice.pdf", "application/pdf") { it.write("ONE".toByteArray()) }
        val b = saver.saveKeepingName("Amazon invoice.pdf", "application/pdf") { it.write("TWO".toByteArray()) }
        assertEquals("Amazon invoice.pdf", a.displayName)
        assertEquals("Amazon invoice (1).pdf", b.displayName)
        assertEquals("Download/Forge/", provider.rows.values.first().values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals(3L, a.size)
    }

    @Test fun saveFromUriCopiesBytes() = runTest {
        val src = File(tmp.root, "src.pdf").also { it.writeBytes(ByteArray(5000) { i -> i.toByte() }) }
        val out = saver.saveFromUri(Uri.fromFile(src), "bank.pdf", "application/pdf")
        assertEquals("bank.pdf", out.displayName)
        assertEquals(5000L, out.size)
    }

    @Test fun renameChangesDisplayNameAndReportsFailure() = runTest {
        val a = saver.saveKeepingName("old.pdf", "application/pdf") { it.write(1) }
        assertEquals("new.pdf", saver.rename(a.uri, "new"))
        assertEquals("new.pdf", provider.rows.values.single().values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertNull(saver.rename(a.uri, "  "))
    }

    @Test fun deleteFileRemovesItAndSaysSoWhenGone() = runTest {
        val a = saver.saveKeepingName("x.pdf", "application/pdf") { it.write(1) }
        assertTrue(saver.deleteFile(a.uri))
        assertTrue(provider.rows.isEmpty())
        assertFalse("already gone", saver.deleteFile(a.uri))
    }

    // ---- share / open-with intents ------------------------------------------------------------------------------------

    @Test fun stagedFileKeepsItsRealNameBehindFileProviderWithGrant() = runTest {
        val src = PdfFixtures.create(File(tmp.root, "render-3f2a.pdf"), 2)
        val uri = ShareStaging.stage(app, src, "Amazon invoice.pdf")
        assertEquals("Amazon invoice.pdf", uri.lastPathSegment)
        assertEquals("${app.packageName}.files", uri.authority)
        val send = FileActions.shareIntent(uri, "application/pdf", "Amazon invoice.pdf").getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(uri, send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
    }

    @Test fun stagingSlotsNeverClashForEqualNames() {
        val a = ShareStaging.target(tmp.root, "a.pdf", 1)
        val b = ShareStaging.target(tmp.root, "a.pdf", 2)
        assertEquals("a.pdf", a.name); assertEquals("a.pdf", b.name)
        assertTrue(a.parentFile != b.parentFile)
    }

    @Test fun openWithIsAChooserThatExcludesForgeAndGrantsRead() {
        val uri = "content://media/external/downloads/7".toUri()
        val me = ComponentName("com.namanbansal.forge", "com.namanbansal.forge.MainActivity")
        val chooser = FileActions.openWithIntent(uri, "application/pdf", me)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val view = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals(uri, view.data)
        assertTrue(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        @Suppress("DEPRECATION") val excluded = chooser.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS)!!
        assertEquals(me, excluded.single())
    }

    // ---- print adapter ------------------------------------------------------------------------------------------------

    @Test fun printAdapterStreamsTheWholeFile() {
        val pdf = PdfFixtures.create(File(tmp.root, "p.pdf"), 3)
        val adapter = PdfPrintAdapter(pdf, "p.pdf", 3)
        var info: PrintDocumentInfo? = null
        adapter.onLayout(null, PrintAttributes.Builder().build(), null, android.print.PrintCallbacks.layout { i, _ -> info = i }, null)
        assertEquals(3, info!!.pageCount)
        assertEquals("p.pdf", info!!.name)
        val dest = File(tmp.root, "printed.pdf")
        var finished = false
        ParcelFileDescriptor.open(dest, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE).use { fd ->
            adapter.onWrite(arrayOf(PageRange.ALL_PAGES), fd, null, android.print.PrintCallbacks.write(onFinished = { finished = true }))
        }
        assertTrue(finished)
        assertArrayEquals(pdf.readBytes(), dest.readBytes())
    }

    @Test fun printAdapterReportsAMissingFile() {
        var failed: CharSequence? = null
        val fd = ParcelFileDescriptor.open(tmp.newFile(), ParcelFileDescriptor.MODE_READ_WRITE)
        PdfPrintAdapter(File(tmp.root, "nope.pdf"), "x").onWrite(null, fd, null, android.print.PrintCallbacks.write(onFailed = { failed = it }))
        fd.close()
        assertNotNull(failed)
    }

    // ---- file info ----------------------------------------------------------------------------------------------------

    @Test fun infoReadsTitleAuthorProducerAndEncryption() {
        val f = PdfFixtures.create(File(tmp.root, "i.pdf"), 4)
        PDDocument.load(f).use { d ->
            d.documentInformation.title = "Order 123"; d.documentInformation.author = "Amazon"; d.documentInformation.producer = "Skia/PDF"
            d.save(f)
        }
        val info = PdfInfo.read(f, wasEncrypted = false)
        assertEquals(4, info.pages); assertEquals("Order 123", info.title); assertEquals("Amazon", info.author); assertEquals("Skia/PDF", info.producer)
        assertFalse(info.encrypted)
        assertTrue(PdfInfo.read(f, wasEncrypted = true).encrypted)
        val rows = FileInfoRows.build("i.pdf", 2048, FileOrigins.classify("content://com.google.android.gm.fileprovider/x".toUri()), FileInfoRows.savedWhere(FileOrigin(true, "Gmail", "")), info)
        val byLabel = rows.associate { it.label to it.value }
        assertEquals("Gmail", byLabel["Came from"]); assertEquals("Not saved on your phone", byLabel["Saved"])
        assertEquals("4", byLabel["Pages"]); assertEquals("Skia/PDF", byLabel["Made with"]); assertEquals("No", byLabel["Password"])
    }

    @Test fun infoOfACorruptFileDoesNotThrow() {
        val info = PdfInfo.read(PdfFixtures.corrupt(File(tmp.root, "c.pdf")), wasEncrypted = false)
        assertEquals(0, info.pages)
        val rows = FileInfoRows.build("c.pdf", -1, FileOrigin(false, "This phone", "On this phone"), "On this phone", info)
        assertTrue(rows.none { it.label == "Pages" })
    }

    // ---- sharing pages ------------------------------------------------------------------------------------------------

    @Test fun selectedPagesBecomeAPdfWithOnlyThosePages() {
        val src = PdfFixtures.withText(File(tmp.root, "s.pdf"), 6, body = { listOf("page text $it") })
        val out = File(tmp.root, "out/s_pages.pdf")
        PageSharer.pagesPdf(src, listOf(1, 3, 4), out)
        PDDocument.load(out).use { assertEquals(3, it.numberOfPages) }
        assertEquals("s_pages_2_4-5.pdf", PageSharer.pdfName("s.pdf", listOf(1, 3, 4)))
        assertEquals("s_page_3.pdf", PageSharer.pdfName("s.pdf", listOf(2)))
    }

    @Test fun pageAsImageIsAPngOfTheRightShape() {
        val src = PdfFixtures.withText(File(tmp.root, "s.pdf"), 2, body = { listOf("hello") })
        val out = File(tmp.root, "img.png")
        PageSharer.pageImage(src, 0, out, widthPx = 400)
        val bmp = android.graphics.BitmapFactory.decodeFile(out.path)
        assertEquals(400, bmp.width)
        val box = PDDocument.load(src).use { it.getPage(0).cropBox }
        assertEquals(400f * box.height.toInt() / box.width.toInt(), bmp.height.toFloat(), 1f) // keeps the page's shape
        assertEquals("s_page_2.png", PageSharer.imageName("s.pdf", 1))
    }

    @Test fun sharingNoPagesIsRefusedInPlainWords() {
        val src = PdfFixtures.create(File(tmp.root, "s.pdf"), 2)
        val e = runCatching { PageSharer.pagesPdf(src, emptyList(), File(tmp.root, "x.pdf")) }.exceptionOrNull()
        assertTrue(e is com.namanbansal.forge.core.ToolException)
    }

    // ---- kept copies --------------------------------------------------------------------------------------------------

    @Test fun keptCopiesRespectTheCapAndDropTheOldestFirst() {
        val kept = KeptCopies(File(tmp.root, "kept"), capBytes = 2500, maxFileBytes = 2000)
        fun src(n: String, size: Int) = File(tmp.root, n).also { it.writeBytes(ByteArray(size)) }
        assertTrue(kept.keep("a", src("a.pdf", 1000))); kept.fileFor("a").setLastModified(1_000)
        assertTrue(kept.keep("b", src("b.pdf", 1000))); kept.fileFor("b").setLastModified(2_000)
        assertTrue(kept.keep("c", src("c.pdf", 1000)))
        assertFalse("oldest was dropped", kept.has("a"))
        assertTrue(kept.has("b")); assertTrue(kept.has("c"))
        assertTrue(kept.totalBytes() <= 2500)
        assertFalse("bigger than the per-file cap", kept.keep("big", src("big.pdf", 5000)))
        assertFalse(kept.has("big"))
    }
}
