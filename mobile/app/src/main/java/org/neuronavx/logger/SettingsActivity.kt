package org.neuronavx.logger

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.neuronavx.logger.nav.OfflineMapStore
import org.neuronavx.logger.ui.Glass
import org.neuronavx.logger.ui.GlassNavBar
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
import org.neuronavx.logger.ui.pressable
import org.neuronavx.logger.ui.row
import org.neuronavx.logger.ui.solid
import org.neuronavx.logger.ui.textView
import kotlin.concurrent.thread

/**
 * Settings and offline maps.
 *
 * Only settings the app genuinely honours appear here. The offline map card installs and
 * replaces the PMTiles archive that the renderer reads, and the preference rows are
 * display-side conveniences that never touch the estimator.
 */
class SettingsActivity : ComponentActivity() {

    private lateinit var mapSlot: LinearLayout
    private var installing = false

    private val pickMap = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importMap(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val navBar = GlassNavBar(this)
        val scaffold = glassScaffold(Glass.TEAL, Glass.SKY, navBar)
        val root = scaffold.content

        root.addView(buildHeader())

        root.addView(eyebrow("Offline maps", Glass.TEXT_SECONDARY, 10.5f), matchWrap(22, this))
        mapSlot = column()
        root.addView(mapSlot, matchWrap(10, this))

        root.addView(eyebrow("Display", Glass.TEXT_SECONDARY, 10.5f), matchWrap(22, this))
        root.addView(buildDisplayCard(), matchWrap(10, this))

        root.addView(eyebrow("Navigation", Glass.TEXT_SECONDARY, 10.5f), matchWrap(22, this))
        root.addView(buildNavigationCard(), matchWrap(10, this))

        root.addView(eyebrow("Advanced", Glass.TEXT_SECONDARY, 10.5f), matchWrap(22, this))
        root.addView(buildAdvancedCard(), matchWrap(10, this))

        root.addView(textView(
            "Waynova ${BuildConfig.VERSION_NAME} · ISRO SIH26168\nOffline basemap tiles © OpenStreetMap contributors, packaged by Protomaps.",
            10f, Glass.TEXT_MUTED, lineMultiplier = 1.3f,
        ).apply { gravity = Gravity.CENTER_HORIZONTAL }, matchWrap(26, this))

        navBar.setTabs(
            listOf(
                GlassNavBar.Tab("⌂", "Home") { finish() },
                GlassNavBar.Tab("◈", "Map") {
                    startActivity(Intent(this, NavigationActivity::class.java))
                },
                GlassNavBar.Tab("◫", "Trips") {
                    startActivity(Intent(this, TripsActivity::class.java))
                },
                GlassNavBar.Tab("⚙", "Settings") {},
            ),
            selectedIndex = 3,
        )

        setContentView(scaffold.shell)
        renderMapCard(null, null)
        refreshMap()
    }

    // -- Offline map --------------------------------------------------------------

    private fun refreshMap() {
        thread(name = "waynova-map-status") {
            val installed = OfflineMapStore.installed(this)
            runOnUiThread { if (!isDestroyed) renderMapCard(installed, null) }
        }
    }

