package org.neuronavx.logger

import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import org.neuronavx.logger.nav.OfflineMapStore
import org.neuronavx.logger.ui.Glass
import org.neuronavx.logger.ui.accentChip
import org.neuronavx.logger.ui.accentFill
import org.neuronavx.logger.ui.column
import org.neuronavx.logger.ui.dp
import org.neuronavx.logger.ui.eyebrow
import org.neuronavx.logger.ui.glass
import org.neuronavx.logger.ui.glassCard
import org.neuronavx.logger.ui.glassScaffold
import org.neuronavx.logger.ui.glow
import org.neuronavx.logger.ui.matchWrap
import org.neuronavx.logger.ui.pipelineStrip
import org.neuronavx.logger.ui.pressable
import org.neuronavx.logger.ui.row
import org.neuronavx.logger.ui.textView
import org.neuronavx.logger.ui.weightParams
import kotlin.concurrent.thread

/**
 * Diagnostics and system status.
 *
 * A control-centre view of everything the estimator depends on: the two IMU channels,
 * the exported speed model, the offline basemap, and the health of the most recent
 * recording. Each tile reports what was actually probed on this device, so a red tile
 * here means the demo will genuinely misbehave.
 */
class DiagnosticsActivity : ComponentActivity() {

    private data class Check(
        val title: String,
        val detail: String,
        val state: String,
        val accent: Int,
        val glyph: String,
    )

    private lateinit var grid: LinearLayout
    private lateinit var summaryBanner: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scaffold = glassScaffold(Glass.SKY, Glass.EMERALD)
        val root = scaffold.content

        root.addView(buildHeader())

        summaryBanner = column()
        root.addView(summaryBanner, matchWrap(16, this))

        root.addView(pipelineCard(), matchWrap(14, this))

        root.addView(eyebrow("Subsystems", Glass.TEXT_SECONDARY, 10.5f), matchWrap(22, this))
        grid = column()
        root.addView(grid, matchWrap(12, this))

        root.addView(buildToolsCard(), matchWrap(16, this))

