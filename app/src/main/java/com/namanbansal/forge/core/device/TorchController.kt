package com.namanbansal.forge.core.device

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.compose.runtime.compositionLocalOf

/** Torch access behind an interface (CameraManager in production, a fake in tests). */
interface TorchController {
    val available: Boolean
    /** Highest strength level, 1 when brightness control is unsupported (needs Android 13+). */
    val maxLevel: Int
    fun set(on: Boolean, level: Int = maxLevel)
    fun registerState(onChange: (Boolean) -> Unit): () -> Unit
}

class AndroidTorchController(context: Context) : TorchController {
    private val manager = context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager?
    private val id: String? = manager?.let { m ->
        runCatching {
            m.cameraIdList.firstOrNull { id ->
                val ch = m.getCameraCharacteristics(id)
                ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true && ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        }.getOrNull()
    }
    override val available get() = id != null
    override val maxLevel: Int = run {
        val m = manager; val i = id
        if (m != null && i != null && Build.VERSION.SDK_INT >= 33) {
            runCatching { m.getCameraCharacteristics(i).get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1 }.getOrDefault(1)
        } else 1
    }

    override fun set(on: Boolean, level: Int) {
        val m = manager ?: return
        val i = id ?: return
        try {
            if (on && Build.VERSION.SDK_INT >= 33 && maxLevel > 1) m.turnOnTorchWithStrengthLevel(i, level.coerceIn(1, maxLevel))
            else m.setTorchMode(i, on)
        } catch (_: Exception) { /* camera in use by another app: ignore, the screen shows the real state from the callback */ }
    }

    override fun registerState(onChange: (Boolean) -> Unit): () -> Unit {
        val m = manager ?: return {}
        val cb = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { if (cameraId == id) onChange(enabled) }
        }
        m.registerTorchCallback(cb, null)
        return { m.unregisterTorchCallback(cb) }
    }
}

val LocalTorchController = compositionLocalOf<TorchController?> { null }

/** Blink schedules for the strobe and SOS modes: (on?, milliseconds) steps that repeat forever. */
object TorchPatterns {
    fun strobe(hz: Float): List<Pair<Boolean, Long>> {
        val period = (1000f / hz.coerceIn(0.5f, 20f)).toLong()
        val on = (period / 2).coerceAtLeast(10)
        return listOf(true to on, false to (period - on).coerceAtLeast(10))
    }

    /** International Morse for "SOS": dot = 1 unit, dash = 3, gap inside a letter 1, between letters 3, then 7 before repeating. */
    fun sos(unitMs: Long = 200): List<Pair<Boolean, Long>> {
        val out = mutableListOf<Pair<Boolean, Long>>()
        fun letter(vararg units: Int) {
            units.forEachIndexed { i, u ->
                out += true to u * unitMs
                if (i != units.lastIndex) out += false to unitMs
            }
        }
        letter(1, 1, 1); out += false to 3 * unitMs
        letter(3, 3, 3); out += false to 3 * unitMs
        letter(1, 1, 1); out += false to 7 * unitMs
        return out
    }
}