    private fun renderMapCard(installed: OfflineMapStore.InstalledMap?, progress: Int?) {
        mapSlot.removeAllViews()
        val card = glassCard()
        val ready = installed != null
        val accent = if (ready) Glass.EMERALD else Glass.AMBER

        val head = row()
        head.addView(textView("▣", 16f, accent).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            background = accentChip(accent, Glass.R_INNER)
        })
        head.addView(column().apply {
            setPadding(dp(13), 0, 0, 0)
            addView(textView("Greater Noida", 15f, Glass.TEXT_PRIMARY, bold = true))
            addView(textView(
                when {
                    progress != null -> "Installing… $progress%"
                    installed != null -> "${OfflineMapStore.formatBytes(installed.bytes)} · vector tiles on device"
                    else -> "Not installed. The map falls back to a blank grid."
                },
                11f, Glass.TEXT_SECONDARY,
            ).apply { setPadding(0, dp(3), 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(textView(if (ready) "READY" else "PENDING", 8.5f, accent, bold = true, tracking = 0.08f).apply {
            setPadding(dp(9), dp(5), dp(9), dp(5))
            background = accentChip(accent, Glass.R_CHIP)
        })
        card.addView(head)

        card.addView(textView(
            "Waynova renders from a local PMTiles archive so navigation keeps a real basemap with no network at all.",
            10.5f, Glass.TEXT_MUTED, lineMultiplier = 1.22f,
        ), matchWrap(12, this))

        card.addView(button(
            if (ready) "Reinstall bundled region" else "Install bundled region",
            primary = !ready,
        ) { installBundled() }, matchWrap(14, this))

        card.addView(button("Import a PMTiles archive…", primary = false) {
            pickMap.launch(arrayOf("*/*"))
        }, matchWrap(10, this))

        mapSlot.addView(card)
    }

    private fun installBundled() {
        if (installing) return
        installing = true
        thread(name = "waynova-map-install") {
            val result = runCatching {
                OfflineMapStore.ensureBundled(this) { percent ->
                    runOnUiThread { if (!isDestroyed) renderMapCard(null, percent) }
                }
            }
            runOnUiThread {
                installing = false
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { renderMapCard(it, null) }
                result.onFailure {
                    renderMapCard(OfflineMapStore.installed(this), null)
                    Toast.makeText(this, "Map install failed: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun importMap(uri: Uri) {
        if (installing) return
        installing = true
        thread(name = "waynova-map-import") {
            val result = runCatching {
                OfflineMapStore.import(this, uri) { percent ->
                    runOnUiThread { if (!isDestroyed) renderMapCard(null, percent) }
                }
            }
            runOnUiThread {
                installing = false
                if (isDestroyed) return@runOnUiThread
                result.onSuccess {
                    renderMapCard(it, null)
                    Toast.makeText(this, "Offline region replaced", Toast.LENGTH_SHORT).show()
                }
                result.onFailure {
                    renderMapCard(OfflineMapStore.installed(this), null)
                    Toast.makeText(this, "Import failed: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // -- Preference groups --------------------------------------------------------

    private fun buildDisplayCard(): View = glassCard(padH = 16, padV = 6).apply {
        addView(toggleRow(
            "Imperial units", "Show speed in mph instead of km/h",
            Prefs.useMph(this@SettingsActivity),
        ) { Prefs.setUseMph(this@SettingsActivity, it) })
        addView(divider())
        addView(toggleRow(
            "Keep screen on", "Hold the display awake for the whole drive",
            Prefs.keepScreenOn(this@SettingsActivity),
        ) { Prefs.setKeepScreenOn(this@SettingsActivity, it) })
        addView(divider())
        addView(toggleRow(
            "Haptics", "Vibrate on controls and mode changes",
            Prefs.haptics(this@SettingsActivity),
        ) { Prefs.setHaptics(this@SettingsActivity, it) })
    }

    private fun buildNavigationCard(): View = glassCard(padH = 16, padV = 6).apply {
        addView(toggleRow(
            "Voice co-pilot", "Offline spoken callouts for blackout and recovery",
            Prefs.voiceEnabled(this@SettingsActivity),
        ) { Prefs.setVoiceEnabled(this@SettingsActivity, it) })
        addView(divider())
        addView(linkRow("Plan a destination", "Search and cache an OSRM route") {
            startActivity(Intent(this@SettingsActivity, NavigationActivity::class.java)
                .putExtra("plan_route", true))
        })
        addView(divider())
        addView(linkRow("Replay a recorded drive", "Run the bundled 120 s outage benchmark") {
            startActivity(Intent(this@SettingsActivity, NavigationActivity::class.java)
                .putExtra("start_demo", true))
        })
    }

    private fun buildAdvancedCard(): View = glassCard(padH = 16, padV = 6).apply {
        addView(linkRow("Diagnostics", "Sensor, model and storage health") {
            startActivity(Intent(this@SettingsActivity, DiagnosticsActivity::class.java))
        })
        addView(divider())
        addView(linkRow("Sensor logger", "Raw 100 Hz capture for offline training") {
            startActivity(Intent(this@SettingsActivity, MainActivity::class.java))
        })
        addView(divider())
        addView(linkRow("Full-loop benchmark", "End-to-end latency against the 100 ms budget") {
            startActivity(Intent(this@SettingsActivity, FullLoopBenchmarkActivity::class.java))
        })
    }

    // -- Row primitives -----------------------------------------------------------

    /** A settings row with a custom pill switch, so the control matches the glass language. */
    private fun toggleRow(
        title: String,
        caption: String,
        initial: Boolean,
        onChange: (Boolean) -> Unit,
    ): View {
        var checked = initial
        val knob = View(this)
        val track = FrameLayout(this)

        fun paint() {
            track.background = if (checked) {
                accentFill(Glass.EMERALD, Glass.R_CHIP)
            } else {
                glass(Glass.R_CHIP, Glass.PANEL_FILL_INNER, Glass.BORDER, sheen = false)
            }
            knob.background = solid(if (checked) Glass.TEXT_ON_ACCENT else Glass.TEXT_SECONDARY, Glass.R_CHIP)
            (knob.layoutParams as FrameLayout.LayoutParams).gravity =
                (if (checked) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
            knob.requestLayout()
        }

        track.addView(knob, FrameLayout.LayoutParams(dp(18), dp(18)).apply {
            marginStart = dp(3)
            marginEnd = dp(3)
        })
        paint()

        return row().apply {
            setPadding(0, dp(14), 0, dp(14))
            isClickable = true
            isFocusable = true
            contentDescription = "$title. $caption"
            addView(column().apply {
                addView(textView(title, 13.5f, Glass.TEXT_PRIMARY, bold = true))
                addView(textView(caption, 10.5f, Glass.TEXT_MUTED, lineMultiplier = 1.2f).apply {
                    setPadding(0, dp(3), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(14)
            })
            addView(track, LinearLayout.LayoutParams(dp(46), dp(26)))
            setOnClickListener {
                if (Prefs.haptics(this@SettingsActivity)) {
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                }
                checked = !checked
                paint()
                onChange(checked)
            }
        }
    }

    private fun linkRow(title: String, caption: String, action: () -> Unit): View = row().apply {
        setPadding(0, dp(14), 0, dp(14))
        isClickable = true
        isFocusable = true
        contentDescription = "$title. $caption"
        addView(column().apply {
            addView(textView(title, 13.5f, Glass.TEXT_PRIMARY, bold = true))
            addView(textView(caption, 10.5f, Glass.TEXT_MUTED, lineMultiplier = 1.2f).apply {
                setPadding(0, dp(3), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(textView("›", 18f, Glass.TEXT_MUTED, bold = true))
        setOnClickListener {
            if (Prefs.haptics(this@SettingsActivity)) {
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            }
            action()
        }
    }

    private fun divider(): View = View(this).apply {
        background = solid(Glass.BORDER, 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun button(title: String, primary: Boolean, action: () -> Unit): View =
        textView(title, 13f, if (primary) Glass.TEXT_ON_ACCENT else Glass.TEXT_PRIMARY, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(46)
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = pressable(
                if (primary) accentFill(Glass.EMERALD, Glass.R_INNER) else glass(Glass.R_INNER),
            )
            if (primary) glow(Glass.EMERALD, 10f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (Prefs.haptics(this@SettingsActivity)) {
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                }
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
            addView(textView("Settings", 22f, Glass.TEXT_PRIMARY, bold = true))
            addView(textView("Offline maps, display and advanced tools", 11f, Glass.TEXT_MUTED).apply {
                setPadding(0, dp(2), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
}
