package com.locksight.guard

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.concurrent.Executors

class LockSightAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var activeInstance: LockSightAccessibilityService? = null

        fun requestSelfDisableForPaymentMode() {
            try {
                activeInstance?.disableSelf()
            } catch (_: Exception) {}
        }

        // 1. BLOCKED WEBSITE DOMAINS
        val BLOCKED_URL_DOMAINS = listOf(
            "1337x.", "1377x.", "1337xx.", "x1337x.", "thepiratebay.", "pirateproxy.",
            "torrentgalaxy.", "tgx.rs", "rargb.", "limetorrents.", "dirtytorrents.",
            "bunkr.", "erome.", "redgifs.", "coomer.su", "kemono.su", "celebmasta",
            "pornhub.", "xvideos.", "xnxx.", "xhamster.", "spankbang",
            "eporner.", "hqporner.", "chaturbate.", "stripchat.", "hanime."
        )

        // 2. BLOCKED EXPLICIT SEARCH KEYWORDS
        val BLOCKED_SEARCH_KEYWORDS = listOf(
            "pornhub", "xvideos", "xnxx", "xhamster", "redgifs", "spankbang", "celebmasta",
            "brazzers", "naughtyamerica", "onlyfans leak", "chaturbate", "stripchat",
            "porn video", "porn videos", "porn pic", "porn images", "porn movies",
            "sex video", "sex videos", "sex movie", "sex movies", "sex pic", "sex images",
            "xxx video", "xxx videos", "xxx images", "xxx pic", "xxx movies",
            "nude video", "nude videos", "nude pic", "nude photos", "nude images", "nudes",
            "naked girls", "naked women", "naked video", "leaked nudes", "send nudes",
            "vagina images", "vagina pic", "vagina video", "real vagina",
            "fucking", "anal porn", "anal sex", "blowjob", "handjob", "cumshot",
            "creampie", "gangbang", "boobs pic", "boobs video", "pussy pic", "pussy video",
            "camgirl", "rule34", "hentai video", "hentai porn", "jav porn", "desipapa"
        )

        // 3. APPS EXEMPT FROM SEARCH KEYWORD BLOCKING
        private val KEYWORD_EXEMPT_APP_TOKENS = listOf(
            "whatsapp", "signal", "mms", "messaging", "dialer", "contacts",
            "gmail", "outlook", "mail", "protonmail",
            "note", "notes", "keep", "notion", "obsidian", "evernote", "docs", "word", "office",
            "openai", "chatgpt", "anthropic", "claude", "perplexity", "deepseek", "copilot", "bard", "gemini"
        )
    }

    // Loaded ASYNCHRONOUSLY on background thread so Main UI Thread NEVER freezes on the logo!
    @Volatile private var aiScanner: NsfwAiScanner? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastScanTime = 0L
    private var lastTreeCheckTime = 0L
    private var lastUserActivityTime = 0L
    private var lastSettingsToastTime = 0L
    private var lastSettingsBlockHitTime = 0L
    private var rapidSettingsTamperCount = 0
    private var lastKeywordBackTime = 0L
    private var currentForegroundPkg: String = ""
    private var guardToast: Toast? = null
    @Volatile private var isScanInProgress = false

    // Calm 2.5s heartbeat (0% CPU when screen off or idle -> keeps Poco M5 / Narzo 50 / OnePlus 12 cool!)
    private val videoHeartbeatRunnable = object : Runnable {
        override fun run() {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (pm.isInteractive && aiScanner != null) {
                    val rootPkg = rootInActiveWindow?.packageName?.toString() ?: ""
                    val activePkg = if (rootPkg.isNotEmpty() && !isIgnoredSystemOrKeyboardPackage(rootPkg)) {
                        currentForegroundPkg = rootPkg
                        rootPkg
                    } else {
                        currentForegroundPkg
                    }
                    val recentlyActive = (System.currentTimeMillis() - lastUserActivityTime) < 15000L
                    if (shouldScanPackage(activePkg) && (isContinuousMediaApp(activePkg) || recentlyActive)) {
                        runSilentAiScreenCheck(activePkg, forceImmediate = false)
                    }
                }
            } catch (_: Exception) {}
            mainHandler.postDelayed(this, 2500L)
        }
    }

    private val scrollSettleScanRunnable = Runnable {
        if (shouldScanPackage(currentForegroundPkg)) {
            runSilentAiScreenCheck(currentForegroundPkg, forceImmediate = false)
        }
    }

    private val fastRecheckRunnable = Runnable {
        if (shouldScanPackage(currentForegroundPkg)) {
            runSilentAiScreenCheck(currentForegroundPkg, forceImmediate = true)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        lastUserActivityTime = System.currentTimeMillis()

        // CRITICAL STABILITY FIX: Load ONNX 320n model & browser cache on background thread!
        // Main UI Thread returns in < 1ms -> ZERO logo freezes and ZERO ANRs!
        executor.execute {
            try {
                PolicyController.refreshCachedBrowsers(applicationContext)
                aiScanner = NsfwAiScanner(applicationContext)
            } catch (_: Exception) {}
        }

        if (PolicyController.prefs(this).getBoolean("safe_payment_mode_active", false)) {
            PolicyController.restoreAfterAccessibilityReenabled(this)
            showSingleToast("Safe Payment Mode OFF! All apps restored.")
        }
        mainHandler.postDelayed(videoHeartbeatRunnable, 2500L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        val now = System.currentTimeMillis()
        lastUserActivityTime = now

// 1. UNIVERSAL CROSS-BRAND SETTINGS, PRIVACY CONTROLS & OEM PHONE MANAGER GUARD (0ms delay!)
        // Strictly runs ONLY inside Settings / Privacy / OEM Security packages -> NEVER touches Notes or Gemini!
        if (isSettingsOrPrivacyGuardPackage(pkg)) {
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && isClickOnLockSightRemoveAccess(event)) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                PolicyController.scheduleSelfHealAccessibility(this)
                if (now - lastSettingsToastTime > 2000L) {
                    lastSettingsToastTime = now
                    showSingleToast("LockSight: Access is locked by System Administrator.")
                }
                return
            }

            if (isLockSightTamperScreenActive(event)) {
                if (now - lastSettingsBlockHitTime < 3000L) {
                    rapidSettingsTamperCount++
                } else {
                    rapidSettingsTamperCount = 1
                }
                lastSettingsBlockHitTime = now

                if (rapidSettingsTamperCount >= 2) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                } else {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }

                if (now - lastSettingsToastTime > 2000L) {
                    lastSettingsToastTime = now
                    showSingleToast("LockSight: Protection is locked by System Administrator.")
                }
                return
            }
            return
        }

        // 2. FAST TYPED KEYWORD INTERCEPTOR (No heavy tree scan needed!)
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            val typedText = event.text?.joinToString(" ")?.lowercase()?.trim() ?: ""
            if (typedText.isNotEmpty() && typedText.length <= 80) {
                val activeApp = if (isIgnoredSystemOrKeyboardPackage(pkg)) currentForegroundPkg else pkg
                if (shouldCheckKeywordsInApp(activeApp, rootInActiveWindow)) {
                    if (BLOCKED_SEARCH_KEYWORDS.any { typedText.contains(it) } ||
                        BLOCKED_URL_DOMAINS.any { typedText.contains(it) }
                    ) {
                        if (now - lastKeywordBackTime > 900L) {
                            lastKeywordBackTime = now
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            showSingleToast("LockSight: Explicit search query blocked.")
                        }
                        return
                    }
                }
            }
        }

        if (isIgnoredSystemOrKeyboardPackage(pkg)) return
        currentForegroundPkg = pkg
        if (pkg == packageName) return

        // 3. THROTTLED NODE-TREE CHECKS (Max once every 180ms -> keeps scrolling 120Hz silky smooth!)
        if (now - lastTreeCheckTime >= 180L) {
            lastTreeCheckTime = now
            val root = rootInActiveWindow
            if (root != null) {
                // 3A. Reddit 18+ Mature Toggle Guard
                if (pkg == "com.reddit.frontpage") {
                    if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                        val clickedText = ((event.text?.joinToString(" ") ?: "") + " " + (event.contentDescription ?: "")).lowercase()
                        if (clickedText.contains("mature content") || clickedText.contains("over 18") || clickedText.contains("blur nsfw")) {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            showSingleToast("LockSight: Reddit 18+ Mature Content toggle is locked.")
                            return
                        }
                    }
                    val isNsfwGatePopup = containsText(root, listOf("must be 18+", "mature content", "nsfw")) &&
                            containsText(root, listOf("continue", "yes, i'm over 18", "view community")) &&
                            !containsText(root, listOf("account settings", "dark mode"))
                    if (isNsfwGatePopup) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        showSingleToast("LockSight: 18+ NSFW Reddit community blocked.")
                        return
                    }
                }

                // 3B. Universal Browser & Social Search/URL Bar Guard
                if (shouldCheckKeywordsInApp(pkg, root)) {
                    val isBrowser = PolicyController.getCachedBrowserPackages(this).contains(pkg) ||
                            pkg.contains("opera") || pkg.contains("chrome") || pkg.contains("brave") ||
                            pkg.contains("firefox") || pkg.contains("edge") || pkg.contains("helium") || pkg.contains("browser")
                    if (checkSearchOrUrlBarForExplicitInput(root, isBrowser)) {
                        if (now - lastKeywordBackTime > 900L) {
                            lastKeywordBackTime = now
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            showSingleToast("LockSight: Restricted domain or explicit search blocked.")
                        }
                        return
                    }
                }
            }
        }

        // 4. EVENT-DRIVEN AI SCREEN SCAN
        if (aiScanner != null && shouldScanPackage(pkg)) {
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                    runSilentAiScreenCheck(pkg, forceImmediate = false)
                    mainHandler.removeCallbacks(scrollSettleScanRunnable)
                    mainHandler.postDelayed(scrollSettleScanRunnable, 480L)
                }
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    mainHandler.removeCallbacks(scrollSettleScanRunnable)
                    mainHandler.postDelayed(scrollSettleScanRunnable, 420L)
                }
            }
        }
    }

    private fun isSettingsOrPrivacyGuardPackage(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return lower == "com.android.settings" ||
                lower.contains("accessibility") ||
                lower.contains("deviceadmin") ||
                lower.contains("safetycenter") ||
                lower.contains("permissioncontroller") ||
                lower.contains("securitycenter") ||
                lower.contains("safecenter") ||
                lower.contains("oneplus.security") ||
                lower.contains("com.vivo.settings") ||
                lower.contains("com.iqoo.secure") ||
                lower.contains("com.samsung.android.lool")
    }

    /**
     * Cross-brand tamper detector (OnePlus OxygenOS/ColorOS, iQOO/Vivo, Xiaomi HyperOS,
     * Samsung One UI, Stock Android & Android Privacy Controls 'Remove access' card).
     */
    private fun isLockSightTamperScreenActive(event: AccessibilityEvent?): Boolean {
        // Fast check on event text (catches OnePlus 'Disable "LockSight"?' & Stock 'Stop LockSight?' popups instantly!)
        val evText = ((event?.text?.joinToString(" ") ?: "") + " " + (event?.contentDescription ?: "")).lowercase()
        if (evText.contains("disable \"locksight\"") ||
            evText.contains("disable locksight") ||
            evText.contains("stop locksight") ||
            evText.contains("turn off locksight") ||
            evText.contains("use locksight") ||
            evText.contains("deactivate this device admin")
        ) {
            return true
        }

        val root = rootInActiveWindow
        if (root != null && isLockSightDetailOrDialogNode(root)) return true

        try {
            val winList = windows
            if (winList != null) {
                for (w in winList) {
                    val wRoot = w.root ?: continue
                    if (isLockSightDetailOrDialogNode(wRoot)) return true
                }
            }
        } catch (_: Exception) {}

        return false
    }

    private fun isLockSightDetailOrDialogNode(root: AccessibilityNodeInfo): Boolean {
        if (!containsText(root, listOf("locksight"))) return false

        // Allow normal App Info screen (so users can check Battery / Storage / Notifications)
        val isNormalAppInfo = containsText(root, listOf("storage usage", "manage notifications", "battery usage", "data usage"))
        if (isNormalAppInfo) return false

        // Allow the main Accessibility 'Downloaded apps' / 'Installed apps' list so other apps remain usable!
        val isMainListScreen = containsText(
            root,
            listOf(
                "talkback", "select to speak", "display size", "color and motion",
                "hearing enhancements", "vision enhancements", "downloaded apps",
                "installed apps", "installed services", "accessibility on-screen keyboard"
            )
        )
        if (isMainListScreen) return false

        // 1B. Sealed-Only Phone Manager Allowlist Guard (stops removing LockSight from Allowlist during an urge!)
        if (PolicyController.isSystemSealed(this)) {
            val isPhoneManagerAllowlist = containsText(
                root,
                listOf("allowlist", "suspicious activity by the following apps", "block suspicious app activities", "abnormal device control")
            )
            if (isPhoneManagerAllowlist) return true
        }

        // 2. OnePlus / Oppo / Realme (OxygenOS/ColorOS) & iQOO / Vivo (Funtouch/OriginOS) detail screens:
        // Shows 'LockSight' toggle + 'Shortcut' + ('App info' OR 'About this function' OR 'Accessibility')
        val isOemDetailPage = containsText(root, listOf("shortcut")) &&
                containsText(root, listOf("app info", "about this function", "after accessibility features", "auto-launch", "service"))
        if (isOemDetailPage) return true

        // 3. OnePlus / iQOO / Stock Disable Confirmation Dialogs & Device Admin Screens:
        return containsText(
            root,
            listOf(
                "disable \"locksight\"",
                "disable locksight",
                "keep on",
                "use locksight",
                "use service",
                "locksight shortcut",
                "stop locksight",
                "tapping stop",
                "turn off locksight",
                "about this function",
                "deactivate this device admin",
                "remove active admin",
                "options and permissions"
            )
        )
    }

    private fun isClickOnLockSightRemoveAccess(event: AccessibilityEvent): Boolean {
        val clickedText = ((event.text?.joinToString(" ") ?: "") + " " + (event.contentDescription ?: "")).lowercase().trim()
        if (clickedText.length > 35 || !clickedText.contains("remove access")) return false

        // Climb up ONLY inside the clicked alert card (stop before ScrollView/RecyclerView so other apps are never affected)
        var node = event.source?.parent
        var depth = 0
        while (node != null && depth < 4) {
            val cls = node.className?.toString()?.lowercase() ?: ""
            if (cls.contains("scrollview") || cls.contains("recyclerview") || cls.contains("listview")) {
                break
            }
            if (containsText(node, listOf("locksight"))) {
                return true
            }
            node = node.parent
            depth++
        }

        // Fallback when event.source is null on custom OEM skins: check only if LockSight is in the visible review card
        if (event.source == null) {
            val root = rootInActiveWindow ?: return false
            return containsText(root, listOf("review app with full device access")) &&
                    containsText(root, listOf("locksight"))
        }
        return false
    }

    private fun shouldCheckKeywordsInApp(pkg: String, root: AccessibilityNodeInfo?): Boolean {
        if (pkg.isEmpty() || pkg == packageName || isIgnoredSystemOrKeyboardPackage(pkg)) return false
        val lower = pkg.lowercase()
        if (KEYWORD_EXEMPT_APP_TOKENS.any { lower.contains(it) }) return false
        if (PolicyController.getFastExcludedAppsSet(this).contains(pkg)) return false

        if (lower.contains("googlequicksearchbox") && root != null) {
            if (containsText(root, listOf("ask gemini", "gemini", "talk live", "gems", "deep research"))) {
                return false
            }
        }
        return true
    }

    private fun isContinuousMediaApp(pkg: String): Boolean {
        val lower = pkg.lowercase()
        val browsers = PolicyController.getCachedBrowserPackages(this)
        return browsers.contains(pkg) ||
                lower.contains("opera") || lower.contains("brave") || lower.contains("chrome") ||
                lower.contains("firefox") || lower.contains("edge") || lower.contains("helium") ||
                lower.contains("browser") || lower.contains("youtube") || lower.contains("instagram") ||
                lower.contains("telegram") || lower.contains("reddit") || lower.contains("twitter") ||
                lower.contains("substack") || lower.contains("upscrolled") || lower.contains("threads") ||
                lower.contains("gallery") || lower.contains("photos") || lower.contains("vlc") ||
                lower.contains("mxtech") || lower.contains("video") || lower.contains("player") ||
                lower.contains("torrent") || lower.contains("netmirror")
    }

    private fun showSingleToast(msg: String) {
        try {
            guardToast?.cancel()
            guardToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT)
            guardToast?.show()
        } catch (_: Exception) {}
    }

    private fun isIgnoredSystemOrKeyboardPackage(pkg: String): Boolean {
        if (pkg.isEmpty()) return true
        val lower = pkg.lowercase()
        return lower == "android" ||
                lower == "com.android.systemui" ||
                lower == "com.android.settings" ||
                lower.contains("wirelesssettings") ||
                lower.contains("com.oplus.") ||
                lower.contains("com.coloros.") ||
                lower.contains("com.heytap.") ||
                lower.contains("com.qualcomm.") ||
                lower.contains("inputmethod") ||
                lower.contains("gboard") ||
                lower.contains("swiftkey") ||
                lower.contains("keyboard") ||
                lower.contains("permissioncontroller") ||
                lower.contains("screenshot") ||
                lower.contains("packageinstaller") ||
                lower.contains("launcher") ||
                lower.contains("trebuchet")
    }

    private fun shouldScanPackage(pkg: String): Boolean {
        if (pkg.isEmpty() || pkg == packageName || isIgnoredSystemOrKeyboardPackage(pkg)) {
            return false
        }
        val excluded = PolicyController.getFastExcludedAppsSet(this)
        return !excluded.contains(pkg)
    }

    private fun checkSearchOrUrlBarForExplicitInput(node: AccessibilityNodeInfo?, isBrowser: Boolean): Boolean {
        if (node == null) return false
        val id = node.viewIdResourceName?.lowercase() ?: ""
        val className = node.className?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase()?.trim() ?: ""

        if (text.isNotEmpty() && text.length <= 80) {
            val isSearchOrUrlInput = node.isEditable ||
                    node.isFocused ||
                    className.contains("edittext") ||
                    id.contains("url") ||
                    id.contains("address") ||
                    id.contains("search") ||
                    id.contains("omnibox")

            if (isSearchOrUrlInput) {
                if (BLOCKED_URL_DOMAINS.any { text.contains(it) } || text.contains("/xxx")) {
                    return true
                }
                if (BLOCKED_SEARCH_KEYWORDS.any { query -> text.contains(query) }) {
                    return true
                }
            } else if (isBrowser && (text.startsWith("http") || text.contains(".to/") || text.contains(".tw/") || text.contains("1337x"))) {
                if (BLOCKED_URL_DOMAINS.any { text.contains(it) } || text.contains("/xxx")) {
                    return true
                }
            }
        }

        for (i in 0 until node.childCount) {
            if (checkSearchOrUrlBarForExplicitInput(node.getChild(i), isBrowser)) return true
        }
        return false
    }

    private fun containsText(node: AccessibilityNodeInfo?, targets: List<String>): Boolean {
        if (node == null) return false
        val txt = (node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")
        val lower = txt.lowercase()
        if (targets.any { lower.contains(it) }) return true
        for (i in 0 until node.childCount) {
            if (containsText(node.getChild(i), targets)) return true
        }
        return false
    }

    private fun stopVideoAndExitCleanly(scannedPkg: String, reason: String) {
        val livePkg = rootInActiveWindow?.packageName?.toString() ?: currentForegroundPkg
        if (livePkg != scannedPkg || isIgnoredSystemOrKeyboardPackage(livePkg)) {
            return
        }

        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        } catch (_: Exception) {}

        performGlobalAction(GLOBAL_ACTION_BACK)
        showSingleToast("LockSight Aegis: $reason")
    }

    private fun runSilentAiScreenCheck(currentPkg: String, forceImmediate: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val scanner = aiScanner ?: return
        val now = System.currentTimeMillis()

        if (isScanInProgress && (now - lastScanTime) > 2500L) {
            isScanInProgress = false
        }
        val minInterval = if (forceImmediate) 320L else 1400L
        if (isScanInProgress || (now - lastScanTime) < minInterval) return

        lastScanTime = now
        isScanInProgress = true

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            executor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hwBuffer = screenshot.hardwareBuffer
                    try {
                        val colorSpace = screenshot.colorSpace
                        val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                        if (hwBitmap != null) {
                            val targetW = 480
                            val targetH = ((hwBitmap.height.toFloat() / hwBitmap.width.toFloat()) * targetW)
                                .toInt().coerceIn(480, 1080)
                            val compactHw = Bitmap.createScaledBitmap(hwBitmap, targetW, targetH, true)
                            val softBitmap = compactHw.copy(Bitmap.Config.ARGB_8888, false)
                            if (compactHw != hwBitmap) compactHw.recycle()
                            hwBitmap.recycle()

                            if (softBitmap != null) {
                                val prefs = PolicyController.prefs(this@LockSightAccessibilityService)
                                val level = prefs.getInt("ai_level", 1)
                                val result = scanner.analyzeScreen(softBitmap, level)
                                softBitmap.recycle()

                                prefs.edit()
                                    .putString(
                                        "last_ai_telemetry",
                                        "App: ${currentPkg.substringAfterLast('.')} | Exp: ${(result.pornScore * 100).toInt()}% | Rev: ${(result.sexyScore * 100).toInt()}% | ${result.reason}"
                                    )
                                    .apply()

                                if (result.blocked) {
                                    mainHandler.post {
                                        stopVideoAndExitCleanly(currentPkg, result.reason)
                                    }
                                } else if (result.needsFastRecheck) {
                                    mainHandler.removeCallbacks(fastRecheckRunnable)
                                    mainHandler.postDelayed(fastRecheckRunnable, 350L)
                                }
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        try {
                            hwBuffer.close()
                        } catch (_: Exception) {}
                        isScanInProgress = false
                    }
                }

                override fun onFailure(errorCode: Int) {
                    isScanInProgress = false
                }
            }
        )
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        PolicyController.scheduleSelfHealAccessibility(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (activeInstance == this) activeInstance = null
        mainHandler.removeCallbacks(videoHeartbeatRunnable)
        mainHandler.removeCallbacks(scrollSettleScanRunnable)
        mainHandler.removeCallbacks(fastRecheckRunnable)
        PolicyController.scheduleSelfHealAccessibility(this)
        super.onDestroy()
    }

    override fun onInterrupt() {}
}