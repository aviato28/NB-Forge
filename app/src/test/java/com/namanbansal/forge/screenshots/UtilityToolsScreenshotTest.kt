package com.namanbansal.forge.screenshots

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.util.*
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.tools.ToolRegistry
import com.namanbansal.forge.tools.util.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

/** Utilities rendered with fixed data (smoke test) and captured light + dark when recording. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class UtilityToolsScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private fun host(code: String) = ToolHost(ToolRegistry.get(code)!!, {})

    @Test fun units() {
        compose.shootBoth("util-units") { UnitsScreen(host("U1")) }
        compose.onNodeWithText("Length").assertIsDisplayed(); compose.onNodeWithText("All length units").assertIsDisplayed()
    }

    @Test fun unitsSwitchCategory() {
        compose.setThemed(false) { UnitsScreen(host("U1")) }
        compose.onNodeWithText("Temperature").performClick()
        compose.onNodeWithText("All temperature units").assertIsDisplayed()
    }

    @Test fun currencyReady() {
        val body = """{"base":"EUR","date":"2026-11-10","rates":{"USD":1.0823,"GBP":0.8412,"JPY":163.2,"INR":90.1,"CHF":0.94}}"""
        val repo = CurrencyRepository(File(app.cacheDir, "fx-test.json"), { body })
        File(app.cacheDir, "fx-test.json").delete()
        val vm = CurrencyViewModel(app, repo)
        compose.shootBoth("util-currency", settle = { compose.pumpUntil { vm.state.value is RatesUi.Ready } }) { CurrencyScreen(host("U2"), vm) }
        compose.onNodeWithText("ECB rates of 2026-11-10", substring = true).assertIsDisplayed()
    }

    @Test fun currencyOfflineWithNothingSaved() {
        val repo = CurrencyRepository(File(app.cacheDir, "fx-none.json").also { it.delete() }, { throw java.io.IOException("offline") })
        val vm = CurrencyViewModel(app, repo)
        compose.setThemed(false) { CurrencyScreen(host("U2"), vm) }
        compose.pumpUntil { vm.state.value is RatesUi.Failed }
        compose.onNodeWithText("Try again").assertIsDisplayed()
    }

    @Test fun calculator() {
        compose.shootBoth("util-calculator") { CalculatorScreen(host("U3")) }
        compose.onNodeWithText("7").performClick(); compose.onNodeWithText("×").performClick(); compose.onNodeWithText("8").performClick()
        compose.onNodeWithText("= 56").assertIsDisplayed()
        compose.onNodeWithText("=").performClick()
    }

    @Test fun calculatorScientificAndError() {
        compose.setThemed(false) { CalculatorScreen(host("U3")) }
        compose.onNodeWithText("Scientific").performClick()
        compose.onNodeWithText("sin").assertIsDisplayed()
        compose.onNodeWithText("1").performClick(); compose.onNodeWithText("÷").performClick(); compose.onNodeWithText("0").performClick(); compose.onNodeWithText("=").performClick()
        compose.onNodeWithText("Can't divide by zero").assertIsDisplayed()
    }

    @Test fun percent() { compose.shootBoth("util-percent") { PercentScreen(host("U4")) }; compose.onNodeWithText("What is X% of Y?").assertIsDisplayed() }

    @Test fun percentTip() {
        compose.setThemed(false) { PercentScreen(host("U4")) }
        compose.onNodeWithText("Tip & split").performClick()
        compose.onNodeWithText("Each person pays").assertIsDisplayed()
    }

    @Test fun dates() { compose.shootBoth("util-date") { DateScreen(host("U5")) }; compose.onNodeWithText("Working days (Mon to Fri)").assertIsDisplayed() }

    @Test fun dateAge() {
        compose.setThemed(false) { DateScreen(host("U5")) }
        compose.onNodeWithText("Age").performClick(); compose.onNodeWithText("Next birthday").assertIsDisplayed()
    }

    @Test fun zones() {
        val fixed = Instant.parse("2026-11-11T12:00:00Z")
        compose.shootBoth("util-timezones") { ZonesScreen(host("U6")) { fixed } }
        compose.onNodeWithText("World clock").assertIsDisplayed()
    }

    @Test fun zonesPlanner() {
        compose.setThemed(false) { ZonesScreen(host("U6")) { Instant.parse("2026-11-11T12:00:00Z") } }
        compose.onNodeWithText("Planner").performClick(); compose.onNodeWithText("Orange", substring = true).assertIsDisplayed()
    }

    @Test fun timers() { compose.shootBoth("util-timers") { TimersScreen(host("U7")) }; compose.onNodeWithText("Start").assertIsDisplayed() }

    @Test fun timersCountdownAndPomodoro() {
        compose.setThemed(false) { TimersScreen(host("U7")) }
        compose.onNodeWithText("Countdown").performClick(); compose.onNodeWithText("Start 5 min").assertIsDisplayed()
        compose.onNodeWithText("Pomodoro").performClick(); compose.onNodeWithText("Start Pomodoro").assertIsDisplayed()
    }

    @Test fun stopwatchLapsRender() {
        compose.setThemed(false) { TimersScreen(host("U7")) }
        compose.onNodeWithText("Start").performClick(); compose.onNodeWithText("Lap").performClick(); compose.onNodeWithText("Lap 1").assertIsDisplayed()
    }

    @Test fun random() {
        compose.shootBoth("util-random") { RandomScreen(host("U8")) }
        compose.onNodeWithText("Generate").performClick()
        compose.onNodeWithText("Dice").performClick(); compose.onNodeWithText("Roll").performClick(); compose.onNodeWithText("Total", substring = true).assertIsDisplayed()
    }

    @Test fun tally() {
        val vm = TallyViewModel(app)
        compose.shootBoth("util-tally", settle = { compose.pumpUntil { vm.loaded.value } }) { TallyScreen(host("U9"), vm) }
        compose.onNodeWithText("Volume keys count").assertIsDisplayed()
    }

    @Test fun loan() { compose.shootBoth("util-loan") { LoanScreen(host("U10")) }; compose.onNodeWithText("Monthly payment").assertIsDisplayed() }

    @Test fun health() { compose.shootBoth("util-health") { HealthScreen(host("U11")) }; compose.onNodeWithText("BMI").assertIsDisplayed() }

    @Test fun qrGenerator() {
        compose.shootBoth("util-qrgen-empty") { QrGenScreen(host("U12")) }
        compose.setContentFilled()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.setContentFilled() {
        // Same screen, but typed into (separate rule content is already set above, so type into it).
        onNodeWithText("Fill in the details above", substring = true).assertIsDisplayed()
        onAllFields()[0].performTextInput("forge.nbansal28.com")
        waitForIdle()
        pumpUntil { try { onNodeWithText("Making your code…").assertDoesNotExistCompat(); true } catch (_: AssertionError) { false } }
        captureShot("util-qrgen-filled")
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllFields() = onAllNodes(hasSetTextAction())
    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() = assertDoesNotExist()
}
