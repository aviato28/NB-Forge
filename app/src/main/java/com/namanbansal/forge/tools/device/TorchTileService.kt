package com.namanbansal.forge.tools.device

import android.graphics.drawable.Icon
import android.hardware.camera2.CameraManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.namanbansal.forge.R
import com.namanbansal.forge.core.device.AndroidTorchController

/** Quick Settings tile that toggles the torch, following its real state (another app or the in-app screen may change it). */
class TorchTileService : TileService() {
    private var torch: AndroidTorchController? = null
    private var unregister: (() -> Unit)? = null
    private var isOn = false

    override fun onStartListening() {
        val t = AndroidTorchController(this).also { torch = it }
        unregister?.invoke()
        unregister = t.registerState { on -> isOn = on; render() }
        render()
    }

    override fun onStopListening() {
        unregister?.invoke(); unregister = null
    }

    override fun onClick() {
        val t = torch ?: AndroidTorchController(this).also { torch = it }
        if (!t.available) return
        t.set(!isOn)
    }

    private fun render() {
        val tile = qsTile ?: return
        tile.state = if (torch?.available == false) Tile.STATE_UNAVAILABLE else if (isOn) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.torch_tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_torch)
        tile.updateTile()
    }
}
