package com.locksight.guard

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.UserManager

class LocalPacketVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Default to "BYEDPI" if Android restarts the sticky service in the background
        val mode = intent?.getStringExtra("mode") ?: "BYEDPI"
        if (mode == "STOP") {
            closeTunnel()
            PolicyController.prefs(this)
                .edit()
                .putBoolean("dpi_active", false)
                .putBoolean("dpi_splitter_active", false)
                .commit()
            stopSelf()
            return START_NOT_STICKY
        }

        // If tunnel is already established and alive, keep it running
        if (vpnInterface != null) {
            PolicyController.prefs(this)
                .edit()
                .putBoolean("dpi_active", true)
                .putBoolean("dpi_splitter_active", true)
                .commit()
            relockVpnRestrictionIfNeeded()
            return START_STICKY
        }

        return try {
            if (PolicyController.isDeviceOwner(this)) {
                PolicyController.dpm(this).clearUserRestriction(
                    PolicyController.admin(this),
                    UserManager.DISALLOW_CONFIG_VPN
                )
            }

            val builder = Builder()
                .setSession("LockSight-DPI-Guard")
                .addAddress("10.215.173.1", 30)
                .addRoute("10.215.173.0", 30)
                .setMtu(1280)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                builder.setUnderlyingNetworks(null)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            vpnInterface = builder.establish()
            val active = vpnInterface != null

            PolicyController.prefs(this)
                .edit()
                .putBoolean("dpi_active", active)
                .putBoolean("dpi_splitter_active", active)
                .commit()

            // Re-lock third-party VPN settings AFTER builder.establish() has succeeded!
            relockVpnRestrictionIfNeeded()

            if (active) START_STICKY else START_NOT_STICKY
        } catch (_: Exception) {
            PolicyController.prefs(this)
                .edit()
                .putBoolean("dpi_active", false)
                .putBoolean("dpi_splitter_active", false)
                .commit()
            relockVpnRestrictionIfNeeded()
            START_NOT_STICKY
        }
    }

    private fun relockVpnRestrictionIfNeeded() {
        val prefs = PolicyController.prefs(this)
        if (PolicyController.isDeviceOwner(this) &&
            PolicyController.isSystemSealed(this) &&
            !prefs.getBoolean("creative_vpn_active", false) &&
            prefs.getLong("full_vpn_active_until", 0L) <= System.currentTimeMillis()
        ) {
            try {
                PolicyController.dpm(this).addUserRestriction(
                    PolicyController.admin(this),
                    UserManager.DISALLOW_CONFIG_VPN
                )
            } catch (_: Exception) {}
        }
    }

    private fun closeTunnel() {
        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null
    }

    override fun onRevoke() {
        closeTunnel()
        PolicyController.prefs(this)
            .edit()
            .putBoolean("dpi_active", false)
            .putBoolean("dpi_splitter_active", false)
            .commit()
        super.onRevoke()
    }

    override fun onDestroy() {
        closeTunnel()
        PolicyController.prefs(this)
            .edit()
            .putBoolean("dpi_active", false)
            .putBoolean("dpi_splitter_active", false)
            .commit()
        super.onDestroy()
    }
}