package com.locksight.guard

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

class AdBypassTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTileState()
    }

    override fun onClick() {
        super.onClick()
        if (!PolicyController.isDeviceOwner(this)) {
            Toast.makeText(this, "LockSight: Grant Device Owner first.", Toast.LENGTH_SHORT).show()
            return
        }
        val prefs = PolicyController.prefs(this)
        val bypassUntil = prefs.getLong("gplinks_bypass_until", 0L)
        val isActive = System.currentTimeMillis() < bypassUntil

        if (isActive) {
            val msg = PolicyController.cancelAdShortenerBypassImmediately(this)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } else {
            // Explicitly starts a clean 15-minute bypass from Quick Settings
            PolicyController.startAdShortenerBypass(this, 15) { refreshTileState() }
            Toast.makeText(this, "LockSight: Ad-Wall Bypass ON (15m)", Toast.LENGTH_SHORT).show()
        }
        refreshTileState()
    }

    private fun refreshTileState() {
        val tile = qsTile ?: return
        val bypassUntil = PolicyController.prefs(this).getLong("gplinks_bypass_until", 0L)
        val remainingMin = ((bypassUntil - System.currentTimeMillis()) / 60000L).coerceAtLeast(0L)
        val isActive = System.currentTimeMillis() < bypassUntil

        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (isActive) "Ad-Wall (${remainingMin + 1}m)" else "Ad-Wall 15m"
        tile.updateTile()
    }
}