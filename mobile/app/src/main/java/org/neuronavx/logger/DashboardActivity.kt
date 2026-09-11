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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/** Product entry point; developer logging/benchmarks remain available under Tools. */
import android.content.res.ColorStateList
import android.view.HapticFeedbackConstants

/**
 * Waynova Mission Control Dashboard.
 *
 * Warm dark avionics telemetry hub providing system readiness, offline coverage,
 * one-touch navigation/demo launch, and structured flight data logs.
 */
class DashboardActivity : ComponentActivity() {

    private lateinit var rides: LinearLayout
    private var generation = 0

    // Warm Dark Palette Tokens
    private val bgDark = Color.parseColor("#0C0D11")
    private val surfaceBase = Color.parseColor("#14161E")
    private val surfaceElevated = Color.parseColor("#1B1F2A")
    private val borderSubtle = Color.parseColor("#262C3B")
    private val borderProminent = Color.parseColor("#3A4459")
    private val textPrimary = Color.parseColor("#F7F5F0")
    private val textSecondary = Color.parseColor("#9EA4B1")
    private val textMuted = Color.parseColor("#697386")
    private val accentAmber = Color.parseColor("#F59E0B")
    private val accentAmberDim = Color.parseColor("#271E13")
    private val accentEmerald = Color.parseColor("#10B981")
    private val accentEmeraldDim = Color.parseColor("#13271F")
    private val accentSky = Color.parseColor("#38BDF8")
    private val accentSkyDim = Color.parseColor("#122331")
    private val accentWarmGold = Color.parseColor("#E5A93C")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bgDark)
            isFillViewport = true
            clipToPadding = false
        }

        val root = column().apply {
            setPadding(dp(20), dp(16), dp(20), dp(32))
        }
        scroll.addView(root)

        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.setPadding(dp(20), bars.top + dp(14), dp(20), bars.bottom + dp(32))
            insets
        }

        // 1. Top Avionics Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val headerTitles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("WAYNOVA", 22f, textPrimary, bold = true).apply {
                letterSpacing = 0.12f
            })
            addView(text("ISRO SIH26168 // INTELLIGENT ESTIMATOR", 10f, accentAmber, bold = true).apply {
                letterSpacing = 0.16f
            }, margin(3))
        }
        header.addView(headerTitles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val toolsButton = text("DIAGNOSTICS ⚙", 11f, textSecondary, bold = true).apply {
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            minHeight = dp(38)
            setPadding(dp(12), 0, dp(12), 0)
            background = pill(surfaceElevated, dp(14), borderSubtle)
            isClickable = true
            isFocusable = true
            contentDescription = "Open developer diagnostics and benchmarks"
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                startActivity(Intent(this@DashboardActivity, MainActivity::class.java))
            }
        }
        header.addView(toolsButton)
        root.addView(header)

        // 2. Hardware / Estimator Status Strip
        val statusStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusStrip.addView(statusChip("● IMU 100 Hz", accentEmerald, accentEmeraldDim), chipParams(dp(6)))
        statusStrip.addView(statusChip("● TCN ONNX", accentAmber, accentAmberDim), chipParams(dp(6)))
        statusStrip.addView(statusChip("● PMTiles Offline", accentSky, accentSkyDim), chipParams())
        root.addView(statusStrip, margin(16))

        // 3. Mission Hero Cockpit Card
        val hero = column().apply {
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(surfaceElevated, surfaceBase)
            ).apply {
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), borderProminent)
            }
        }
        hero.addView(text("GNSS-DENIED DEAD RECKONING", 10f, accentAmber, bold = true).apply {
            letterSpacing = 0.18f
        })
        hero.addView(text("Autonomous Inertial\nPositioning System", 25f, textPrimary, bold = true).apply {
            setLineSpacing(0f, 1.1f)
        }, margin(10))
        hero.addView(text(
            "Hybrid 6-state Error-State EKF coupled with causal Speed-TCN. Maintains lane-level trajectory through satellite outages with zero-jump recovery.",
            13.5f, textSecondary
        ).apply { setLineSpacing(0f, 1.18f) }, margin(10))

        // Specs Telemetry Row
        val specRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = pill(bgDark, dp(12), borderSubtle)
        }
        specRow.addView(specItem("LATENCY", "0.18 ms", accentEmerald))
        specRow.addView(specDivider())
        specRow.addView(specItem("ESTIMATOR", "6-DOF EKF", accentAmber))
        specRow.addView(specDivider())
        specRow.addView(specItem("RATE", "100 Hz", accentSky))
        hero.addView(specRow, margin(16))

        // Primary & Secondary Action Buttons
        val primaryBtn = primaryButton("▶  LAUNCH LIVE MISSION") {
            startActivity(Intent(this, NavigationActivity::class.java))
        }
        hero.addView(primaryBtn, margin(16))

        val secondaryBtn = secondaryButton("⚡  REPLAY 120s OUTAGE BENCHMARK") {
            startActivity(Intent(this, NavigationActivity::class.java).putExtra("start_demo", true))
        }
        hero.addView(secondaryBtn, margin(10))
        root.addView(hero, margin(16))

        // 4. Quick Actions / Destination & Offline Sector Card
        val utilityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val destinationCard = utilityCard(
            title = "⌕  Plan Destination",
            caption = "OSRM preview · offline directions",
            accent = accentSky
        ) {
            startActivity(Intent(this, NavigationActivity::class.java).putExtra("plan_route", true))
        }
        val offlineMapCard = utilityCard(
            title = "🗺️  Greater Noida",
            caption = "12.7 MB bundled vector map",
            accent = accentEmerald
        ) {
            startActivity(Intent(this, NavigationActivity::class.java))
        }

        utilityRow.addView(destinationCard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(8)
        })
        utilityRow.addView(offlineMapCard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(8)
        })
        root.addView(utilityRow, margin(12))

        // 5. Flight Data Records (Recent Drives)
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(text("Flight Data Records", 18f, textPrimary, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        heading.addView(text("SAVED ON DEVICE", 9f, textMuted, bold = true).apply {
            letterSpacing = 0.12f
        })
        root.addView(heading, margin(24))

        rides = column()
        root.addView(rides, margin(10))

        // 6. Mission Protocol & Footer
        val protocolBtn = secondaryButton("📋  View 60-Second Field Protocol") {
            showInstructions()
        }
        root.addView(protocolBtn, margin(16))

        val footer = column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(text("WAYNOVA // BUILD v${BuildConfig.VERSION_NAME}", 10f, textMuted, bold = true).apply {
                letterSpacing = 0.12f
            })
            addView(text("All test metrics benchmarked against phone GPS reference.", 10f, textMuted).apply {
                setPadding(0, dp(4), 0, 0)
            })
        }
        root.addView(footer, margin(20))

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
                if (records.isEmpty()) {
                    rides.addView(card().apply {
                        addView(text("No flight records yet", 15f, textPrimary, bold = true))
                        addView(text("Live sessions and controlled 60s blackout tests record telemetry automatically. Results will appear here.",
                            12.5f, textSecondary), margin(6))
                    })
                } else {
                    records.forEach { record ->
                        rides.addView(buildRecordCard(record), margin(8))
                    }
                }
            }
        }
    }

    private fun buildRecordCard(record: SessionRecord): View {
        return column().apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = pill(surfaceBase, dp(18), borderSubtle)
            isClickable = true
            isFocusable = true
            contentDescription = "Recorded mission ${date(record.id)}, ${record.status}. Open telemetry details"

            // Header row: Date and Status Badge
            val topRow = LinearLayout(this@DashboardActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topRow.addView(text(date(record.id), 14.5f, textPrimary, bold = true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val statusColor = if (record.complete) accentEmerald else accentAmber
            val statusBg = if (record.complete) accentEmeraldDim else accentAmberDim
            val statusBadge = text(if (record.complete) "COMPLETE" else "PARTIAL", 10f, statusColor, bold = true).apply {
                letterSpacing = 0.08f
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = pill(statusBg, dp(10), statusColor)
            }
            topRow.addView(statusBadge)
            addView(topRow)

            // Metrics row: Duration, Fixes, Outage/Recovery
            val statsRow = LinearLayout(this@DashboardActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
            statsRow.addView(logStat("DURATION", "%.1f min".format(record.durationS / 60)))
            statsRow.addView(logStat("FIXES", "%,d GPS".format(record.phoneFixes)))
            val outageText = record.endErrorM?.let { "%.1f m err".format(it) }
                ?: (if (record.outageS != null) "%.0fs out".format(record.outageS) else "Aided only")
            statsRow.addView(logStat("OUTAGE REF", outageText, if (record.endErrorM != null) accentWarmGold else textSecondary))
            addView(statsRow)

            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                showRecord(record)
            }
        }
    }

    private fun logStat(label: String, value: String, valueColor: Int = textSecondary) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(text(label, 8.5f, textMuted, bold = true).apply { letterSpacing = 0.08f })
            addView(text(value, 12f, valueColor, bold = true).apply { setPadding(0, dp(2), 0, 0) })
        }

    private fun showRecord(record: SessionRecord) {
        fun formatVal(value: Double?, unit: String) = value?.let { "%.1f %s".format(it, unit) } ?: "Not recorded"

        val container = column().apply {
            setPadding(dp(22), dp(16), dp(22), dp(12))
            setBackgroundColor(surfaceBase)

            addView(text(date(record.id), 17f, textPrimary, bold = true))
            addView(text("Session ID: ${record.id}", 11f, textMuted), margin(4))

            // Summary card inside dialog
            val card = column().apply {
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = pill(surfaceElevated, dp(14), borderSubtle)
            }
            card.addView(dialogRow("Status", record.status, if (record.complete) accentEmerald else accentAmber))
            card.addView(dialogRow("Total Duration", "%.1f min".format(record.durationS / 60)))
            card.addView(dialogRow("IMU Samples", "%,d (100 Hz)".format(record.imuSamples)))
            card.addView(dialogRow("Satellite Fixes", "%,d".format(record.phoneFixes)))
            card.addView(dialogRow("Controlled Outage", formatVal(record.outageS, "s")))
            card.addView(dialogRow("Outage Position Error", formatVal(record.endErrorM, "m"), accentWarmGold))
            card.addView(dialogRow("Recovery Slew Time", formatVal(record.recoveryS, "s"), accentSky))
            addView(card, margin(14))

            addView(text(
                "Error is measured against the phone's hidden GPS reference during controlled withholding. Raw 100 Hz logs and 10 Hz EKF diagnostics remain stored on this phone.",
                11f, textMuted
            ).apply { setLineSpacing(0f, 1.15f) }, margin(12))
        }

        AlertDialog.Builder(this)
            .setView(container)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun dialogRow(label: String, value: String, valColor: Int = textPrimary): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
            addView(text(label, 12f, textSecondary), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text(value, 12f, valColor, bold = true))
        }
    }

    private fun showInstructions() {
        val container = column().apply {
            setPadding(dp(22), dp(16), dp(22), dp(12))
            setBackgroundColor(surfaceBase)
            addView(text("60-Second Controlled Outage Protocol", 17f, textPrimary, bold = true))
            addView(text("Standard Operating Procedure (SOP)", 11f, accentAmber, bold = true), margin(3))

            val steps = listOf(
                "1. Rigid Mounting" to "Affix phone rigidly to vehicle dashboard. Calibration will fail if the phone wobbles.",
                "2. Park & Initialize" to "Open Live Navigation while vehicle is stationary. Confirm GPS lock.",
                "3. Arm Blackout" to "Tap 'Arm 60s Outage'. It will automatically engage after 15s aided lead-in and dynamic turns.",
                "4. Live Withholding" to "The estimator withholds satellite fixes for 60s while logging raw ground truth in the background.",
                "5. Recovery & Analysis" to "Verify that GNSS returns without map teleportation (TrackSmoother slew-limiting). Park, then stop session."
            )

            val list = column()
            steps.forEach { (title, desc) ->
                val row = column().apply {
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    background = pill(surfaceElevated, dp(12), borderSubtle)
                    addView(text(title, 13f, accentWarmGold, bold = true))
                    addView(text(desc, 11.5f, textSecondary).apply { setLineSpacing(0f, 1.14f) }, margin(3))
                }
                list.addView(row, margin(6))
            }
            addView(list, margin(10))
        }

        AlertDialog.Builder(this)
            .setView(container)
            .setPositiveButton("Acknowledge", null)
            .show()
    }

    private fun statusChip(title: String, textColor: Int, bgColor: Int): View {
        return text(title, 10.5f, textColor, bold = true).apply {
            letterSpacing = 0.06f
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = pill(bgColor, dp(12), textColor)
        }
    }

    private fun chipParams(endMargin: Int = 0) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            this.marginEnd = endMargin
        }

    private fun specItem(title: String, value: String, accent: Int): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(text(title, 8.5f, textMuted, bold = true).apply { letterSpacing = 0.1f })
            addView(text(value, 12f, accent, bold = true).apply { setPadding(0, dp(2), 0, 0) })
        }
    }

    private fun specDivider(): View {
        return View(this).apply {
            background = pill(borderSubtle, dp(1))
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(22))
        }
    }

    private fun utilityCard(title: String, caption: String, accent: Int, action: () -> Unit): View {
        return column().apply {
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = pill(surfaceBase, dp(16), borderSubtle)
            isClickable = true
            isFocusable = true
            addView(text(title, 13f, textPrimary, bold = true))
            addView(text(caption, 10.5f, textSecondary).apply { setPadding(0, dp(3), 0, 0) })
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                action()
            }
        }
    }

    private fun primaryButton(title: String, action: () -> Unit): View =
        text(title, 14.5f, Color.parseColor("#0C0D11"), bold = true).apply {
            letterSpacing = 0.08f
            minHeight = dp(52)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                setColor(accentAmber)
                cornerRadius = dp(16).toFloat()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                action()
            }
        }

    private fun secondaryButton(title: String, action: () -> Unit): View =
        text(title, 13f, textPrimary, bold = true).apply {
            letterSpacing = 0.06f
            minHeight = dp(46)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = pill(surfaceElevated, dp(15), borderProminent)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                action()
            }
        }

    private fun pill(color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

    private fun card() = column().apply {
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = pill(surfaceBase, dp(18), borderSubtle)
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        includeFontPadding = false
    }

    private fun date(id: Long) = SimpleDateFormat("EEE, d MMM · h:mm a", Locale.getDefault()).format(Date(id))
    private fun margin(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
