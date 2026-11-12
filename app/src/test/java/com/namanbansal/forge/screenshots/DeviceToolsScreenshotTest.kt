package com.namanbansal.forge.screenshots

import android.Manifest
import android.app.Application
import android.hardware.Sensor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.core.device.*
import com.namanbansal.forge.device.*
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.tools.ToolRegistry
import com.namanbansal.forge.tools.device.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Device tools rendered with scripted sensors / camera / mic (smoke test) and captured light + dark when recording. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class DeviceToolsScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val g = GRAVITY

    @Before fun grant() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun host(code: String) = ToolHost(ToolRegistry.get(code)!!, {})

    private fun env(
        sensors: Map<Int, List<SensorSample>> = emptyMap(),
        camera: CameraBackend = FakeCameraBackend(),
        audio: AudioLevelSource = FakeAudio(emptyList()),
        torch: TorchController = FakeTorch(),
        decoder: BarcodeDecoder = FakeDecoder(),
        content: @Composable () -> Unit,
    ): @Composable () -> Unit = {
        CompositionLocalProvider(
            LocalSensorHub provides FakeSensorHub(sensors), LocalCameraBackend provides camera, LocalAudioLevelSource provides audio,
            LocalTorchController provides torch, LocalBarcodeDecoder provides decoder,
        ) { content() }
    }

    private fun tiltedGravity(deg: Double, deg2: Double = 0.0) = List(10) {
        sample((-Math.sin(Math.toRadians(deg)) * g).toFloat(), (Math.sin(Math.toRadians(deg2)) * g).toFloat(), (Math.cos(Math.toRadians(deg)) * g).toFloat())
    }

    @Test fun level() {
        compose.shootBoth("device-level", settle = { compose.waitForIdle() }, content = env(mapOf(Sensor.TYPE_GRAVITY to tiltedGravity(2.4, 1.1))) { LevelScreen(host("D1")) })
        compose.onNodeWithText("Surface").assertIsDisplayed()
    }

    @Test fun levelEdgeMode() {
        compose.setThemed(false, env(mapOf(Sensor.TYPE_GRAVITY to List(5) { sample(g, 0.3f, 0f) })) { LevelScreen(host("D1")) })
        compose.onNodeWithText("Edge").performClickCompat()
        compose.onNodeWithText("Calibrate").assertIsDisplayed()
    }

    @Test fun levelWithoutSensor() {
        compose.setThemed(false, env { LevelScreen(host("D1")) })
        compose.onNodeWithText("Sensor not available").assertIsDisplayed()
    }

    @Test fun ruler() {
        compose.shootBoth("device-ruler", content = env { RulerScreen(host("D2")) })
        compose.onNodeWithText("Calibrate with a card").assertIsDisplayed()
    }

    @Test fun protractorCamera() {
        compose.shootBoth("device-protractor", content = env { ProtractorScreen(host("D3")) })
        compose.onNodeWithText("Camera protractor").assertIsDisplayed()
    }

    @Test fun protractorPermissionDenied() {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        compose.setThemed(false, env { ProtractorScreen(host("D3")) })
        compose.onNodeWithText("Camera access").assertIsDisplayed()
        compose.onNodeWithText("Allow").assertIsDisplayed()
    }

    @Test fun compass() {
        // Rotation vector for a heading of about 60 degrees (rotation of -60 around Z in the ENU world frame).
        val h = Math.toRadians(-60.0)
        val rv = sample(0f, 0f, Math.sin(h / 2).toFloat(), Math.cos(h / 2).toFloat())
        compose.shootBoth("device-compass", content = env(mapOf(Sensor.TYPE_ROTATION_VECTOR to List(30) { rv })) { CompassScreen(host("D4")) })
        compose.onNodeWithText("Lock heading").assertIsDisplayed()
    }

    @Test fun compassNeedsCalibration() {
        val rv = sample(0f, 0f, 0f, 1f, accuracy = 1)
        compose.setThemed(false, env(mapOf(Sensor.TYPE_ROTATION_VECTOR to listOf(rv))) { CompassScreen(host("D4")) })
        compose.onNodeWithText("Calibrate the compass").assertIsDisplayed()
    }

    @Test fun sound() {
        compose.shootBoth("device-sound", content = env(audio = FakeAudio(List(60) { -40f + 25f * Math.sin(it / 6.0).toFloat() })) { SoundScreen(host("D5")) })
        compose.onNodeWithText("Typical sounds").assertIsDisplayed()
    }

    @Test fun soundPermissionDenied() {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        compose.setThemed(false, env { SoundScreen(host("D5")) })
        compose.onNodeWithText("Microphone access").assertIsDisplayed()
    }

    @Test fun flashlight() {
        compose.shootBoth("device-flashlight", content = env { FlashlightScreen(host("D6")) })
        compose.onNodeWithText("Tap to switch on").assertIsDisplayed()
    }

    @Test fun flashlightNoFlash() {
        compose.setThemed(false, env(torch = FakeTorch(available = false)) { FlashlightScreen(host("D6")) })
        compose.onNodeWithText("Sensor not available").assertIsDisplayed()
    }

    @Test fun magnifier() {
        compose.shootBoth("device-magnifier", content = env { MagnifierScreen(host("D7")) })
        compose.onNodeWithText("Freeze").assertIsDisplayed()
    }

    @Test fun scannerLive() {
        val vm = ScannerViewModel(app)
        compose.shootBoth("device-scanner", content = env { ScannerScreen(host("D8"), vm) })
        compose.onNodeWithText("Point at a QR code or barcode").assertIsDisplayed()
    }

    @Test fun scannerWifiResult() {
        val vm = ScannerViewModel(app)
        vm.onCode(ScannedCode("WIFI:T:WPA;S:Forge Guest;P:s3cret-pass;;", "QR_CODE"))
        compose.shootBoth("device-scanner-wifi", content = env { ScannerScreen(host("D8"), vm) })
        compose.onNodeWithText("Forge Guest").assertIsDisplayed()
        compose.onNodeWithText("Add network").assertIsDisplayed()
    }

    @Test fun scannerUrlResult() {
        val vm = ScannerViewModel(app)
        vm.onCode(ScannedCode("https://forge.nbansal28.com/get", "QR_CODE"))
        compose.setThemed(false, env { ScannerScreen(host("D8"), vm) })
        compose.onNodeWithText("Open").assertIsDisplayed()
        compose.onNodeWithText("Copy").assertIsDisplayed()
    }

    @Test fun light() {
        compose.shootBoth("device-light", content = env(mapOf(Sensor.TYPE_LIGHT to List(5) { sample(320f) })) { LightScreen(host("D9")) })
        compose.onNodeWithText("Typical light levels").assertIsDisplayed()
    }

    @Test fun lightMissing() {
        compose.setThemed(false, env { LightScreen(host("D9")) })
        compose.onNodeWithText("Sensor not available").assertIsDisplayed()
    }

    @Test fun vibration() {
        val s = List(80) { sample(0f, 0f, g + (Math.sin(it / 3.0) * (1 + it / 40.0)).toFloat()) }
        compose.shootBoth("device-vibration", content = env(mapOf(Sensor.TYPE_ACCELEROMETER to s)) { VibrationScreen(host("D10")) })
        compose.onNodeWithText("Reset peak").assertIsDisplayed()
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.performClickCompat() = performClick()
