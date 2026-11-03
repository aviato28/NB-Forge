package com.namanbansal.forge.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/** Who opens a PDF when the user taps one. */
sealed interface DefaultViewer {
    /** Forge is the default. */
    data object Forge : DefaultViewer
    /** Another app opens PDFs straight away; [label] is its name, [packageName] lets us open its App Info. */
    data class Other(val label: String, val packageName: String) : DefaultViewer
    /** No default: Android asks every time (or nothing can open PDFs). */
    data object AskEveryTime : DefaultViewer
}

/** Detection and helpers for the "Make Forge your PDF viewer" setup. */
object DefaultPdfViewer {
    const val SAMPLE_ASSET = "forge-sample.pdf"
    private const val SAMPLE_NAME = "Forge sample.pdf"
    private val CHOOSER_PACKAGES = setOf("android", "com.android.intentresolver")

    /** Pure decision from the package Android resolves ACTION_VIEW application/pdf to. */
    fun classify(resolvedPackage: String?, resolvedClass: String?, label: String?, ours: String): DefaultViewer = when {
        resolvedPackage == null -> DefaultViewer.AskEveryTime
        resolvedPackage in CHOOSER_PACKAGES || resolvedClass?.contains("Resolver", ignoreCase = true) == true ||
            resolvedClass?.contains("Chooser", ignoreCase = true) == true -> DefaultViewer.AskEveryTime
        resolvedPackage == ours -> DefaultViewer.Forge
        else -> DefaultViewer.Other(label ?: resolvedPackage, resolvedPackage)
    }

    fun viewIntent(uri: Uri): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    fun detect(context: Context): DefaultViewer = try {
        val pm = context.packageManager
        val probe = viewIntent(Uri.parse("content://${context.packageName}.files/sample/$SAMPLE_NAME"))
        val info = pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        val label = info?.packageName?.let { p -> runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrNull() }
        classify(info?.packageName, info?.name, label, context.packageName)
    } catch (_: Exception) {
        DefaultViewer.AskEveryTime
    }

    /** App Info of the app that currently opens PDFs: "Open by default" > "Clear defaults" lives there. */
    fun appInfoIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Copies the bundled sample PDF into the cache and returns a content uri other apps can read. */
    fun sampleUri(context: Context): Uri {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val f = File(dir, SAMPLE_NAME)
        if (!f.exists() || f.length() == 0L) context.assets.open(SAMPLE_ASSET).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", f)
    }

    /** Opens the sample through ACTION_VIEW (no chooser of our own) so Android shows its own chooser if there is no default. */
    fun testIntent(context: Context): Intent = viewIntent(sampleUri(context)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
