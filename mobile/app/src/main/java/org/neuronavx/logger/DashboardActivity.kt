package org.neuronavx.logger

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import org.neuronavx.logger.ui.Glass
import org.neuronavx.logger.ui.GlassNavBar
import org.neuronavx.logger.ui.accentChip
import org.neuronavx.logger.ui.accentFill
import org.neuronavx.logger.ui.column
import org.neuronavx.logger.ui.dp
import org.neuronavx.logger.ui.glassScaffold
import org.neuronavx.logger.ui.dpf
import org.neuronavx.logger.ui.eyebrow
import org.neuronavx.logger.ui.glass
import org.neuronavx.logger.ui.glassCard
import org.neuronavx.logger.ui.glow
import org.neuronavx.logger.ui.matchWrap
import org.neuronavx.logger.ui.pressable
import org.neuronavx.logger.ui.row
import org.neuronavx.logger.ui.solid
import org.neuronavx.logger.ui.statusLine
import org.neuronavx.logger.ui.textView
import org.neuronavx.logger.ui.weightParams
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Waynova home.
 *
 * Liquid-glass mission dashboard: readiness of the estimator stack at a glance, the two
 * launch actions, and the most recent recorded drive. Developer logging and benchmarks
 * stay reachable under Diagnostics.
 */
class DashboardActivity : ComponentActivity() {

    private lateinit var recentSlot: LinearLayout
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val navBar = GlassNavBar(this)
        val scaffold = glassScaffold(navBar = navBar)
        val root = scaffold.content

        root.addView(buildHeader())
        root.addView(buildHeroCard(), matchWrap(18, this))
        root.addView(buildResilienceCard(), matchWrap(14, this))
        root.addView(buildActionGrid(), matchWrap(14, this))
        root.addView(buildRecentHeading(), matchWrap(22, this))

        recentSlot = column()
        root.addView(recentSlot, matchWrap(12, this))

        root.addView(textView(
            "Build v${BuildConfig.VERSION_NAME} · ISRO SIH26168\nAll drift figures are benchmarked against the phone GPS reference.",
            10f, Glass.TEXT_MUTED, lineMultiplier = 1.3f,
        ).apply { gravity = Gravity.CENTER_HORIZONTAL }, matchWrap(24, this))

        navBar.setTabs(
            listOf(
                GlassNavBar.Tab("⌂", "Home") {},
                GlassNavBar.Tab("◈", "Map") { launchNavigation() },
                GlassNavBar.Tab("◫", "Trips") { startActivity(Intent(this, TripsActivity::class.java)) },
                GlassNavBar.Tab("⚙", "Settings") { startActivity(Intent(this, SettingsActivity::class.java)) },
            ),
            selectedIndex = 0,
        )

