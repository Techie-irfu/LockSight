package com.locksight.guard

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        PolicyController.enforceAccessibilityService(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                PolicyController.revertToMainDnsIfExpired(context)
                PolicyController.checkAndRelockFullVpn(context)
                PolicyController.checkAndRelockAdb(context)
                PolicyController.checkAndRestoreSafePayIfExpired(context)
                PolicyController.enforceAccessibilityService(context)
            }
            PolicyController.ACTION_SAFE_PAY_EXPIRED -> {
                PolicyController.handleSafePayTimerExpired(context)
            }
            PolicyController.ACTION_SELF_HEAL_ACCESSIBILITY -> {
                PolicyController.handleSelfHealAccessibility(context)
            }
        }
    }
}