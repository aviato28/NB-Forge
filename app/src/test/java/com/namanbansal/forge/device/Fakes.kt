package com.namanbansal.forge.device

import android.graphics.Bitmap
import android.net.Uri
import androidx.camera.core.ImageProxy
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.namanbansal.forge.core.device.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Scripted sensors: each type in [script] emits its samples once, then idles (like a phone resting on a table). */
class FakeSensorHub(private val script: Map<Int, List<SensorSample>>) : SensorHub {
    override fun isAvailable(type: Int) = type in script
    override fun samples(type: Int, samplingPeriodUs: Int): Flow<SensorSample> = flow {
        script[type]?.forEach { emit(it) }
        awaitCancellation()
    }
}

fun sample(vararg v: Float, accuracy: Int = 3) = SensorSample(floatArrayOf(*v), accuracy)

class FakeSession(override val hasTorch: Boolean = true, override val maxZoom: Float = 8f) : CameraSession {
    var torchOn = false; var zoomRatio = 1f
    override fun setTorch(on: Boolean) { torchOn = on }
    override fun setZoom(ratio: Float) { zoomRatio = ratio }
    override fun grabFrame(): Bitmap? = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also { it.eraseColor(android.graphics.Color.DKGRAY) }
}

class FakeCameraBackend(private val session: CameraSession? = FakeSession()) : CameraBackend {
    @Composable
    override fun Preview(modifier: Modifier, analyzer: FrameAnalyzer?, colorMatrix: FloatArray?, onSession: (CameraSession?) -> Unit) {
        LaunchedEffect(Unit) { onSession(session) }
        Box(modifier.background(Brush.verticalGradient(listOf(Color(0xFF3B4252), Color(0xFF1B1E26)))), contentAlignment = Alignment.Center) {
            Text("camera preview", color = Color(0x66FFFFFF))
        }
    }
}

class FakeAudio(private val levels: List<Float>) : AudioLevelSource {
    override fun levels(): Flow<Float> = flow { levels.forEach { emit(it) }; awaitCancellation() }
}

class FakeTorch(override val available: Boolean = true, override val maxLevel: Int = 5) : TorchController {
    val log = mutableListOf<Pair<Boolean, Int>>()
    override fun set(on: Boolean, level: Int) { log += on to level }
    override fun registerState(onChange: (Boolean) -> Unit): () -> Unit = {}
}

class FakeDecoder(var result: ScannedCode? = null) : BarcodeDecoder {
    override fun decodeFrame(image: ImageProxy, onResult: (ScannedCode?) -> Unit) { image.close(); onResult(result) }
    override suspend fun decodeUri(context: android.content.Context, uri: Uri): ScannedCode? = result
    override fun close() {}
}
