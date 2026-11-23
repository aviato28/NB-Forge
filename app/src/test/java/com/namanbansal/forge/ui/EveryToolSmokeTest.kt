package com.namanbansal.forge.ui

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.core.ImageFixtures
import com.namanbansal.forge.core.PdfBoxRasterizer
import com.namanbansal.forge.core.PdfFixtures
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.ForgeApp
import com.namanbansal.forge.tools.ToolRegistry
import com.namanbansal.forge.tools.ToolStatus
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.toolkit.LocalForgeSnackbar
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Opens EVERY available tool through the app's real tool route ([ToolDestination] in a NavHost), so the screen's own default
 * `viewModel()` creation runs exactly as on a phone: no injected view models, sensors, cameras or repositories.
 * Asserts the first frame renders, nothing throws (also on background threads), and that a recreate (rotation) survives.
 * Tools that take a file are opened again with a real fixture (and a corrupt one) handed over like the share sheet does.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EveryToolSmokeTest(private val code: String) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun codes(): List<String> = ToolRegistry.all.filter { it.status == ToolStatus.Available }.map { it.code }
    }

    @Before fun init() {
        PDFBoxResourceLoader.init(app); PdfBoxRasterizer.install()
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        // Permissions granted: the denied paths are covered by the permission tests; here the real tool UI must come up.
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    @After fun done() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        PdfBoxRasterizer.uninstall()
    }

    private val content: @Composable () -> Unit = {
        val nav = rememberNavController()
        val snackbar = androidx.compose.runtime.remember { SnackbarHostState() }
        ForgeTheme {
            CompositionLocalProvider(LocalForgeSnackbar provides snackbar) {
                NavHost(nav, startDestination = Routes.tool(code)) {
                    composable(Routes.TOOL, arguments = listOf(androidx.navigation.navArgument("code") { type = androidx.navigation.NavType.StringType })) { e ->
                        ToolDestination(e.arguments?.getString("code").orEmpty(), emptyList(), { nav.popBackStack() }, { _, _ -> }, {})
                    }
                }
            }
        }
    }

    private fun settle(ms: Long = 1500) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); compose.waitForIdle(); Thread.sleep(25)
        }
        compose.waitForIdle()
    }

    private fun assertFirstFrame() {
        assertTrue("$code: nothing was rendered", compose.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes().isNotEmpty())
        assertTrue("$code showed the Coming soon screen", compose.onAllNodes(hasText("Coming soon", substring = true, ignoreCase = true)).fetchSemanticsNodes().isEmpty())
        assertTrue("$code: uncaught exception: ${uncaught.firstOrNull()?.stackTraceToString()}", uncaught.isEmpty())
    }

    private fun recreate() {
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { it.setContent(content = content) }
        settle(500)
    }

    @Test fun opensAndSurvivesRecreate() {
        compose.setContent(content)
        settle(); assertFirstFrame()
        recreate(); assertFirstFrame()
    }

    private fun fixtures(): Map<String, PickedFile> {
        val tool = ToolRegistry.get(code)!!
        val out = LinkedHashMap<String, PickedFile>()
        fun pf(f: File, mime: String) = PickedFile(Uri.fromFile(f), f.name, f.length(), mime)
        if (FileKind.PDF in tool.accepts) {
            out["pdf"] = pf(PdfFixtures.create(File(tmp.root, "doc.pdf"), pages = 3), "application/pdf")
            out["corrupt pdf"] = pf(PdfFixtures.corrupt(File(tmp.root, "broken.pdf").also { PdfFixtures.create(it, pages = 2) }), "application/pdf")
        }
        if (FileKind.IMAGE in tool.accepts) {
            val f = File(tmp.root, "pic.jpg")
            f.outputStream().use { ImageFixtures.photo(120, 90).compress(Bitmap.CompressFormat.JPEG, 90, it) }
            out["image"] = pf(f, "image/jpeg")
            out["corrupt image"] = pf(File(tmp.root, "bad.jpg").also { it.writeBytes(ByteArray(64) { b -> b.toByte() }) }, "image/jpeg")
        }
        if (FileKind.VIDEO in tool.accepts || FileKind.AUDIO in tool.accepts) {
            // No real codec fixtures are cheap under Robolectric; a corrupt media file must fail gracefully, not crash.
            out["corrupt video"] = pf(File(tmp.root, "bad.mp4").also { it.writeBytes(ByteArray(256) { b -> b.toByte() }) }, "video/mp4")
        }
        if (FileKind.ARCHIVE in tool.accepts && out.isEmpty()) {
            out["text"] = pf(File(tmp.root, "a.txt").also { it.writeText("hello") }, "text/plain")
        }
        return out
    }

    @Test fun opensWithPickedFiles() {
        val cases = fixtures()
        if (cases.isEmpty()) return
        for ((label, file) in cases) {
            (app as ForgeApp).pendingFiles.put(code, listOf(file))
            if (label == "pdf" || label == "image") (app as ForgeApp).pendingFiles.put(code, listOf(file, file))
            compose.activityRule.scenario.onActivity { it.setContent(content = content) }
            settle(2500)
            assertTrue("$code with $label: uncaught exception: ${uncaught.firstOrNull()?.stackTraceToString()}", uncaught.isEmpty())
            assertFirstFrame()
            recreate()
            assertTrue("$code with $label after recreate: ${uncaught.firstOrNull()?.stackTraceToString()}", uncaught.isEmpty())
        }
    }
}
