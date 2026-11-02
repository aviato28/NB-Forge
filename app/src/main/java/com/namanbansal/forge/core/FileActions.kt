package com.namanbansal.forge.core

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Share / Open intents for output files. All launchers return false instead of throwing. */
object FileActions {

    fun shareIntent(uri: Uri, mime: String, name: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, name)
            clipData = ClipData.newRawUri(name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share $name").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun viewIntent(uri: Uri, mime: String): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun share(context: Context, uri: Uri, mime: String, name: String): Boolean =
        launch(context, shareIntent(uri, mime, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    /** False when no installed app can open [mime] (caller shows a friendly message). */
    fun open(context: Context, uri: Uri, mime: String): Boolean = launch(context, viewIntent(uri, mime))

    private fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
