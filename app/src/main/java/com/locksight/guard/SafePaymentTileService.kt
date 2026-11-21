package com.locksight.guard

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

class SafePaymentTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTileState()
    }

    override fun onClick() {
        super.onClick()
        val prefs = PolicyController.prefs(this)
        val payModeActive = prefs.getBoolean("safe_payment_mode_active", false)

        if (!payModeActive) {
            val msg = PolicyController.setSafePaymentMode(this, true)
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            refreshTileState()
        } else {
            val result = PolicyController.setSafePaymentMode(this, false)
            if (result == "NEEDS_ACCESSIBILITY") {
                // Opens Accessibility Settings directly so the user just flips LockSight ON -> apps auto-restore!
                val accIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pending = PendingIntent.getActivity(
                        this,
                        202,
                        accIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    startActivityAndCollapse(pending)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(accIntent)
                }
                Toast.makeText(
                    this,
                    "Turn LockSight ON in Accessibility — all apps will restore automatically!",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
            }
            refreshTileState()
        }
    }

    private fun refreshTileState() {
        val tile = qsTile ?: return
        val active = PolicyController.prefs(this).getBoolean("safe_payment_mode_active", false)
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (active) "Safe Pay ON" else "Safe Pay"
        tile.updateTile()
    }
}