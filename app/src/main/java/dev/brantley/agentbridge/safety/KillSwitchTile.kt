package dev.brantley.agentbridge.safety

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.brantley.agentbridge.BridgeApp

class KillSwitchTile : TileService() {
    override fun onStartListening() {
        refresh()
    }

    override fun onClick() {
        val app = application as BridgeApp
        if (app.core.armed.value) ArmedService.stop(this)
        refresh(forceInactive = true)
    }

    private fun refresh(forceInactive: Boolean = false) {
        val tile = qsTile ?: return
        val armed = !forceInactive && (application as BridgeApp).core.armed.value
        tile.state = if (armed) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Agent Bridge"
        tile.updateTile()
    }
}
