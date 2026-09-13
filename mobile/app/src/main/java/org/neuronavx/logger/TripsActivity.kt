package org.neuronavx.logger

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import org.neuronavx.logger.ui.AvailabilityBar
import org.neuronavx.logger.ui.Glass
import org.neuronavx.logger.ui.GlassNavBar
import org.neuronavx.logger.ui.Sparkline
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
import org.neuronavx.logger.ui.metricTile
import org.neuronavx.logger.ui.pipelineStrip
import org.neuronavx.logger.ui.pressable
import org.neuronavx.logger.ui.row
import org.neuronavx.logger.ui.solid
import org.neuronavx.logger.ui.textView
import org.neuronavx.logger.ui.weightParams
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Trips and analytics.
 *
 * Three tabs over the same recorded session: Overview summarises the drive, Analytics
 * plots the estimator's own speed and heading traces alongside satellite availability,
 * and System reports the calibration and filter state the run finished in.
 */
class TripsActivity : ComponentActivity() {

    private enum class Tab(val label: String) { OVERVIEW("Overview"), ANALYTICS("Analytics"), SYSTEM("System") }

    private lateinit var content: LinearLayout
    private lateinit var tabStrip: LinearLayout
    private lateinit var selector: LinearLayout

    private var tab = Tab.OVERVIEW
    private var records: List<SessionRecord> = emptyList()
    private var selected: SessionRecord? = null
    private var trace: TripTrace = TripTrace.EMPTY
    private var generation = 0
    private var exporting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val navBar = GlassNavBar(this)
        val scaffold = glassScaffold(Glass.CYAN, Glass.EMERALD, navBar)
        val root = scaffold.content

        root.addView(buildHeader())

        tabStrip = buildTabs()
        root.addView(tabStrip, matchWrap(16, this))

        selector = column()
        root.addView(selector, matchWrap(14, this))

        content = column()
        root.addView(content, matchWrap(14, this))

        navBar.setTabs(
            listOf(
                GlassNavBar.Tab("⌂", "Home") { finish() },
                GlassNavBar.Tab("◈", "Map") {
                    startActivity(Intent(this, NavigationActivity::class.java))
                },
                GlassNavBar.Tab("◫", "Trips") {},
                GlassNavBar.Tab("⚙", "Settings") {
                    startActivity(Intent(this, SettingsActivity::class.java))
                },
            ),
            selectedIndex = 2,
        )

