package com.namanbansal.forge.core.device

import android.graphics.Bitmap
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.View
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Controls for a running camera. All calls are safe to make from the main thread. */
interface CameraSession {
    val hasTorch: Boolean
    val maxZoom: Float
    fun setTorch(on: Boolean)
    fun setZoom(ratio: Float)
    /** The current preview picture, or null when none is available yet. */
    fun grabFrame(): Bitmap?
}

/** Receives camera frames on a background thread and MUST close every image. */
fun interface FrameAnalyzer { fun analyze(image: ImageProxy) }

/** Seam so screens can be tested without a real camera. */
interface CameraBackend {
    /**
     * Shows the back camera and calls [onSession] once it is running (null if it could not start). [colorMatrix] is a 4x5
     * colour matrix applied to the live picture (contrast / invert), or null.
     */
    @Composable
    fun Preview(modifier: Modifier, analyzer: FrameAnalyzer?, colorMatrix: FloatArray?, onSession: (CameraSession?) -> Unit)
}

val LocalCameraBackend = compositionLocalOf<CameraBackend> { CameraXBackend }

object CameraXBackend : CameraBackend {
    @Composable
    override fun Preview(modifier: Modifier, analyzer: FrameAnalyzer?, colorMatrix: FloatArray?, onSession: (CameraSession?) -> Unit) {
        val context = LocalContext.current
        val owner = LocalLifecycleOwner.current
        val view = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER } }
        val currentAnalyzer by rememberUpdatedState(analyzer)
        val callback by rememberUpdatedState(onSession)
        var executor by remember { mutableStateOf<ExecutorService?>(null) }

        DisposableEffect(owner, view) {
            val exec = Executors.newSingleThreadExecutor()
            executor = exec
            val future = ProcessCameraProvider.getInstance(context)
            var provider: ProcessCameraProvider? = null
            future.addListener({
                try {
                    val p = future.get()
                    provider = p
                    val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                    val useCases = mutableListOf<androidx.camera.core.UseCase>(preview)
                    if (currentAnalyzer != null) {
                        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                        analysis.setAnalyzer(exec) { image -> currentAnalyzer?.analyze(image) ?: image.close() }
                        useCases += analysis
                    }
                    p.unbindAll()
                    val camera = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray())
                    callback(RealSession(camera, view))
                } catch (t: Exception) {
                    callback(null)
                }
            }, ContextCompat.getMainExecutor(context))
            onDispose {
                runCatching { provider?.unbindAll() }
                exec.shutdown()
            }
        }
        AndroidView(factory = { view }, modifier = modifier, update = { applyColorMatrix(it, colorMatrix) })
    }

    private fun applyColorMatrix(view: View, matrix: FloatArray?) {
        if (matrix == null) view.setLayerType(View.LAYER_TYPE_NONE, null)
        else view.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) })
    }

    private class RealSession(private val camera: Camera, private val view: PreviewView) : CameraSession {
        override val hasTorch: Boolean get() = camera.cameraInfo.hasFlashUnit()
        override val maxZoom: Float get() = camera.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
        override fun setTorch(on: Boolean) { runCatching { camera.cameraControl.enableTorch(on) } }
        override fun setZoom(ratio: Float) { runCatching { camera.cameraControl.setZoomRatio(ratio.coerceIn(1f, maxZoom)) } }
        override fun grabFrame(): Bitmap? = view.bitmap
    }
}

/** Colour matrices for the magnifier's contrast / invert filters (Android 4x5 layout, offsets in 0..255). */
object ImageFilters {
    fun matrix(contrast: Float, invert: Boolean): FloatArray? {
        if (contrast == 1f && !invert) return null
        val t = 128f * (1f - contrast)
        val s = if (invert) -contrast else contrast
        val o = if (invert) 255f - t else t
        return floatArrayOf(
            s, 0f, 0f, 0f, o,
            0f, s, 0f, 0f, o,
            0f, 0f, s, 0f, o,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** Draws [src] through [matrix] (or as is) into a new bitmap. */
    fun apply(src: Bitmap, matrix: FloatArray?): Bitmap {
        if (matrix == null) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
        android.graphics.Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }
}
