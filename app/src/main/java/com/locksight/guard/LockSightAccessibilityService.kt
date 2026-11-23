package com.locksight.guard

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
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

        val BLOCKED_URL_DOMAINS = listOf(
            "1337x.", "1377x.", "1337xx.", "x1337x.", "thepiratebay.", "pirateproxy.",
            "torrentgalaxy.", "tgx.rs", "rargb.", "limetorrents.", "dirtytorrents.",
            "bunkr.", "erome.", "redgifs.", "coomer.su", "kemono.su", "celebmasta",
            "pornhub.", "xvideos.", "xnxx.", "xhamster.", "spankbang",
            "eporner.", "hqporner.", "chaturbate.", "stripchat.", "hanime."
        )

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

        private val KEYWORD_EXEMPT_APP_TOKENS = listOf(
            "whatsapp", "signal", "mms", "messaging", "dialer", "contacts",
            "gmail", "outlook", "mail", "protonmail",
            "note", "notes", "keep", "notion", "obsidian", "evernote", "docs", "word", "office",
            "openai", "chatgpt", "anthropic", "claude", "perplexity", "deepseek", "copilot", "bard", "gemini"
        )
    }

    @Volatile private var aiScanner: NsfwAiScanner? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastScanTime = 0L
    private var lastSettingsContentTime = 0L
    private var lastTreeCheckTime = 0L
    private var lastUserActivityTime = 0L
    private var lastSettingsToastTime = 0L
    private var lastSettingsBlockHitTime = 0L
    private var rapidSettingsTamperCount = 0
    private var lastKeywordBackTime = 0L
    private var currentForegroundPkg: String = ""
    private var guardToast: Toast? = null
    @Volatile private var isScanInProgress = false

    // Ultra-low-battery heartbeat: 3.0s normal, 4.5s in Power Saver, 0% when screen OFF or idle!
    private val videoHeartbeatRunnable = object : Runnable {
        override fun run() {
            var nextDelay = 3000L
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (pm.isPowerSaveMode) nextDelay = 4500L

                if (pm.isInteractive && aiScanner != null) {
                    val rootPkg = rootInActiveWindow?.packageName?.toString() ?: ""
                    val activePkg = if (rootPkg.isNotEmpty() && !isIgnoredSystemOrKeyboardPackage(rootPkg)) {
                        currentForegroundPkg = rootPkg
                        rootPkg
                    } else {
                        currentForegroundPkg
                    }
                    val recentlyActive = (System.currentTimeMillis() - lastUserActivityTime) < 12000L
                    if (shouldScanPackage(activePkg) && (isContinuousMediaApp(activePkg) || recentlyActive)) {
                        runSilentAiScreenCheck(activePkg, forceImmediate = false)
                    }
                }
            } catch (_: Exception) {}
            mainHandler.postDelayed(this, nextDelay)
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

        // Defer ONNX load by 2.5s so fresh-install MainActivity cold launch NEVER freezes on the logo!
        mainHandler.postDelayed({
            if (activeInstance == this) {
                executor.execute {
                    try {
                        PolicyController.refreshCachedBrowsers(applicationContext)
                        aiScanner = NsfwAiScanner(applicationContext)
                    } catch (_: Exception) {}
                }
            }
        }, 2500L)

        if (PolicyController.prefs(this).getBoolean("safe_payment_mode_active", false)) {
            PolicyController.restoreAfterAccessibilityReenabled(this)
            showSingleToast("Safe Payment Mode OFF! All apps restored.")
        }
        mainHandler.postDelayed(videoHeartbeatRunnable, 3500L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        val now = System.currentTimeMillis()
        lastUserActivityTime = now
        val evType = event.eventType

        // 1. ZERO-LATENCY SETTINGS, PRIVACY CONTROLS & OEM PHONE MANAGER GUARD
        if (isSettingsOrPrivacyGuardPackage(pkg)) {
            // NEVER throttle click events inside Settings / Phone Manager!
            if (evType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                // 1A. Targeted "Remove access" inside Privacy Controls
                if (isClickOnLockSightRemoveAccess(event)) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    PolicyController.scheduleSelfHealAccessibility(this)
                    showDebouncedSettingsToast(now, "LockSight: Access is locked by System Administrator.")
                    return
                }

                // 1B. Surgical Phone Manager / OEM Allowlist Guard (works immediately, allows other apps & Add!)
                if (isClickRemovingLockSightFromOemProtection(event)) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    showDebouncedSettingsToast(now, "LockSight: Protected inside OEM Security Allowlist.")
                    return
                }

                // 1C. Pre-emptively block tapping "LockSight" row inside Accessibility "Downloaded apps" list!
                // Stops spamming from ever reaching the toggle screen, even 1 second after a fresh install!
                if (isClickOpeningLockSightFromAccessibilityList(event)) {
                    triggerSettingsKickback(now)
                    return
                }
            }

            // NEVER throttle TYPE_WINDOW_STATE_CHANGED! Only throttle rapid duplicate content changes (60ms).
            if (evType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && (now - lastSettingsContentTime) < 60L) {
                return
            }
            if (evType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                lastSettingsContentTime = now
            }

            if (isLockSightTamperScreenActive(event)) {
                triggerSettingsKickback(now)
                return
            }
            return
        }

        // 2. BRAND-AGNOSTIC OEM SECURITY CLICK FALLBACK (Catches unknown brand Phone Managers on any phone!)
        if (evType == AccessibilityEvent.TYPE_VIEW_CLICKED && isPotentialUnknownOemSystemPkg(pkg)) {
            if (isClickRemovingLockSightFromOemProtection(event)) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                showDebouncedSettingsToast(now, "LockSight: Protected inside OEM Security Allowlist.")
                return
            }
        }

        // 3. FAST TYPED KEYWORD INTERCEPTOR
        if (evType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
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

        // 4. THROTTLED NODE-TREE CHECKS (Max once every 240ms -> smooth scrolling & negligible CPU!)
        if (now - lastTreeCheckTime >= 240L) {
            lastTreeCheckTime = now
            val root = rootInActiveWindow
            if (root != null) {
                if (pkg == "com.reddit.frontpage") {
                    if (evType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                        val clickedText = ((event.text?.joinToString(" ") ?: "") + " " + (event.contentDescription ?: "")).lowercase()
                        if (clickedText.contains("mature content") || clickedText.contains("over 18") || clickedText.contains("blur nsfw")) {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            showSingleToast("LockSight: Reddit 18+ Mature Content toggle is locked.")
                            return
                        }
                    }
                    val screenTxt = collectNodeTextFast(root, maxNodes = 80)
                    val isNsfwGatePopup = (screenTxt.contains("must be 18+") || screenTxt.contains("mature content") || screenTxt.contains("nsfw")) &&
                            (screenTxt.contains("continue") || screenTxt.contains("yes, i'm over 18") || screenTxt.contains("view community")) &&
                            !screenTxt.contains("account settings") && !screenTxt.contains("dark mode")
                    if (isNsfwGatePopup) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        showSingleToast("LockSight: 18+ NSFW Reddit community blocked.")
                        return
                    }
                }

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

        // 5. EVENT-DRIVEN AI SCREEN SCAN
        if (aiScanner != null && shouldScanPackage(pkg)) {
            when (evType) {
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                    runSilentAiScreenCheck(pkg, forceImmediate = false)
                    mainHandler.removeCallbacks(scrollSettleScanRunnable)
                    mainHandler.postDelayed(scrollSettleScanRunnable, 500L)
                }
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    mainHandler.removeCallbacks(scrollSettleScanRunnable)
                    mainHandler.postDelayed(scrollSettleScanRunnable, 440L)
                }
            }
        }
    }

    private fun triggerSettingsKickback(now: Long) {
        if (now - lastSettingsBlockHitTime < 3000L) {
            rapidSettingsTamperCount++
        } else {
            rapidSettingsTamperCount = 1
        }
        lastSettingsBlockHitTime = now

        performGlobalAction(GLOBAL_ACTION_BACK)
        if (rapidSettingsTamperCount >= 2) {
            mainHandler.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 80L)
        }
        showDebouncedSettingsToast(now, "LockSight: Protection is locked by System Administrator.")
    }

    private fun showDebouncedSettingsToast(now: Long, msg: String) {
        if (now - lastSettingsToastTime > 2000L) {
            lastSettingsToastTime = now
            showSingleToast(msg)
        }
    }

    private fun isSettingsOrPrivacyGuardPackage(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return lower == "com.android.settings" ||
                lower.contains("oplus.settings") ||
                lower.contains("coloros.settings") ||
                lower.contains("vivo.settings") ||
                lower.contains("miui.settings") ||
                lower.contains("samsung.android.settings") ||
                lower.contains("accessibility") ||
                lower.contains("deviceadmin") ||
                lower.contains("safetycenter") ||
                lower.contains("permissioncontroller") ||
                lower.contains("phonemanager") ||
                lower.contains("securitypermission") ||
                lower.contains("securityguard") ||
                lower.contains("safecenter") ||
                lower.contains("securitycenter") ||
                lower.contains("oneplus.security") ||
                lower.contains("iqoo.secure") ||
                lower.contains("imanager") ||
                lower.contains("samsung.android.lool") ||
                lower.contains("samsung.android.sm") ||
                lower.contains("systemmanager")
    }

    private fun isPotentialUnknownOemSystemPkg(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return lower.startsWith("com.oplus.") ||
                lower.startsWith("com.coloros.") ||
                lower.startsWith("com.heytap.") ||
                lower.startsWith("com.miui.") ||
                lower.startsWith("com.xiaomi.") ||
                lower.startsWith("com.vivo.") ||
                lower.startsWith("com.iqoo.") ||
                lower.startsWith("com.samsung.") ||
                lower.startsWith("com.huawei.") ||
                lower.startsWith("com.hihonor.")
    }

    private fun collectNodeTextFast(root: AccessibilityNodeInfo?, maxNodes: Int = 120, maxDepth: Int = 12): String {
        if (root == null) return ""
        val sb = StringBuilder(512)
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0

        while (queue.isNotEmpty() && visited < maxNodes) {
            val (node, depth) = queue.removeFirst()
            visited++

            val t = node.text
            if (!t.isNullOrEmpty()) sb.append(t).append(' ')
            val cd = node.contentDescription
            if (!cd.isNullOrEmpty()) sb.append(cd).append(' ')

            if (depth < maxDepth) {
                val count = node.childCount
                for (i in 0 until count) {
                    node.getChild(i)?.let { queue.add(it to (depth + 1)) }
                }
            }
        }
        return sb.toString().lowercase()
    }

    private fun isClickOpeningLockSightFromAccessibilityList(event: AccessibilityEvent): Boolean {
        val clickedText = ((event.text?.joinToString(" ") ?: "") + " " + (event.contentDescription ?: "")).lowercase().trim()
        if (!clickedText.contains("locksight")) return false
        val screenText = collectNodeTextFast(rootInActiveWindow, maxNodes = 80)

        // Never intercept taps inside MIUI/HyperOS "Other permissions" or regular App Info screens
        if (screenText.contains("other permissions") ||
            screenText.contains("search among") ||
            screenText.contains("storage usage") ||
            screenText.contains("battery usage")
        ) {
            return false
        }

        return screenText.contains("downloaded apps") ||
                screenText.contains("downloaded services") ||
                screenText.contains("installed apps") ||
                screenText.contains("installed services") ||
                screenText.contains("accessibility")
    }

    private fun isLockSightTamperScreenActive(event: AccessibilityEvent?): Boolean {
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
        if (root != null) {
            val screenText = collectNodeTextFast(root)
            if (isLockSightDetailOrDialogText(screenText)) return true
        }

        try {
            val winList = windows
            if (winList != null) {
                for (w in winList) {
                    val wRoot = w.root ?: continue
                    if (wRoot == root) continue
                    val wText = collectNodeTextFast(wRoot, maxNodes = 40, maxDepth = 8)
                    if (isLockSightDetailOrDialogText(wText)) return true
                }
            }
        } catch (_: Exception) {}

        return false
    }

    private fun isLockSightDetailOrDialogText(screenText: String): Boolean {
        if (!screenText.contains("locksight")) return false

        // Allow normal App Info & MIUI/HyperOS "Other permissions" app lists
        if (screenText.contains("storage usage") ||
            screenText.contains("manage notifications") ||
            screenText.contains("battery usage") ||
            screenText.contains("data usage") ||
            screenText.contains("other permissions") ||
            screenText.contains("search among") ||
            screenText.contains("high-risk permissions")
        ) {
            return false
        }

        // Allow main Accessibility list screens across Pixel, OnePlus, iQOO/Vivo, Xiaomi & Samsung
        if (screenText.contains("talkback") ||
            screenText.contains("select to speak") ||
            screenText.contains("display size") ||
            screenText.contains("color and motion") ||
            screenText.contains("hearing enhancements") ||
            screenText.contains("vision enhancements") ||
            screenText.contains("downloaded apps") ||
            screenText.contains("downloaded services") ||
            screenText.contains("installed apps") ||
            screenText.contains("installed services") ||
            screenText.contains("switch access") ||
            screenText.contains("timing controls") ||
            screenText.contains("accessibility menu") ||
            screenText.contains("accessibility shortcuts") ||
            screenText.contains("interaction control") ||
            screenText.contains("accessibility on-screen keyboard")
        ) {
            return false
        }

        // OnePlus (OxygenOS/ColorOS) & iQOO/Vivo (OriginOS/Funtouch OS) LockSight detail screens
        if (screenText.contains("shortcut") &&
            (screenText.contains("app info") ||
                    screenText.contains("about this function") ||
                    screenText.contains("after accessibility features"))
        ) {
            return true
        }

        return screenText.contains("disable \"locksight\"") ||
                screenText.contains("disable locksight") ||
                (screenText.contains("keep on") && screenText.contains("turn off")) ||
                screenText.contains("use locksight") ||
                screenText.contains("use service") ||
                screenText.contains("locksight shortcut") ||
                screenText.contains("stop locksight") ||
                screenText.contains("tapping stop") ||
                screenText.contains("turn off locksight") ||
                screenText.contains("about this function") ||
                screenText.contains("deactivate this device admin") ||
                screenText.contains("remove active admin") ||
                screenText.contains("options and permissions")
    }

    private fun isClickOnLockSightRemoveAccess(event: AccessibilityEvent): Boolean {
        val clickedText = ((event.text?.joinToString(" ") ?: "") + " " + (event.contentDescription ?: "")).lowercase().trim()
        if (clickedText.length > 35 || !clickedText.contains("remove access")) return false

        var node = event.source?.parent
        var depth = 0
        while (node != null && depth < 4) {
            val cls = node.className?.toString()?.lowercase() ?: ""
            if (cls.contains("scrollview") || cls.contains("recyclerview") || cls.contains("listview")) {
                break
            }
            if (collectNodeTextFast(node, maxNodes = 25, maxDepth = 5).contains("locksight")) {
                return true
            }
            node = node.parent
            depth++
        }

        if (event.source == null) {
            val txt = collectNodeTextFast(rootInActiveWindow, maxNodes = 90)
            return txt.contains("review app with full device access") && txt.contains("locksight")
        }
        return false
    }

    private fun isClickRemovingLockSightFromOemProtection(event: AccessibilityEvent): Boolean {
        val root = rootInActiveWindow ?: return false
        val screenText = collectNodeTextFast(root, maxNodes = 100)

        // Always allow adding LockSight to the allowlist!
        if (screenText.contains("add to allowlist")) return false
        if (!screenText.contains("locksight")) return false

        val isProtectionListScreen = screenText.contains("remove from exceptions") ||
                screenText.contains("suspicious activity by the following apps") ||
                screenText.contains("never auto sleeping") ||
                screenText.contains("autostart") ||
                screenText.contains("protected apps") ||
                screenText.contains("high background power consumption")
        if (!isProtectionListScreen) return false

        val clickedText = ((event.text?.joinToString(" ") ?: "") + " " + (event.contentDescription ?: "")).lowercase().trim()

        // Allow entering Edit mode & managing other apps
        if (clickedText == "edit" || clickedText == "cancel" || clickedText == "add apps") return false

        // 1. Block "Select all" while LockSight is listed on the removal screen
        if (clickedText == "select all" || clickedText == "all") return true

        // 2. Block clicking "Remove from exceptions" / "Remove" if LockSight is checked
        if (clickedText.contains("remove from exceptions") || clickedText == "remove") {
            if (isLockSightCheckboxCheckedInTree(root)) return true
        }

        // 3. Direct click on LockSight row text
        if (clickedText.length <= 45 && clickedText.contains("locksight")) return true

        // 4. Click on LockSight's checkbox/switch inside its row container
        var node = event.source
        var depth = 0
        while (node != null && depth < 4) {
            val cls = node.className?.toString()?.lowercase() ?: ""
            if (cls.contains("recyclerview") || cls.contains("listview") || cls.contains("scrollview")) {
                break
            }
            if (collectNodeTextFast(node, maxNodes = 15, maxDepth = 4).contains("locksight")) {
                return true
            }
            node = node.parent
            depth++
        }
        return false
    }

    private fun isLockSightCheckboxCheckedInTree(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 100) {
            val node = queue.removeFirst()
            visited++
            val t = (node.text?.toString() ?: "").lowercase()
            if (t.contains("locksight")) {
                val row = node.parent?.parent ?: node.parent ?: node
                if (hasAnyCheckedBox(row)) return true
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun hasAnyCheckedBox(container: AccessibilityNodeInfo): Boolean {
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(container)
        var visited = 0
        while (q.isNotEmpty() && visited < 20) {
            val n = q.removeFirst()
            visited++
            if (n.isCheckable && n.isChecked) return true
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { q.add(it) }
            }
        }
        return false
    }

    private fun shouldCheckKeywordsInApp(pkg: String, root: AccessibilityNodeInfo?): Boolean {
        if (pkg.isEmpty() || pkg == packageName || isIgnoredSystemOrKeyboardPackage(pkg)) return false
        val lower = pkg.lowercase()
        if (KEYWORD_EXEMPT_APP_TOKENS.any { lower.contains(it) }) return false
        if (PolicyController.getFastExcludedAppsSet(this).contains(pkg)) return false

        if (lower.contains("googlequicksearchbox") && root != null) {
            val txt = collectNodeTextFast(root, maxNodes = 60, maxDepth = 8)
            if (txt.contains("ask gemini") || txt.contains("gemini") || txt.contains("talk live") || txt.contains("gems")) {
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

    private fun checkSearchOrUrlBarForExplicitInput(root: AccessibilityNodeInfo?, isBrowser: Boolean): Boolean {
        if (root == null) return false
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0

        while (queue.isNotEmpty() && visited < 85) {
            val (node, depth) = queue.removeFirst()
            visited++

            val text = node.text?.toString()?.lowercase()?.trim() ?: ""
            if (text.isNotEmpty() && text.length <= 80) {
                val id = node.viewIdResourceName?.lowercase() ?: ""
                val className = node.className?.toString()?.lowercase() ?: ""
                val isSearchOrUrlInput = node.isEditable ||
                        node.isFocused ||
                        className.contains("edittext") ||
                        id.contains("url") ||
                        id.contains("address") ||
                        id.contains("search") ||
                        id.contains("omnibox")

                if (isSearchOrUrlInput) {
                    if (BLOCKED_URL_DOMAINS.any { text.contains(it) } || text.contains("/xxx")) return true
                    if (BLOCKED_SEARCH_KEYWORDS.any { text.contains(it) }) return true
                } else if (isBrowser && (text.startsWith("http") || text.contains(".to/") || text.contains(".tw/") || text.contains("1337x"))) {
                    if (BLOCKED_URL_DOMAINS.any { text.contains(it) } || text.contains("/xxx")) return true
                }
            }

            if (depth < 10) {
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it to (depth + 1)) }
                }
            }
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
        val minInterval = if (forceImmediate) 320L else 1500L
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
                            // 384px wide: 36% less RAM & bandwidth on low-end phones while staying sharper than 320px input!
                            val targetW = 384
                            val targetH = ((hwBitmap.height.toFloat() / hwBitmap.width.toFloat()) * targetW)
                                .toInt().coerceIn(384, 864)
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

    override fun onUnbind(intent: Intent?): Boolean {
        PolicyController.scheduleSelfHealAccessibility(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (activeInstance == this) activeInstance = null
        mainHandler.removeCallbacks(videoHeartbeatRunnable)
        mainHandler.removeCallbacks(scrollSettleScanRunnable)
        mainHandler.removeCallbacks(fastRecheckRunnable)
        try {
            executor.shutdownNow()
        } catch (_: Exception) {}
        PolicyController.scheduleSelfHealAccessibility(this)
        super.onDestroy()
    }

    override fun onInterrupt() {}
}