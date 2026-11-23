package com.locksight.guard

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import android.Manifest
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.UserManager
import android.provider.Settings
import android.text.InputType
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    companion object {
        private const val AI_SHIELD_NAME = "Aegis Neural Core"
        private const val APP_TAGLINE = "Autonomous System & Vision Guard"
    }

    private lateinit var rootLayout: LinearLayout
    private lateinit var mainScrollView: ScrollView

    private var currentTab = "SHIELD"
    private var skipNextResumeRender = false
    private val mainTabsOrder = listOf("SHIELD", "ZEN", "REMINDERS")

    private var zenTimer: CountDownTimer? = null
    private var zenAudioThread: Thread? = null
    private var safePaySubtitleView: TextView? = null
    private val safePayTickerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val safePayTickerRunnable = object : Runnable {
        override fun run() {
            val prefs = PolicyController.prefs(this@MainActivity)
            if (prefs.getBoolean("safe_payment_mode_active", false)) {
                val until = prefs.getLong("safe_pay_until", 0L)
                val remSec = ((until - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
                if (remSec > 0L) {
                    val m = remSec / 60
                    val s = remSec % 60
                    safePaySubtitleView?.text = String.format("ACTIVE (%02d:%02d left • Browsers Open on NextDNS)", m, s)
                    safePayTickerHandler.postDelayed(this, 1000L)
                } else {
                    PolicyController.checkAndRestoreSafePayIfExpired(this@MainActivity)
                    safePaySubtitleView?.text = "3-Min Timer Ended • Tap Exit or Enable Accessibility"
                }
            }
        }
    }
    private var rawMediaPlayer: MediaPlayer? = null
    @Volatile private var isZenAudioPlaying = false
    @Volatile private var selectedSoundscapeMode = 0

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dpF(value: Float): Float = value * resources.displayMetrics.density

    data class SpiritualReminder(
        val tradition: String,
        val categoryBadge: String,
        val title: String,
        val arabicOrOriginal: String,
        val quote: String,
        val source: String,
        val actionStep: String,
        val tags: List<String>
    )

    private val offlineReminders = listOf(
        SpiritualReminder(
            "Islamic",
            "QURANIC COMMAND • SURAH AN-NUR",
            "Guarding the Gaze & the Sweetness of Iman",
            "قُل لِّلْمُؤْمِنِينَ يَغُضُّوا مِنْ أَبْصَارِهِمْ وَيَحْفَظُوا فُرُوجَهُمْ ۚ ذَٰلِكَ أَزْكَىٰ لَهُمْ",
            "\"Tell the believing men to lower their gaze and guard their chastity. That is purer for them. Indeed, Allah is All-Aware of what they do.\"",
            "Surah An-Nur (24:30)",
            "Iman Action: Whoever lowers his gaze for Allah, Allah grants him a sweetness of faith and light that he feels directly in his heart.",
            listOf("Hifz al-Basar", "Sweetness of Iman", "Purity")
        ),
        SpiritualReminder(
            "Islamic",
            "TAQWA IN SOLITUDE • SURAH AL-ALAQ",
            "When the Door Is Locked & the Room Is Dark",
            "أَلَمْ يَعْلَم بِأَنَّ اللَّهَ يَرَىٰ",
            "\"Does he not know that Allah sees?\"",
            "Surah Al-Alaq (96:14) & Sunan Ibn Majah (4245)",
            "Iman Action: Beware of sins committed in secret when you are alone with your phone. Make Allah more honored in your sight than any human being.",
            listOf("Muraqabah", "Secret Deeds", "Taqwa")
        ),
        SpiritualReminder(
            "Islamic",
            "PROPHETIC PROMISE • DIVINE COMPENSATION",
            "The Guarantee of Replacement (Al-'Iwad)",
            "إِنَّكَ لَنْ تَدَعَ شَيْئًا لِلَّهِ عَزَّ وَجَلَّ إِلَّا بَدَّلَكَ اللَّهُ بِهِ مَا هُوَ خَيْرٌ لَكَ مِنْهُ",
            "\"Verily, you will never leave anything for the sake of Allah Almighty, but that Allah will replace it with something far better for you.\"",
            "Musnad Ahmad (23074 — Sahih)",
            "Iman Action: Every urge you reject today is recorded as a victory. Allah replaces forbidden pixels with barakah in your provision, clarity, and future marriage.",
            listOf("Sabr", "Barakah", "Future Marriage")
        ),
        SpiritualReminder(
            "Islamic",
            "CLASSICAL MEDICINE OF THE HEART",
            "Breaking the Chain at the First Thought",
            "الخطرات مبدأ الآفات، فمن ملك خاطره ملك زمام نفسه",
            "\"The gaze generates a thought, the thought generates a desire, and the desire becomes an action. Repelling the first fleeting thought is easy; repelling it after it takes root is like uprooting a mountain.\"",
            "Imam Ibn al-Qayyim — Al-Jawab al-Kafi",
            "Iman Action: Do not negotiate with the first urge. The moment a trigger whispers to you, seek refuge in Allah and stand up from your seat.",
            listOf("Tazkiyah", "Ibn al-Qayyim", "First 5 Seconds")
        ),
        SpiritualReminder(
            "Hindu",
            "BHAGAVAD GITA • SANKHYA YOGA",
            "Mastering the Restless Senses (Indriya-Jaya)",
            "इन्द्रियाणां हि चरतां यन्मनोऽनुविधीयते । तदस्य हरति प्रज्ञां वायुर्नावमिवम्भसि ॥",
            "\"As a strong wind sweeps away a boat on the water, even one of the roaming senses on which the mind focuses can carry away a person's intelligence.\"",
            "Bhagavad Gita (2.67)",
            "Dharma Action: Withdraw your attention from the screen for 3 minutes. When the senses are restrained, Buddhi (spiritual intelligence) becomes unshakable.",
            listOf("Indriya-Jaya", "Buddhi", "Self-Mastery")
        ),
        SpiritualReminder(
            "Hindu",
            "BHAGAVAD GITA • KARMA YOGA",
            "Conquering the Insatiable Fire of Lust",
            "एवं बुद्धेः परं बुद्ध्वा संस्तभ्यात्मानमात्मना । जहि शत्रुं महाबाहो कामरूपं दुरासदम् ॥",
            "\"Thus knowing oneself to be transcendental to the material senses and mind, O mighty-armed Arjuna, steady the mind by deliberate spiritual intelligence and conquer this insatiable enemy known as lust.\"",
            "Bhagavad Gita (3.43)",
            "Dharma Action: Feeding desire never extinguishes it—it is like pouring ghee onto fire. Starve the impulse today and reclaim your inner Ojas (vital energy).",
            listOf("Ojas", "Brahmacharya", "Inner Fire")
        ),
        SpiritualReminder(
            "Christian",
            "BOOK OF JOB & GOSPEL OF MATTHEW",
            "A Sacred Covenant With the Eyes",
            "Job 31:1 • Matthew 5:8",
            "\"I have made a covenant with my eyes; how then could I gaze with lust? Blessed are the pure in heart, for they shall see God.\"",
            "Job 31:1 & Matthew 5:8",
            "Grace Action: The eye is the lamp of the body. Guard the gateway of your sight for the next 10 minutes, and peace will return to your spirit.",
            listOf("Covenant", "Pure Heart", "Light")
        ),
        SpiritualReminder(
            "Christian",
            "FIRST EPISTLE TO THE CORINTHIANS",
            "Taking the Way of Escape",
            "1 Corinthians 10:13",
            "\"God is faithful; He will not let you be tempted beyond what you can bear. But when you are tempted, He will also provide a way out so that you can endure it.\"",
            "1 Corinthians 10:13",
            "Grace Action: This blocked screen is your way of escape right now. Switch to the Zen tab, lock the phone for 5 minutes, and walk away victorious.",
            listOf("Endurance", "Way of Escape", "Strength")
        ),
        SpiritualReminder(
            "Universal",
            "BEHAVIORAL NEUROSCIENCE • DELTA-FOSB",
            "The 8-Minute Dopamine Spike",
            "PREFRONTAL CORTEX OVERRIDE",
            "\"A compulsive urge is a temporary neurochemical surge in the midbrain. It cannot sustain peak intensity for more than 8 to 10 minutes unless you feed it new visual stimuli.\"",
            "Neurobiology of Habit & Dopamine Regulation",
            "Action Protocol: Every time you starve an urge without acting on it, you weaken the DeltaFosB addiction pathway and physically strengthen your prefrontal willpower.",
            listOf("Neuroplasticity", "Dopamine Reset", "Focus")
        ),
        SpiritualReminder(
            "Universal",
            "STOIC PHILOSOPHY • SELF-COMMAND",
            "Sovereignty Over Your Own Mind",
            "ΑΥΤΑΡΚΕΙΑ • SELF-MASTERY",
            "\"No man is free who is not master of himself. Conquer your impulses in the first five seconds before they conquer your day.\"",
            "Epictetus — Discourses",
            "Action Protocol: Trade 5 minutes of artificial stimulation for 24 hours of unshakeable self-respect, sharp focus, and natural energy.",
            listOf("Stoicism", "Sovereignty", "Discipline")
        )
    )

    private val liveTickerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val liveTickerRunnable = object : Runnable {
        override fun run() {
            val p = PolicyController.prefs(this@MainActivity)
            val now = System.currentTimeMillis()
            val hasRunningCountdown = p.getLong("gplinks_bypass_until", 0L) > now ||
                    p.getLong("ai_cooldown_ready_at", 0L) > now ||
                    p.getLong("full_vpn_ready_at", 0L) > now ||
                    p.getLong("full_vpn_active_until", 0L) > now ||
                    p.getLong("adb_ready_at", 0L) > now ||
                    p.getLong("adb_active_until", 0L) > now

            if (hasRunningCountdown && currentTab == "SHIELD" && currentFocus !is EditText) {
                renderScreen()
            }
            liveTickerHandler.postDelayed(this, 30000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        PolicyController.syncSealStateWithDeviceOwner(this)
        PolicyController.revertToMainDnsIfExpired(this)
        PolicyController.checkAndRelockFullVpn(this)
        PolicyController.checkAndRelockAdb(this)
        PolicyController.prewarmExcludedAppCachesAsync(this)

        val prefs = PolicyController.prefs(this)
        if (prefs.getLong("clean_streak_start", 0L) == 0L) {
            prefs.edit().putLong("clean_streak_start", System.currentTimeMillis()).apply()
        }

        @Suppress("DEPRECATION")
        window.statusBarColor = Color.parseColor("#0B0F19")
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.parseColor("#0B0F19")

        val outerFrame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0F19"))
        }

        val swipeDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                if (abs(diffX) > abs(diffY) * 1.4f && abs(diffX) > dp(55) && abs(velocityX) > 220) {
                    val idx = mainTabsOrder.indexOf(currentTab)
                    if (idx != -1) {
                        if (diffX < 0 && idx < mainTabsOrder.size - 1) {
                            currentTab = mainTabsOrder[idx + 1]
                            renderScreen()
                            mainScrollView.scrollTo(0, 0)
                            return true
                        } else if (diffX > 0 && idx > 0) {
                            currentTab = mainTabsOrder[idx - 1]
                            renderScreen()
                            mainScrollView.scrollTo(0, 0)
                            return true
                        }
                    }
                }
                return false
            }
        })

        skipNextResumeRender = true

        mainScrollView = object : ScrollView(this) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                swipeDetector.onTouchEvent(ev)
                return super.dispatchTouchEvent(ev)
            }
        }.apply {
            setBackgroundColor(Color.parseColor("#0B0F19"))
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(32))
        }

        mainScrollView.addView(rootLayout)
        outerFrame.addView(mainScrollView)

        ViewCompat.setOnApplyWindowInsetsListener(outerFrame) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        setContentView(outerFrame)
        renderScreen()

        if (!prefs.getBoolean("faith_chosen_once", false)) {
            rootLayout.post {
                showCustomFaithPersonalizationDialog(isFirstLaunch = true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        PolicyController.syncSealStateWithDeviceOwner(this)
        PolicyController.revertToMainDnsIfExpired(this)
        PolicyController.checkAndRestoreSafePayIfExpired(this)
        PolicyController.handleSelfHealAccessibility(this)
        if (skipNextResumeRender) {
            skipNextResumeRender = false
        } else {
            renderScreen()
        }
        liveTickerHandler.removeCallbacks(liveTickerRunnable)
        liveTickerHandler.postDelayed(liveTickerRunnable, 30000L)
        safePayTickerHandler.removeCallbacks(safePayTickerRunnable)
        safePayTickerHandler.post(safePayTickerRunnable)
    }

    override fun onPause() {
        super.onPause()
        liveTickerHandler.removeCallbacks(liveTickerRunnable)
        safePayTickerHandler.removeCallbacks(safePayTickerRunnable)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200)
            }
        }
    }

    private fun renderScreen() {
        rootLayout.removeAllViews()
        renderTopLogoAndNavBar()

        when (currentTab) {
            "SHIELD" -> renderShieldDashboard()
            "ZEN" -> renderZenAndStreakScreen()
            "REMINDERS" -> renderRemindersScreen()
            "CHECKLIST" -> renderChecklistScreen()
            "FEATURES" -> renderFeaturesScreen()
            "ABOUT" -> renderAboutScreen()
        }
    }

    // =========================================================================
    // TOP BAR: DENSITY-SCALED (`dp()`) SHIELD LOGO, MENU BUTTON & 3 HOME TABS
    // =========================================================================
    private fun renderTopLogoAndNavBar() {
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(14))
        }

        val logoView = object : View(this) {
            private val shieldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#38BDF8")
                style = Paint.Style.FILL
            }
            private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#0B0F19")
                style = Paint.Style.FILL
            }
            private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#10B981")
                style = Paint.Style.FILL
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val w = width.toFloat()
                val h = height.toFloat()
                val path = Path().apply {
                    moveTo(w * 0.5f, h * 0.06f)
                    lineTo(w * 0.90f, h * 0.22f)
                    lineTo(w * 0.90f, h * 0.54f)
                    quadTo(w * 0.90f, h * 0.84f, w * 0.5f, h * 0.96f)
                    quadTo(w * 0.10f, h * 0.84f, w * 0.10f, h * 0.54f)
                    lineTo(w * 0.10f, h * 0.22f)
                    close()
                }
                canvas.drawPath(path, shieldPaint)
                canvas.drawOval(w * 0.24f, h * 0.36f, w * 0.76f, h * 0.64f, innerPaint)
                canvas.drawCircle(w * 0.5f, h * 0.5f, w * 0.11f, pupilPaint)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) }
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply {
                text = "LockSight"
                textSize = 24f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
            })
            addView(TextView(this@MainActivity).apply {
                text = APP_TAGLINE
                textSize = 12f
                setTextColor(Color.parseColor("#64748B"))
            })
        }

        val menuBtn = object : View(this) {
            private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#161D2F")
                style = Paint.Style.FILL
            }
            private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#263554")
                style = Paint.Style.STROKE
                strokeWidth = dpF(1.5f)
            }
            private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#38BDF8")
                style = Paint.Style.STROKE
                strokeWidth = dpF(2.6f)
                strokeCap = Paint.Cap.ROUND
            }
            private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#10B981")
                style = Paint.Style.FILL
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val w = width.toFloat()
                val h = height.toFloat()
                val rad = dpF(13f)
                canvas.drawRoundRect(3f, 3f, w - 3f, h - 3f, rad, rad, bgPaint)
                canvas.drawRoundRect(3f, 3f, w - 3f, h - 3f, rad, rad, borderPaint)

                canvas.drawLine(w * 0.26f, h * 0.33f, w * 0.74f, h * 0.33f, barPaint)
                canvas.drawLine(w * 0.35f, h * 0.50f, w * 0.74f, h * 0.50f, barPaint)
                canvas.drawLine(w * 0.26f, h * 0.67f, w * 0.58f, h * 0.67f, barPaint)
                canvas.drawCircle(w * 0.70f, h * 0.67f, dpF(2.2f), accentPaint)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(46))
            isClickable = true
            isFocusable = true
            setOnClickListener { showMinimalExecutiveMenuDialog() }
        }

        headerRow.addView(logoView)
        headerRow.addView(titleCol)
        headerRow.addView(menuBtn)
        rootLayout.addView(headerRow)

        if (currentTab == "CHECKLIST" || currentTab == "FEATURES" || currentTab == "ABOUT") {
            val backBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBg("#161D2F", 14f)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(14) }
                setOnClickListener {
                    currentTab = "SHIELD"
                    renderScreen()
                }
            }
            backBar.addView(TextView(this).apply {
                text = "←   RETURN TO MAIN DASHBOARD"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#38BDF8"))
                letterSpacing = 0.05f
            })
            rootLayout.addView(backBar)
            return
        }

        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedBg("#161D2F", 16f)
            setPadding(dp(5), dp(5), dp(5), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }

        fun navTab(label: String, tabId: String) = TextView(this).apply {
            text = label
            textSize = 13.5f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            val active = currentTab == tabId
            setTextColor(if (active) Color.WHITE else Color.parseColor("#94A3B8"))
            background = if (active) roundedBg("#2563EB", 12f) else null
            setPadding(0, dp(11), 0, dp(11))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                currentTab = tabId
                renderScreen()
            }
        }

        navRow.addView(navTab("Shield", "SHIELD"))
        navRow.addView(navTab("Zen & Streak", "ZEN"))
        navRow.addView(navTab("Reminders", "REMINDERS"))
        rootLayout.addView(navRow)
    }

    private fun showMinimalExecutiveMenuDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val prefs = PolicyController.prefs(this)
        val currentFaith = prefs.getString("tradition_filter", "Universal") ?: "Universal"

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBgWithBorder("#0F172A", "#263554", 20f, dp(1))
            setPadding(dp(20), dp(20), dp(20), dp(18))
        }

        container.addView(TextView(this).apply {
            text = "LOCKSIGHT MENU"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            letterSpacing = 0.12f
            setPadding(dp(2), 0, 0, dp(12))
        })

        fun makeMenuOption(code: String, title: String, subtitle: String, accentHex: String, onClick: () -> Unit) =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBg("#162038", 14f)
                setPadding(dp(14), dp(14), dp(14), dp(14))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }

                val codeBadge = TextView(this@MainActivity).apply {
                    text = code
                    textSize = 13f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor(accentHex))
                    gravity = Gravity.CENTER
                    background = roundedBg("#0B0F19", 10f)
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(12) }
                }

                val col = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(this@MainActivity).apply {
                        text = title
                        textSize = 15.5f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(Color.WHITE)
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = subtitle
                        textSize = 12f
                        setTextColor(Color.parseColor("#94A3B8"))
                        setPadding(0, dp(2), 0, 0)
                    })
                }

                addView(codeBadge)
                addView(col)
                setOnClickListener {
                    dialog.dismiss()
                    onClick()
                }
            }

        container.addView(makeMenuOption(
            "01",
            "Setup Checklist",
            "Verify all 9 protection layers & permissions",
            "#10B981"
        ) {
            currentTab = "CHECKLIST"
            renderScreen()
        })

        container.addView(makeMenuOption(
            "02",
            "Features",
            "Quick Settings tiles, 6-layer shield & daily playbook",
            "#0EA5E9"
        ) {
            currentTab = "FEATURES"
            renderScreen()
        })

        container.addView(makeMenuOption(
            "03",
            "Faith Personalization",
            "Active: $currentFaith (Personalize Home & Reminders)",
            "#38BDF8"
        ) {
            showCustomFaithPersonalizationDialog(isFirstLaunch = false)
        })

        container.addView(makeMenuOption(
            "04",
            "About",
            "App version, hardware info & GNU AGPL-3.0 license",
            "#A855F7"
        ) {
            currentTab = "ABOUT"
            renderScreen()
        })

        container.addView(makeButton("Close", "#1E293B", true) {
            dialog.dismiss()
        })

        dialog.setContentView(container)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.90).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    private fun showCustomFaithPersonalizationDialog(isFirstLaunch: Boolean) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(!isFirstLaunch)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val prefs = PolicyController.prefs(this)
        val current = prefs.getString("tradition_filter", "Universal") ?: "Universal"

        val scrollWrapper = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBgWithBorder("#0F172A", "#263554", 20f, dp(1))
            setPadding(dp(20), dp(20), dp(20), dp(18))
        }
        scrollWrapper.addView(container)

        container.addView(TextView(this).apply {
            text = if (isFirstLaunch) "WELCOME TO LOCKSIGHT" else "FAITH PERSONALIZATION"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#10B981"))
            letterSpacing = 0.12f
        })

        container.addView(TextView(this).apply {
            text = "Choose Your Reflection Path"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, dp(4), 0, dp(4))
        })

        container.addView(TextView(this).apply {
            text = "Personalize LockSight's daily reflections and self-accountability checklist to match your faith or philosophy (you can change this anytime in the top-right Menu):"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, dp(12))
        })

        val options = listOf(
            Triple("Islamic", "Islamic • Quran & Sunnah", "Verses on Hifz al-Basar (lowering the gaze), Taqwa & daily Muhasabah"),
            Triple("Hindu", "Hindu • Bhagavad Gita & Upanishads", "Verses on Indriya-Jaya (mastering the senses), Buddhi & inner Ojas"),
            Triple("Christian", "Christian • Bible Scripture", "Scriptures on pure heart, covenant with the eyes & endurance"),
            Triple("Universal", "Universal • Neuroscience & Stoicism", "Dopamine regulation science, neuroplasticity & Stoic self-command"),
            Triple("All", "All Traditions • Combined Library", "Rotates daily across all spiritual & philosophical traditions"),
            Triple("Hidden", "Minimal Mode • Hide Reflection Card", "Hides the daily reflection banner on the main Shield dashboard")
        )

        options.forEach { (key, title, desc) ->
            val isSelected = current == key && prefs.getBoolean("faith_chosen_once", false)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBgWithBorder(
                    if (isSelected) "#132F4C" else "#162038",
                    if (isSelected) "#38BDF8" else "#263554",
                    13f,
                    dp(1)
                )
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }

                addView(TextView(this@MainActivity).apply {
                    text = if (isSelected) "✓  $title" else title
                    textSize = 14.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(if (isSelected) Color.parseColor("#38BDF8") else Color.WHITE)
                })
                addView(TextView(this@MainActivity).apply {
                    text = desc
                    textSize = 12f
                    setTextColor(Color.parseColor("#94A3B8"))
                    setPadding(0, dp(3), 0, 0)
                })

                setOnClickListener {
                    prefs.edit()
                        .putString("tradition_filter", key)
                        .putBoolean("faith_chosen_once", true)
                        .commit()
                    dialog.dismiss()
                    toast("Personalization set to: $key")
                    renderScreen()
                }
            }
            container.addView(card)
        }

        if (!isFirstLaunch) {
            container.addView(makeButton("Cancel", "#1E293B", true) {
                dialog.dismiss()
            })
        }

        dialog.setContentView(scrollWrapper)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    // =========================================================================
    // TAB 1: SHIELD DASHBOARD
    // =========================================================================
    private fun renderShieldDashboard() {
        val isOwner = PolicyController.isDeviceOwner(this)
        val isSealed = PolicyController.isSystemSealed(this)
        val isAcc2Sealed = PolicyController.isAccount2Sealed(this)
        val isDnsLocked = PolicyController.isPrivateDnsLocked(this)
        val isAdbLocked = PolicyController.isAdbLocked(this)
        val isAccOn = PolicyController.isAccessibilityServiceEnabled(this)
        val prefs = PolicyController.prefs(this)

        val selectedTradition = prefs.getString("tradition_filter", "Universal") ?: "Universal"
        if (selectedTradition != "Hidden") {
            val filteredList = offlineReminders.filter { selectedTradition == "All" || it.tradition == selectedTradition }
                .ifEmpty { offlineReminders }
            val dayOffset = prefs.getInt("reflection_offset", 0)
            val dayIndex = (Calendar.getInstance().get(Calendar.DAY_OF_YEAR) + dayOffset) % filteredList.size
            val todayReminder = filteredList[dayIndex]

            rootLayout.addView(makeCard().apply {
                addView(makeCardHeader("✨", "#10B981", "Daily Reflection • ${todayReminder.tradition}", todayReminder.title))
                addView(TextView(this@MainActivity).apply {
                    text = todayReminder.quote
                    textSize = 14f
                    setTypeface(null, Typeface.ITALIC)
                    setTextColor(Color.parseColor("#F8FAFC"))
                    setPadding(0, dp(3), 0, dp(4))
                })
                addView(TextView(this@MainActivity).apply {
                    text = "— ${todayReminder.source}"
                    textSize = 12.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#38BDF8"))
                })
            })
        }

        val slotText = PolicyController.getRotationStatusLabel(this)

        rootLayout.addView(makeCard().apply {
            addView(makeCardHeader(
                "🔒",
                "#38BDF8",
                "System Protection Status",
                if (isSealed && isDnsLocked) "DNS Shield Active • Open Menu -> Setup Checklist to verify" else "Setup Open • Open Menu -> Setup Checklist for guide"
            ))
            addView(makeStatusRow("Device Owner Core", if (isOwner) "ACTIVE" else "PENDING ADB", isOwner))
            addView(makeStatusRow("Accessibility Guard", if (isAccOn) "ACTIVE" else "OFF (See Setup Checklist)", isAccOn))
            addView(makeStatusRow("Private DNS Menu", if (isDnsLocked) "GRAYED OUT & LOCKED" else "OPEN", isDnsLocked))
            addView(makeStatusRow("ADB & Developer Options", if (isAdbLocked) "LOCKED" else "UNLOCKED", isAdbLocked))
            addView(makeStatusRow("DNS Rotation Mode", slotText, true))
            addView(makeStatusRow("Active Endpoint", PolicyController.getCurrentLiveDnsEndpoint(this@MainActivity), true))
        })

        // --- CARD 1: ONE-TIME NEXTDNS SETUP ---
        rootLayout.addView(makeCard().apply {
            addView(makeCardHeader(
                "🌐",
                if (isSealed) "#10B981" else "#EF4444",
                "One-Time NextDNS Setup",
                if (isSealed) "DNS Sealed & Locked (ADB controlled separately below)" else "Locks Private DNS & Uninstall (VPN & ADB controlled separately)"
            ))

            if (isSealed) {
                addView(makeBody("Your NextDNS configuration is permanently sealed into Android's protected storage."))
                addView(makeStatusRow("Account 1 Main (1–15)", prefs.getString("acc1_main", "") ?: "", true))
                addView(makeStatusRow("Account 1 Bypass (1–15)", prefs.getString("acc1_bypass", "") ?: "", true))

                val savedAcc2Main = prefs.getString("acc2_main", "") ?: ""
                val savedAcc2Bypass = prefs.getString("acc2_bypass", "") ?: ""
                if (isAcc2Sealed && savedAcc2Main.isNotBlank()) {
                    addView(makeStatusRow("Account 2 Main (16–30)", savedAcc2Main, true))
                    addView(makeStatusRow("Account 2 Bypass (16–30)", savedAcc2Bypass, true))
                }

                addView(makeButton("✓ NEXTDNS SEALED & LOCKED PERMANENTLY", "#1E293B", false) {})

                if (isAcc2Sealed) {
                    addView(makeButton("✓ ACCOUNT 2 SEALED & LOCKED PERMANENTLY", "#1E293B", false) {})
                } else {
                    var showAcc2Editor = false
                    val acc2Box = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        visibility = View.GONE
                    }
                    acc2Box.addView(makeFieldLabel("Account 2 — Main Profile ID (Active Days 16–30):"))
                    val postAcc2Main = makeInput("e.g. 9f77cc (6-char NextDNS ID)", "")
                    acc2Box.addView(postAcc2Main)

                    acc2Box.addView(makeFieldLabel("Account 2 — Ad-Bypass Profile ID (Active Days 16–30):"))
                    val postAcc2Bypass = makeInput("e.g. d4e5f6 (Ads Allowed, Adult Blocked)", "")
                    acc2Box.addView(postAcc2Bypass)

                    acc2Box.addView(makeButton("Seal & Lock Account 2 Permanently (One-Time Only)", "#EF4444", true) {
                        val (ok, msg) = PolicyController.sealSecondAccountPostSeal(
                            this@MainActivity,
                            postAcc2Main.text.toString(),
                            postAcc2Bypass.text.toString()
                        )
                        toast(msg)
                        if (ok) renderScreen()
                    })

                    addView(makeButton("+ Link 2nd NextDNS Account (One-Time Seal for 600k/mo)", "#334155", true) {
                        showAcc2Editor = !showAcc2Editor
                        acc2Box.visibility = if (showAcc2Editor) View.VISIBLE else View.GONE
                    })
                    addView(acc2Box)
                }
            } else {
                addView(makeBody("Enter your 6-character NextDNS ID (e.g. c4d9e2) or verified Family DNS. Sealing this locks Private DNS and Uninstall protection while keeping ADB open until you lock ADB separately:"))

                addView(makeFieldLabel("Account 1 — Main Profile ID (Ads + Adult Content Blocked):"))
                val acc1Main = makeInput("e.g. c4d9e2", prefs.getString("acc1_main", "") ?: "")
                addView(acc1Main)

                addView(makeFieldLabel("Account 1 — Ad-Bypass Profile ID (Adult Blocked, Ads Allowed):"))
                val acc1Bypass = makeInput("e.g. a1b2c3 (Optional duplicate profile)", prefs.getString("acc1_bypass", "") ?: "")
                addView(acc1Bypass)

                var useDual = prefs.getBoolean("use_dual_accounts", false)
                val dualContainer = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    visibility = if (useDual) View.VISIBLE else View.GONE
                }

                val dualSwitch = CheckBox(this@MainActivity).apply {
                    text = "Enable 2nd NextDNS Account Now (600k/mo 15-Day Auto-Rotation)"
                    setTextColor(Color.parseColor("#E2E8F0"))
                    textSize = 13f
                    isChecked = useDual
                    setPadding(dp(4), dp(8), dp(4), dp(6))
                    setOnCheckedChangeListener { _, checked ->
                        useDual = checked
                        dualContainer.visibility = if (checked) View.VISIBLE else View.GONE
                    }
                }
                addView(dualSwitch)

                dualContainer.addView(makeFieldLabel("Account 2 — Main Profile ID (Active Days 16–30):"))
                val acc2Main = makeInput("e.g. 9f77cc", "")
                dualContainer.addView(acc2Main)

                dualContainer.addView(makeFieldLabel("Account 2 — Ad-Bypass Profile ID (Active Days 16–30):"))
                val acc2Bypass = makeInput("e.g. d4e5f6", "")
                dualContainer.addView(acc2Bypass)

                addView(dualContainer)

                addView(makeButton("SEAL & LOCK NEXTDNS PERMANENTLY", "#EF4444", true) {
                    if (!isOwner) {
                        toast("Run the PC ADB Device Owner command first! Open Menu -> Setup Checklist.")
                        return@makeButton
                    }
                    val mainInput = acc1Main.text.toString().trim()
                    if (mainInput.isNotEmpty() && !PolicyController.isValidProtectedDnsInput(mainInput)) {
                        toast("Rejected: Enter a valid 6-character NextDNS ID or verified Family DNS!")
                        return@makeButton
                    }
                    if (useDual && acc2Main.text.toString().trim().isNotEmpty() &&
                        !PolicyController.isValidProtectedDnsInput(acc2Main.text.toString())
                    ) {
                        toast("Rejected Account 2: Enter a valid 6-character NextDNS ID!")
                        return@makeButton
                    }
                    PolicyController.saveDnsProfiles(
                        this@MainActivity,
                        useDual,
                        mainInput,
                        acc1Bypass.text.toString(),
                        acc2Main.text.toString(),
                        acc2Bypass.text.toString()
                    )
                    PolicyController.sealAndLockSystem(this@MainActivity)
                    toast("NextDNS & Uninstall Protection Sealed!")
                    renderScreen()
                })
            }
        })

        // --- CARD 2: SELECTABLE (10M / 30M / 1 HOUR) AD-WALL BYPASS ---
        rootLayout.addView(makeCard().apply {
            val bypassUntil = prefs.getLong("gplinks_bypass_until", 0L)
            val isBypassActive = System.currentTimeMillis() < bypassUntil
            val remainingMin = ((bypassUntil - System.currentTimeMillis()) / 60000L).coerceAtLeast(0L)
            var selectedMin = prefs.getInt("selected_bypass_minutes", 10)

            addView(makeCardHeader(
                "⏱️",
                "#0284C7",
                "Ad-Wall & Shortener Bypass",
                if (isBypassActive) "Active (${remainingMin + 1}m remaining)" else "Select 10m, 30m, or 1 Hour • Also in Quick Settings"
            ))
            addView(makeBody("Temporarily switches to your secondary NextDNS profile (Ads Allowed, Adult Content & Bypasses still 100% Blocked) so ad-gated download links can load, then automatically restores ad-blocking."))

            val pillRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(2), 0, dp(6))
            }
            val pills = mutableListOf<TextView>()
            val durations = listOf(10 to "10 Mins", 30 to "30 Mins", 60 to "1 Hour")

            durations.forEach { (mins, label) ->
                val pill = TextView(this@MainActivity).apply {
                    text = label
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    background = roundedBg(if (mins == selectedMin) "#0284C7" else "#0B0F19", 11f)
                    setPadding(0, dp(10), 0, dp(10))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        rightMargin = dp(6)
                    }
                    setOnClickListener {
                        if (!isBypassActive) {
                            selectedMin = mins
                            prefs.edit().putInt("selected_bypass_minutes", mins).commit()
                            pills.forEachIndexed { idx, tv ->
                                val m = durations[idx].first
                                tv.background = roundedBg(if (m == selectedMin) "#0284C7" else "#0B0F19", 11f)
                            }
                            renderScreen()
                        }
                    }
                }
                pills.add(pill)
                pillRow.addView(pill)
            }
            addView(pillRow)

            val durationText = if (selectedMin == 60) "1 Hour" else "$selectedMin Minutes"
            addView(makeButton(
                if (isBypassActive) "Ad-Wall Bypass Active (${remainingMin + 1} mins left)" else "Allow Ad-Gated Links for $durationText",
                "#0284C7",
                !isBypassActive
            ) {
                PolicyController.startAdShortenerBypass(this@MainActivity, selectedMin) { renderScreen() }
                toast("Switched to Ad-Bypass Profile (${PolicyController.getActiveBypassDns(this@MainActivity)}) for $durationText.")
            })

            if (isBypassActive) {
                addView(makeButton("Cancel Bypass & Restore Ad-Block DNS Now", "#EF4444", true) {
                    toast(PolicyController.cancelAdShortenerBypassImmediately(this@MainActivity))
                    renderScreen()
                })
            }
        })

        // --- CARD 3: AEGIS NEURAL CORE ---
        rootLayout.addView(makeCard().apply {
            val currentLevel = prefs.getInt("ai_level", 1)
            val cooldownReadyAt = prefs.getLong("ai_cooldown_ready_at", 0L)
            val pendingLevel = prefs.getInt("ai_pending_level", 0)
            val now = System.currentTimeMillis()
            val excludedGames = prefs.getStringSet("excluded_games", emptySet()) ?: emptySet()
            val excludedBanks = prefs.getStringSet("excluded_banks", emptySet()) ?: emptySet()
            val lastTelemetry = prefs.getString("last_ai_telemetry", "Aegis Vision Standby • 0% Idle CPU") ?: ""

            addView(makeCardHeader("👁️", "#8B5CF6", AI_SHIELD_NAME, "Active Protection: Level $currentLevel"))
            addView(makeBody(
                "System-wide on-device anatomical vision guard monitoring all apps, browsers, and offline media (excluding your whitelisted games, banking & document apps).\n" +
                        "• Level 1 (Core Shield): Exposed explicit nudity only\n" +
                        "• Level 2 (Vanguard Guard): Explicit nudity + revealing undergarments\n" +
                        "• Level 3 (Apex Sentinel): Strict protection against explicit & provocative media\n" +
                        "• Telemetry: $lastTelemetry"
            ))

            if (cooldownReadyAt > now) {
                val minsLeft = (cooldownReadyAt - now) / 60000L
                addView(makeBody("⏳ 6-Hour Cooldown running to lower to Level $pendingLevel: ${minsLeft / 60}h ${minsLeft % 60}m left."))
            } else if (cooldownReadyAt in 1..now) {
                addView(makeBody("✅ 6-Hour Cooldown complete! Tap Level $pendingLevel below to confirm."))
            }

            addView(makeButton(
                if (currentLevel == 3) "✓ Level 3 Active (Apex Strict Shield)" else "Upgrade to Level 3 (Apex Shield — Instant)",
                "#10B981",
                currentLevel != 3
            ) {
                toast(PolicyController.requestAiLevelChange(this@MainActivity, 3) { renderScreen() })
            })

            val lvl2Label = when {
                currentLevel == 2 -> "✓ Level 2 Active (Balanced Guard)"
                currentLevel < 2 -> "Upgrade to Level 2 (Balanced — Instant)"
                cooldownReadyAt in 1..now && pendingLevel == 2 -> "Confirm Switch to Level 2 (Cooldown Complete)"
                cooldownReadyAt > now && pendingLevel == 2 -> "Level 2 Locked (6h Cooldown Running...)"
                else -> "Request Level 2 (Starts 6-Hour Cooldown)"
            }
            addView(makeButton(lvl2Label, "#3B82F6", currentLevel != 2 && !(cooldownReadyAt > now && pendingLevel == 2)) {
                toast(PolicyController.requestAiLevelChange(this@MainActivity, 2) { renderScreen() })
            })

            val lvl1Label = when {
                currentLevel == 1 -> "✓ Level 1 Active (Default Core — Explicit Only)"
                cooldownReadyAt in 1..now && pendingLevel == 1 -> "Confirm Switch to Level 1 (Cooldown Complete)"
                cooldownReadyAt > now && pendingLevel == 1 -> "Level 1 Locked (6h Cooldown Running...)"
                else -> "Request Level 1 (Starts 6-Hour Cooldown)"
            }
            addView(makeButton(lvl1Label, "#64748B", currentLevel != 1 && !(cooldownReadyAt > now && pendingLevel == 1)) {
                toast(PolicyController.requestAiLevelChange(this@MainActivity, 1) { renderScreen() })
            })

            addView(makeButton("Excluded Safe Games & Emulators (${excludedGames.size})", "#334155", true) {
                showSeparateExclusionDialog(isGames = true)
            })
            addView(makeButton("Excluded Banking, KYC & Document Apps (${excludedBanks.size})", "#1E293B", true) {
                showSeparateExclusionDialog(isGames = false)
            })
        })

