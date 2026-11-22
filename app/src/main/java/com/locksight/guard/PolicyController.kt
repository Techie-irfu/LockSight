package com.locksight.guard

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import java.util.Calendar

object PolicyController {
    private const val PREFS = "locksight_prefs"
    private const val CHANNEL_ID = "locksight_timers"
    const val ACTION_SAFE_PAY_EXPIRED = "com.locksight.guard.ACTION_SAFE_PAY_EXPIRED"
    const val ACTION_SELF_HEAL_ACCESSIBILITY = "com.locksight.guard.ACTION_SELF_HEAL_ACCESSIBILITY"

    private val adBypassHandler = Handler(Looper.getMainLooper())
    private var adBypassRunnable: Runnable? = null
    private val safePayHandler = Handler(Looper.getMainLooper())
    private var safePayAutoRestoreRunnable: Runnable? = null
    @Volatile private var cachedBrowsers: Set<String>? = null
    @Volatile private var cachedEligibleGames: List<Pair<String, String>>? = null
    @Volatile private var cachedEligibleBanks: List<Pair<String, String>>? = null

    fun dpm(ctx: Context): DevicePolicyManager =
        ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun admin(ctx: Context): ComponentName =
        ComponentName(ctx, AdminReceiver::class.java)

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isDeviceOwner(ctx: Context): Boolean = dpm(ctx).isDeviceOwnerApp(ctx.packageName)

    fun syncSealStateWithDeviceOwner(ctx: Context) {
        val p = prefs(ctx)
        if (!isDeviceOwner(ctx) && (p.getBoolean("system_sealed", false) || p.getBoolean("acc2_sealed", false))) {
            p.edit()
                .putBoolean("system_sealed", false)
                .putBoolean("acc2_sealed", false)
                .apply()
        }
    }

    fun isSystemSealed(ctx: Context): Boolean {
        syncSealStateWithDeviceOwner(ctx)
        return isDeviceOwner(ctx) && prefs(ctx).getBoolean("system_sealed", false)
    }

    fun isAccount2Sealed(ctx: Context): Boolean {
        syncSealStateWithDeviceOwner(ctx)
        return isDeviceOwner(ctx) && prefs(ctx).getBoolean("acc2_sealed", false)
    }

    // --- LIVE OS RESTRICTION CHECKS ---
    fun isPrivateDnsLocked(ctx: Context): Boolean {
        if (!isDeviceOwner(ctx)) return false
        return try {
            dpm(ctx).getUserRestrictions(admin(ctx)).getBoolean(UserManager.DISALLOW_CONFIG_PRIVATE_DNS, false)
        } catch (_: Exception) {
            isSystemSealed(ctx)
        }
    }

    fun isVpnLocked(ctx: Context): Boolean {
        if (!isDeviceOwner(ctx)) return false
        return try {
            val dpmFlag = dpm(ctx).getUserRestrictions(admin(ctx)).getBoolean(UserManager.DISALLOW_CONFIG_VPN, false)
            val um = ctx.getSystemService(Context.USER_SERVICE) as UserManager
            dpmFlag || um.hasUserRestriction(UserManager.DISALLOW_CONFIG_VPN)
        } catch (_: Exception) {
            false
        }
    }

    fun isAdbLocked(ctx: Context): Boolean {
        if (!isDeviceOwner(ctx)) return false
        return try {
            dpm(ctx).getUserRestrictions(admin(ctx)).getBoolean(UserManager.DISALLOW_DEBUGGING_FEATURES, false)
        } catch (_: Exception) {
            false
        }
    }

    fun isUninstallBlocked(ctx: Context): Boolean {
        if (!isDeviceOwner(ctx)) return false
        return try {
            dpm(ctx).isUninstallBlocked(admin(ctx), ctx.packageName)
        } catch (_: Exception) {
            false
        }
    }

    fun isAccessibilityServiceEnabled(ctx: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(ctx.packageName, ignoreCase = true)
    }

    fun isCustomDnsSaved(ctx: Context): Boolean {
        val saved = prefs(ctx).getString("acc1_main", "") ?: ""
        return saved.isNotBlank()
    }