        setContentView(scaffold.shell)
        loadSessions()
    }

    // -- Chrome -------------------------------------------------------------------

    private fun buildHeader(): View = row().apply {
        val back = textView("‹", 22f, Glass.TEXT_SECONDARY, bold = true).apply {
            gravity = Gravity.CENTER
            background = pressable(glass(Glass.R_CHIP))
            isClickable = true
            isFocusable = true
            contentDescription = "Back to home"
            setOnClickListener { finish() }
        }
        addView(back, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(12) })
        addView(column().apply {
            addView(textView("Trips", 22f, Glass.TEXT_PRIMARY, bold = true))
            addView(textView("Recorded drives and estimator telemetry", 11f, Glass.TEXT_MUTED).apply {
                setPadding(0, dp(2), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun buildTabs(): LinearLayout = row().apply {
        setPadding(dp(5), dp(5), dp(5), dp(5))
        background = glass(Glass.R_CHIP)
        Tab.entries.forEach { entry ->
            addView(tabButton(entry), LinearLayout.LayoutParams(0, dp(36), 1f))
        }
    }

    private fun tabButton(entry: Tab): View {
        val active = entry == tab
        return textView(entry.label, 12.5f, if (active) Glass.TEXT_ON_ACCENT else Glass.TEXT_SECONDARY, bold = active).apply {
            gravity = Gravity.CENTER
            background = if (active) {
                accentFill(Glass.EMERALD, Glass.R_CHIP)
            } else {
                pressable(solid(android.graphics.Color.TRANSPARENT, Glass.R_CHIP))
            }
            if (active) glow(Glass.EMERALD, 8f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                tab = entry
                refreshTabs()
                renderContent()
            }
        }
    }

    private fun refreshTabs() {
        tabStrip.removeAllViews()
        Tab.entries.forEach { entry ->
            tabStrip.addView(tabButton(entry), LinearLayout.LayoutParams(0, dp(36), 1f))
        }
    }

    // -- Data ---------------------------------------------------------------------

    private fun loadSessions() {
        val token = ++generation
        thread(name = "waynova-trips") {
            val loaded = SessionRecord.recent(getExternalFilesDir(null))
            runOnUiThread {
                if (isDestroyed || token != generation) return@runOnUiThread
                records = loaded
                selectSession(loaded.firstOrNull())
            }
        }
    }

    private fun selectSession(record: SessionRecord?) {
        selected = record
        trace = TripTrace.EMPTY
        renderSelector()
        renderContent()
        val id = record?.id ?: return
        val token = ++generation
        thread(name = "waynova-trip-trace") {
            val loaded = TripTrace.load(getExternalFilesDir(null), id)
            runOnUiThread {
                if (isDestroyed || token != generation || selected?.id != id) return@runOnUiThread
                trace = loaded
                renderContent()
            }
        }
    }

    /** Horizontal chip row so a demo can switch between the last few recorded drives. */
    private fun renderSelector() {
        selector.removeAllViews()
        if (records.size < 2) return
        val strip = row()
        records.take(6).forEach { record ->
            val active = record.id == selected?.id
            val chip = textView(shortDate(record.id), 11f,
                if (active) Glass.EMERALD else Glass.TEXT_SECONDARY, bold = active).apply {
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = pressable(
                    if (active) accentChip(Glass.EMERALD, Glass.R_CHIP) else glass(Glass.R_CHIP),
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    selectSession(record)
                }
            }
            strip.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) })
        }
        val scroller = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(strip)
        }
        selector.addView(scroller)
    }

    // -- Content ------------------------------------------------------------------

    private fun renderContent() {
        content.removeAllViews()
        val record = selected
        if (record == null) {
            content.addView(glassCard().apply {
                addView(textView("No trips recorded", 15f, Glass.TEXT_PRIMARY, bold = true))
                addView(textView(
                    "Run a live session from the home screen. Every drive writes a summary and a 10 Hz diagnostics trace to this device.",
                    11.5f, Glass.TEXT_SECONDARY, lineMultiplier = 1.25f,
                ), matchWrap(7, this@TripsActivity))
            })
            return
        }
        when (tab) {
            Tab.OVERVIEW -> renderOverview(record)
            Tab.ANALYTICS -> renderAnalytics(record)
            Tab.SYSTEM -> renderSystem(record)
        }
        content.addView(textView("Share ride evidence ZIP", 13f, Glass.EMERALD, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(16), dp(12), dp(16))
            background = pressable(glass(Glass.R_CHIP))
            setOnClickListener { confirmEvidenceShare(record) }
        }, matchWrap(14, this))
        content.addView(textView("Replay this recorded ride · 4×", 13f, Glass.EMERALD, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(16), dp(12), dp(16))
            background = pressable(glass(Glass.R_CHIP))
            setOnClickListener { startActivity(Intent(this@TripsActivity, NavigationActivity::class.java)
                .putExtra("saved_ride_id", record.id)) }
        }, matchWrap(10, this))
    }

    private fun confirmEvidenceShare(record: SessionRecord) {
        if (exporting) return
        android.app.AlertDialog.Builder(this)
            .setTitle("Share this ride’s location history?")
            .setMessage("Includes raw GPS/sensors, diagnostics and the saved summary for session ${record.id}. Choose the recipient yourself. Only share with people you trust.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Prepare ZIP") { _, _ ->
                if (exporting) return@setPositiveButton
                exporting = true
                android.widget.Toast.makeText(this, "Preparing ride evidence…", android.widget.Toast.LENGTH_SHORT).show()
                thread(name = "waynova-evidence-export") {
                    val result = runCatching { DriveEvidence.create(checkNotNull(getExternalFilesDir(null)), cacheDir, record.id) }
                    runOnUiThread {
                        exporting = false
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        result.onSuccess { file ->
                            runCatching {
                                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.evidence", file)
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/zip"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    clipData = android.content.ClipData.newRawUri("Waynova ride evidence", uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                startActivity(Intent.createChooser(send, "Share selected ride evidence"))
                            }.onFailure { showExportError(it) }
                        }.onFailure { showExportError(it) }
                    }
                }
            }.show()
    }

    private fun showExportError(error: Throwable) {
        android.widget.Toast.makeText(this, error.message ?: "Could not share this ride", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun renderOverview(record: SessionRecord) {
        val summary = glassCard()
        val head = row().apply {
            addView(textView("◫", 15f, Glass.EMERALD).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
                background = accentChip(Glass.EMERALD, Glass.R_INNER)
            })
            addView(textView("Trip Summary", 15.5f, Glass.TEXT_PRIMARY, bold = true).apply {
                setPadding(dp(11), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(column().apply {
                gravity = Gravity.END
                addView(textView(shortDate(record.id), 11.5f, Glass.TEXT_PRIMARY, bold = true))
                addView(textView(timeOf(record.id), 10f, Glass.TEXT_MUTED).apply {
                    setPadding(0, dp(2), 0, 0)
                })
            })
        }
        summary.addView(head)

        val gap = dp(10)
        val distance = if (trace.hasTrace) "%.1f km".format(trace.distanceKm) else "—"
        val average = if (trace.hasTrace) "%.0f km/h".format(trace.averageSpeedKph) else "—"
        val blackout = record.outageS?.let { "%.0f s".format(it) }
            ?: trace.blackoutSeconds.takeIf { it > 0 }?.let { "%.0f s".format(it) } ?: "None"
        val reacquire = record.recoveryS?.let { "%.1f s".format(it) } ?: "—"

        summary.addView(row().apply {
            addView(metricTile("Distance", distance, Glass.TEXT_PRIMARY, "⇄"), weightParams(endMarginPx = gap))
            addView(metricTile("Average Speed", average, Glass.TEXT_PRIMARY, "◑"), weightParams())
        }, matchWrap(14, this))
        summary.addView(row().apply {
            addView(metricTile("GNSS Blackout", blackout, Glass.CORAL, "⊘"), weightParams(endMarginPx = gap))
            addView(metricTile("Reacquisition", reacquire, Glass.SKY, "✧"), weightParams())
        }, matchWrap(10, this))
        content.addView(summary)

        val verdict = glassCard(
            fill = Glass.alpha(if (record.complete) Glass.EMERALD else Glass.AMBER, 0.14f),
            border = Glass.alpha(if (record.complete) Glass.EMERALD else Glass.AMBER, 0.45f),
        ).apply {
            val accent = if (record.complete) Glass.EMERALD else Glass.AMBER
            glow(accent, 12f)
            val line = row()
            line.addView(textView(if (record.complete) "✓" else "!", 16f, accent, bold = true).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                background = accentChip(accent, Glass.R_CHIP)
            })
            line.addView(column().apply {
                setPadding(dp(12), 0, 0, 0)
                addView(textView(
                    if (record.complete) "Navigation held through the outage" else "Test incomplete",
                    14f, Glass.TEXT_PRIMARY, bold = true,
                ))
                addView(textView(record.status, 11f, Glass.TEXT_SECONDARY, lineMultiplier = 1.2f).apply {
                    setPadding(0, dp(3), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(line)

            record.endErrorM?.let { error ->
                addView(textView(
                    "Position error against the withheld GPS reference at the end of the outage: %.1f m.".format(error),
                    11f, Glass.TEXT_SECONDARY, lineMultiplier = 1.25f,
                ), matchWrap(12, this@TripsActivity))
            }
        }
        content.addView(verdict, matchWrap(12, this))

        content.addView(pipelineCard(), matchWrap(12, this))
    }

    private fun renderAnalytics(record: SessionRecord) {
        if (!trace.hasTrace) {
            content.addView(glassCard().apply {
                addView(textView("No trace for this drive", 15f, Glass.TEXT_PRIMARY, bold = true))
                addView(textView(
                    "The 10 Hz diagnostics CSV for session ${record.id} is not on this device, so there is nothing to plot.",
                    11.5f, Glass.TEXT_SECONDARY, lineMultiplier = 1.25f,
                ), matchWrap(7, this@TripsActivity))
            })
            return
        }

        val performance = glassCard()
        performance.addView(textView("Performance", 15.5f, Glass.TEXT_PRIMARY, bold = true))
        performance.addView(textView(
            "%,d estimator states · peak %.0f km/h".format(trace.samples, trace.topSpeedKph),
            10.5f, Glass.TEXT_MUTED,
        ), matchWrap(4, this))

        performance.addView(chartBlock("Speed Estimate", "km/h", trace.speedKph, Glass.EMERALD), matchWrap(16, this))
        performance.addView(chartBlock("Heading", "degrees", trace.headingDeg, Glass.SKY, fixed = -180f to 180f),
            matchWrap(18, this))

        performance.addView(eyebrow("GNSS Signal", Glass.TEXT_SECONDARY, 10f), matchWrap(18, this))
        val bar = AvailabilityBar(this).apply { setSlices(trace.availability) }
        performance.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(26),
        ).apply { topMargin = dp(8) })
        performance.addView(legend(), matchWrap(10, this))
        content.addView(performance)

        content.addView(pipelineCard(), matchWrap(12, this))
    }

    private fun chartBlock(
        title: String,
        unit: String,
        values: FloatArray,
        accent: Int,
        fixed: Pair<Float, Float>? = null,
    ): View = column().apply {
        val head = row()
        head.addView(textView(title, 12.5f, Glass.TEXT_SECONDARY, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(textView(unit, 10f, Glass.TEXT_MUTED))
        addView(head)

        val chart = Sparkline(this@TripsActivity).apply {
            this.accent = accent
            fixedRange = fixed
            setValues(values)
        }
        addView(chart, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(76),
        ).apply { topMargin = dp(8) })
    }

    private fun legend(): View = row().apply {
        listOf(
            "GNSS available" to Glass.EMERALD,
            "Blackout" to Glass.RED,
            "Reacquiring" to Glass.SKY,
        ).forEach { (label, color) ->
            val item = row().apply {
                addView(View(this@TripsActivity).apply { background = solid(color, Glass.R_CHIP) },
                    LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(5) })
                addView(textView(label, 9.5f, Glass.TEXT_MUTED))
            }
            addView(item, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(14) })
        }
    }

    private fun renderSystem(record: SessionRecord) {
        val card = glassCard()
        card.addView(textView("Session record", 15.5f, Glass.TEXT_PRIMARY, bold = true))
        card.addView(textView("Written by the on-device recorder at the end of the drive.", 11f, Glass.TEXT_MUTED),
            matchWrap(4, this))

        listOf(
            "Session id" to record.id.toString(),
            "Phase" to record.phase,
            "Duration" to "%.1f min".format(record.durationS / 60),
            "IMU samples" to "%,d".format(record.imuSamples),
            "Satellite fixes" to "%,d".format(record.phoneFixes),
            "Recovered" to if (record.recovered) "Yes" else "No",
            "Writer errors" to if (record.writerError) "Yes" else "None",
        ).forEach { (label, value) ->
            card.addView(detailRow(label, value), matchWrap(2, this))
        }
        content.addView(card)

        content.addView(glassCard().apply {
            addView(textView("Estimator trace", 15.5f, Glass.TEXT_PRIMARY, bold = true))
            if (trace.hasTrace) {
                addView(detailRow("States logged", "%,d".format(trace.samples)), matchWrap(8, this@TripsActivity))
                addView(detailRow("Integrated distance", "%.2f km".format(trace.distanceKm)), matchWrap(2, this@TripsActivity))
                addView(detailRow("Mean speed", "%.1f km/h".format(trace.averageSpeedKph)), matchWrap(2, this@TripsActivity))
                addView(detailRow("Peak speed", "%.1f km/h".format(trace.topSpeedKph)), matchWrap(2, this@TripsActivity))
                addView(detailRow("Time in blackout", "%.0f s".format(trace.blackoutSeconds), Glass.CORAL),
                    matchWrap(2, this@TripsActivity))
                addView(textView(
                    "Distance is integrated from the filter's own speed estimate, so it reflects what the estimator believed, not a GPS odometer.",
                    10.5f, Glass.TEXT_MUTED, lineMultiplier = 1.22f,
                ), matchWrap(12, this@TripsActivity))
            } else {
                addView(textView("Diagnostics CSV not present for this session.", 11.5f, Glass.TEXT_SECONDARY),
                    matchWrap(8, this@TripsActivity))
            }
        }, matchWrap(12, this))
    }

    private fun pipelineCard(): View = glassCard().apply {
        addView(textView("Navigation Pipeline", 14.5f, Glass.TEXT_PRIMARY, bold = true))
        addView(textView("Every 10 Hz state passes through this chain.", 10.5f, Glass.TEXT_MUTED),
            matchWrap(4, this@TripsActivity))
        val scroller = android.widget.HorizontalScrollView(this@TripsActivity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(pipelineStrip(listOf("IMU", "Calibration", "TCN", "ES-EKF", "Recovery")))
        }
        addView(scroller, matchWrap(14, this@TripsActivity))
    }

    private fun detailRow(label: String, value: String, accent: Int = Glass.TEXT_PRIMARY): View = row().apply {
        setPadding(0, dp(5), 0, dp(5))
        addView(textView(label, 12f, Glass.TEXT_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(textView(value, 12f, accent, bold = true))
    }

    private fun shortDate(id: Long) = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(id))
    private fun timeOf(id: Long) = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(id))
}