// --- CARD 3B: SAFE PAYMENT MODE (LIVE COUNTDOWN TIMER!) ---
        rootLayout.addView(makeCard().apply {
            val payMode = prefs.getBoolean("safe_payment_mode_active", false)
            val safePayUntil = prefs.getLong("safe_pay_until", 0L)
            val remSec = ((safePayUntil - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
            val liveSub = when {
                payMode && remSec > 0L -> String.format("ACTIVE (%02d:%02d left • Browsers Open on NextDNS)", remSec / 60, remSec % 60)
                payMode -> "3-Min Timer Ended • Tap Exit or Enable Accessibility"
                else -> "Seamless checkout for strict payment & banking apps"
            }

            val header = makeCardHeader("💳", "#10B981", "Safe Payment Mode (UPI & Banking)", liveSub)
            // Capture the subtitle TextView so the 1-second ticker updates it smoothly without scrolling jumps!
            safePaySubtitleView = ((header.getChildAt(1) as? LinearLayout)?.getChildAt(1) as? TextView)
            addView(header)
            if (payMode && remSec > 0L) {
                safePayTickerHandler.removeCallbacks(safePayTickerRunnable)
                safePayTickerHandler.postDelayed(safePayTickerRunnable, 1000L)
            }
            addView(makeBody("Temporarily pauses Accessibility for 3 minutes whenever a UPI or banking app flags Accessibility Services. Browsers and shopping apps stay open on locked NextDNS so checkouts never fail, while offline media and social apps stay locked until protection restores."))

            addView(makeButton("Enter Safe Payment Mode (3-Min Auto-Restore)", "#10B981", !payMode) {
                toast(PolicyController.setSafePaymentMode(this@MainActivity, true))
                renderScreen()
            })
            addView(makeButton("Exit Payment Mode & Restore Shield Now", "#2563EB", payMode) {
                val res = PolicyController.setSafePaymentMode(this@MainActivity, false)
                if (res == "NEEDS_ACCESSIBILITY") {
                    toast("Turn LockSight ON in Accessibility — all apps will restore automatically!")
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } else {
                    toast(res)
                    renderScreen()
                }
            })
        })

        // --- CARD 4: DPI PACKET FRAGMENTATION ---
        rootLayout.addView(makeCard().apply {
            val dpiActive = prefs.getBoolean("dpi_splitter_active", false) || prefs.getBoolean("dpi_active", false)
            addView(makeCardHeader(
                "⚡",
                "#6366F1",
                "DPI Packet Fragmentation",
                if (dpiActive) "Status: ACTIVE (Local SNI Splitter Running)" else "Status: STOPPED • Also in Quick Settings"
            ))
            addView(makeBody("Fragments outgoing TLS/SNI handshake packets locally on-device to bypass regional ISP domain throttling without using a foreign proxy server. NextDNS filtering remains 100% enforced."))

            addView(makeButton(
                if (dpiActive) "✓ Local Packet Fragmentation Active" else "Enable Local Packet Fragmentation",
                "#6366F1",
                !dpiActive
            ) {
                startByeDpiInternalSplitter()
            })
            addView(makeButton("Disable Local Packet Fragmentation", "#334155", dpiActive) {
                startService(Intent(this@MainActivity, LocalPacketVpnService::class.java).putExtra("mode", "STOP"))
                prefs.edit().putBoolean("dpi_splitter_active", false).putBoolean("dpi_active", false).commit()
                PolicyController.sendTimerNotification(
                    this@MainActivity,
                    "DPI Packet Fragmentation Stopped",
                    "Local SNI packet splitter has been turned off.",
                    1007
                )
                toast("Local Packet Fragmentation Stopped.")
                renderScreen()
            })
        })

        // --- CARD 5: CREATIVE APPS VPN MODE ---
        rootLayout.addView(makeCard().apply {
            val creativeVpn = prefs.getBoolean("creative_vpn_active", false)
            addView(makeCardHeader("🎬", "#A855F7", "Creative Apps VPN Mode", if (creativeVpn) "ACTIVE (Browsers Suspended)" else "LOCKED"))
            addView(makeBody("Unlocks VPN access strictly for verified creative editors (CapCut, VN, InShot, KineMaster, Lightroom, Canva). Automatically suspends all Browsers, Social Media, Messaging & Play Store installs while active so the VPN cannot be misused."))

            addView(makeButton("Unlock VPN for Creative Editors (Suspends Browsers)", "#8B5CF6", !creativeVpn) {
                toast(PolicyController.setCreativeVpnMode(this@MainActivity, true))
                renderScreen()
            })
            addView(makeButton("Relock VPN & Restore All Browsers", "#10B981", creativeVpn) {
                toast(PolicyController.setCreativeVpnMode(this@MainActivity, false))
                renderScreen()
            })
        })

        // --- CARD 6: FULL SYSTEM VPN ACCESS ---
        rootLayout.addView(makeCard().apply {
            val readyAt = prefs.getLong("full_vpn_ready_at", 0L)
            val activeUntil = prefs.getLong("full_vpn_active_until", 0L)
            val now = System.currentTimeMillis()
            val isReadyToActivate = readyAt in 1..now
            val isCurrentlyUnlocked = activeUntil > now

            val subTitle = when {
                isCurrentlyUnlocked -> "UNLOCKED (${(activeUntil - now) / 60000L}m left)"
                readyAt > now -> "Cooldown: ${(readyAt - now) / 3600000L}h ${((readyAt - now) % 3600000L) / 60000L}m left"
                isReadyToActivate -> "Ready to Activate (7 AM – 6 PM)"
                else -> "Locked (Daylight Timer: 7 AM – 6 PM)"
            }
            addView(makeCardHeader("🛡️", "#0EA5E9", "Full System VPN Access", subTitle))
            addView(makeBody("For genuine work requiring a full VPN alongside browsers. Requires a 12h or 24h countdown, sends a notification when ready, and only activates during Daylight (7:00 AM – 6:00 PM) for 60 minutes."))

            addView(makeButton("Start 12-Hour Full VPN Countdown", "#0284C7", !isCurrentlyUnlocked && readyAt <= now) {
                PolicyController.startFullVpnCountdown(this@MainActivity, 12) { renderScreen() }
                toast("12-Hour Full VPN countdown started. You will be notified when ready.")
            })
            addView(makeButton("Start 24-Hour Full VPN Countdown", "#0369A1", !isCurrentlyUnlocked && readyAt <= now) {
                PolicyController.startFullVpnCountdown(this@MainActivity, 24) { renderScreen() }
                toast("24-Hour Full VPN countdown started.")
            })
            addView(makeButton("Activate 1-Hour Full VPN Window (7 AM – 6 PM)", "#10B981", isReadyToActivate) {
                toast(PolicyController.activateFullVpnWindow(this@MainActivity, 60) { renderScreen() })
            })
            addView(makeButton("Lock Full VPN Immediately", "#EF4444", true) {
                toast(PolicyController.lockFullVpnNow(this@MainActivity))
                renderScreen()
            })
        })

// --- CARD 6B: OPTIONAL MULTI-USER, SYSTEM CLONER & PRIVATE SPACE LOCK ---
        rootLayout.addView(makeCard().apply {
            val isMultiLocked = PolicyController.isMultiUserLocked(this@MainActivity)
            val readyAt = prefs.getLong("multi_user_ready_at", 0L)
            val now = System.currentTimeMillis()
            val isReady = readyAt in 1..now

            val status = when {
                !isMultiLocked -> "OPTIONAL • Currently Unlocked"
                readyAt > now -> "Unlock Countdown: ${(readyAt - now) / 3600000L}h ${((readyAt - now) % 3600000L) / 60000L}m left"
                isReady -> "Ready to Unlock (Daylight 7 AM – 6 PM)"
                else -> "LOCKED (Multiple Users, System Cloner & Private Space Blocked)"
            }
            addView(makeCardHeader("👥", "#EC4899", "Multi-User & System Cloner Vault (Optional)", status))
            addView(makeBody("Closes secondary-OS loopholes by blocking new Multiple Users, Guest Mode, System Cloner, Work Profiles, and Android Private Space. Regular App Cloner (Dual WhatsApp) and File Private Safe remain 100% usable."))

            addView(makeButton(
                if (isMultiLocked) "✓ Multi-User, System Cloner & Private Space Locked" else "Lock Multi-User, System Cloner & Private Space",
                "#EC4899",
                !isMultiLocked
            ) {
                toast(PolicyController.lockMultiUserAndClonerNow(this@MainActivity))
                renderScreen()
            })
            if (isMultiLocked) {
                if (readyAt > now) {
                    addView(makeButton("Cancel 12-Hour Unlock Countdown", "#EF4444", true) {
                        toast(PolicyController.cancelMultiUserCountdown(this@MainActivity))
                        renderScreen()
                    })
                } else {
                    addView(makeButton("Start 12-Hour Unlock Countdown", "#334155", true) {
                        PolicyController.startMultiUserUnlockCountdown(this@MainActivity, 12) { renderScreen() }
                        toast("12-Hour Multi-User unlock countdown started.")
                    })
                }
                if (isReady) {
                    addView(makeButton("Unlock Multi-User & System Cloner (7 AM – 6 PM)", "#10B981", true) {
                        toast(PolicyController.unlockMultiUserNow(this@MainActivity))
                        renderScreen()
                    })
                }
            }
        })

        // --- CARD 7: SEPARATE ADB & DEVELOPER OPTIONS LOCK ---
        rootLayout.addView(makeCard().apply {
            val readyAt = prefs.getLong("adb_ready_at", 0L)
            val activeUntil = prefs.getLong("adb_active_until", 0L)
            val now = System.currentTimeMillis()
            val isReadyToActivate = readyAt in 1..now
            val isCurrentlyUnlocked = activeUntil > now

            val statusLabel = when {
                isCurrentlyUnlocked -> "ADB UNLOCKED (${(activeUntil - now) / 60000L} mins remaining)"
                readyAt > now -> "Countdown: ${(readyAt - now) / 3600000L}h ${((readyAt - now) % 3600000L) / 60000L}m remaining"
                isReadyToActivate -> "Ready! Tap Activate (7 AM – 6 PM Only)"
                isAdbLocked -> "LOCKED (USB & Wireless ADB Disabled)"
                else -> "UNLOCKED (Tap Red Button Below to Lock ADB)"
            }
            addView(makeCardHeader("💻", "#F59E0B", "ADB & Developer Options (Separate Lock)", statusLabel))
            addView(makeBody("Controlled independently from NextDNS Setup. Lock this LAST after verifying your DNS and Accessibility Guard. Once locked, unlocking requires a 12h or 24h countdown during Daylight (7:00 AM – 6:00 PM)."))

            addView(makeButton(
                if (isAdbLocked && !isCurrentlyUnlocked) "✓ ADB & Developer Options Locked" else "Lock ADB & Developer Options Now",
                "#EF4444",
                !isAdbLocked || isCurrentlyUnlocked
            ) {
                toast(PolicyController.lockAdbImmediately(this@MainActivity))
                renderScreen()
            })
            addView(makeButton("Start 12-Hour ADB Countdown", "#F59E0B", isAdbLocked && !isCurrentlyUnlocked && readyAt <= now) {
                PolicyController.startAdbCountdown(this@MainActivity, 12) { renderScreen() }
                toast("12-Hour ADB countdown started. You will receive a notification when complete.")
            })
            addView(makeButton("Start 24-Hour ADB Countdown", "#D97706", isAdbLocked && !isCurrentlyUnlocked && readyAt <= now) {
                PolicyController.startAdbCountdown(this@MainActivity, 24) { renderScreen() }
                toast("24-Hour ADB countdown started.")
            })
            addView(makeButton("Activate 1-Hour ADB Window (7 AM – 6 PM Only)", "#10B981", isReadyToActivate) {
                toast(PolicyController.activateAdbWindow1Hour(this@MainActivity) { renderScreen() })
            })
            addView(makeButton("Extend Active ADB by +1 Hour (Daytime)", "#0284C7", isCurrentlyUnlocked) {
                toast(PolicyController.extendAdb1Hour(this@MainActivity))
                renderScreen()
            })
        })
    }

    // =========================================================================
    // TAB 2: ZEN & STREAK
    // =========================================================================
    private fun renderZenAndStreakScreen() {
        val prefs = PolicyController.prefs(this)
        val streakStart = prefs.getLong("clean_streak_start", System.currentTimeMillis())
        val elapsedMillis = (System.currentTimeMillis() - streakStart).coerceAtLeast(0L)
        val cleanDays = elapsedMillis / (24 * 3600 * 1000L)
        val cleanHours = (elapsedMillis % (24 * 3600 * 1000L)) / (3600 * 1000L)
        val cleanMins = (elapsedMillis % (3600 * 1000L)) / 60000L

        val badgeTitle = when {
            cleanDays >= 90 -> "Rank: Master of the Self (90+ Days)"
            cleanDays >= 30 -> "Rank: Iron Discipline (30+ Days)"
            cleanDays >= 14 -> "Rank: Dopamine Rewired (14+ Days)"
            cleanDays >= 7 -> "Rank: First Week Conquered (7+ Days)"
            cleanDays >= 3 -> "Rank: Breaking the Loop (3+ Days)"
            else -> "Rank: Foundation Builder (Day ${cleanDays + 1})"
        }

        rootLayout.addView(makeCard().apply {
            addView(makeCardHeader("🔥", "#F59E0B", "$cleanDays Days • $cleanHours Hours • $cleanMins Mins", badgeTitle))
            addView(makeBody("Your brain is actively healing its dopamine receptors every hour you stay clean. Protect your streak at all costs."))
            addView(makeButton("Pledge / Reset Clean Streak Counter", "#334155", true) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Reset Clean Streak Counter?")
                    .setMessage("Every sincere return is a new beginning. Do you want to reset your clean streak timer to Day 0, Hour 0 right now?")
                    .setPositiveButton("Yes, Start Fresh") { _, _ ->
                        prefs.edit().putLong("clean_streak_start", System.currentTimeMillis()).commit()
                        toast("New pledge started! Stay steadfast.")
                        renderScreen()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            })
        })

        rootLayout.addView(makeCard().apply {
            addView(makeCardHeader("🧘", "#10B981", "Zen Sanctuary Mode (Urge Lockout)", "Kiosk Lock + Pure Melodic Chimes & Focus Game"))
            addView(makeBody(
                "When an intense urge hits, enter Zen Sanctuary Mode. It locks your screen into a calm, distraction-free sanctuary with:\n" +
                        "• Pure Melodic Singing Bowls / Wind Chimes & Ocean Swells\n" +
                        "• Guided 4-4-4 Box Breathing Rhythm\n" +
                        "• Interactive 'Impulse Destroyer' Focus Mini-Game\n" +
                        "• Emergency Call Access Only"
            ))

            var selectedMinutes = 5
            val durationRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, dp(8))
            }
            val durationButtons = mutableListOf<TextView>()
            listOf(1, 5, 15, 30).forEach { mins ->
                val pill = TextView(this@MainActivity).apply {
                    text = "${mins}m"
                    textSize = 13.5f
                    gravity = Gravity.CENTER
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    background = roundedBg(if (mins == selectedMinutes) "#10B981" else "#0B0F19", 11f)
                    setPadding(0, dp(11), 0, dp(11))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        rightMargin = dp(6)
                    }
                }
                pill.setOnClickListener {
                    selectedMinutes = mins
                    durationButtons.forEachIndexed { i, tv ->
                        val m = listOf(1, 5, 15, 30)[i]
                        tv.background = roundedBg(if (m == selectedMinutes) "#10B981" else "#0B0F19", 11f)
                    }
                }
                durationButtons.add(pill)
                durationRow.addView(pill)
            }
            addView(durationRow)

            addView(makeButton("ENTER ZEN SANCTUARY LOCKOUT NOW", "#10B981", true) {
                val totalMs = selectedMinutes * 60 * 1000L
                launchFullScreenZenSanctuary(selectedMinutes, totalMs)
            })
        })
    }

    private fun launchFullScreenZenSanctuary(minutes: Int, customDurationMs: Long = minutes * 60 * 1000L) {
        PolicyController.enableZenLockTaskPermission(this)
        try {
            startLockTask()
        } catch (_: Exception) {}

        val zenDialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        zenDialog.setCancelable(false)
        zenDialog.setCanceledOnTouchOutside(false)

        val prefs = PolicyController.prefs(this)
        val faith = prefs.getString("tradition_filter", "Universal") ?: "Universal"
        val faithQuotes = offlineReminders.filter { faith == "All" || faith == "Hidden" || it.tradition == faith }
            .ifEmpty { offlineReminders }
        var currentQuoteIdx = Random.nextInt(faithQuotes.size)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#070B12"))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#070B12"))
            setPadding(dp(18), dp(28), dp(18), dp(28))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "ZEN SANCTUARY LOCK ACTIVE"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#10B981"))
            letterSpacing = 0.14f
        })

        val timerText = TextView(this).apply {
            text = String.format("%02d:00", minutes)
            textSize = 46f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(timerText)

        val breathBox = TextView(this).apply {
            text = "🌬️ INHALE DEEPLY (4s)..."
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
            background = roundedBg("#0F1A2C", 12f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        root.addView(breathBox)

        val quoteView = TextView(this).apply {
            text = "${faithQuotes[currentQuoteIdx].quote}\n— ${faithQuotes[currentQuoteIdx].source}"
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#CBD5E1"))
            background = roundedBg("#0F292E", 12f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            setOnClickListener {
                currentQuoteIdx = (currentQuoteIdx + 1) % faithQuotes.size
                text = "${faithQuotes[currentQuoteIdx].quote}\n— ${faithQuotes[currentQuoteIdx].source}"
            }
        }
        root.addView(quoteView)

        // =====================================================================
        // 2-IN-1 ZEN FOCUS ARCADE: NEON BRICK BREAKER + NEURAL PULSE MEMORY
        // =====================================================================
        val gameCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBgWithBorder("#0F172A", "#1E3A8A", 16f, dp(1))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

        val modeSwitchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedBg("#070B12", 11f)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        var activeGameMode = 0 // 0 = Neon Brick Breaker, 1 = Neural Pulse Memory
        val arcadeContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(300)
            )
        }

        var brickGameView: NeonBrickBreakerView? = null
        val memoryHandler = android.os.Handler(android.os.Looper.getMainLooper())

        fun mountBrickBreaker() {
            memoryHandler.removeCallbacksAndMessages(null)
            arcadeContainer.removeAllViews()
            val v = NeonBrickBreakerView(this)
            brickGameView = v
            arcadeContainer.addView(
                v,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )
        }

        fun mountNeuralPulseMemory() {
            brickGameView?.stopLoop()
            brickGameView = null
            arcadeContainer.removeAllViews()

            val memWrap = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }

            var bestMem = prefs.getInt("zen_mem_best", 0)
            var memLevel = 1
            val sequence = mutableListOf<Int>()
            var playerStep = 0
            var acceptingInput = false

            val statusTv = TextView(this).apply {
                text = "🧠 NEURAL PULSE • LEVEL 1  |  BEST: $bestMem\nWatch the glowing sequence, then repeat it!"
                textSize = 12.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#38BDF8"))
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(10))
            }
            memWrap.addView(statusTv)

            val padButtons = ArrayList<Button>(9)
            val padGrid = GridLayout(this).apply {
                columnCount = 3
                rowCount = 3
                layoutParams = LinearLayout.LayoutParams(dp(230), dp(230))
            }

            fun playSequence() {
                acceptingInput = false
                playerStep = 0
                statusTv.text = "👀 WATCH CAREFULLY... (Level $memLevel • ${sequence.size} steps)"
                padButtons.forEach { it.background = roundedBg("#1E293B", 12f) }

                sequence.forEachIndexed { idx, tileIdx ->
                    val startMs = 450L + idx * 540L
                    memoryHandler.postDelayed({
                        padButtons[tileIdx].background = roundedBg("#38BDF8", 12f)
                    }, startMs)
                    memoryHandler.postDelayed({
                        padButtons[tileIdx].background = roundedBg("#1E293B", 12f)
                    }, startMs + 340L)
                }

                val unlockMs = 450L + sequence.size * 540L
                memoryHandler.postDelayed({
                    acceptingInput = true
                    statusTv.text = "🎯 YOUR TURN! Repeat all ${sequence.size} pulses (Best: $bestMem)"
                }, unlockMs)
            }

            fun startNextRound(reset: Boolean) {
                if (reset) {
                    memLevel = 1
                    sequence.clear()
                    sequence.add(Random.nextInt(9))
                    sequence.add(Random.nextInt(9))
                    sequence.add(Random.nextInt(9))
                } else {
                    memLevel++
                    sequence.add(Random.nextInt(9))
                }
                playSequence()
            }

            for (i in 0 until 9) {
                val tileBtn = Button(this).apply {
                    text = ""
                    background = roundedBg("#1E293B", 12f)
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 0
                        height = 0
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        setMargins(dp(5), dp(5), dp(5), dp(5))
                    }
                    setOnClickListener {
                        if (!acceptingInput) return@setOnClickListener
                        if (i == sequence[playerStep]) {
                            background = roundedBg("#10B981", 12f)
                            memoryHandler.postDelayed({ background = roundedBg("#1E293B", 12f) }, 180L)
                            playerStep++
                            if (playerStep == sequence.size) {
                                acceptingInput = false
                                if (memLevel > bestMem) {
                                    bestMem = memLevel
                                    prefs.edit().putInt("zen_mem_best", bestMem).apply()
                                }
                                statusTv.text = "✅ PERFECT! Advancing to Level ${memLevel + 1}..."
                                memoryHandler.postDelayed({ startNextRound(false) }, 750L)
                            }
                        } else {
                            acceptingInput = false
                            background = roundedBg("#EF4444", 12f)
                            statusTv.text = "❌ MISSED! Resetting to Level 1 (Best: $bestMem)..."
                            memoryHandler.postDelayed({ startNextRound(true) }, 1000L)
                        }
                    }
                }
                padButtons.add(tileBtn)
                padGrid.addView(tileBtn)
            }

            memWrap.addView(padGrid)
            arcadeContainer.addView(memWrap)
            startNextRound(true)
        }

        val tabBrick = TextView(this).apply {
            text = "🧱 Neon Breaker"
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = roundedBg("#2563EB", 9f)
            setPadding(0, dp(8), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tabMem = TextView(this).apply {
            text = "🧠 Memory Pulse"
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(8), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        tabBrick.setOnClickListener {
            if (activeGameMode != 0) {
                activeGameMode = 0
                tabBrick.background = roundedBg("#2563EB", 9f)
                tabBrick.setTextColor(Color.WHITE)
                tabMem.background = null
                tabMem.setTextColor(Color.parseColor("#94A3B8"))
                mountBrickBreaker()
            }
        }
        tabMem.setOnClickListener {
            if (activeGameMode != 1) {
                activeGameMode = 1
                tabMem.background = roundedBg("#2563EB", 9f)
                tabMem.setTextColor(Color.WHITE)
                tabBrick.background = null
                tabBrick.setTextColor(Color.parseColor("#94A3B8"))
                mountNeuralPulseMemory()
            }
        }

        modeSwitchRow.addView(tabBrick)
        modeSwitchRow.addView(tabMem)
        gameCard.addView(modeSwitchRow)
        gameCard.addView(arcadeContainer)
        root.addView(gameCard)

        mountBrickBreaker()

        // =====================================================================
        // REAL NATURE SOUNDSCAPES (Loads res/raw/zen_*.ogg with fallback!)
        // =====================================================================
        val soundLabels = listOf(
            "🐦 Nature Audio: Forest Birds (Tap to Switch)",
            "🌧️ Nature Audio: Gentle Rain (Tap to Switch)",
            "🌊 Nature Audio: Ocean Waves (Tap to Switch)",
            "🍃 Nature Audio: Mountain Wind (Tap to Switch)",
            "🔇 Nature Audio: Muted (Tap to Unmute)"
        )
        startZenMelodicAudio()
        val soundBtn = makeButton(soundLabels[selectedSoundscapeMode.coerceIn(0, 4)], "#0284C7", true) {}
        soundBtn.setOnClickListener {
            selectedSoundscapeMode = (selectedSoundscapeMode + 1) % 5
            stopZenMelodicAudio()
            if (selectedSoundscapeMode != 4) {
                startZenMelodicAudio()
            }
            soundBtn.text = soundLabels[selectedSoundscapeMode]
        }
        root.addView(soundBtn)

        root.addView(makeButton("📞 Emergency Call Dialer", "#334155", true) {
            try {
                startActivity(Intent(Intent.ACTION_DIAL))
            } catch (_: Exception) {}
        })

        zenDialog.setContentView(scroll)
        zenDialog.show()

        zenTimer?.cancel()
        zenTimer = object : CountDownTimer(customDurationMs, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val totalSec = (millisUntilFinished / 1000L).toInt()
                val m = totalSec / 60
                val s = totalSec % 60
                timerText.text = String.format("%02d:%02d", m, s)

                val cycle = totalSec % 12
                breathBox.text = when {
                    cycle >= 8 -> "🌬️ INHALE SLOWLY THROUGH NOSE... (${cycle - 7}s)"
                    cycle >= 4 -> "✋ HOLD BREATH & CLEAR YOUR MIND... (${cycle - 3}s)"
                    else -> "💨 EXHALE COMPLETELY & LET GO... (${cycle + 1}s)"
                }
            }

            override fun onFinish() {
                zenTimer = null
                brickGameView?.stopLoop()
                memoryHandler.removeCallbacksAndMessages(null)
                stopZenMelodicAudio()
                try {
                    stopLockTask()
                } catch (_: Exception) {}
                zenDialog.dismiss()
                toast("Zen Sanctuary Complete! You conquered the urge.")
            }
        }.start()
    }

    private inner class NeonBrickBreakerView(context: Context) : View(context) {
        private val bgPaint = Paint().apply { color = Color.parseColor("#070B12") }
        private val paddlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#38BDF8") }
        private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E2E8F0")
            textSize = dpF(12.5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        private val brickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rowColors = intArrayOf(
            Color.parseColor("#F43F5E"),
            Color.parseColor("#F59E0B"),
            Color.parseColor("#10B981"),
            Color.parseColor("#38BDF8")
        )

        private var running = true
        private var initialized = false
        private var score = 0
        private var bestScore = PolicyController.prefs(context).getInt("zen_brick_best", 0)
        private var wave = 1
        private var paddleX = 0f
        private var paddleW = dpF(88f)
        private val paddleH = dpF(11f)
        private var ballX = 0f
        private var ballY = 0f
        private var vx = dpF(3.6f)
        private var vy = -dpF(4.2f)
        private val ballR = dpF(7f)

        private val cols = 6
        private val rows = 4
        private val bricks = BooleanArray(rows * cols) { true }

        private val frameRunnable = object : Runnable {
            override fun run() {
                if (!running) return
                stepPhysics()
                invalidate()
                postDelayed(this, 16L)
            }
        }

        init {
            post(frameRunnable)
        }

        fun stopLoop() {
            running = false
            removeCallbacks(frameRunnable)
        }

        private fun resetBall() {
            ballX = width * 0.5f
            ballY = height * 0.66f
            val speedBoost = 1f + (wave - 1) * 0.12f
            vx = (if (Random.nextBoolean()) 1f else -1f) * dpF(3.5f) * speedBoost
            vy = -dpF(4.1f) * speedBoost
        }

        private fun stepPhysics() {
            if (width <= 0 || height <= 0) return
            if (!initialized) {
                initialized = true
                paddleX = (width - paddleW) * 0.5f
                resetBall()
            }

            ballX += vx
            ballY += vy

            // Wall bounces
            if (ballX - ballR < 0f) { ballX = ballR; vx = abs(vx) }
            if (ballX + ballR > width) { ballX = width - ballR; vx = -abs(vx) }
            if (ballY - ballR < dpF(26f)) { ballY = dpF(26f) + ballR; vy = abs(vy) }

            // Paddle bounce
            val paddleY = height - dpF(22f)
            if (vy > 0 && ballY + ballR >= paddleY && ballY - ballR <= paddleY + paddleH &&
                ballX >= paddleX - ballR && ballX <= paddleX + paddleW + ballR
            ) {
                val hitRatio = ((ballX - (paddleX + paddleW * 0.5f)) / (paddleW * 0.5f)).coerceIn(-1f, 1f)
                vx = hitRatio * dpF(5.5f)
                vy = -abs(vy)
                ballY = paddleY - ballR
            }

            // Brick collisions
            val pad = dpF(5f)
            val topStart = dpF(34f)
            val brickW = (width - pad * (cols + 1)) / cols
            val brickH = dpF(18f)
            var remaining = 0
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val idx = r * cols + c
                    if (!bricks[idx]) continue
                    remaining++
                    val bx = pad + c * (brickW + pad)
                    val by = topStart + r * (brickH + pad)
                    if (ballX + ballR >= bx && ballX - ballR <= bx + brickW &&
                        ballY + ballR >= by && ballY - ballR <= by + brickH
                    ) {
                        bricks[idx] = false
                        remaining--
                        vy = -vy
                        score += 10 * wave
                        if (score > bestScore) {
                            bestScore = score
                            PolicyController.prefs(context).edit().putInt("zen_brick_best", bestScore).apply()
                        }
                    }
                }
            }

            if (remaining == 0) {
                wave++
                bricks.fill(true)
                resetBall()
            }

            // Missed ball -> gentle reset (keeps flow without punishing frustration!)
            if (ballY - ballR > height) {
                score = (score - 20).coerceAtLeast(0)
                resetBall()
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                paddleX = (event.x - paddleW * 0.5f).coerceIn(0f, (width - paddleW).coerceAtLeast(0f))
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), dpF(12f), dpF(12f), bgPaint)
            canvas.drawText("WAVE $wave   •   SCORE: $score   •   BEST: $bestScore", dpF(10f), dpF(18f), hudPaint)

            val pad = dpF(5f)
            val topStart = dpF(34f)
            val brickW = (width - pad * (cols + 1)) / cols
            val brickH = dpF(18f)

            for (r in 0 until rows) {
                brickPaint.color = rowColors[r % rowColors.size]
                for (c in 0 until cols) {
                    if (!bricks[r * cols + c]) continue
                    val bx = pad + c * (brickW + pad)
                    val by = topStart + r * (brickH + pad)
                    canvas.drawRoundRect(bx, by, bx + brickW, by + brickH, dpF(5f), dpF(5f), brickPaint)
                }
            }

            val paddleY = height - dpF(22f)
            canvas.drawRoundRect(paddleX, paddleY, paddleX + paddleW, paddleY + paddleH, dpF(6f), dpF(6f), paddlePaint)
            canvas.drawCircle(ballX, ballY, ballR, ballPaint)
        }
    }

    private fun startZenMelodicAudio() {
        if (isZenAudioPlaying) return

        // Looks for real copyright-free audio files in app/src/main/res/raw/:
        // zen_birds.ogg, zen_rain.ogg, zen_ocean.ogg, zen_wind.ogg
        val rawNames = listOf("zen_birds", "zen_rain", "zen_ocean", "zen_wind")
        val targetName = rawNames.getOrElse(selectedSoundscapeMode) { "zen_birds" }
        val rawResId = resources.getIdentifier(targetName, "raw", packageName)
        if (rawResId != 0) {
            try {
                rawMediaPlayer?.release()
                rawMediaPlayer = MediaPlayer.create(this, rawResId)?.apply {
                    isLooping = true
                    start()
                }
                isZenAudioPlaying = true
                return
            } catch (_: Exception) {}
        }

        // Smooth built-in fallback synth if a raw file hasn't been added yet
        isZenAudioPlaying = true
        zenAudioThread = Thread {
            val sampleRate = 22050
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(2048)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()

            val chimeNotes = doubleArrayOf(261.63, 293.66, 329.63, 392.00, 440.00, 528.00, 659.25)
            var activeChimeFreq = 528.0
            var chimeAgeSec = 0.0
            var phaseBase = 0.0
            var phaseFifth = 0.0
            var phaseChime = 0.0
            var phaseSwell = 0.0
            val dt = 1.0 / sampleRate
            val samples = ShortArray(1024)

            try {
                track.play()
                while (isZenAudioPlaying) {
                    for (i in samples.indices) {
                        chimeAgeSec += dt
                        if (chimeAgeSec >= 2.8) {
                            chimeAgeSec = 0.0
                            activeChimeFreq = chimeNotes[Random.nextInt(chimeNotes.size)]
                        }
                        val chimeEnvelope = exp(-2.2 * chimeAgeSec)
                        val slowSwell = 0.5 + 0.5 * sin(phaseSwell)
                        val sampleVal = when (selectedSoundscapeMode) {
                            0 -> (sin(phaseChime) * chimeEnvelope * 0.35) + (sin(phaseBase) * 0.16) + (sin(phaseFifth) * 0.10)
                            1, 2 -> ((sin(phaseBase) * 0.25) + (sin(phaseFifth) * 0.18)) * (0.35 + 0.65 * slowSwell)
                            else -> (sin(phaseBase) * 0.24) + (sin(phaseFifth) * 0.16)
                        }
                        phaseBase += (2.0 * Math.PI * 174.0) * dt
                        phaseFifth += (2.0 * Math.PI * 261.63) * dt
                        phaseChime += (2.0 * Math.PI * activeChimeFreq) * dt
                        phaseSwell += (2.0 * Math.PI * 0.16) * dt
                        samples[i] = (sampleVal.coerceIn(-0.85, 0.85) * 14000).toInt().toShort()
                    }
                    track.write(samples, 0, samples.size)
                }
            } catch (_: Exception) {
            } finally {
                try {
                    track.stop()
                    track.release()
                } catch (_: Exception) {}
            }
        }
        zenAudioThread?.start()
    }

    private fun stopZenMelodicAudio() {
        isZenAudioPlaying = false
        zenAudioThread = null
        try {
            rawMediaPlayer?.stop()
            rawMediaPlayer?.release()
        } catch (_: Exception) {}
        rawMediaPlayer = null
    }

    // =========================================================================
    // TAB 3: REMINDERS & DAILY FORTRESS CHECKLIST
    // =========================================================================
    private fun renderRemindersScreen() {
        val prefs = PolicyController.prefs(this)
        val currentFaith = prefs.getString("tradition_filter", "Universal") ?: "Universal"
        val effectiveFaith = if (currentFaith == "Hidden") "Universal" else currentFaith

        val todayKey = "muhasabah_${Calendar.getInstance().get(Calendar.DAY_OF_YEAR)}"
        val completedSet = (prefs.getStringSet(todayKey, emptySet()) ?: emptySet()).toMutableSet()

        val habits = when (effectiveFaith) {
            "Islamic" -> listOf(
                "Guarded my gaze (Hifz al-Basar) on screens & in public today",
                "Prayed my obligatory Salah on time with focus (Khushu')",
                "Recited Morning & Evening Adhkar / Istighfar (100x)",
                "Read & reflected on at least 5 verses of the Noble Quran",
                "Avoided taking the phone to bed in the dark without purpose"
            )
            "Hindu" -> listOf(
                "Practiced Indriya-Jaya (sense restraint) over digital triggers",
                "Completed morning meditation / Japa / Surya Namaskar",
                "Read 2 verses of the Bhagavad Gita with reflection",
                "Channeled vital energy (Ojas) into physical exercise or study",
                "Kept the mind sattvic and free from late-night screen scrolling"
            )
            "Christian" -> listOf(
                "Kept my covenant with my eyes (Job 31:1) throughout the day",
                "Spent time in morning prayer and Scripture reading",
                "Turned away from the first appearance of temptation immediately",
                "Served my family or work with integrity and full focus",
                "Kept my bedroom free from late-night phone distractions"
            )
            else -> listOf(
                "Zero explicit searches or trigger-seeking behavior today",
                "Completed 30+ minutes of physical training or outdoor walk",
                "Protected deep work / study hours from dopamine distractions",
                "Practiced urge-surfing when stressed or bored",
                "No screens in the bedroom 30 minutes before sleep"
            )
        }

        rootLayout.addView(makeCard().apply {
            addView(makeCardHeader(
                "🛡️",
                "#10B981",
                "Daily Fortress Checklist (${completedSet.size} / ${habits.size})",
                "Active Path: $effectiveFaith (Change in top-right Menu -> Faith Personalization)"
            ))
            addView(makeBody("True freedom isn't just blocking bad websites—it is filling your day with discipline so urges lose their power."))

            habits.forEachIndexed { idx, habitText ->
                val key = "h_$idx"
                val cb = CheckBox(this@MainActivity).apply {
                    text = habitText
                    textSize = 13.5f
                    setTextColor(Color.parseColor("#F1F5F9"))
                    isChecked = completedSet.contains(key)
                    setPadding(dp(6), dp(7), dp(6), dp(7))
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) completedSet.add(key) else completedSet.remove(key)
                        prefs.edit().putStringSet(todayKey, completedSet).commit()
                        renderScreen()
                    }
                }
                addView(cb)
            }
        })

        val displayList = offlineReminders.filter { effectiveFaith == "All" || it.tradition == effectiveFaith }
            .ifEmpty { offlineReminders }

        displayList.forEach { item ->
            rootLayout.addView(makeCard().apply {
                addView(TextView(this@MainActivity).apply {
                    text = item.categoryBadge
                    textSize = 11f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#38BDF8"))
                    letterSpacing = 0.08f
                    setPadding(0, 0, 0, dp(4))
                })
                addView(TextView(this@MainActivity).apply {
                    text = item.title
                    textSize = 18f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    setPadding(0, 0, 0, dp(8))
                })

                val poster = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = roundedBgWithBorder("#0B2126", "#134E4A", 14f, dp(1))
                    setPadding(dp(16), dp(18), dp(16), dp(18))
                }
                if (item.arabicOrOriginal.isNotBlank()) {
                    poster.addView(TextView(this@MainActivity).apply {
                        text = item.arabicOrOriginal
                        textSize = 18.5f
                        gravity = Gravity.CENTER
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(Color.parseColor("#6EE7B7"))
                        setPadding(0, 0, 0, dp(8))
                    })
                }
                poster.addView(TextView(this@MainActivity).apply {
                    text = item.quote
                    textSize = 14.5f
                    gravity = Gravity.CENTER
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#ECFDF5"))
                })
                poster.addView(TextView(this@MainActivity).apply {
                    text = "— ${item.source}"
                    textSize = 12.5f
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor("#34D399"))
                    setPadding(0, dp(8), 0, 0)
                })
                addView(poster)

                addView(TextView(this@MainActivity).apply {
                    text = item.actionStep
                    textSize = 13.5f
                    setTextColor(Color.parseColor("#CBD5E1"))
                    setPadding(0, dp(10), 0, dp(8))
                })

                val bottomRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val tagsWrap = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                item.tags.forEach { tag ->
                    tagsWrap.addView(TextView(this@MainActivity).apply {
                        text = tag
                        textSize = 11f
                        setTextColor(Color.parseColor("#38BDF8"))
                        background = roundedBg("#0B0F19", 10f)
                        setPadding(dp(9), dp(4), dp(9), dp(4))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { rightMargin = dp(6) }
                    })
                }
                val shareBtn = TextView(this@MainActivity).apply {
                    text = "Share ↗"
                    textSize = 13f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#38BDF8"))
                    setPadding(dp(8), dp(4), dp(4), dp(4))
                    setOnClickListener {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "${item.quote}\n— ${item.source}\n\nShared via LockSight")
                        }
                        startActivity(Intent.createChooser(shareIntent, "Share Reminder"))
                    }
                }
                bottomRow.addView(tagsWrap)
                bottomRow.addView(shareBtn)
                addView(bottomRow)
            })
        }
    }

    // =========================================================================
    // SUBPAGE 2: FEATURES, QUICK SETTINGS TILES & DAILY PLAYBOOK
    // =========================================================================
    private fun renderFeaturesScreen() {
        // --- HEADER INTRO BANNER ---
        rootLayout.addView(makeCard().apply {
            addView(makeCardHeader(
                "⚡",
                "#0EA5E9",
                "LockSight Architecture & Features",
                "Zero subscriptions • Zero cloud tracking • Built for real life"
            ))
            addView(makeBody(
                "Unlike ordinary blockers that drain battery through background VPNs or collapse when you solve a 4-letter Device Admin captcha, LockSight seals every technical loophole at the Android OS kernel level while leaving your phone 100% fast for gaming, banking, and daily work."
            ))
        })

        // --- SECTION 1: QUICK SETTINGS TILES (3 ONE-TAP CONTROLS) ---
        rootLayout.addView(makeSectionLabel("Quick Settings Tiles (3 One-Tap Controls)"))
        rootLayout.addView(makeCard().apply {
            addView(makeCardHeader(
                "🎛️",
                "#2563EB",
                "Control LockSight From Your Notification Shade",
                "Pull down Quick Settings -> Tap Edit (Pencil) -> Drag tiles up"
            ))
            addView(makeFeatureRow(
                "⏱️", "#0284C7",
                "Ad-Wall 15m (Stopwatch Icon)",
                "One-tap 15-minute switch to your secondary NextDNS profile so ad-gated download links load while explicit sites and bypasses stay 100% blocked. Tap again anytime to cancel instantly."
            ))
            addView(makeDivider())
            addView(makeFeatureRow(
                "⚡", "#6366F1",
                "DPI Splitter (Lightning Icon)",
                "One-tap local SNI packet fragmentation to bypass regional ISP domain throttling with 0ms ping loss—no foreign VPN needed and NextDNS remains fully enforced."
            ))
            addView(makeDivider())
            addView(makeFeatureRow(
                "💳", "#10B981",
                "Safe Pay (Card Icon)",
                "One-tap 3-minute checkout mode for strict UPI & banking apps. Keeps browsers and shopping apps open on NextDNS while pausing offline media apps, then restores Accessibility automatically."
            ))
        })

        // --- SECTION 2: 6-LAYER ENTERPRISE SHIELD ---
        rootLayout.addView(makeSectionLabel("What LockSight Solves (7-Layer Precision)"))
        rootLayout.addView(makeCard().apply {
            addView(makeFeatureRow(
                "🔒", "#38BDF8",
                "1. Kernel Private DNS Lock (0ms Ping)",
                "Grays out Android's native Private DNS menu via Enterprise Device Owner. Zero background VPN battery drain and baseline gaming latency."
            ))
            addView(makeDivider())
            addView(makeFeatureRow(
                "🔄", "#10B981",
                "2. Dual-Account 15-Day Rotation (600k/mo)",
                "Automatically rotates between two free NextDNS accounts every 15 days (600,000 queries/month) so heavy users never hit the 300k free cap."
            ))
            addView(makeDivider())
            addView(makeFeatureRow(
                "👁️", "#8B5CF6",
                "3. Aegis Neural Core (100% Offline ONNX AI)",
                "Powered by 16 KB-aligned NudeNet v3 (320n.onnx) with 0.02ms static-frame skipping (0% idle CPU), face-overlap filtering, and 3 sensitivity levels across all apps."
            ))
            addView(makeDivider())
            addView(makeFeatureRow(
                "🔍", "#0EA5E9",
                "4. Universal Browser & Social Keyword Guard",
                "Intercepts explicit search queries and mixed-content torrent domains across all current and future browsers/social apps while exempting AI chats, Notes, and WhatsApp."
            ))
            addView(makeDivider())
            addView(makeFeatureRow(
                "🎬", "#A855F7",
                "5. Sealed Creative VPN & Daylight ADB Vault",
                "Unlocks VPN strictly for verified editors (CapCut, VN, Lightroom, Canva) while suspending browsers, and locks USB/Wireless ADB behind a 12h/24h Daylight-only (7 AM – 6 PM) timer."
            ))
            addView(makeDivider())
            addView(makeFeatureRow(
                "👥", "#EC4899",
                "6. Multi-User & System Cloner Vault (Optional)",
                "Blocks secondary OS spaces (Multiple Users, Guest Mode, System Cloner, Work Profiles & Android Private Space) behind a 12-hour Daylight unlock timer while keeping Dual WhatsApp and File Private Safe usable."
            ))
            addView(makeDivider())
            addView(makeFeatureRow(
                "🧘", "#F59E0B",
                "7. Zen Sanctuary & Multi-Faith Discipline",
                "Fullscreen urge-lockout sanctuary with melodic soundscapes, box breathing, a focus mini-game, clean-streak tracking, and personalized daily reflections."
            ))
        })

        // --- SECTION 3: DAILY USAGE PLAYBOOK ---
        rootLayout.addView(makeSectionLabel("Daily Usage Playbook"))
        rootLayout.addView(makeCard().apply {
            addView(makeAboutRow(
                "Making UPI or Bank Payments",
                "If a payment app flags Accessibility, pull down Quick Settings and tap 'Safe Pay'. Complete your payment (even inside a browser checkout) — protection restores cleanly in 3 minutes."
            ))
            addView(makeDivider())
            addView(makeAboutRow(
                "Downloading Files Behind Ad-Shorteners",
                "Tap the 'Ad-Wall 15m' Quick Settings tile (or choose 10m / 30m / 1h on Shield). Tap again the moment your download starts to restore ad-blocking immediately."
            ))
            addView(makeDivider())
            addView(makeAboutRow(
                "Editing Videos in CapCut / Creative Apps",
                "Tap 'Unlock VPN for Creative Editors' on the Shield tab, finish your edit, and tap 'Relock VPN & Restore All Browsers' when done."
            ))
            addView(makeDivider())
            addView(makeAboutRow(
                "When an Intense Urge Hits",
                "Swipe to 'Zen & Streak', choose 5m or 15m, and enter Zen Sanctuary Mode. Let the 8-minute dopamine wave pass with guided breathing and the focus game."
            ))
        })
    }

    private fun makeFeatureRow(emoji: String, badgeHex: String, title: String, desc: String) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(8), 0, dp(8))

            val badge = TextView(this@MainActivity).apply {
                text = emoji
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = roundedBg(badgeHex, 12f)
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                    rightMargin = dp(12)
                    topMargin = dp(2)
                }
            }

            val col = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = title
                    textSize = 14.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                })
                addView(TextView(this@MainActivity).apply {
                    text = desc
                    textSize = 12.5f
                    setTextColor(Color.parseColor("#CBD5E1"))
                    setPadding(0, dp(3), 0, 0)
                })
            }

            addView(badge)
            addView(col)
        }

    // =========================================================================
    // SUBPAGE 1: SETUP CHECKLIST
    // =========================================================================
    private fun renderChecklistScreen() {
        val isOwner = PolicyController.isDeviceOwner(this)
        val isAccOn = PolicyController.isAccessibilityServiceEnabled(this)
        val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        val pmPower = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val isBgUnrestricted = pmPower.isIgnoringBatteryOptimizations(packageName)
        val hasCustomDns = PolicyController.isCustomDnsSaved(this)
        val isDnsLocked = PolicyController.isPrivateDnsLocked(this)
        val isVpnLocked = PolicyController.isVpnLocked(this)
        val isUninstallLocked = PolicyController.isUninstallBlocked(this)
        val isAdbLocked = PolicyController.isAdbLocked(this)

        val completedCount = listOf(
            isOwner, isAccOn, hasNotif, isBgUnrestricted, hasCustomDns,
            isDnsLocked, isVpnLocked, isUninstallLocked, isAdbLocked
        ).count { it }

        rootLayout.addView(makeCard().apply {
            addView(makeCardHeader(
                "✅",
                if (completedCount == 9) "#10B981" else "#3B82F6",
                "Setup Checklist ($completedCount / 9)",
                if (completedCount == 9) "All 9 protection layers verified & active!" else "Follow steps 1 to 9 in order"
            ))
            addView(makeBody("Verify every permission and system lock below. Make sure Steps 1–8 show a green check mark before locking ADB in Step 9."))
        })

        rootLayout.addView(makeChecklistCard(
            stepNum = 1,
            title = "Device Owner Privilege (PC ADB)",
            passed = isOwner,
            desc = if (isOwner) "Granted! LockSight has Enterprise Device Owner authority."
            else "Connect phone to PC and run: adb shell dpm set-device-owner com.locksight.guard/.AdminReceiver",
            actionLabel = if (!isOwner) "Copy ADB Command" else null
        ) {
            val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clip.setPrimaryClip(ClipData.newPlainText("ADB", "adb shell dpm set-device-owner com.locksight.guard/.AdminReceiver"))
            toast("ADB Command copied to clipboard!")
        })

        rootLayout.addView(makeChecklistCard(
            stepNum = 2,
            title = "Accessibility Service ($AI_SHIELD_NAME & URL Guard)",
            passed = isAccOn,
            desc = if (isAccOn) "Active! Real-time browser URL guard and $AI_SHIELD_NAME are running."
            else "Tap LockSight once inside Accessibility (grayed out), then open App Info -> ⋮ (Top Right) -> Allow restricted settings, and turn LockSight ON.",
            actionLabel = if (!isAccOn) "Open Accessibility Settings" else null
        ) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        rootLayout.addView(makeChecklistCard(
            stepNum = 3,
            title = "Notifications Permission",
            passed = hasNotif,
            desc = if (hasNotif) "Granted! Countdown completion and protection alerts are enabled."
            else "Allow LockSight to send timer completion and security alerts.",
            actionLabel = if (!hasNotif) "Grant Notification Permission" else null
        ) {
            requestNotificationPermissionIfNeeded()
        })

        rootLayout.addView(makeChecklistCard(
            stepNum = 4,
            title = "Allow Background Activity (Battery Unrestricted)",
            passed = isBgUnrestricted,
            desc = if (isBgUnrestricted) "Granted! System battery savers will not kill LockSight in the background."
            else "Required on OnePlus, Xiaomi, Vivo & Samsung. Tap below -> Battery usage -> Enable 'Allow background activity' & 'Unrestricted'.",
            actionLabel = if (!isBgUnrestricted) "Open App Battery Settings" else null
        ) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })

        rootLayout.addView(makeChecklistCard(
            stepNum = 5,
            title = "NextDNS Endpoint Configured",
            passed = hasCustomDns,
            desc = if (hasCustomDns) "Configured: ${PolicyController.getActiveMainDns(this)}"
            else "Using default Control D Family DNS. Enter your NextDNS ID in the Shield tab before sealing.",
            actionLabel = if (!hasCustomDns) "Go to Shield Setup" else null
        ) {
            currentTab = "SHIELD"
            renderScreen()
        })

        rootLayout.addView(makeChecklistCard(
            stepNum = 6,
            title = "Private DNS Settings Menu Locked",
            passed = isDnsLocked,
            desc = if (isDnsLocked) "Locked! Android's Private DNS menu is permanently grayed out."
            else "Not locked yet. Tap 'SEAL & LOCK NEXTDNS PERMANENTLY' on the Shield tab.",
            actionLabel = null
        ) {})

        rootLayout.addView(makeChecklistCard(
            stepNum = 7,
            title = "Third-Party VPN Bypass Blocked (Separate Lock)",
            passed = isVpnLocked,
            desc = if (isVpnLocked) "Locked! Unauthorized third-party VPN apps cannot connect."
            else "Unlocked. Lock this separately so third-party VPNs cannot bypass NextDNS.",
            actionLabel = if (isOwner && !isVpnLocked) "Lock VPN Now" else null
        ) {
            toast(PolicyController.lockFullVpnNow(this))
            renderScreen()
        })

        rootLayout.addView(makeChecklistCard(
            stepNum = 8,
            title = "Immortal App Protection (Uninstall & Force Stop)",
            passed = isUninstallLocked,
            desc = if (isUninstallLocked) "Active! LockSight's Uninstall and Force Stop buttons are grayed out in Android Settings."
            else "Pending NextDNS seal.",
            actionLabel = null
        ) {})

        rootLayout.addView(makeChecklistCard(
            stepNum = 9,
            title = "ADB & Wireless Debugging Locked (Separate Lock)",
            passed = isAdbLocked,
            desc = if (isAdbLocked) "Locked! USB Debugging and Local Wireless ADB (LADB/Termux) are disabled."
            else "Unlocked. Lock this LAST after Steps 1–8 are green!",
            actionLabel = if (isOwner && !isAdbLocked) "Lock ADB Now" else null
        ) {
            toast(PolicyController.lockAdbImmediately(this))
            renderScreen()
        })
    }

    private fun makeChecklistCard(
        stepNum: Int,
        title: String,
        passed: Boolean,
        desc: String,
        actionLabel: String?,
        onAction: () -> Unit
    ) = makeCard().apply {
        addView(makeCardHeader(
            if (passed) "✓" else "$stepNum",
            if (passed) "#10B981" else "#EF4444",
            "Step $stepNum: $title",
            if (passed) "VERIFIED & ACTIVE" else "ACTION REQUIRED"
        ))
        addView(makeBody(desc))
        if (actionLabel != null) {
            addView(makeButton(actionLabel, "#2563EB", true) { onAction() })
        }
    }

    // =========================================================================
    // SUBPAGE 2: ABOUT
    // =========================================================================
    private fun renderAboutScreen() {
        val actManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        val totalRamGb = String.format("%.1f GB", memInfo.totalMem / (1024.0 * 1024.0 * 1024.0))
        val socName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else Build.HARDWARE
        val deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        val androidVer = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val appVersionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.1.0"
        } catch (_: Exception) { "1.1.0" }

        rootLayout.addView(makeSectionLabel("Basic info"))
        rootLayout.addView(makeCard().apply {
            addView(makeAboutRow("App name", "LockSight"))
            addView(makeDivider())
            addView(makeAboutRow("App version", "v$appVersionName"))
            addView(makeDivider())
            addView(makeAboutRow("Developer & Architect", "Mohammed Irfan (Techie-irfu)"))
            addView(makeDivider())
            addView(makeAboutRow("Device", deviceName))
            addView(makeDivider())
            addView(makeAboutRow("Android Version", androidVer))
            addView(makeButton("Check for App Updates", "#2563EB", true) {
                checkForGithubUpdates()
            })
        })

        rootLayout.addView(makeSectionLabel("Hardware & AI Engine"))
        rootLayout.addView(makeCard().apply {
            addView(makeAboutRow("Chipset / SoC", socName))
            addView(makeDivider())
            addView(makeAboutRow("RAM", totalRamGb))
            addView(makeDivider())
            addView(makeAboutRow(AI_SHIELD_NAME, "On-Device Anatomical Vision (ONNX 320n • 16 KB Aligned)"))
            addView(makeDivider())
            addView(makeAboutRow("Active Protection Layers", "4 Layers:\n• Enterprise Device Owner (DPM)\n• Encrypted Private DNS (DoT)\n• Real-Time Accessibility URL & Search Guard\n• On-Device $AI_SHIELD_NAME"))
        })

        rootLayout.addView(makeSectionLabel("Links & actions"))
        rootLayout.addView(makeCard().apply {
            addView(makeActionRow("🔧", "GitHub Repository (Techie-irfu/LockSight)") {
                openUrl("https://github.com/Techie-irfu/LockSight")
            })
            addView(makeDivider())
            addView(makeActionRow("▶️", "YouTube Setup Guide") {
                openUrl("https://youtube.com/")
            })
            addView(makeDivider())
            addView(makeActionRow("💬", "Reddit Community (r/MuslimNoFap & r/NoFap)") {
                openUrl("https://reddit.com/r/MuslimNoFap")
            })
            addView(makeDivider())
            addView(makeActionRow("🐞", "Report a Bug / Send Feedback (GitHub Issues)") {
                openUrl("https://github.com/Techie-irfu/LockSight/issues/new")
            })
            addView(makeDivider())
            addView(makeActionRow("✉️", "Contact Developer (locksight.dev@gmail.com)") {
                val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:locksight.dev@gmail.com?subject=LockSight%20Feedback")
                }
                try {
                    startActivity(emailIntent)
                } catch (_: Exception) {
                    toast("Email: locksight.dev@gmail.com")
                }
            })
            addView(makeDivider())
            addView(makeActionRow("💙", "Support / Donate on Ko-fi (techieirfu)") {
                openUrl("https://ko-fi.com/techieirfu")
            })
            addView(makeDivider())
            addView(makeActionRow("ℹ️", "Copy System & Diagnostic Info") {
                val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val info = "LockSight v\$appVersionName | Architect: Mohammed Irfan (Techie-irfu) | Device: $deviceName | OS: $androidVer | SoC: $socName | RAM: $totalRamGb"
                clip.setPrimaryClip(ClipData.newPlainText("LockSight Info", info))
                toast("Device & LockSight info copied to clipboard!")
            })
            addView(makeDivider())
            addView(makeActionRow("📜", "GNU AGPL-3.0 License & Credits") {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("GNU AGPL-3.0 License")
                    .setMessage(
                        "Copyright © 2026 Mohammed Irfan (Techie-irfu).\n\n" +
                                "LockSight is free, open-source software licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).\n\n" +
                                "• On-device vision powered by NudeNet v3 (320n.onnx — AGPL-3.0) and Microsoft ONNX Runtime (MIT).\n" +
                                "• Anyone may audit, use, or modify the code, provided any modified version or derivative remains open-source under GNU AGPL-3.0 with original credit intact."
                    )
                    .setPositiveButton("Close", null)
                    .setNeutralButton("View Full License") { _, _ ->
                        openUrl("https://github.com/Techie-irfu/LockSight/blob/main/LICENSE")
                    }
                    .show()
            })
        })
    }

    private fun checkForGithubUpdates() {
        toast("Checking GitHub for updates...")
        Thread {
            try {
                val installedVer = packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
                val conn = (URL("https://api.github.com/repos/Techie-irfu/LockSight/releases/latest")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "LockSight-Android")
                    connectTimeout = 8000
                    readTimeout = 8000
                }

                if (conn.responseCode != 200) {
                    runOnUiThread { toast("No public release found yet on GitHub (Status ${conn.responseCode}).") }
                    return@Thread
                }

                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                val json = JSONObject(body)
                val latestTag = json.optString("tag_name", "").trim()
                val releaseNotes = json.optString("body", "Bug fixes and improvements.").take(350)
                val htmlUrl = json.optString("html_url", "https://github.com/Techie-irfu/LockSight/releases/latest")

                // Prefer direct arm64 APK download URL if attached to the GitHub Release
                var downloadUrl = htmlUrl
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "").lowercase()
                        if (name.endsWith(".apk") && name.contains("arm64")) {
                            downloadUrl = asset.optString("browser_download_url", htmlUrl)
                            break
                        }
                    }
                }

                val isNewer = isVersionNewer(latestTag, installedVer)
                runOnUiThread {
                    if (isNewer) {
                        AlertDialog.Builder(this)
                            .setTitle("Update Available: $latestTag")
                            .setMessage("Installed: v$installedVer\nLatest: $latestTag\n\n$releaseNotes")
                            .setPositiveButton("Download APK") { _, _ -> openUrl(downloadUrl) }
                            .setNegativeButton("Later", null)
                            .show()
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle("LockSight is Up to Date")
                            .setMessage("You are already running the latest version (v$installedVer).")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            } catch (_: Exception) {
                runOnUiThread { toast("Could not check for updates. Verify internet connection.") }
            }
        }.start()
    }

    private fun isVersionNewer(latestTag: String, currentVer: String): Boolean {
        val latestParts = latestTag.removePrefix("v").removePrefix("V").split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = currentVer.removePrefix("v").removePrefix("V").split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    private fun showSeparateExclusionDialog(isGames: Boolean) {
        PolicyController.getEligibleAppsFastAsync(this, isGames) { eligible ->
            if (isFinishing || isDestroyed) return@getEligibleAppsFastAsync
            if (eligible.isEmpty()) {
                toast(if (isGames) "No installed Games or Emulators detected." else "No installed Banking or KYC apps detected.")
                return@getEligibleAppsFastAsync
            }

            val prefKey = if (isGames) "excluded_games" else "excluded_banks"
            val prefs = PolicyController.prefs(this)
            val currentExcluded = (prefs.getStringSet(prefKey, emptySet()) ?: emptySet()).toMutableSet()
            val labels = eligible.map { it.first }.toTypedArray()
            val checked = eligible.map { currentExcluded.contains(it.second) }.toBooleanArray()

            AlertDialog.Builder(this)
                .setTitle(if (isGames) "Exclude Safe Games & Emulators" else "Exclude Banking, KYC & Document Apps")
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    val pkg = eligible[which].second
                    if (isChecked) currentExcluded.add(pkg) else currentExcluded.remove(pkg)
                }
                .setPositiveButton("Save") { _, _ ->
                    prefs.edit().putStringSet(prefKey, currentExcluded).commit()
                    renderScreen()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    @Suppress("DEPRECATION")
    private fun startByeDpiInternalSplitter() {
        if (PolicyController.isDeviceOwner(this)) {
            val dpm = PolicyController.dpm(this)
            val admin = PolicyController.admin(this)
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_VPN)
        }
        val prep: Intent? = VpnService.prepare(this)
        if (prep != null) {
            startActivityForResult(prep, 100)
        } else {
            activateDpiServiceAndNotify()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100) {
            if (resultCode == RESULT_OK) {
                activateDpiServiceAndNotify()
            } else {
                if (PolicyController.isDeviceOwner(this) && PolicyController.isSystemSealed(this) &&
                    !PolicyController.prefs(this).getBoolean("creative_vpn_active", false)
                ) {
                    PolicyController.dpm(this).addUserRestriction(
                        PolicyController.admin(this),
                        UserManager.DISALLOW_CONFIG_VPN
                    )
                }
                toast("VPN Permission cancelled.")
            }
        }
    }

    private fun activateDpiServiceAndNotify() {
        val prefs = PolicyController.prefs(this)
        prefs.edit().putBoolean("dpi_splitter_active", true).putBoolean("dpi_active", true).commit()
        startService(Intent(this, LocalPacketVpnService::class.java).putExtra("mode", "BYEDPI"))

        PolicyController.sendTimerNotification(
            this,
            "DPI Packet Fragmentation Active",
            "Local SNI packet splitter is running with 0ms ping loss. NextDNS protection remains enforced.",
            1007
        )
        toast("Local Packet Fragmentation Enabled!")
        renderScreen()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {}
    }

    // =========================================================================
    // UI STYLING HELPERS
    // =========================================================================
    private fun roundedBg(hexColor: String, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dpF(radiusDp)
        setColor(Color.parseColor(hexColor))
    }

    private fun roundedBgWithBorder(fillHex: String, borderHex: String, radiusDp: Float, strokePx: Int) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(radiusDp)
            setColor(Color.parseColor(fillHex))
            setStroke(strokePx, Color.parseColor(borderHex))
        }

    private fun makeCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBg("#161D2F", 18f)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(15) }
    }

    private fun makeCardHeader(iconEmoji: String, badgeHex: String, title: String, subtitle: String) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))

            val badge = TextView(this@MainActivity).apply {
                text = iconEmoji
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                background = roundedBg(badgeHex, 50f)
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(12) }
            }

            val textCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = title
                    textSize = 16.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                })
                addView(TextView(this@MainActivity).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(Color.parseColor("#94A3B8"))
                })
            }

            addView(badge)
            addView(textCol)
        }

    private fun makeStatusRow(label: String, value: String, positive: Boolean) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 12.5f
                isSingleLine = true
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(0, 0, dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
            addView(TextView(this@MainActivity).apply {
                text = value
                textSize = if (value.length > 22) 11.5f else 12.5f
                isSingleLine = true
                gravity = Gravity.END
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (positive) Color.parseColor("#34D399") else Color.parseColor("#F87171"))
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            })
        }

    private fun makeSectionLabel(txt: String) = TextView(this).apply {
        text = txt
        textSize = 13.5f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.parseColor("#60A5FA"))
        setPadding(dp(6), dp(4), 0, dp(8))
    }

    private fun makeAboutRow(title: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 15.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        addView(TextView(this@MainActivity).apply {
            text = value
            textSize = 13.5f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(2), 0, 0)
        })
    }

    private fun makeActionRow(emoji: String, label: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(10))
        addView(TextView(this@MainActivity).apply {
            text = emoji
            textSize = 18f
            setPadding(0, 0, dp(14), 0)
        })
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 15.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        setOnClickListener { onClick() }
    }

    private fun makeDivider() = View(this).apply {
        setBackgroundColor(Color.parseColor("#26304A"))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        }
    }

    private fun makeFieldLabel(txt: String) = TextView(this).apply {
        text = txt
        textSize = 13f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.parseColor("#E2E8F0"))
        setPadding(dp(2), dp(8), dp(2), dp(4))
    }

    private fun makeBody(txt: String) = TextView(this).apply {
        text = txt
        textSize = 13f
        setTextColor(Color.parseColor("#CBD5E1"))
        setPadding(0, 0, 0, dp(8))
    }

    private fun makeInput(hintText: String, initial: String) = EditText(this).apply {
        hint = hintText
        setText(initial)
        textSize = 14f
        setHintTextColor(Color.parseColor("#64748B"))
        setTextColor(Color.WHITE)
        background = roundedBg("#0B0F19", 11f)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        inputType = InputType.TYPE_CLASS_TEXT
    }

    private fun makeButton(label: String, hexColor: String, enabled: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        isEnabled = enabled
        textSize = 13.5f
        setTypeface(null, Typeface.BOLD)
        setTextColor(if (enabled) Color.WHITE else Color.parseColor("#64748B"))
        background = roundedBg(if (enabled) hexColor else "#1E293B", 13f)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        setOnClickListener { if (enabled) onClick() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}