        setContentView(scaffold.shell)
    }

    // -- Header -------------------------------------------------------------------

    private fun buildHeader(): View = row().apply {
        val mark = View(this@DashboardActivity).apply {
            background = markDrawable()
            glow(Glass.EMERALD, 12f)
        }
        addView(mark, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(11) })

        val titles = column().apply {
            addView(textView("WayNova", 25f, Glass.TEXT_PRIMARY, bold = true, tracking = -0.01f))
            addView(textView("Navigate Beyond Limits", 11.5f, Glass.TEXT_SECONDARY).apply {
                setPadding(0, dp(2), 0, 0)
            })
        }
        addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        addView(circleButton("◎", "Open diagnostics and system status") {
            startActivity(Intent(this@DashboardActivity, DiagnosticsActivity::class.java))
        })
    }

    /** The brand mark: an emerald gradient rounded square with the Waynova chevron. */
    private fun markDrawable() = LayerDrawable(
        arrayOf(
            GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Glass.EMERALD, Glass.TEAL, Glass.CYAN),
            ).apply { cornerRadius = dpf(11f) },
            getDrawable(R.drawable.ic_waynova_mark)!!,
        ),
    ).apply { setLayerInset(1, dp(6), dp(6), dp(6), dp(6)) }

    private fun circleButton(glyph: String, description: String, action: () -> Unit) =
        textView(glyph, 15f, Glass.TEXT_SECONDARY).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            background = pressable(glass(Glass.R_CHIP))
            isClickable = true
            isFocusable = true
            contentDescription = description
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                action()
            }
        }

    // -- Hero ---------------------------------------------------------------------

    private fun buildHeroCard(): View {
        val card = FrameLayout(this).apply {
            background = heroBackground()
            glow(Glass.EMERALD, 16f)
            isClickable = true
            isFocusable = true
            foreground = pressable(solid(Color.TRANSPARENT, Glass.R_CARD))
            contentDescription = "Go further, stay on track. Start a live navigation session"
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                launchNavigation()
            }
        }

        val body = column().apply { setPadding(dp(20), dp(22), dp(20), dp(22)) }
        body.addView(textView("Go Further.\nStay on Track.", 27f, Glass.TEXT_PRIMARY, bold = true, lineMultiplier = 1.12f))
        body.addView(textView(
            "Resilient navigation for a more open world.\nInertial estimation carries the vehicle through satellite outages.",
            12.5f, Glass.TEXT_SECONDARY, lineMultiplier = 1.3f,
        ), matchWrap(12, this))
        card.addView(body, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(46) })

        val chevron = textView("›", 24f, Glass.TEXT_ON_ACCENT, bold = true).apply {
            gravity = Gravity.CENTER
            background = accentFill(Glass.EMERALD, Glass.R_CHIP)
            glow(Glass.EMERALD, 12f)
        }
        card.addView(chevron, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = dp(18)
        })
        return card
    }

    /**
     * A horizon behind the hero copy: a teal-to-deep-green wash with a soft light source
     * in the upper right, standing in for the aerial photography of the concept art
     * without shipping a bitmap.
     */
    private fun heroBackground() = LayerDrawable(
        arrayOf(
            GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF0E2A24.toInt(), 0xFF0A1A20.toInt(), 0xFF0B1017.toInt()),
            ).apply { cornerRadius = dpf(Glass.R_CARD.toFloat()) },
            GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                colors = intArrayOf(Glass.alpha(Glass.MINT, 0.30f), Glass.alpha(Glass.TEAL, 0.08f), Color.TRANSPARENT)
                gradientRadius = dpf(190f)
                setGradientCenter(0.82f, 0.12f)
                cornerRadius = dpf(Glass.R_CARD.toFloat())
            },
            GradientDrawable().apply {
                cornerRadius = dpf(Glass.R_CARD.toFloat())
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), Glass.BORDER_BRIGHT)
            },
        ),
    )

    // -- Resilience ---------------------------------------------------------------

    private fun buildResilienceCard(): View = glassCard().apply {
        val head = row()
        val shield = textView("◇", 17f, Glass.EMERALD).apply {
            gravity = Gravity.CENTER
            background = accentChip(Glass.EMERALD, Glass.R_INNER)
        }
        head.addView(shield, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(13) })

        val copy = column().apply {
            addView(textView("Navigation Resilience", 15.5f, Glass.TEXT_PRIMARY, bold = true))
            addView(textView("Continues to navigate when GNSS is unavailable.", 11.5f, Glass.TEXT_SECONDARY).apply {
                setPadding(0, dp(3), 0, 0)
            })
        }
        head.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val states = column().apply {
            addView(statusLine("GNSS", "AIDED", Glass.EMERALD))
            addView(statusLine("IMU", "100 Hz", Glass.TEAL), matchWrap(7, this@DashboardActivity))
            addView(statusLine("Model", "TCN READY", Glass.CYAN), matchWrap(7, this@DashboardActivity))
        }
        head.addView(states)
        addView(head)
    }

    // -- Actions ------------------------------------------------------------------

    private fun buildActionGrid(): View = column().apply {
        val gap = dp(11)

        val topRow = row().apply {
            addView(primaryAction("▶", "Start Drive", "Begin live navigation") { launchNavigation() },
                weightParams(endMarginPx = gap))
            addView(action("↻", "Replay Route", "Benchmark past drives", Glass.CYAN) {
                startActivity(Intent(this@DashboardActivity, NavigationActivity::class.java)
                    .putExtra("start_demo", true))
            }, weightParams())
        }
        addView(topRow)

        val bottomRow = row().apply {
            addView(action("▣", "Offline Map", "Download and manage", Glass.TEAL) {
                startActivity(Intent(this@DashboardActivity, SettingsActivity::class.java))
            }, weightParams(endMarginPx = gap))
            addView(action("⌁", "Diagnostics", "System health", Glass.SKY) {
                startActivity(Intent(this@DashboardActivity, DiagnosticsActivity::class.java))
            }, weightParams())
        }
        addView(bottomRow, matchWrap(11, this@DashboardActivity))
    }

    private fun primaryAction(glyph: String, title: String, caption: String, action: () -> Unit): View =
        column().apply {
            setPadding(dp(15), dp(15), dp(15), dp(15))
            background = pressable(
                glass(Glass.R_PANEL, Glass.alpha(Glass.EMERALD, 0.18f), Glass.alpha(Glass.EMERALD, 0.55f)),
            )
            glow(Glass.EMERALD, 14f)
            isClickable = true
            isFocusable = true
            contentDescription = "$title. $caption"
            addView(textView(glyph, 15f, Glass.TEXT_ON_ACCENT, bold = true).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
                background = accentFill(Glass.EMERALD, Glass.R_CHIP)
            })
            addView(textView(title, 14.5f, Glass.TEXT_PRIMARY, bold = true), matchWrap(12, this@DashboardActivity))
            addView(textView(caption, 10.5f, Glass.alpha(Glass.MINT, 0.85f)), matchWrap(3, this@DashboardActivity))
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                action()
            }
        }

    private fun action(glyph: String, title: String, caption: String, accent: Int, action: () -> Unit): View =
        column().apply {
            setPadding(dp(15), dp(15), dp(15), dp(15))
            background = pressable(glass(Glass.R_PANEL))
            isClickable = true
            isFocusable = true
            contentDescription = "$title. $caption"
            addView(textView(glyph, 15f, accent).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
                background = accentChip(accent, Glass.R_CHIP)
            })
            addView(textView(title, 14.5f, Glass.TEXT_PRIMARY, bold = true), matchWrap(12, this@DashboardActivity))
            addView(textView(caption, 10.5f, Glass.TEXT_MUTED), matchWrap(3, this@DashboardActivity))
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                action()
            }
        }

    // -- Recent drives ------------------------------------------------------------

    private fun buildRecentHeading(): View = row().apply {
        addView(textView("Recent Route", 17f, Glass.TEXT_PRIMARY, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(textView("See All ›", 11.5f, Glass.EMERALD, bold = true).apply {
            setPadding(dp(11), dp(6), dp(11), dp(6))
            background = pressable(accentChip(Glass.EMERALD, Glass.R_CHIP))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                startActivity(Intent(this@DashboardActivity, TripsActivity::class.java))
            }
        })
    }

    override fun onResume() {
        super.onResume()
        val token = ++generation
        thread(name = "waynova-session-history") {
            val records = SessionRecord.recent(getExternalFilesDir(null))
            runOnUiThread {
                if (isDestroyed || token != generation) return@runOnUiThread
                recentSlot.removeAllViews()
                if (records.isEmpty()) {
                    recentSlot.addView(emptyState())
                } else {
                    records.take(3).forEachIndexed { index, record ->
                        recentSlot.addView(routeCard(record), matchWrap(if (index == 0) 0 else 10, this))
                    }
                }
            }
        }
    }

    private fun emptyState(): View = glassCard().apply {
        addView(textView("No drives recorded yet", 14.5f, Glass.TEXT_PRIMARY, bold = true))
        addView(textView(
            "Live sessions and controlled blackout tests save telemetry automatically. Results appear here.",
            11.5f, Glass.TEXT_SECONDARY, lineMultiplier = 1.25f,
        ), matchWrap(6, this@DashboardActivity))
    }

    private fun routeCard(record: SessionRecord): View = column().apply {
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = pressable(glass(Glass.R_PANEL))
        isClickable = true
        isFocusable = true
        contentDescription = "Drive recorded ${dateOf(record.id)}, ${record.status}. Open telemetry"

        val head = row()
        val pin = textView("◉", 13f, Glass.EMERALD).apply {
            gravity = Gravity.CENTER
            background = accentChip(Glass.EMERALD, Glass.R_CHIP)
        }
        head.addView(pin, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(11) })
        head.addView(column().apply {
            addView(textView(dateOf(record.id), 14f, Glass.TEXT_PRIMARY, bold = true))
            addView(textView(
                "%.1f min · %,d GPS fixes".format(record.durationS / 60, record.phoneFixes),
                11f, Glass.TEXT_MUTED,
            ).apply { setPadding(0, dp(3), 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val ok = record.complete
        head.addView(textView(if (ok) "COMPLETE" else "PARTIAL", 9f,
            if (ok) Glass.EMERALD else Glass.AMBER, bold = true, tracking = 0.08f).apply {
            setPadding(dp(9), dp(5), dp(9), dp(5))
            background = accentChip(if (ok) Glass.EMERALD else Glass.AMBER, Glass.R_CHIP)
        })
        addView(head)

        val stats = row().apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = glass(Glass.R_INNER, Glass.PANEL_FILL_INNER, sheen = false)
        }
        stats.addView(miniStat("BLACKOUT", record.outageS?.let { "%.0f s".format(it) } ?: "—", Glass.CORAL))
        stats.addView(miniStat("END ERROR", record.endErrorM?.let { "%.1f m".format(it) } ?: "—", Glass.AMBER))
        stats.addView(miniStat("RECOVERY", record.recoveryS?.let { "%.1f s".format(it) } ?: "—", Glass.SKY))
        addView(stats, matchWrap(12, this@DashboardActivity))

        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            showRecord(record)
        }
    }

    private fun miniStat(label: String, value: String, accent: Int): View = column().apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addView(eyebrow(label, Glass.TEXT_MUTED, 8.5f))
        addView(textView(value, 12.5f, accent, bold = true).apply { setPadding(0, dp(3), 0, 0) })
    }

    // -- Detail dialog ------------------------------------------------------------

    private fun showRecord(record: SessionRecord) {
        fun value(raw: Double?, unit: String) = raw?.let { "%.1f %s".format(it, unit) } ?: "Not recorded"

        val container = column().apply {
            setPadding(dp(22), dp(20), dp(22), dp(14))
            addView(textView(dateOf(record.id), 17f, Glass.TEXT_PRIMARY, bold = true))
            addView(textView("Session ${record.id}", 10.5f, Glass.TEXT_MUTED), matchWrap(4, this@DashboardActivity))

            val card = column().apply {
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = glass(Glass.R_INNER, Glass.PANEL_FILL_INNER)
            }
            card.addView(detailRow("Status", record.status, if (record.complete) Glass.EMERALD else Glass.AMBER))
            card.addView(detailRow("Duration", "%.1f min".format(record.durationS / 60)))
            card.addView(detailRow("IMU samples", "%,d at 100 Hz".format(record.imuSamples)))
            card.addView(detailRow("Satellite fixes", "%,d".format(record.phoneFixes)))
            card.addView(detailRow("Controlled outage", value(record.outageS, "s"), Glass.CORAL))
            card.addView(detailRow("Outage position error", value(record.endErrorM, "m"), Glass.AMBER))
            card.addView(detailRow("Recovery slew", value(record.recoveryS, "s"), Glass.SKY))
            addView(card, matchWrap(14, this@DashboardActivity))

            addView(textView(
                "Error is measured against the phone's withheld GPS reference. Raw 100 Hz logs and 10 Hz EKF diagnostics stay on this device.",
                10.5f, Glass.TEXT_MUTED, lineMultiplier = 1.2f,
            ), matchWrap(12, this@DashboardActivity))
        }

        AlertDialog.Builder(this).setView(container).setPositiveButton("Close", null).show()
    }

    private fun detailRow(label: String, value: String, accent: Int = Glass.TEXT_PRIMARY): View = row().apply {
        setPadding(0, dp(5), 0, dp(5))
        addView(textView(label, 12f, Glass.TEXT_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(textView(value, 12f, accent, bold = true))
    }

    // -- Misc ---------------------------------------------------------------------

    private fun launchNavigation() {
        startActivity(Intent(this, NavigationActivity::class.java))
    }

    private fun dateOf(id: Long) =
        SimpleDateFormat("EEE, d MMM · h:mm a", Locale.getDefault()).format(Date(id))
}
