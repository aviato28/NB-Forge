package com.namanbansal.forge.core.device

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.BufferOverflow

/** One sensor reading. [values] is a private copy, safe to keep. */
class SensorSample(val values: FloatArray, val accuracy: Int, val timestampNs: Long = 0L)

/** Seam between the tools and Android's SensorManager so screens can be tested with scripted readings. */
interface SensorHub {
    fun isAvailable(type: Int): Boolean

    /** Registers when collected and unregisters when the collector is cancelled. Collect it only while resumed. */
    fun samples(type: Int, samplingPeriodUs: Int = SensorManager.SENSOR_DELAY_GAME): Flow<SensorSample>
}

class AndroidSensorHub(context: Context) : SensorHub {
    private val manager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager?

    override fun isAvailable(type: Int): Boolean = manager?.getDefaultSensor(type) != null

    override fun samples(type: Int, samplingPeriodUs: Int): Flow<SensorSample> = callbackFlow {
        val mgr = manager
        val sensor = mgr?.getDefaultSensor(type)
        if (mgr == null || sensor == null) {
            close()
            return@callbackFlow
        }
        var accuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                trySend(SensorSample(event.values.copyOf(), event.accuracy.takeIf { it > 0 } ?: accuracy, event.timestamp))
            }
            override fun onAccuracyChanged(sensor: Sensor?, newAccuracy: Int) { accuracy = newAccuracy }
        }
        mgr.registerListener(listener, sensor, samplingPeriodUs)
        awaitClose { mgr.unregisterListener(listener) }
    }.buffer(1, BufferOverflow.DROP_OLDEST)
}

val LocalSensorHub = compositionLocalOf<SensorHub?> { null }

@Composable
fun rememberSensorHub(): SensorHub {
    val provided = LocalSensorHub.current
    val context = LocalContext.current
    return provided ?: remember(context) { AndroidSensorHub(context) }
}

/** Collects [type] only while the screen is resumed (sensors unregister in onPause) and hands each sample to [onSample] on the main thread. */
@Composable
fun SensorEffect(hub: SensorHub, type: Int, samplingPeriodUs: Int = SensorManager.SENSOR_DELAY_GAME, onSample: (SensorSample) -> Unit) {
    val owner = LocalLifecycleOwner.current
    val current = rememberUpdatedState(onSample)
    LaunchedEffect(hub, type, samplingPeriodUs, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            hub.samples(type, samplingPeriodUs).collect { current.value(it) }
        }
    }
}
