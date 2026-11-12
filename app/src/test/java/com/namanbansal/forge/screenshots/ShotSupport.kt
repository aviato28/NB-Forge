package com.namanbansal.forge.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.namanbansal.forge.data.ThemeMode
import com.namanbansal.forge.ui.theme.ForgeTheme

/**
 * Screenshot recording (Roborazzi). Images are only written when recording is on:
 *   ./gradlew recordRoborazziDebug --tests '*ScreenshotTest'
 * A plain `testDebugUnitTest` still renders every screen but writes nothing.
 * Phone size 411x891dp at xxhdpi, saved at half scale to keep the PNGs small.
 */
const val PHONE_QUALIFIERS = "w411dp-h891dp-xxhdpi"

private val options = RoborazziOptions(
    recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5),
)

private fun path(name: String, dark: Boolean) = "../docs/screenshots/$name-${if (dark) "dark" else "light"}.png"

/** Renders [content] in light then dark (the theme flips in place) and captures `docs/screenshots/<name>-light|dark.png`. [settle] runs after each flip. */
fun ComposeContentTestRule.shootBoth(name: String, settle: () -> Unit = {}, content: @Composable () -> Unit) {
    var dark by mutableStateOf(false)
    setContent { ShotFrame(dark, content) }
    for (d in listOf(false, true)) {
        dark = d
        waitForIdle()
        settle()
        onRoot().captureRoboImage(path(name, d), roborazziOptions = options)
    }
}

/** Captures the current content as `docs/screenshots/<name>.png` (for screens that need interaction first). */
fun ComposeContentTestRule.captureShot(name: String) {
    onRoot().captureRoboImage("../docs/screenshots/$name.png", roborazziOptions = options)
}

/** Sets [content] in a fixed theme without capturing; follow with [captureShot]. */
fun ComposeContentTestRule.setThemed(dark: Boolean, content: @Composable () -> Unit) = setContent { ShotFrame(dark, content) }

/**
 * Captures a dialog window (Roborazzi cannot grab the scrim behind it). One theme per call: flipping the theme in place
 * does not reach the dialog window, so light and dark are separate tests.
 */
fun ComposeContentTestRule.shootDialog(name: String, dark: Boolean, content: @Composable () -> Unit) {
    var shown by mutableStateOf(false) // a dialog present in the very first composition never lets Compose go idle under Robolectric
    setContent { ShotFrame(dark) { if (shown) content() } }
    shown = true
    waitForIdle()
    mainClock.autoAdvance = false // dialogs may keep an animation running, so the clock never goes idle on its own
    mainClock.advanceTimeBy(600)
    onNode(isDialog()).captureRoboImage(path(name, dark), roborazziOptions = options)
}

@Composable
private fun ShotFrame(dark: Boolean, content: @Composable () -> Unit) {
    ForgeTheme(mode = if (dark) ThemeMode.Dark else ThemeMode.Light) {
        Box(Modifier.fillMaxSize().background(ForgeTheme.colors.bg)) { content() }
    }
}

/** Spins the main looper + compose until [until] holds (view-model work runs on background dispatchers). */
fun ComposeContentTestRule.pumpUntil(timeoutMs: Long = 20_000, until: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!until() && System.currentTimeMillis() < deadline) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        waitForIdle()
        Thread.sleep(25)
    }
    org.junit.Assert.assertTrue("condition not reached in time", until())
    waitForIdle()
}

/** Like [setThemed] but returns a switch for the theme, so one test can capture light then dark with [captureBoth]. */
fun ComposeContentTestRule.setFlippable(content: @Composable () -> Unit): (Boolean) -> Unit {
    var dark by mutableStateOf(false)
    setContent { ShotFrame(dark, content) }
    return { dark = it; waitForIdle() }
}

/** Captures `docs/screenshots/<name>-light.png`, flips to dark and captures `<name>-dark.png`. */
fun ComposeContentTestRule.captureBoth(name: String, flip: (Boolean) -> Unit) {
    captureShot("$name-light"); flip(true); captureShot("$name-dark"); flip(false)
}