    // --- 1. NOTIFICATION ENGINE ---
    fun sendTimerNotification(ctx: Context, title: String, message: String, notifId: Int) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "LockSight Security Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                )
                nm.createNotificationChannel(channel)
            }
            val tapIntent = Intent(ctx, MainActivity::class.java)
            val pending = PendingIntent.getActivity(
                ctx,
                notifId,
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            nm.notify(notifId, notif)
        } catch (_: Exception) {}
    }

    // --- 2. STRICT SAFE-DNS VALIDATOR & 15-DAY ELAPSED CYCLE ROTATOR ---
    fun isValidProtectedDnsInput(rawInput: String): Boolean {
        val cleaned = cleanHost(rawInput)
        val verifiedFamilyProviders = setOf(
            "family.freedns.controld.com",
            "family.cloudflare-dns.com",
            "family.adguard-dns.com",
            "dns-family.adguard.com",
            "adult-filter-dns.cleanbrowsing.org",
            "family-filter-dns.cleanbrowsing.org"
        )
        if (verifiedFamilyProviders.contains(cleaned)) return true

        if (cleaned.endsWith(".dns.nextdns.io")) {
            val idPart = cleaned.removeSuffix(".dns.nextdns.io")
            return idPart.length in 5..12 && idPart.all { it.isLetterOrDigit() }
        }
        return false
    }

    fun saveDnsProfiles(
        ctx: Context,
        useDualAccounts: Boolean,
        acc1Main: String,
        acc1Bypass: String,
        acc2Main: String,
        acc2Bypass: String
    ) {
        val c1Main = cleanHost(acc1Main)
        val c1Bypass = cleanHost(acc1Bypass.ifBlank { c1Main })

        val hasValidAcc2 = useDualAccounts && acc2Main.trim().isNotEmpty()
        val c2Main = if (hasValidAcc2) cleanHost(acc2Main) else ""
        val c2Bypass = if (hasValidAcc2) cleanHost(acc2Bypass.ifBlank { c2Main }) else ""

        val p = prefs(ctx)
        val cycleStart = p.getLong("dns_cycle_start_epoch", 0L).let {
            if (it == 0L) System.currentTimeMillis() else it
        }

        p.edit()
            .putBoolean("use_dual_accounts", hasValidAcc2)
            .putBoolean("acc2_sealed", hasValidAcc2)
            .putLong("dns_cycle_start_epoch", cycleStart)
            .putString("acc1_main", c1Main)
            .putString("acc1_bypass", c1Bypass)
            .putString("acc2_main", c2Main)
            .putString("acc2_bypass", c2Bypass)
            .commit()
    }

    fun sealSecondAccountPostSeal(ctx: Context, acc2Main: String, acc2Bypass: String): Pair<Boolean, String> {
        if (isAccount2Sealed(ctx)) {
            return false to "Account 2 is already permanently sealed and cannot be modified."
        }
        val rawMain = acc2Main.trim()
        val rawBypass = acc2Bypass.trim().ifEmpty { rawMain }

        if (rawMain.isEmpty()) {
            return false to "Please enter your Account 2 Main NextDNS ID."
        }
        if (!isValidProtectedDnsInput(rawMain) || !isValidProtectedDnsInput(rawBypass)) {
            return false to "Rejected: Unfiltered DNS detected! Enter a valid 6-char NextDNS ID (e.g. f9c2a1) or verified Family DNS."
        }

        val c2Main = cleanHost(rawMain)
        val c2Bypass = cleanHost(rawBypass)
        val p = prefs(ctx)
        val cycleStart = p.getLong("dns_cycle_start_epoch", 0L).let {
            if (it == 0L) System.currentTimeMillis() else it
        }

        p.edit()
            .putBoolean("use_dual_accounts", true)
            .putBoolean("acc2_sealed", true)
            .putLong("dns_cycle_start_epoch", cycleStart)
            .putString("acc2_main", c2Main)
            .putString("acc2_bypass", c2Bypass)
            .commit()

        if (p.getLong("gplinks_bypass_until", 0L) <= System.currentTimeMillis()) {
            applyPrivateDns(ctx, getActiveMainDns(ctx))
        }
        return true to "Account 2 Permanently Sealed! Account 1 remains active for Days 1–15, then auto-rotates to Account 2 on Day 16."
    }

    private fun cleanHost(input: String): String {
        var s = input.trim().lowercase()
        if (s.isEmpty()) return "family.freedns.controld.com"
        s = s.removePrefix("https://").removePrefix("http://").removePrefix("tls://").removePrefix("quic://")
        if (s.startsWith("dns.nextdns.io/")) {
            val id = s.removePrefix("dns.nextdns.io/").substringBefore("/")
            return "$id.dns.nextdns.io"
        }
        s = s.substringBefore("/")
        return if (s.contains(".")) s else "$s.dns.nextdns.io"
    }

    fun getCurrentCycleDay(ctx: Context): Int {
        val p = prefs(ctx)
        val start = p.getLong("dns_cycle_start_epoch", 0L)
        if (start == 0L) return 1
        val elapsedDays = ((System.currentTimeMillis() - start).coerceAtLeast(0L) / (24 * 3600 * 1000L)).toInt()
        return (elapsedDays % 30) + 1
    }

    fun isCurrentlyOnAccount1Slot(ctx: Context): Boolean {
        val p = prefs(ctx)
        val dual = p.getBoolean("use_dual_accounts", false)
        val saved2 = p.getString("acc2_main", "") ?: ""
        if (!dual || saved2.isBlank()) return true
        return getCurrentCycleDay(ctx) <= 15
    }

    fun getRotationStatusLabel(ctx: Context): String {
        val p = prefs(ctx)
        val dual = p.getBoolean("use_dual_accounts", false)
        val saved2 = p.getString("acc2_main", "") ?: ""
        if (!dual || saved2.isBlank()) return "Single Account (Full Month)"

        val cycleDay = getCurrentCycleDay(ctx)
        return if (cycleDay <= 15) {
            "Dual Mode • Acc 1 (Day $cycleDay/15)"
        } else {
            "Dual Mode • Acc 2 (Day $cycleDay/30)"
        }
    }

    fun getActiveMainDns(ctx: Context): String {
        val p = prefs(ctx)
        val saved1 = p.getString("acc1_main", "") ?: ""
        val saved2 = p.getString("acc2_main", "") ?: ""
        val chosen = if (isCurrentlyOnAccount1Slot(ctx)) saved1 else saved2.ifBlank { saved1 }
        return chosen.ifBlank { "family.freedns.controld.com" }
    }

    fun getActiveBypassDns(ctx: Context): String {
        val p = prefs(ctx)
        val saved1 = p.getString("acc1_bypass", "") ?: ""
        val saved2 = p.getString("acc2_bypass", "") ?: ""
        val chosen = if (isCurrentlyOnAccount1Slot(ctx)) saved1 else saved2.ifBlank { saved1 }
        return chosen.ifBlank { getActiveMainDns(ctx) }
    }

    fun getCurrentLiveDnsEndpoint(ctx: Context): String {
        val bypassUntil = prefs(ctx).getLong("gplinks_bypass_until", 0L)
        val remMin = ((bypassUntil - System.currentTimeMillis()) / 60000L).coerceAtLeast(0L)
        return if (System.currentTimeMillis() < bypassUntil) {
            "${getActiveBypassDns(ctx)} (Bypass: ${remMin + 1}m)"
        } else {
            getActiveMainDns(ctx)
        }
    }

    // --- 3. SEAL & LOCK NEXTDNS & UNINSTALL PROTECTION (SEPARATED FROM VPN & ADB LOCKS!) ---
    fun sealAndLockSystem(ctx: Context) {
        if (!isDeviceOwner(ctx)) return
        val dpm = dpm(ctx)
        val admin = admin(ctx)

        val p = prefs(ctx)
        if (p.getLong("dns_cycle_start_epoch", 0L) == 0L) {
            p.edit().putLong("dns_cycle_start_epoch", System.currentTimeMillis()).commit()
        }
        p.edit().putBoolean("system_sealed", true).commit()

        try {
            applyPrivateDns(ctx, getActiveMainDns(ctx))
            dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
        } catch (_: Exception) {}

        try {
            dpm.setUninstallBlocked(admin, ctx.packageName, true)
        } catch (_: Exception) {}

        enforceAccessibilityService(ctx)
    }

    fun applyPrivateDns(ctx: Context, hostname: String) {
        if (!isDeviceOwner(ctx)) return
        try {
            val dpm = dpm(ctx)
            val admin = admin(ctx)
            dpm.setGlobalSetting(admin, "private_dns_mode", "hostname")
            dpm.setGlobalSetting(admin, "private_dns_specifier", hostname)
        } catch (_: Exception) {}
    }

    // --- 4. SELECTABLE (10M / 30M / 60M) AD-WALL BYPASS ---
    fun startAdShortenerBypass(ctx: Context, durationMinutes: Int, onUpdate: () -> Unit) {
        if (!isDeviceOwner(ctx)) return
        val appCtx = ctx.applicationContext
        val bypassHost = getActiveBypassDns(appCtx)
        val durationMs = durationMinutes * 60 * 1000L
        val until = System.currentTimeMillis() + durationMs

        adBypassRunnable?.let { adBypassHandler.removeCallbacks(it) }

        prefs(appCtx).edit()
            .putLong("gplinks_bypass_until", until)
            .putInt("last_bypass_duration_min", durationMinutes)
            .commit()

        applyPrivateDns(appCtx, bypassHost)
        onUpdate()

        val runnable = Runnable {
            val wasActive = prefs(appCtx).getLong("gplinks_bypass_until", 0L) > 0L
            if (wasActive && System.currentTimeMillis() >= prefs(appCtx).getLong("gplinks_bypass_until", 0L)) {
                prefs(appCtx).edit().putLong("gplinks_bypass_until", 0L).commit()
                applyPrivateDns(appCtx, getActiveMainDns(appCtx))
                sendTimerNotification(
                    appCtx,
                    "Ad-Blocker Restored",
                    "$durationMinutes-minute ad-wall bypass ended. Primary NextDNS profile (${getActiveMainDns(appCtx)}) is active again.",
                    1001
                )
                onUpdate()
            }
        }
        adBypassRunnable = runnable
        adBypassHandler.postDelayed(runnable, durationMs + 500L)
    }

    fun startAdShortenerBypass15Min(ctx: Context, onUpdate: () -> Unit) {
        startAdShortenerBypass(ctx, 15, onUpdate)
    }

    fun cancelAdShortenerBypassImmediately(ctx: Context): String {
        val appCtx = ctx.applicationContext
        adBypassRunnable?.let { adBypassHandler.removeCallbacks(it) }
        adBypassRunnable = null

        prefs(appCtx).edit().putLong("gplinks_bypass_until", 0L).commit()
        if (isDeviceOwner(appCtx)) {
            applyPrivateDns(appCtx, getActiveMainDns(appCtx))
        }
        return "Ad-Wall Bypass cancelled! Primary Ad-Blocking DNS (${getActiveMainDns(appCtx)}) restored."
    }

    fun revertToMainDnsIfExpired(ctx: Context) {
        if (!isDeviceOwner(ctx)) return
        val until = prefs(ctx).getLong("gplinks_bypass_until", 0L)
        if (until > 0L && System.currentTimeMillis() >= until) {
            adBypassRunnable?.let { adBypassHandler.removeCallbacks(it) }
            adBypassRunnable = null
            prefs(ctx).edit().putLong("gplinks_bypass_until", 0L).apply()
            applyPrivateDns(ctx, getActiveMainDns(ctx))
        }
    }

    // --- 5. AI VISION SHIELD: 6-HOUR DOWNGRADE COOLDOWN ---
    fun requestAiLevelChange(ctx: Context, targetLevel: Int, onUpdate: () -> Unit): String {
        val p = prefs(ctx)
        val currentLevel = p.getInt("ai_level", 1)
        if (targetLevel == currentLevel) return "AI Shield is already at Level $targetLevel."

        if (targetLevel > currentLevel) {
            p.edit()
                .putInt("ai_level", targetLevel)
                .putLong("ai_cooldown_ready_at", 0L)
                .putInt("ai_pending_level", 0)
                .commit()
            onUpdate()
            return "Upgraded immediately to Level $targetLevel!"
        }

        val now = System.currentTimeMillis()
        val readyAt = p.getLong("ai_cooldown_ready_at", 0L)
        val pendingLevel = p.getInt("ai_pending_level", 0)

        if (readyAt in 1..now && pendingLevel == targetLevel) {
            p.edit()
                .putInt("ai_level", targetLevel)
                .putLong("ai_cooldown_ready_at", 0L)
                .putInt("ai_pending_level", 0)
                .commit()
            onUpdate()
            return "AI Shield lowered to Level $targetLevel."
        }

        val unlockTime = now + (6 * 3600 * 1000L)
        p.edit()
            .putLong("ai_cooldown_ready_at", unlockTime)
            .putInt("ai_pending_level", targetLevel)
            .commit()

        Handler(Looper.getMainLooper()).postDelayed({
            sendTimerNotification(
                ctx,
                "AI Shield Cooldown Complete",
                "Your 6-hour cooldown to switch to Level $targetLevel is now ready.",
                1002
            )
            onUpdate()
        }, 6 * 3600 * 1000L + 1000L)

        onUpdate()
        return "6-Hour Cooldown started for Level $targetLevel. You will receive a notification when ready."
    }

    // --- 6A. SEALED CREATIVE APPS VPN MODE ---
    fun setCreativeVpnMode(ctx: Context, enable: Boolean): String {
        if (!isDeviceOwner(ctx)) return "Requires Device Owner mode."
        val dpm = dpm(ctx)
        val admin = admin(ctx)
        val appsToSuspend = getAllRiskyAppsForVpnSuspension(ctx)

        return if (enable) {
            dpm.setPackagesSuspended(admin, appsToSuspend, true)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_VPN)
            prefs(ctx).edit().putBoolean("creative_vpn_active", true).commit()
            "Creative VPN Mode ON! Editors can use VPN. All Browsers, Social Apps & Play Store Installs are suspended."
        } else {
            dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_VPN)
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
            dpm.setPackagesSuspended(admin, appsToSuspend, false)
            prefs(ctx).edit().putBoolean("creative_vpn_active", false).commit()
            "Creative VPN Mode Relocked! All Browsers & Apps are restored."
        }
    }

    private fun getAllRiskyAppsForVpnSuspension(ctx: Context): Array<String> {
        val pm = ctx.packageManager
        val browsers = getCachedBrowserPackages(ctx)
        val verifiedEditors = setOf(
            "com.lemon.lvoverseas",
            "com.frontrow.vlog",
            "com.camerasideas.instashot",
            "com.nexstreaming.app.kinemasterfree",
            "com.adobe.lrmobile",
            "com.canva.editor"
        )
        val riskyKeywords = listOf(
            "browser", "opera", "chrome", "firefox", "brave", "ucmobile", "tor", "duckduckgo",
            "telegram", "reddit", "twitter", "instagram", "facebook", "discord", "threads",
            "torrent", "torrdroid", "flud", "libre", "moviebox", "hdobox", "beetv", "teatv"
        )
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        return installed.map { it.packageName }
            .filter { pkg ->
                pkg != ctx.packageName &&
                        pkg != "com.android.settings" &&
                        !verifiedEditors.contains(pkg) &&
                        (browsers.contains(pkg) || riskyKeywords.any { pkg.lowercase().contains(it) })
            }
            .toTypedArray()
    }

    // --- 6B. CHECKOUT-SAFE PAYMENT MODE (RELIABLE ALARM-BACKED 3-MIN AUTO-RESTORE) ---
    fun setSafePaymentMode(ctx: Context, enable: Boolean): String {
        val appCtx = ctx.applicationContext
        val p = prefs(appCtx)
        val appsToSuspend = getAllRiskyAppsForPaymentSuspension(appCtx)

        return if (enable) {
            cancelSafePayAlarms(appCtx)

            if (isDeviceOwner(appCtx)) {
                try {
                    dpm(appCtx).setPackagesSuspended(admin(appCtx), appsToSuspend, true)
                    dpm(appCtx).addUserRestriction(admin(appCtx), UserManager.DISALLOW_INSTALL_APPS)
                } catch (_: Exception) {}
            }

            val until = System.currentTimeMillis() + (3 * 60 * 1000L)
            p.edit()
                .putBoolean("safe_payment_mode_active", true)
                .putLong("safe_pay_until", until)
                .commit()

            LockSightAccessibilityService.requestSelfDisableForPaymentMode()

            // 1. OS-level AlarmManager backup (fires reliably even in Doze/background!)
            scheduleSafePayAlarm(appCtx, until)

            // 2. Fast in-process Handler backup while active
            val autoRestore = Runnable { handleSafePayTimerExpired(appCtx) }
            safePayAutoRestoreRunnable = autoRestore
            safePayHandler.postDelayed(autoRestore, 3 * 60 * 1000L + 500L)

            "Safe Pay ON (3m)! Browsers & Shopping stay open on NextDNS while your payment completes."
        } else {
            cancelSafePayAlarms(appCtx)

            if (!isAccessibilityServiceEnabled(appCtx)) {
                tryAutoEnableAccessibility(appCtx)
            }
            if (!isAccessibilityServiceEnabled(appCtx)) {
                return "NEEDS_ACCESSIBILITY"
            }
            restoreAfterAccessibilityReenabled(appCtx)
            "Safe Payment Mode OFF! Aegis Shield & all apps restored."
        }
    }

    fun handleSafePayTimerExpired(ctx: Context) {
        val appCtx = ctx.applicationContext
        val p = prefs(appCtx)
        if (!p.getBoolean("safe_payment_mode_active", false)) return

        val until = p.getLong("safe_pay_until", 0L)
        if (until > 0L && System.currentTimeMillis() < until - 2000L) return

        cancelSafePayAlarms(appCtx)
        p.edit().putLong("safe_pay_until", 0L).commit()

        val autoEnabled = tryAutoEnableAccessibility(appCtx)
        if (autoEnabled) {
            restoreAfterAccessibilityReenabled(appCtx)
            sendTimerNotification(
                appCtx,
                "Safe Payment Mode Ended",
                "3-minute window finished. Aegis Shield & all apps have been restored automatically.",
                1010
            )
        } else {
            sendAccessibilityRestoreNotification(
                appCtx,
                "Payment Done? Tap to Restore LockSight",
                "3-minute Safe Pay window ended. Tap here to open Accessibility Settings and flip LockSight ON."
            )
        }
    }

    fun checkAndRestoreSafePayIfExpired(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean("safe_payment_mode_active", false)) {
            val until = p.getLong("safe_pay_until", 0L)
            if (until > 0L && System.currentTimeMillis() >= until) {
                handleSafePayTimerExpired(ctx)
            }
        }
    }

    private fun scheduleSafePayAlarm(ctx: Context, triggerAtMillis: Long) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(ctx, AdminReceiver::class.java).apply {
                action = ACTION_SAFE_PAY_EXPIRED
            }
            val pending = PendingIntent.getBroadcast(
                ctx,
                1010,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            }
        } catch (_: Exception) {}
    }

    private fun cancelSafePayAlarms(ctx: Context) {
        safePayAutoRestoreRunnable?.let { safePayHandler.removeCallbacks(it) }
        safePayAutoRestoreRunnable = null
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(ctx, AdminReceiver::class.java).apply {
                action = ACTION_SAFE_PAY_EXPIRED
            }
            val pending = PendingIntent.getBroadcast(
                ctx,
                1010,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pending)
        } catch (_: Exception) {}
    }

    // Clicking this notification opens Android's Accessibility Settings directly!
    private fun sendAccessibilityRestoreNotification(ctx: Context, title: String, message: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "LockSight Security Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                )
                nm.createNotificationChannel(channel)
            }
            val accIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pending = PendingIntent.getActivity(
                ctx,
                1011,
                accIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            nm.notify(1010, notif)
        } catch (_: Exception) {}
    }

    fun hasWriteSecureSettings(ctx: Context): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun scheduleSelfHealAccessibility(ctx: Context) {
        val appCtx = ctx.applicationContext
        if (!isSystemSealed(appCtx) && !hasWriteSecureSettings(appCtx)) return
        if (prefs(appCtx).getBoolean("safe_payment_mode_active", false)) return

        // Fast in-process heal attempt at 1.5s
        safePayHandler.postDelayed({ handleSelfHealAccessibility(appCtx) }, 1500L)

        // OS AlarmManager backup at 3s (survives background kill by system/OEM!)
        try {
            val am = appCtx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(appCtx, AdminReceiver::class.java).apply {
                action = ACTION_SELF_HEAL_ACCESSIBILITY
            }
            val pending = PendingIntent.getBroadcast(
                appCtx,
                1012,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = System.currentTimeMillis() + 3000L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        } catch (_: Exception) {}
    }

    fun handleSelfHealAccessibility(ctx: Context) {
        val appCtx = ctx.applicationContext
        if (!isSystemSealed(appCtx) && !hasWriteSecureSettings(appCtx)) return
        if (prefs(appCtx).getBoolean("safe_payment_mode_active", false)) return
        if (!isAccessibilityServiceEnabled(appCtx)) {
            tryAutoEnableAccessibility(appCtx)
        }
    }

    fun tryAutoEnableAccessibility(ctx: Context): Boolean {
        if (isAccessibilityServiceEnabled(ctx)) return true
        val myService = "${ctx.packageName}/${LockSightAccessibilityService::class.java.canonicalName}"

        if (ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
            try {
                val current = Settings.Secure.getString(
                    ctx.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
                val updated = when {
                    current.isEmpty() -> myService
                    !current.contains(myService) -> "$current:$myService"
                    else -> current
                }
                Settings.Secure.putString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
                Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                if (isAccessibilityServiceEnabled(ctx)) return true
            } catch (_: Exception) {}
        }

        enforceAccessibilityService(ctx)
        return isAccessibilityServiceEnabled(ctx)
    }

    fun restoreAfterAccessibilityReenabled(ctx: Context) {
        val appCtx = ctx.applicationContext
        cancelSafePayAlarms(appCtx)

        val p = prefs(appCtx)
        if (!p.getBoolean("safe_payment_mode_active", false)) return
        val appsToUnsuspend = getAllRiskyAppsForPaymentSuspension(appCtx)
        if (isDeviceOwner(appCtx)) {
            try {
                dpm(appCtx).clearUserRestriction(admin(appCtx), UserManager.DISALLOW_INSTALL_APPS)
                dpm(appCtx).setPackagesSuspended(admin(appCtx), appsToUnsuspend, false)
            } catch (_: Exception) {}
        }
        p.edit()
            .putBoolean("safe_payment_mode_active", false)
            .putLong("safe_pay_until", 0L)
            .commit()
    }

    private fun getAllRiskyAppsForPaymentSuspension(ctx: Context): Array<String> {
        val pm = ctx.packageManager
        val browsers = getCachedBrowserPackages(ctx)
        val nonBrowserMediaKeywords = listOf(
            "telegram", "reddit", "twitter", "instagram", "facebook", "discord", "threads", "youtube",
            "gallery", "photos", "vlc", "mxtech", "video", "player", "torrent", "torrdroid", "flud",
            "moviebox", "hdobox", "beetv", "teatv", "netmirror", "substack", "upscrolled"
        )
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .map { it.packageName }
            .filter { pkg ->
                pkg != ctx.packageName &&
                        pkg != "com.android.settings" &&
                        !browsers.contains(pkg) &&
                        nonBrowserMediaKeywords.any { pkg.lowercase().contains(it) }
            }
            .toTypedArray()
    }

    // --- 7. FULL SYSTEM VPN ACCESS ---
    fun startFullVpnCountdown(ctx: Context, hours: Int, onUpdate: () -> Unit) {
        val readyAt = System.currentTimeMillis() + (hours * 3600 * 1000L)
        prefs(ctx).edit().putLong("full_vpn_ready_at", readyAt).commit()
        Handler(Looper.getMainLooper()).postDelayed({
            sendTimerNotification(
                ctx,
                "Full VPN Access Ready",
                "Your $hours-hour VPN countdown is complete. Activate during Daylight (7 AM – 6 PM).",
                1003
            )
            onUpdate()
        }, hours * 3600 * 1000L + 1000L)
        onUpdate()
    }

    fun activateFullVpnWindow(ctx: Context, minutes: Int, onUpdate: () -> Unit): String {
        if (!isDeviceOwner(ctx)) return "Requires Device Owner mode."
        val now = System.currentTimeMillis()
        val readyAt = prefs(ctx).getLong("full_vpn_ready_at", 0L)
        if (readyAt == 0L || now < readyAt) return "Countdown has not finished yet."
        if (!isDaylightWindow()) return "Blocked: Full VPN can only be activated in Daylight (7:00 AM – 6:00 PM)."

        dpm(ctx).clearUserRestriction(admin(ctx), UserManager.DISALLOW_CONFIG_VPN)
        val lockAt = now + (minutes * 60 * 1000L)
        prefs(ctx).edit()
            .putLong("full_vpn_active_until", lockAt)
            .putLong("full_vpn_ready_at", 0L)
            .commit()

        Handler(Looper.getMainLooper()).postDelayed({
            checkAndRelockFullVpn(ctx)
            sendTimerNotification(
                ctx,
                "Full VPN Window Expired",
                "Your $minutes-minute VPN usage window has ended. VPN configuration is locked again.",
                1004
            )
            onUpdate()
        }, minutes * 60 * 1000L + 1000L)

        onUpdate()
        return "Full VPN Unlocked for $minutes minutes!"
    }

    fun lockFullVpnNow(ctx: Context): String {
        if (!isDeviceOwner(ctx)) return "Requires Device Owner mode."
        dpm(ctx).addUserRestriction(admin(ctx), UserManager.DISALLOW_CONFIG_VPN)
        prefs(ctx).edit()
            .putLong("full_vpn_active_until", 0L)
            .putLong("full_vpn_ready_at", 0L)
            .commit()
        return "VPN configuration is now permanently locked."
    }

    fun checkAndRelockFullVpn(ctx: Context) {
        if (!isDeviceOwner(ctx)) return
        val activeUntil = prefs(ctx).getLong("full_vpn_active_until", 0L)
        val creativeMode = prefs(ctx).getBoolean("creative_vpn_active", false)
        if (!creativeMode && activeUntil > 0L && System.currentTimeMillis() >= activeUntil) {
            dpm(ctx).addUserRestriction(admin(ctx), UserManager.DISALLOW_CONFIG_VPN)
            prefs(ctx).edit().putLong("full_vpn_active_until", 0L).apply()
        }
    }

    // --- 8. SEPARATE ADB & DEVELOPER OPTIONS LOCK + FORCE-STOP PROTECTION ---
    fun isDaylightWindow(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour in 7..17
    }

    fun startAdbCountdown(ctx: Context, hours: Int, onUpdate: () -> Unit) {
        val readyAt = System.currentTimeMillis() + (hours * 3600 * 1000L)
        prefs(ctx).edit().putLong("adb_ready_at", readyAt).commit()
        Handler(Looper.getMainLooper()).postDelayed({
            sendTimerNotification(
                ctx,
                "ADB Unlock Window Ready",
                "Your $hours-hour ADB countdown has finished! Open LockSight between 7 AM and 6 PM to activate your 1-hour window.",
                1005
            )
            onUpdate()
        }, hours * 3600 * 1000L + 1000L)
        onUpdate()
    }

    fun activateAdbWindow1Hour(ctx: Context, onUpdate: () -> Unit): String {
        if (!isDeviceOwner(ctx)) return "Device Owner not active."
        val readyAt = prefs(ctx).getLong("adb_ready_at", 0L)
        val now = System.currentTimeMillis()
        if (readyAt == 0L || now < readyAt) return "Countdown not finished yet."
        if (!isDaylightWindow()) return "Blocked: ADB can only be unlocked in daylight (7:00 AM – 6:00 PM)."

        val dpm = dpm(ctx)
        val admin = admin(ctx)
        dpm.clearUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                dpm.setUserControlDisabledPackages(admin, emptyList())
            } catch (_: Exception) {}
        }

        val lockAt = now + (60 * 60 * 1000L)
        prefs(ctx).edit()
            .putLong("adb_active_until", lockAt)
            .putLong("adb_ready_at", 0L)
            .commit()

        Handler(Looper.getMainLooper()).postDelayed({
            checkAndRelockAdb(ctx)
            sendTimerNotification(
                ctx,
                "ADB & Developer Options Relocked",
                "Your 1-hour ADB window has expired. USB & Wireless Debugging are now locked.",
                1006
            )
            onUpdate()
        }, 60 * 60 * 1000L + 1000L)

        onUpdate()
        return "ADB Unlocked for 1 Hour!"
    }

    fun extendAdb1Hour(ctx: Context): String {
        val activeUntil = prefs(ctx).getLong("adb_active_until", 0L)
        if (System.currentTimeMillis() >= activeUntil) return "ADB is not currently unlocked."
        if (!isDaylightWindow()) return "Daylight window ended (after 6:00 PM). Extension denied."
        val newUntil = activeUntil + (60 * 60 * 1000L)
        prefs(ctx).edit().putLong("adb_active_until", newUntil).commit()
        return "ADB extended by 1 additional hour."
    }

    fun lockAdbImmediately(ctx: Context): String {
        if (!isDeviceOwner(ctx)) return "Requires Device Owner mode."
        val dpm = dpm(ctx)
        val admin = admin(ctx)
        dpm.addUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                dpm.setUserControlDisabledPackages(admin, listOf(ctx.packageName))
            } catch (_: Exception) {}
        }
        prefs(ctx).edit()
            .putBoolean("adb_manually_sealed", true)
            .putLong("adb_active_until", 0L)
            .putLong("adb_ready_at", 0L)
            .commit()
        return "ADB, Developer Options & Force-Stop are now LOCKED."
    }

    fun checkAndRelockAdb(ctx: Context) {
        if (!isDeviceOwner(ctx)) return
        val activeUntil = prefs(ctx).getLong("adb_active_until", 0L)
        if (activeUntil > 0L && System.currentTimeMillis() >= activeUntil) {
            val dpm = dpm(ctx)
            val admin = admin(ctx)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    dpm.setUserControlDisabledPackages(admin, listOf(ctx.packageName))
                } catch (_: Exception) {}
            }
            prefs(ctx).edit().putLong("adb_active_until", 0L).apply()
        }
    }

    // --- 8B. OPTIONAL MULTI-USER, SYSTEM CLONER & PRIVATE SPACE LOCK ---
    fun isMultiUserLocked(ctx: Context): Boolean {
        if (!isDeviceOwner(ctx)) return false
        return try {
            val dpmFlag = dpm(ctx).getUserRestrictions(admin(ctx)).getBoolean(UserManager.DISALLOW_ADD_USER, false)
            val um = ctx.getSystemService(Context.USER_SERVICE) as UserManager
            dpmFlag || um.hasUserRestriction(UserManager.DISALLOW_ADD_USER)
        } catch (_: Exception) {
            false
        }
    }

    fun lockMultiUserAndClonerNow(ctx: Context): String {
        if (!isDeviceOwner(ctx)) return "Requires Device Owner mode."
        val dpm = dpm(ctx)
        val admin = admin(ctx)
        try { dpm.addUserRestriction(admin, UserManager.DISALLOW_ADD_USER) } catch (_: Exception) {}
        try { dpm.addUserRestriction(admin, UserManager.DISALLOW_ADD_MANAGED_PROFILE) } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try { dpm.addUserRestriction(admin, "no_add_clone_profile") } catch (_: Exception) {}
        }
        if (Build.VERSION.SDK_INT >= 35) {
            try { dpm.addUserRestriction(admin, "no_add_private_profile") } catch (_: Exception) {}
        }
        prefs(ctx).edit().putLong("multi_user_ready_at", 0L).commit()
        return "Multi-User, Guest Mode, System Cloner & Private Space are now LOCKED."
    }

    fun startMultiUserUnlockCountdown(ctx: Context, hours: Int, onUpdate: () -> Unit) {
        val readyAt = System.currentTimeMillis() + (hours * 3600 * 1000L)
        prefs(ctx).edit().putLong("multi_user_ready_at", readyAt).commit()
        Handler(Looper.getMainLooper()).postDelayed({
            sendTimerNotification(
                ctx,
                "Multi-User / Cloner Unlock Ready",
                "Your $hours-hour countdown finished. Activate during Daylight (7 AM – 6 PM).",
                1013
            )
            onUpdate()
        }, hours * 3600 * 1000L + 1000L)
        onUpdate()
    }

    fun unlockMultiUserNow(ctx: Context): String {
        if (!isDeviceOwner(ctx)) return "Requires Device Owner mode."
        val readyAt = prefs(ctx).getLong("multi_user_ready_at", 0L)
        if (readyAt == 0L || System.currentTimeMillis() < readyAt) return "Countdown not finished yet."
        if (!isDaylightWindow()) return "Blocked: Can only be unlocked in Daylight (7:00 AM – 6:00 PM)."

        val dpm = dpm(ctx)
        val admin = admin(ctx)
        try { dpm.clearUserRestriction(admin, UserManager.DISALLOW_ADD_USER) } catch (_: Exception) {}
        try { dpm.clearUserRestriction(admin, UserManager.DISALLOW_ADD_MANAGED_PROFILE) } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try { dpm.clearUserRestriction(admin, "no_add_clone_profile") } catch (_: Exception) {}
        }
        if (Build.VERSION.SDK_INT >= 35) {
            try { dpm.clearUserRestriction(admin, "no_add_private_profile") } catch (_: Exception) {}
        }
        prefs(ctx).edit().putLong("multi_user_ready_at", 0L).commit()
        return "Multi-User, System Cloner & Private Space Unlocked."
    }

    // --- 9. SAFE ACCESSIBILITY PERMIT ---
    fun enforceAccessibilityService(ctx: Context) {
        if (!isDeviceOwner(ctx)) return
        try {
            val dpm = dpm(ctx)
            val admin = admin(ctx)
            dpm.setPermittedAccessibilityServices(admin, null)
            val myService = "${ctx.packageName}/${LockSightAccessibilityService::class.java.canonicalName}"
            val current = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val updated = if (current.isEmpty()) {
                myService
            } else if (!current.contains(myService)) {
                "$current:$myService"
            } else {
                current
            }
            dpm.setSecureSetting(admin, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
            dpm.setSecureSetting(admin, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
        } catch (_: Exception) {}
    }

    fun enforceAccessibilityWatchdog(ctx: Context) {}

    fun suspendAppTemporarily(ctx: Context, targetPkg: String, minutes: Int = 5) {
        if (!isDeviceOwner(ctx) || targetPkg == ctx.packageName || targetPkg == "com.android.settings") return
        val dpm = dpm(ctx)
        val admin = admin(ctx)
        dpm.setPackagesSuspended(admin, arrayOf(targetPkg), true)
        Handler(Looper.getMainLooper()).postDelayed({
            dpm.setPackagesSuspended(admin, arrayOf(targetPkg), false)
        }, minutes * 60 * 1000L)
    }

    // --- 10. FAST IN-MEMORY BROWSER CACHE & EXPANDED GAMES / BANKING LISTS ---
    fun refreshCachedBrowsers(ctx: Context): Set<String> {
        return try {
            val pm = ctx.packageManager
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com"))
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            val resolves = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            val set = resolves.map { it.activityInfo.packageName }.toSet()
            cachedBrowsers = set
            set
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun getCachedBrowserPackages(ctx: Context): Set<String> {
        return cachedBrowsers ?: refreshCachedBrowsers(ctx)
    }

    fun getAllBrowserPackages(ctx: Context): Set<String> = getCachedBrowserPackages(ctx)

    fun getFastExcludedAppsSet(ctx: Context): Set<String> {
        val p = prefs(ctx)
        val games = p.getStringSet("excluded_games", emptySet()) ?: emptySet()
        val banks = p.getStringSet("excluded_banks", emptySet()) ?: emptySet()
        val legacy = p.getStringSet("excluded_apps", emptySet()) ?: emptySet()
        return games + banks + legacy
    }

    fun getAllExcludedApps(ctx: Context): Set<String> = getFastExcludedAppsSet(ctx)

    private fun isForbiddenMediaOrSocialApp(ctx: Context, pkg: String): Boolean {
        if (getCachedBrowserPackages(ctx).contains(pkg)) return true
        val forbiddenKeywords = listOf(
            "browser", "opera", "chrome", "firefox", "brave", "ucmobile", "tor", "webview",
            "telegram", "reddit", "twitter", "instagram", "tiktok", "discord", "facebook", "whatsapp",
            "vlc", "mxtech", "video", "player", "torrent", "torrdroid", "flud",
            "moviebox", "hdobox", "beetv", "teatv", "gallery", "photos", "youtube", "netflix"
        )
        val lower = pkg.lowercase()
        return forbiddenKeywords.any { lower.contains(it) }
    }

    fun prewarmExcludedAppCachesAsync(ctx: Context) {
        val appCtx = ctx.applicationContext
        Thread {
            try {
                refreshCachedBrowsers(appCtx)
                cachedEligibleGames = getEligibleGameApps(appCtx)
                cachedEligibleBanks = getEligibleBankingApps(appCtx)
            } catch (_: Exception) {}
        }.start()
    }

    fun getEligibleAppsFastAsync(
        ctx: Context,
        isGames: Boolean,
        onReady: (List<Pair<String, String>>) -> Unit
    ) {
        val cached = if (isGames) cachedEligibleGames else cachedEligibleBanks
        if (cached != null) {
            onReady(cached)
            // Refresh silently in background for next tap
            Thread {
                if (isGames) cachedEligibleGames = getEligibleGameApps(ctx.applicationContext)
                else cachedEligibleBanks = getEligibleBankingApps(ctx.applicationContext)
            }.start()
            return
        }
        Thread {
            val fresh = if (isGames) {
                getEligibleGameApps(ctx.applicationContext).also { cachedEligibleGames = it }
            } else {
                getEligibleBankingApps(ctx.applicationContext).also { cachedEligibleBanks = it }
            }
            Handler(Looper.getMainLooper()).post { onReady(fresh) }
        }.start()
    }

    fun getEligibleGameApps(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val gameAndEmulatorKeywords = listOf(
            "dominations", "bgmi", "pubg", "tencent", "krafton", "activision", "supercell",
            "garena", "riot", "mihoyo", "hoyoverse", "epicgames", "roblox", "minecraft", "ea.",
            "ubisoft", "gameloft", "zynga", "miniclip", "playrix", "niantic", "rockstargames",
            "citron", "azahar", "suyu", "sudachi", "yuzu", "skyline", "dolphin", "cemu",
            "aps3e", "armsx3", "rpcs", "eden", "gamehub", "gamenative", "winlator", "mobox",
            "micewine", "ppsspp", "aethersx2", "nethersx2", "duckstation", "vita3k", "melonds",
            "drastic", "lemuroid", "retroarch", "moonlight", "artemis", "parsec", "steam",
            "xbox", "playstation", "geforcenow", "emulator", "game"
        )
        return installed
            .filter { info ->
                val pkg = info.packageName
                val lower = pkg.lowercase()
                val labelLower = pm.getApplicationLabel(info).toString().lowercase()
                val isSystemApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                @Suppress("DEPRECATION")
                val isFlaggedGame = info.category == ApplicationInfo.CATEGORY_GAME ||
                        (info.flags and ApplicationInfo.FLAG_IS_GAME) != 0
                !isForbiddenMediaOrSocialApp(ctx, pkg) &&
                        !isSystemApp &&
                        (isFlaggedGame || gameAndEmulatorKeywords.any { lower.contains(it) || labelLower.contains(it) })
            }
            .map { pm.getApplicationLabel(it).toString() to it.packageName }
            .sortedBy { it.first.lowercase() }
    }

    fun getEligibleBankingApps(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val verifiedBanksAndFintech = setOf(
            "com.google.android.apps.nbu.paisa.user",
            "com.phonepe.app",
            "net.one97.paytm",
            "in.org.npci.upiapp",
            "com.dreamplug.androidapp",
            "com.mobikwik_new",
            "money.super.payments",
            "com.coupl.app",
            "indwin.c3.shareapp",
            "org.altruist.BajajExperia",
            "com.bajajfinservmarkets.app",
            "com.freecharge.android",
            "com.naviapp",
            "com.fampay.in",
            "money.jupiter",
            "com.epifi.paisa",
            "com.myairtelapp",
            "com.jio.myjio",
            "in.amazon.mShop.android.shopping",
            "com.indusind.indusmobile",
            "com.sbi.lotusintouch",
            "com.csam.icici.bank.imobile",
            "com.axis.mobile",
            "com.snapwork.hdfc",
            "com.canarabank.mobility",
            "com.msf.kbank.mobile",
            "com.idfcfirstbank.optimus",
            "com.fedmobile",
            "com.rblbank.mobank",
            "com.bob.bobworld",
            "com.Version1",
            "com.paypal.android.p2pmobile"
        )
        val financeAndDocKeywords = listOf(
            "bank", "upi", "paisa", "phonepe", "paytm", "indusmobile", "bhim", "yono", "imobile",
            "cred", "dreamplug", "mobikwik", "super.payments", "supermoney", "coupl", "slice", "bajaj",
            "kotak", "hdfc", "icici", "axis", "canara", "sbi", "idfc", "federal", "yesbank", "rbl",
            "freecharge", "navi", "fampay", "jupiter", "fi.money", "finserv", "finance", "wallet",
            "credit", "loan", "money", "zerodha", "kite", "groww", "upstox", "angel", "dhan", "coin",
            "revolut", "wise", "monzo", "chase", "venmo", "cashapp", "paypal",
            "digilocker", "aadhaar", "maadhaar", "umang", "incometax", "parivahan",
            "notion", "obsidian", "keep", "docs", "word", "excel", "acrobat", "scanner"
        )
        return installed
            .filter { info ->
                val pkg = info.packageName
                val lower = pkg.lowercase()
                val labelLower = pm.getApplicationLabel(info).toString().lowercase()
                !isForbiddenMediaOrSocialApp(ctx, pkg) &&
                        info.category != ApplicationInfo.CATEGORY_GAME &&
                        (verifiedBanksAndFintech.contains(pkg) ||
                                financeAndDocKeywords.any { lower.contains(it) || labelLower.contains(it) })
            }
            .map { pm.getApplicationLabel(it).toString() to it.packageName }
            .sortedBy { it.first.lowercase() }
    }

    fun enableZenLockTaskPermission(ctx: Context) {
        if (!isDeviceOwner(ctx)) return
        try {
            val dpm = dpm(ctx)
            val admin = admin(ctx)
            dpm.setLockTaskPackages(admin, arrayOf(ctx.packageName, "com.android.dialer", "com.google.android.dialer"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            }
        } catch (_: Exception) {}
    }
}