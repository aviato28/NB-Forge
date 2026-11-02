package com.namanbansal.forge.ui.toolkit

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * Storage Access Framework picker. Returns a launcher function; [onPicked] gets the chosen uris
 * (never called if the user backs out). Single and multi selection share one call site.
 * No persistable permission is taken: tools copy what they need immediately.
 */
@Composable
fun rememberFilePicker(mimes: Array<String>, multiple: Boolean, onPicked: (List<Uri>) -> Unit): () -> Unit {
    val callback = rememberUpdatedState(onPicked)
    return if (multiple) {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { callback.value(it) }
        remember(launcher, mimes.toList()) { { launcher.launch(mimes) } }
    } else {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) callback.value(listOf(uri)) }
        remember(launcher, mimes.toList()) { { launcher.launch(mimes) } }
    }
}
