package com.locksight.guard

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.UserManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

class DpiTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTileState()
    }

    override fun onClick() {
        super.onClick()
        val prefs = PolicyController.prefs(this)
        val isActive = prefs.getBoolean("dpi_splitter_active", false) || prefs.getBoolean("dpi_active", false)

        if (isActive) {
            try {
                startService(Intent(this, LocalPacketVpnService::class.java).putExtra("mode", "STOP"))
            } catch (_: Exception) {}
            prefs.edit().putBoolean("dpi_splitter_active", false).putBoolean("dpi_active", false).commit()
            Toast.makeText(this, "LockSight: DPI Splitter Stopped", Toast.LENGTH_SHORT).show()
            refreshTileState()
            return
        }

        if (PolicyController.isDeviceOwner(this)) {
            try {
                PolicyController.dpm(this).clearUserRestriction(
                    PolicyController.admin(this),
                    UserManager.DISALLOW_CONFIG_VPN
                )
            } catch (_: Exception) {}
        }

        val prep = try {
            VpnService.prepare(this)
        } catch (_: Exception) {
            null
        }

        if (prep != null) {
            // Android 14+ (API 34-37) requires PendingIntent for startActivityAndCollapse!
            val openApp = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pending = PendingIntent.getActivity(
                    this,
                    201,
                    openApp,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(openApp)
            }
            Toast.makeText(this, "Tap 'Enable Local Packet Fragmentation' once inside LockSight.", Toast.LENGTH_LONG).show()
        } else {
            try {
                startService(Intent(this, LocalPacketVpnService::class.java).putExtra("mode", "BYEDPI"))
                prefs.edit().putBoolean("dpi_splitter_active", true).putBoolean("dpi_active", true).commit()
                Toast.makeText(this, "LockSight: DPI Packet Splitter ON", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(this, "Open LockSight once to start DPI Splitter.", Toast.LENGTH_SHORT).show()
            }
            refreshTileState()
        }
    }

    private fun refreshTileState() {
        val tile = qsTile ?: return
        val prefs = PolicyController.prefs(this)
        val isActive = prefs.getBoolean("dpi_splitter_active", false) || prefs.getBoolean("dpi_active", false)
        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (isActive) "DPI Active" else "DPI Splitter"
        tile.updateTile()
    }
}