        setContentView(scaffold.shell)
    }

    override fun onResume() {
        super.onResume()
        thread(name = "waynova-diagnostics") {
            val checks = runChecks()
            val lastSession = SessionRecord.recent(getExternalFilesDir(null)).firstOrNull()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                renderChecks(checks)
                renderSummary(checks, lastSession)
            }
        }
    }

    // -- Probes -------------------------------------------------------------------

    /** Everything here is measured on this handset, not asserted. */
    private fun runChecks(): List<Check> {
        val sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        val checks = ArrayList<Check>()

        val accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        checks.add(
            if (accelerometer != null) Check(
                "Accelerometer", "${accelerometer.name} · min delay ${accelerometer.minDelay / 1000} ms",
                "ACTIVE", Glass.EMERALD, "⊞",
            ) else Check("Accelerometer", "No accelerometer reported by this device", "MISSING", Glass.RED, "⊞"),
        )

        val gyroscope = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        checks.add(
            if (gyroscope != null) Check(
                "Gyroscope", "${gyroscope.name} · min delay ${gyroscope.minDelay / 1000} ms",
                "ACTIVE", Glass.EMERALD, "◎",
            ) else Check("Gyroscope", "No gyroscope: heading cannot be propagated", "MISSING", Glass.RED, "◎"),
        )

        val gravity = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
        checks.add(
            if (gravity != null) Check(
                "Gravity vector", "Native gravity sensor available for yaw projection", "READY", Glass.EMERALD, "↓",
            ) else Check(
                "Gravity vector", "Derived from the accelerometer low-pass instead", "FALLBACK", Glass.AMBER, "↓",
            ),
        )

        val modelBytes = assetSize("speed_tcn.onnx")
        checks.add(
            if (modelBytes > 0) Check(
                "Speed TCN", "ONNX graph bundled · ${OfflineMapStore.formatBytes(modelBytes)}",
                "LOADED", Glass.EMERALD, "◆",
            ) else Check("Speed TCN", "speed_tcn.onnx missing from assets", "MISSING", Glass.RED, "◆"),
        )

        val runtimeBytes = assetSize("speed_tcn_runtime.json")
        checks.add(
            if (runtimeBytes > 0) Check(
                "Feature scaler", "Normalisation statistics bundled with the model", "READY", Glass.EMERALD, "≡",
            ) else Check(
                "Feature scaler", "speed_tcn_runtime.json missing: predictions would be unscaled",
                "MISSING", Glass.RED, "≡",
            ),
        )

        val map = OfflineMapStore.installed(this)
        checks.add(
            if (map != null) Check(
                "Offline basemap", "Greater Noida · ${OfflineMapStore.formatBytes(map.bytes)} installed",
                "INSTALLED", Glass.EMERALD, "▣",
            ) else Check(
                "Offline basemap", "Not installed yet. Install it from Settings before driving.",
                "PENDING", Glass.AMBER, "▣",
            ),
        )

        val replayBytes = assetSize("replay_M_seg00.csv")
        checks.add(
            if (replayBytes > 0) Check(
                "Replay dataset", "Bundled IO-VNBD segment · ${OfflineMapStore.formatBytes(replayBytes)}",
                "READY", Glass.EMERALD, "↻",
            ) else Check("Replay dataset", "Bundled replay segment missing", "MISSING", Glass.AMBER, "↻"),
        )

        val free = getExternalFilesDir(null)?.freeSpace ?: 0L
        checks.add(
            Check(
                "Log storage",
                "${OfflineMapStore.formatBytes(free)} free for raw 100 Hz logs",
                if (free > 512L * 1024 * 1024) "HEALTHY" else "LOW",
                if (free > 512L * 1024 * 1024) Glass.EMERALD else Glass.AMBER,
                "▤",
            ),
        )

        return checks
    }

    private fun assetSize(name: String): Long = runCatching {
        assets.openFd(name).use { it.length }
    }.recoverCatching {
        assets.open(name).use { stream -> stream.available().toLong() }
    }.getOrDefault(0L)

    // -- Rendering ----------------------------------------------------------------

    private fun renderSummary(checks: List<Check>, lastSession: SessionRecord?) {
        summaryBanner.removeAllViews()
        val failures = checks.count { it.accent == Glass.RED }
        val warnings = checks.count { it.accent == Glass.AMBER }
        val accent = when {
            failures > 0 -> Glass.RED
            warnings > 0 -> Glass.AMBER
            else -> Glass.EMERALD
        }
        val headline = when {
            failures > 0 -> "$failures subsystem${if (failures == 1) "" else "s"} unavailable"
            warnings == 1 -> "1 item needs attention"
            warnings > 0 -> "$warnings items need attention"
            else -> "All systems nominal"
        }
        val detail = when {
            failures > 0 -> "Navigation will not produce a trustworthy estimate until these are resolved."
            warnings > 0 -> "Navigation will run, but the demo is degraded until these are cleared."
            else -> "Sensors, model and basemap are present and readable on this device."
        }

        val card = glassCard(
            fill = Glass.alpha(accent, 0.14f),
            border = Glass.alpha(accent, 0.45f),
        ).apply { glow(accent, 14f) }

        val line = row()
        line.addView(textView(if (failures > 0) "✕" else if (warnings > 0) "!" else "✓", 17f, accent, bold = true).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            background = accentChip(accent, Glass.R_CHIP)
        })
        line.addView(column().apply {
            setPadding(dp(13), 0, 0, 0)
            addView(textView(headline, 15.5f, Glass.TEXT_PRIMARY, bold = true))
            addView(textView(detail, 11f, Glass.TEXT_SECONDARY, lineMultiplier = 1.22f).apply {
                setPadding(0, dp(3), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(line)

        if (lastSession != null) {
            card.addView(row().apply {
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = glass(Glass.R_INNER, Glass.PANEL_FILL_INNER, sheen = false)
                addView(column().apply {
                    addView(eyebrow("Last recording", Glass.TEXT_MUTED, 8.5f))
                    addView(textView(lastSession.status, 11.5f, Glass.TEXT_PRIMARY, bold = true).apply {
                        setPadding(0, dp(3), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(textView(
                    lastSession.endErrorM?.let { "%.1f m".format(it) } ?: "—",
                    14f, if (lastSession.complete) Glass.EMERALD else Glass.AMBER, bold = true,
                ))
            }, matchWrap(14, this))
        }

        summaryBanner.addView(card)
    }

    private fun renderChecks(checks: List<Check>) {
        grid.removeAllViews()
        val gap = dp(10)
        checks.chunked(2).forEachIndexed { index, pair ->
            val line = row().apply {
                addView(checkTile(pair[0]), weightParams(endMarginPx = if (pair.size > 1) gap else 0))
                if (pair.size > 1) {
                    addView(checkTile(pair[1]), weightParams())
                } else {
                    addView(View(this@DiagnosticsActivity), weightParams())
                }
            }
            grid.addView(line, matchWrap(if (index == 0) 0 else 10, this))
        }
    }

    private fun checkTile(check: Check): View = column().apply {
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = glass(Glass.R_PANEL)
        contentDescription = "${check.title}. ${check.state}. ${check.detail}"

        val head = row()
        head.addView(textView(check.glyph, 14f, check.accent).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
            background = accentChip(check.accent, Glass.R_CHIP)
        })
        head.addView(View(this@DiagnosticsActivity), LinearLayout.LayoutParams(0, 1, 1f))
        head.addView(textView(check.state, 8.5f, check.accent, bold = true, tracking = 0.08f).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = accentChip(check.accent, Glass.R_CHIP)
        })
        addView(head)

        addView(textView(check.title, 13.5f, Glass.TEXT_PRIMARY, bold = true), matchWrap(12, this@DiagnosticsActivity))
        addView(textView(check.detail, 10.5f, Glass.TEXT_MUTED, lineMultiplier = 1.2f),
            matchWrap(4, this@DiagnosticsActivity))
    }

    private fun pipelineCard(): View = glassCard().apply {
        addView(textView("Estimator pipeline", 14.5f, Glass.TEXT_PRIMARY, bold = true))
        addView(textView(
            "Raw IMU is calibrated to the vehicle frame, the TCN predicts forward speed, and the error-state EKF fuses both with whatever GNSS survives.",
            10.5f, Glass.TEXT_MUTED, lineMultiplier = 1.25f,
        ), matchWrap(5, this@DiagnosticsActivity))
        val scroller = android.widget.HorizontalScrollView(this@DiagnosticsActivity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(pipelineStrip(listOf("IMU", "Calibration", "TCN", "ES-EKF", "Recovery")))
        }
        addView(scroller, matchWrap(14, this@DiagnosticsActivity))
    }

    private fun buildToolsCard(): View = glassCard().apply {
        addView(textView("Developer tools", 14.5f, Glass.TEXT_PRIMARY, bold = true))
        addView(textView("Raw sensor logging, model benchmarks and the full-loop timing harness.",
            10.5f, Glass.TEXT_MUTED), matchWrap(5, this@DiagnosticsActivity))

        addView(actionButton("Open sensor logger", primary = true) {
            startActivity(Intent(this@DiagnosticsActivity, MainActivity::class.java))
        }, matchWrap(14, this@DiagnosticsActivity))
        addView(actionButton("Full-loop latency benchmark", primary = false) {
            startActivity(Intent(this@DiagnosticsActivity, FullLoopBenchmarkActivity::class.java))
        }, matchWrap(10, this@DiagnosticsActivity))
    }

    private fun actionButton(title: String, primary: Boolean, action: () -> Unit): View =
        textView(title, 13.5f, if (primary) Glass.TEXT_ON_ACCENT else Glass.TEXT_PRIMARY, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = pressable(
                if (primary) accentFill(Glass.EMERALD, Glass.R_INNER) else glass(Glass.R_INNER),
            )
            if (primary) glow(Glass.EMERALD, 10f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                action()
            }
        }

    private fun buildHeader(): View = row().apply {
        val back = textView("‹", 22f, Glass.TEXT_SECONDARY, bold = true).apply {
            gravity = Gravity.CENTER
            background = pressable(glass(Glass.R_CHIP))
            isClickable = true
            isFocusable = true
            contentDescription = "Back"
            setOnClickListener { finish() }
        }
        addView(back, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(12) })
        addView(column().apply {
            addView(textView("Diagnostics", 22f, Glass.TEXT_PRIMARY, bold = true))
            addView(textView("Live subsystem health on this device", 11f, Glass.TEXT_MUTED).apply {
                setPadding(0, dp(2), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
}
