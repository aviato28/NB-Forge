package com.namanbansal.forge.core

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class FileActionsTest {
    private val uri = "content://media/external/downloads/7".toUri()

    @Test fun shareIntentGrantsReadAndCarriesStream() {
        val chooser = FileActions.shareIntent(uri, "application/pdf", "a.pdf")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/pdf", send.type)
        assertEquals(uri, send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test fun shareMultipleSendsEveryUriWithReadGrant() {
        val uris = (1..3).map { "content://media/external/downloads/$it".toUri() }
        val chooser = FileActions.shareMultipleIntent(uris, "application/pdf", "3 files")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
        assertEquals("application/pdf", send.type)
        assertEquals(uris, send.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(3, send.clipData!!.itemCount) // the grant needs every uri in the ClipData
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        assertFalse(FileActions.shareMultiple(app, emptyList(), "application/pdf", "none"))
    }

    @Test fun openWithNoHandlerReturnsFalseInsteadOfCrashing() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val noApps = object : android.content.ContextWrapper(app) {
            override fun startActivity(intent: Intent?) = throw android.content.ActivityNotFoundException("none")
        }
        assertFalse(FileActions.open(noApps, uri, "application/x-nothing-opens-this"))
        assertFalse(FileActions.share(noApps, uri, "application/pdf", "a.pdf"))
    }

    @Test fun openWithHandlerLaunches() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val intent = FileActions.viewIntent(uri, "application/pdf")
        shadowOf(ctx.packageManager).addResolveInfoForIntent(intent, android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply { packageName = "viewer"; name = "V" }
        })
        assertTrue(FileActions.open(ctx, uri, "application/pdf"))
    }

    @Test fun incomingSendViewAndMultiple() {
        val a = "content://x/a.pdf".toUri()
        val b = "content://x/b.png".toUri()
        assertEquals(listOf(a), IncomingIntents.uris(Intent(Intent.ACTION_VIEW, a)))
        assertEquals(listOf(a), IncomingIntents.uris(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, a)))
        assertEquals(listOf(a, b), IncomingIntents.uris(Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(a, b))))
        assertTrue(IncomingIntents.uris(Intent(Intent.ACTION_MAIN)).isEmpty())
        assertTrue(IncomingIntents.uris(Intent(Intent.ACTION_SEND)).isEmpty())
        assertTrue(IncomingIntents.uris(Intent(Intent.ACTION_VIEW, "https://evil/x.pdf".toUri())).isEmpty())
        assertTrue(IncomingIntents.uris(null).isEmpty())
    }

    @Test fun incomingFilterKeepsOnlyPdfAndImages() {
        val files = listOf(
            PickedFile(uri, "a.pdf", 1, "application/pdf"),
            PickedFile(uri, "b.png", 1, "image/png"),
            PickedFile(uri, "c.mp4", 1, "video/mp4"),
            PickedFile(uri, "d.bin", 1, null),
        )
        assertEquals(listOf("a.pdf", "b.png"), IncomingIntents.accept(files).map { it.name })
    }
}
