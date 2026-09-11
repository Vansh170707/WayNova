package org.neuronavx.logger

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/** Product entry point; developer logging/benchmarks remain available under Tools. */
class DashboardActivity : ComponentActivity() {
    private lateinit var rides: LinearLayout
    private var generation = 0
    private val ink = Color.rgb(239, 245, 252)
    private val muted = Color.rgb(151, 170, 193)
    private val blue = Color.rgb(96, 165, 250)
    private val mint = Color.rgb(87, 220, 181)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(8, 15, 26))
            isFillViewport = true
            clipToPadding = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        val root = column().apply { setPadding(dp(22), dp(24), dp(22), dp(24)) }
        scroll.addView(root)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("WAYNOVA", 21f, ink, true).apply { letterSpacing = .12f },
            LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(text("Tools  ↗", 13f, muted, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(12), 0, dp(4), 0)
            isClickable = true; isFocusable = true
            contentDescription = "Open developer tools and benchmarks"
            setOnClickListener { startActivity(Intent(this@DashboardActivity, MainActivity::class.java)) }
        })
        root.addView(header)
        root.addView(text("ON-DEVICE POSITIONING", 10f, mint, true).apply { letterSpacing = .18f })

        val hero = card().apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(24, 49, 76), Color.rgb(13, 29, 44))).apply {
                cornerRadius = dp(26).toFloat(); setStroke(dp(1), Color.rgb(45, 73, 98))
            }
        }
        hero.addView(text("ROUND TWO  /  WORKING PROTOTYPE", 10f, mint, true))
        hero.addView(text("Your journey.\nYour signal.", 33f, ink, true), margin(12))
        hero.addView(text("Satellite fixes and phone motion, working together. Offline maps stay on your device.",
            15f, muted), margin(12))
        hero.addView(text("GPS AIDED  →  SIGNAL LOSS  →  RECOVERY", 10f, blue, true), margin(22))
        root.addView(hero, margin(24))

        root.addView(button("Open live navigation  →", true) {
            startActivity(Intent(this, NavigationActivity::class.java))
        }, margin(20))
        root.addView(button("Watch the recorded demo", false) {
            startActivity(Intent(this, NavigationActivity::class.java).putExtra("start_demo", true))
        }, margin(10))
        root.addView(text("Online route planning · offline saved directions\nMount the phone and set up only while parked.",
            12f, muted), margin(12))
        root.addView(button("Find a destination  ↗", false) {
            startActivity(Intent(this, NavigationActivity::class.java).putExtra("plan_route", true))
        }, margin(10))
        root.addView(text("Search for a place or enter coordinates.",
            12f, muted), margin(12))

        val offline = card()
        offline.addView(text("◎   GREATER NOIDA · OFFLINE MAP", 12f, mint, true))
        offline.addView(text("Bundled street map · no map download needed\nOutside coverage, the local track view stays available.",
            12f, muted), margin(8))
        root.addView(offline, margin(24))

        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(text("Recent drives", 21f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(text("ON THIS PHONE", 9f, muted, true))
        root.addView(heading, margin(28))
        rides = column()
        root.addView(rides, margin(12))
        root.addView(button("How to run a 60-second test", false) { showInstructions() }, margin(20))
        root.addView(text("WAYNOVA  /  ${BuildConfig.VERSION_NAME}\nTest results are phone-reference estimates, not certified accuracy.",
            10f, muted), margin(24))
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        val token = ++generation
        thread(name = "waynova-session-history") {
            val records = SessionRecord.recent(getExternalFilesDir(null))
            runOnUiThread {
                if (isDestroyed || token != generation) return@runOnUiThread
                rides.removeAllViews()
                if (records.isEmpty()) rides.addView(card().apply {
                    addView(text("Your first drive starts here", 16f, ink, true))
                    addView(text("Live sessions save automatically when you stop. Your results will appear here.",
                        13f, muted), margin(8))
                })
                records.forEach { record ->
                    rides.addView(card().apply {
                        isClickable = true; isFocusable = true
                        contentDescription = "Recorded drive ${date(record.id)}, ${record.status}. Open details"
                        addView(text(date(record.id), 15f, ink, true))
                        addView(text(record.status, 12f, if (record.complete) mint else muted), margin(7))
                        addView(text("%.1f min  ·  %,d GPS fixes   ↗".format(record.durationS / 60, record.phoneFixes),
                            12f, muted), margin(7))
                        setOnClickListener { showRecord(record) }
                    }, margin(8))
                }
            }
        }
    }

    private fun showRecord(record: SessionRecord) {
        fun number(value: Double?, unit: String) = value?.let { "%.1f %s".format(it, unit) } ?: "Not recorded"
        AlertDialog.Builder(this).setTitle(date(record.id)).setMessage(
            "${record.status}\n\n" +
                "Session: %.1f minutes\n".format(record.durationS / 60) +
                "IMU samples: ${record.imuSamples}\nGPS fixes: ${record.phoneFixes}\n\n" +
                "Controlled outage: ${number(record.outageS, "s")}\n" +
                "Outage-end position error: ${number(record.endErrorM, "m")}\n" +
                "GPS recovery: ${number(record.recoveryS, "s")}\n\n" +
                "Error is measured against the phone's hidden GPS reference. A completed test does not mean the accuracy target passed.\n\n" +
                "Session ID: ${record.id}\nRaw data, diagnostics and summary remain saved on this phone."
        ).setPositiveButton("Done", null).show()
    }

    private fun showInstructions() {
        AlertDialog.Builder(this).setTitle("Test safely, while parked").setMessage(
            "1. Fix the phone firmly in its mount. Keep Android Location on.\n\n" +
                "2. Open live navigation, then tap Start live navigation.\n\n" +
                "3. While still parked, tap Arm 60s test. Drive normally; calibration needs motion and turns.\n\n" +
                "4. The test starts after calibration and a 15-second lead-in. The app hides GPS fixes from its estimator for 60 seconds, but records them for comparison.\n\n" +
                "5. Keep the app visible. Wait for Test complete and GPS recovery. Park, then stop.\n\n" +
                "Airplane mode tests internet loss, not satellite loss. Never operate the phone while driving."
        ).setPositiveButton("Got it", null).show()
    }

    private fun date(id: Long) = SimpleDateFormat("EEE, d MMM · h:mm a", Locale.getDefault()).format(Date(id))
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun card() = column().apply {
        setPadding(dp(18), dp(19), dp(18), dp(19))
        background = GradientDrawable().apply {
            setColor(Color.rgb(16, 28, 43)); cornerRadius = dp(20).toFloat()
            setStroke(dp(1), Color.rgb(35, 52, 71))
        }
    }
    private fun button(title: String, primary: Boolean, action: () -> Unit): View =
        text(title, 15f, if (primary) Color.rgb(7, 24, 39) else ink, true).apply {
            minHeight = dp(56); gravity = Gravity.CENTER
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                setColor(if (primary) blue else Color.rgb(22, 36, 53))
                cornerRadius = dp(17).toFloat()
                setStroke(dp(1), if (primary) blue else Color.rgb(50, 70, 94))
            }
            isClickable = true; isFocusable = true
            setOnClickListener { action() }
        }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        setLineSpacing(0f, 1.12f)
    }
    private fun margin(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
