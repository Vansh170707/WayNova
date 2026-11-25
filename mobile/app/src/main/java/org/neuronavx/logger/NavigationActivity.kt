package org.neuronavx.logger

import android.content.res.ColorStateList
import android.Manifest
import android.app.AlertDialog
import java.util.Locale
import android.location.LocationManager
import android.content.Context
import android.view.WindowManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import org.neuronavx.logger.nav.GoogleMapRenderer
import org.neuronavx.logger.nav.FieldBlackoutPhase
import org.neuronavx.logger.nav.FieldBlackoutSnapshot
import org.neuronavx.logger.nav.LiveSource
import org.neuronavx.logger.nav.LiveSessionSummary
import org.neuronavx.logger.nav.MapLibreOfflineRenderer
import org.neuronavx.logger.nav.NavMode
import org.neuronavx.logger.nav.NavigationMapSnapshot
import org.neuronavx.logger.nav.NavigationView
import org.neuronavx.logger.nav.Navigator
import org.neuronavx.logger.nav.OfflineMapStore
import org.neuronavx.logger.nav.ReplaySource
import org.neuronavx.logger.nav.RuntimeConfig
import org.neuronavx.logger.nav.SensorLoggerRate
import org.neuronavx.logger.nav.SpeedModel
import org.neuronavx.logger.nav.RoutePlan
import org.neuronavx.logger.nav.RoutePoint
import org.neuronavx.logger.nav.RouteGuidance
import org.neuronavx.logger.nav.RoadLine
import org.neuronavx.logger.nav.RoadSnapper
import org.neuronavx.logger.nav.DemoRide
import org.neuronavx.logger.nav.TurnCuePlanner
import org.neuronavx.logger.nav.EnuMapProjection
import org.neuronavx.logger.nav.AvionicsVoiceCoPilot
import org.neuronavx.logger.nav.SentinelReport
import org.neuronavx.logger.nav.SentinelState
import org.neuronavx.logger.ui.Glass
import org.neuronavx.logger.ui.accentChip
import org.neuronavx.logger.ui.accentFill
import org.neuronavx.logger.ui.glass
import org.neuronavx.logger.ui.glow
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Map-first navigation cockpit.
 *
 * This class deliberately owns presentation only. Replay and live sensors still feed the
 * same [Navigator], and [NavigationView] still renders the estimator's display-smoothed
 * position. Google Maps and offline MapLibre are presentation adapters around that same
 * estimator, which has already passed desktop/device parity tests.
 */
class NavigationActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var map: NavigationView
    private lateinit var mapSourceBadge: TextView
    private lateinit var mapAttribution: TextView
    private lateinit var recenterButton: TextView
    private lateinit var modeBadge: TextView
    private lateinit var eyebrow: TextView
    private lateinit var headline: TextView
    private lateinit var guidance: TextView
    private lateinit var contextLine: TextView
    private lateinit var calibrationProgress: ProgressBar
    private lateinit var liveButton: TextView
    private lateinit var replayButton: TextView
    private lateinit var speedMetric: MetricViews
    private lateinit var headingMetric: MetricViews
    private lateinit var uncertaintyMetric: MetricViews
    private lateinit var routeButton: TextView
    private lateinit var voiceButton: TextView
    private lateinit var sentinelChip: TextView
    private lateinit var bottomPanel: LinearLayout
    private lateinit var outageBanner: LinearLayout
    private lateinit var voiceCoPilot: AvionicsVoiceCoPilot
    private var prevNavMode: NavMode = NavMode.AIDED
    private var prevSentinelState: SentinelState = SentinelState.NOMINAL
    private var announcedMissionStart = false
    private var plannedRoute: RoutePlan? = null
    private var routeGuidance: RouteGuidance? = null
    private val spokenRouteCues = HashSet<String>()
    private var lastRouteUpdateMs = 0L
    private var lastRouteResult: RouteGuidance.Result? = null
    private var lastRouteWasAssisted = false
    @Volatile private var savedReplayGeneration = 0
    private var viewingSavedRide = false
    private var mapBottomInsetPx = 0

    private var navigator: Navigator? = null
    private var googleMapRenderer: GoogleMapRenderer? = null
    private var mapLibreRenderer: MapLibreOfflineRenderer? = null
    private var googleMapReady = false
    private var googleMapActive = false
    private var mapLibreReady = false
    private var mapLibreActive = false
    private var activityStarted = false
    private var activityResumed = false
    private var preparingOfflineMap = false
    private var latestMapSnapshot: NavigationMapSnapshot? = null
    private val roadSnapper = RoadSnapper()
    private var roadSnapSession: Any? = null
    private var roadSnapRoads: List<RoadLine>? = null
    private var roadLockStatus: RoadSnapper.Status? = null
    private var model: SpeedModel? = null
    private var live: LiveSource? = null
    @Volatile private var replayThread: Thread? = null
    @Volatile private var stopRequested = false
    @Volatile private var failure: String? = null

    private val ui = Handler(Looper.getMainLooper())
    private var lastRendered = 0L
    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) onLive()
        else showFailure("Precise location is required. Enable it in Android app permissions and try again.")
    }
    private val offlineMapPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(::importOfflineMap) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voiceCoPilot = AvionicsVoiceCoPilot(this)
        configureWindow()
        setContentView(buildNavigationShell())
        googleMapRenderer?.onCreate(savedInstanceState)
        if (!Prefs.voiceEnabled(this)) {
            voiceCoPilot.toggleMute()
            voiceButton.text = "🔇"
        }
        showIdleState()
        prepareBundledOfflineMap()
        runCatching {
            val cache = File(filesDir, "planned_route.json")
            if (cache.exists() && cache.length() <= 2_000_000)
                RoutePlan.fromCache(JSONObject(cache.readText())) else null
        }.getOrNull()?.let { applyRoute(it, save = false) }
        if (savedInstanceState == null && intent.getLongExtra("saved_ride_id", 0) > 0)
            startSavedRide(intent.getLongExtra("saved_ride_id", 0))
        else if (savedInstanceState == null && intent.getBooleanExtra("start_demo", false)) onReplay()
        else if (savedInstanceState == null && intent.getBooleanExtra("plan_route", false)) openRouteSearch()
    }

    private fun configureWindow() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    /** Build the premium shell with ordinary Android views, keeping dependencies minimal. */
    private fun buildNavigationShell(): View {
        root = FrameLayout(this).apply {
            setBackgroundColor(NAV_BACKGROUND)
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        // Android 15+ enforces edge-to-edge content. Respect the real status/navigation
        // bar insets so the cockpit never collides with the clock or gesture handle.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        if (mapsConfigured() && GoogleMapRenderer.playServicesAvailable(this)) {
            googleMapRenderer = GoogleMapRenderer(
                context = this,
                topContentInsetPx = dp(76),
                bottomContentInsetPx = dp(328),
            ) {
                ui.post {
                    googleMapReady = true
                    showGoogleBasemap()
                }
            }.also { renderer ->
                root.addView(renderer.view, FrameLayout.LayoutParams(MATCH, MATCH))
            }
        }

        map = NavigationView(this).apply {
            // Retained as a snapshot sink only; never present the canvas as navigation.
            visibility = View.GONE
            setPadding(dp(16), dp(76), dp(16), dp(328))
            contentDescription = "Waynova estimated track and uncertainty map"
        }

        // Unified Top Floating Glass Island
        root.addView(buildTopBar(), FrameLayout.LayoutParams(MATCH, dp(52)).apply {
            gravity = Gravity.TOP
            setMargins(dp(14), dp(10), dp(14), 0)
        })

        recenterButton = actionText("⌖", 22f, dp(44)).apply {
            visibility = View.GONE
            contentDescription = "Recenter map on Waynova position"
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                if (googleMapActive) googleMapRenderer?.recenter()
                if (mapLibreActive) mapLibreRenderer?.recenter()
            }
        }
        root.addView(recenterButton, FrameLayout.LayoutParams(dp(44), dp(44)).apply {
            gravity = Gravity.END or Gravity.BOTTOM
            setMargins(0, 0, dp(16), dp(340))
        })

        mapAttribution = label(
            "MapLibre · © OSM · Protomaps",
            8.5f, TEXT_MUTED, Typeface.BOLD,
        ).apply {
            letterSpacing = 0.03f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = pill(SURFACE_STRONG, dp(10), BORDER_SUBTLE)
            visibility = View.GONE
            contentDescription = "Map data OpenStreetMap; map tiles Protomaps; renderer MapLibre"
        }
        root.addView(mapAttribution, FrameLayout.LayoutParams(WRAP, dp(24)).apply {
            gravity = Gravity.START or Gravity.BOTTOM
            setMargins(dp(16), 0, 0, dp(342))
        })

        outageBanner = buildOutageBanner()
        // Anchored on the left: the map's own compass and the recenter control both live
        // on the right edge, and the warning must never sit on top of a control.
        root.addView(outageBanner, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.START or Gravity.TOP
            setMargins(dp(16), dp(76), 0, 0)
        })

        val bottomPanel = buildBottomPanel()
        root.addView(bottomPanel, FrameLayout.LayoutParams(MATCH, WRAP).apply {
            gravity = Gravity.BOTTOM
            setMargins(dp(12), 0, dp(12), dp(10))
        })
        bottomPanel.addOnLayoutChangeListener { _, _, top, _, _, _, _, _, _ ->
            val bottomInset = root.height - root.paddingBottom - top + dp(10)
            mapBottomInsetPx = bottomInset
            map.setPadding(dp(16), dp(76), dp(16), bottomInset)
            googleMapRenderer?.setContentInsets(dp(76), bottomInset)
            mapLibreRenderer?.setContentInsets(dp(76), bottomInset)
            (recenterButton.layoutParams as FrameLayout.LayoutParams).also {
                it.bottomMargin = bottomInset + dp(12); recenterButton.layoutParams = it
            }
            (mapAttribution.layoutParams as FrameLayout.LayoutParams).also {
                it.bottomMargin = bottomInset + dp(14); mapAttribution.layoutParams = it
            }
        }
        return root
    }

    private fun onRouteAction() {
        if (replayThread != null) {
            Toast.makeText(this, "Stop the demo before planning your own route.", Toast.LENGTH_LONG).show()
            return
        }
        if (live != null && (navigator?.state?.speed ?: 0.0) > 0.5) {
            Toast.makeText(this, "Park before searching or reading directions.", Toast.LENGTH_LONG).show()
            return
        }
        val route = plannedRoute
        if (route == null) { openRouteSearch(); return }

        val directions = android.widget.ListView(this).apply {
            divider = null
            setPadding(dp(16), 0, dp(16), dp(8))

            // Header banner
            val header = LinearLayout(this@NavigationActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(14))
                background = pill(SURFACE_SOFT, dp(16), BORDER_SUBTLE)
                addView(label("OSRM DRIVING ROUTE", 9f, ACCENT_AMBER, Typeface.BOLD).apply { letterSpacing = 0.14f })
                addView(label("%.1f km · about %.0f min".format(route.distanceM / 1000, route.durationS / 60), 18f, TEXT_PRIMARY, Typeface.BOLD).apply {
                    setPadding(0, dp(4), 0, dp(2))
                })
                addView(label("Saved offline route preview · Follow signs while driving", 11.5f, TEXT_SECONDARY, Typeface.NORMAL))
            }
            addHeaderView(header, null, false)

            addFooterView(label("Mint = planned route · Blue/Orange = estimated track\nRoutes: OSRM · © OpenStreetMap (ODbL)",
                10.5f, TEXT_MUTED, Typeface.NORMAL).apply {
                setPadding(dp(8), dp(12), dp(8), dp(8))
            }, null, false)

            adapter = object : android.widget.BaseAdapter() {
                override fun getCount() = route.steps.size
                override fun getItem(position: Int) = route.steps[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun isEnabled(position: Int) = false
                override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                    val row = (convertView as? LinearLayout) ?: LinearLayout(this@NavigationActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(12), dp(10), dp(12), dp(10))

                        // Directional glyph
                        addView(label("↑", 17f, ACCENT_AMBER, Typeface.BOLD).apply {
                            gravity = Gravity.CENTER
                            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                        })

                        // Instruction text column
                        val textCol = LinearLayout(this@NavigationActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(8), 0, dp(8), 0)
                            addView(label("", 12.5f, TEXT_PRIMARY, Typeface.BOLD))
                            addView(label("", 11f, TEXT_SECONDARY, Typeface.NORMAL).apply { setPadding(0, dp(2), 0, 0) })
                        }
                        addView(textCol, LinearLayout.LayoutParams(0, WRAP, 1f))

                        // Distance badge
                        addView(label("", 10f, TEXT_PRIMARY, Typeface.BOLD).apply {
                            gravity = Gravity.CENTER
                            setPadding(dp(7), dp(3), dp(7), dp(3))
                            background = pill(SURFACE_SOFT, dp(8), BORDER_SUBTLE)
                        })
                    }

                    val step = route.steps[position]
                    val lower = step.instruction.lowercase(Locale.ROOT)
                    val glyph = when {
                        lower.contains("roundabout") || lower.contains("rotary") -> "↶"
                        lower.contains("left") -> "↰"
                        lower.contains("right") -> "↱"
                        lower.contains("destination") || lower.contains("arrive") -> "🏁"
                        else -> "↑"
                    }

                    val glyphView = row.getChildAt(0) as TextView
                    glyphView.text = glyph
                    glyphView.setTextColor(if (glyph == "🏁") ACCENT_GREEN else ACCENT_AMBER)

                    val textCol = row.getChildAt(1) as LinearLayout
                    (textCol.getChildAt(0) as TextView).text = step.instruction
                    (textCol.getChildAt(1) as TextView).text = if (step.distanceM > 0)
                        "Continue along roadway" else "Destination reached"

                    val distBadge = row.getChildAt(2) as TextView
                    distBadge.text = if (step.distanceM > 0) "%.0f m".format(step.distanceM) else "Arrive"

                    row.background = pill(if (position % 2 == 0) SURFACE_SOFT else Color.parseColor("#12141C"), dp(12))
                    return row
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle(route.destination)
            .setView(directions)
            .setPositiveButton("Done", null)
            .setNeutralButton("New Route") { _, _ -> openRouteSearch() }
            .setNegativeButton("Clear") { _, _ ->
                plannedRoute = null
                live?.setRoute(null)
                File(filesDir, "planned_route.json").delete()
                googleMapRenderer?.setPlannedRoute(emptyList())
                mapLibreRenderer?.setPlannedRoute(emptyList())
                map.setPlannedRoute(emptyList(), Double.NaN, Double.NaN)
                routeButton.text = "⌕  Destination / Route"
                refreshMapAttribution()
            }.show()
    }

    private fun showNavigationBackOptions() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Navigation")
            .setItems(arrayOf("Choose another route", "Back to dashboard")) { _, which ->
                if (which == 1) {
                    finish()
                } else if (live != null && (navigator?.state?.speed ?: 0.0) > 0.5) {
                    Toast.makeText(this, "Park before changing your route.", Toast.LENGTH_LONG).show()
                } else if (live != null) {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Save this drive and choose a route?")
                        .setMessage("The current recording will be saved. Your existing route stays until you select a replacement.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Save and choose") { _, _ ->
                            live?.interruptTest()
                            stopLive()?.let(::showSavedSession)
                            setActions(replayRunning = false, liveRunning = false)
                            openRouteSearch()
                        }.show()
                } else {
                    stopReplay()
                    setActions(replayRunning = false, liveRunning = false)
                    openRouteSearch()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openRouteSearch() {
        val nav = navigator
        val state = nav?.state
        val point = if (state != null && state.phase == Navigator.Phase.NAVIGATING &&
            state.mode == NavMode.AIDED && state.gnssFixAgeS < 3 && state.sigmaM < 30) {
            EnuMapProjection.toLatLng(nav.originLat, nav.originLon, state.east, state.north)
                .let { RoutePoint(it.latitude, it.longitude) }
        } else null
        RoutePlannerDialog.show(this, point) { applyRoute(it) }
    }

    private fun applyRoute(route: RoutePlan, save: Boolean = true) {
        live?.setRoute(route)
        viewingSavedRide = false
        plannedRoute = route
        routeGuidance = RouteGuidance(route)
        spokenRouteCues.clear()
        lastRouteResult = null
        lastRouteWasAssisted = false
        lastRouteUpdateMs = 0L
        if (live == null && replayThread == null) {
            latestMapSnapshot = null
            googleMapRenderer?.clear(); mapLibreRenderer?.clear(); map.clear()
            if (mapLibreReady) showMapLibreBasemap()
        }
        googleMapRenderer?.setPlannedRoute(route.points)
        mapLibreRenderer?.setPlannedRoute(route.points)
        map.setPlannedRoute(route.points, route.points.first().lat, route.points.first().lon)
        routeButton.text = "↗  %.1f km · Directions".format(route.distanceM / 1000)
        refreshMapAttribution()
        if (save) runCatching { File(filesDir, "planned_route.json").writeText(route.toJson().toString()) }
            .onFailure { Toast.makeText(this, "Route loaded but could not be saved for later.", Toast.LENGTH_LONG).show() }
    }

    private fun buildTopBar(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(5), dp(8), dp(5))
            background = glass(20, SURFACE_STRONG, BORDER_PROMINENT)
            glow(Glass.TEAL, 10f)

            val back = actionText("‹", 26f, dp(38)).apply {
                contentDescription = "Back: choose another route or dashboard"
                setOnClickListener { showNavigationBackOptions() }
            }
            addView(back, LinearLayout.LayoutParams(dp(38), dp(38)))

            routeButton = TextView(this@NavigationActivity).apply {
                text = "⌕  Destination / Directions"
                textSize = 12f
                setTextColor(TEXT_PRIMARY)
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(10), 0)
                background = glass(14, Glass.PANEL_FILL_INNER)
                isClickable = true
                isFocusable = true
                contentDescription = "Find destination or view saved driving directions"
                setOnClickListener { onRouteAction() }
            }
            addView(routeButton, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            })

            modeBadge = label("● STANDBY", 9.5f, TEXT_SECONDARY, Typeface.BOLD).apply {
                letterSpacing = 0.08f
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(8), 0)
                background = glass(14, Glass.PANEL_FILL_INNER)
            }
            addView(modeBadge, LinearLayout.LayoutParams(WRAP, dp(38)))

            mapSourceBadge = label(
                if (googleMapRenderer != null) "GOOGLE MAP" else "OFFLINE GN",
                9f, ACCENT_GREEN, Typeface.BOLD
            ).apply {
                letterSpacing = 0.06f
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(8), 0)
                background = accentChip(ACCENT_GREEN, 14)
                isClickable = true
                isFocusable = true
                contentDescription = "Switch map renderer; long press to import an offline map"
                setOnClickListener { cycleMapRenderer() }
                setOnLongClickListener {
                    chooseOfflineMap()
                    true
                }
            }
            addView(mapSourceBadge, LinearLayout.LayoutParams(WRAP, dp(38)).apply {
                marginStart = dp(5)
            })

            voiceButton = actionText("🔊", 16f, dp(38)).apply {
                contentDescription = "Toggle offline avionics voice co-pilot"
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    val muted = voiceCoPilot.toggleMute()
                    Prefs.setVoiceEnabled(this@NavigationActivity, !muted)
                    text = if (muted) "🔇" else "🔊"
                    Toast.makeText(
                        this@NavigationActivity,
                        if (muted) "Voice Co-Pilot Muted" else "Voice Co-Pilot Enabled",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            addView(voiceButton, LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                marginStart = dp(5)
            })
        }
    }

    /**
     * The satellite-loss warning that floats over the map.
     *
     * It is deliberately separate from the status panel: during an outage the driver
     * needs one unmissable signal that the blue dot is now an estimate, without the map
     * itself turning into an alarm.
     */
    private fun buildOutageBanner(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = glass(18, Glass.alpha(ACCENT_RED, 0.18f), Glass.alpha(ACCENT_RED, 0.55f))
        glow(ACCENT_RED, 14f)
        visibility = View.GONE
        contentDescription = "Satellite signal unavailable. Position is being estimated."

        addView(label("⊘", 18f, ACCENT_RED, Typeface.BOLD))
        addView(label("GNSS", 10f, ACCENT_RED, Typeface.BOLD).apply {
            letterSpacing = 0.12f
            setPadding(0, dp(5), 0, 0)
        })
        addView(label("UNAVAILABLE", 10f, ACCENT_RED, Typeface.BOLD).apply {
            letterSpacing = 0.12f
        })
    }

    private fun buildBottomPanel(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(16))
            background = glass(26, SURFACE_STRONG, BORDER_PROMINENT)
            glow(Glass.EMERALD, 18f)
            bottomPanel = this

            addView(View(this@NavigationActivity).apply {
                background = pill(BORDER_PROMINENT, dp(2))
            }, LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(10)
            })

            val statusHeader = LinearLayout(this@NavigationActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            eyebrow = label("READY", 10f, ACCENT_BLUE, Typeface.BOLD).apply {
                letterSpacing = 0.16f
            }
            statusHeader.addView(eyebrow, LinearLayout.LayoutParams(0, WRAP, 1f))

            sentinelChip = label("● SENTINEL: NOMINAL", 8.5f, ACCENT_GREEN, Typeface.BOLD).apply {
                letterSpacing = 0.06f
                gravity = Gravity.CENTER
                setPadding(dp(7), dp(3), dp(7), dp(3))
                background = accentChip(ACCENT_GREEN, 10)
            }
            statusHeader.addView(sentinelChip)
            addView(statusHeader)

            headline = label("Ready to check your position", 19f, TEXT_PRIMARY, Typeface.BOLD)
            addView(headline, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })

            guidance = label(
                "Satellite fixes and IMU motion are strengthening on-device estimate.",
                12.5f, TEXT_SECONDARY, Typeface.NORMAL
            ).apply { setLineSpacing(0f, 1.15f) }
            addView(guidance, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(3) })

            calibrationProgress = ProgressBar(
                this@NavigationActivity, null, android.R.attr.progressBarStyleHorizontal
            ).apply {
                max = 100
                progress = 0
                progressTintList = ColorStateList.valueOf(ACCENT_GREEN)
                progressBackgroundTintList = ColorStateList.valueOf(BORDER_SUBTLE)
                visibility = View.GONE
            }
            addView(calibrationProgress, LinearLayout.LayoutParams(MATCH, dp(4)).apply {
                topMargin = dp(10)
            })

            val metrics = LinearLayout(this@NavigationActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            speedMetric = metric("SPEED")
            headingMetric = metric("HEADING")
            uncertaintyMetric = metric("UNCERTAINTY")
            metrics.addView(speedMetric.root, metricParams(endMargin = dp(8)))
            metrics.addView(headingMetric.root, metricParams(endMargin = dp(8)))
            metrics.addView(uncertaintyMetric.root, metricParams())
            addView(metrics, LinearLayout.LayoutParams(MATCH, dp(68)).apply { topMargin = dp(12) })

            contextLine = label("6-DOF ES-EKF  •  SPEED-TCN  •  100 Hz", 9.5f, TEXT_MUTED, Typeface.BOLD).apply {
                letterSpacing = 0.08f
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 2
            }
            addView(contextLine, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            })

            val actions = LinearLayout(this@NavigationActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            liveButton = actionButton("Start live navigation", primary = true).apply {
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    onLive()
                }
            }
            replayButton = actionButton("Replay demo", primary = false).apply {
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    onSecondaryAction()
                }
                // The original recorded-data demo (real estimator, outside offline map coverage).
                setOnLongClickListener {
                    if (live != null || replayThread != null) return@setOnLongClickListener false
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onRecordedReplay()
                    true
                }
            }
            actions.addView(liveButton, LinearLayout.LayoutParams(0, dp(50), 1f))
            actions.addView(Space(this@NavigationActivity), LinearLayout.LayoutParams(dp(10), 1))
            actions.addView(replayButton, LinearLayout.LayoutParams(dp(126), dp(50)))
            addView(actions, LinearLayout.LayoutParams(MATCH, dp(50)).apply { topMargin = dp(4) })
        }
    }

    private data class MetricViews(
        val root: View,
        val value: TextView,
        val unit: TextView,
    )

    private fun metric(title: String): MetricViews {
        lateinit var value: TextView
        lateinit var unit: TextView
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(10), dp(7))
            background = glass(16, Glass.PANEL_FILL_INNER)
            addView(label(title, 8.5f, TEXT_MUTED, Typeface.BOLD).apply { letterSpacing = 0.12f })
            val line = LinearLayout(this@NavigationActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                value = label("—", 22f, TEXT_PRIMARY, Typeface.BOLD).apply {
                    typeface = Typeface.MONOSPACE
                }
                unit = label("", 8.5f, ACCENT_GREEN, Typeface.BOLD).apply {
                    setPadding(dp(4), 0, 0, dp(3))
                    letterSpacing = 0.06f
                }
                addView(value)
                addView(unit)
            }
            addView(line)
        }
        return MetricViews(root, value, unit)
    }

    private fun metricParams(endMargin: Int = 0) = LinearLayout.LayoutParams(0, MATCH, 1f).apply {
        marginEnd = endMargin
    }

    private fun label(text: String, size: Float, color: Int, style: Int): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            typeface = Typeface.create("sans-serif", style)
            includeFontPadding = false
        }

    private fun actionText(text: String, size: Float, width: Int): TextView =
        label(text, size, TEXT_PRIMARY, Typeface.NORMAL).apply {
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf(Glass.alpha(Color.WHITE, 0.16f)),
                glass(15, Glass.PANEL_FILL_INNER),
                null,
            )
            layoutParams = LinearLayout.LayoutParams(width, dp(44))
        }

    private fun actionButton(text: String, primary: Boolean): TextView =
        label(text, 13.5f, if (primary) Glass.TEXT_ON_ACCENT else TEXT_PRIMARY, Typeface.BOLD).apply {
            this.text = text
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            letterSpacing = 0.04f
            background = RippleDrawable(
                ColorStateList.valueOf(Glass.alpha(Color.WHITE, 0.2f)),
                if (primary) accentFill(ACCENT_GREEN, 16) else glass(16, SURFACE_SOFT, BORDER_PROMINENT),
                null,
            )
            if (primary) glow(ACCENT_GREEN, 10f)
        }

    private fun pill(color: Int, radius: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius.toFloat()
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    private fun ripple(color: Int, radius: Int, strokeColor: Int): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(Color.argb(55, 255, 255, 255)),
            pill(color, radius, strokeColor),
            null,
        )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    @Suppress("DEPRECATION")
    private fun mapsConfigured(): Boolean {
        val metadata = packageManager.getApplicationInfo(
            packageName, PackageManager.GET_META_DATA
        ).metaData
        val key = metadata?.getString("com.google.android.geo.API_KEY").orEmpty()
        return key.isNotBlank() && key != "DEFAULT_API_KEY" && key != "YOUR_API_KEY"
    }

    private fun showGoogleBasemap() {
        if (!googleMapReady) return
        googleMapActive = true
        mapLibreActive = false
        googleMapRenderer?.view?.visibility = View.VISIBLE
        mapLibreRenderer?.view?.visibility = View.INVISIBLE
        map.visibility = View.INVISIBLE
        refreshMapAttribution()
        mapSourceBadge.text = "GOOGLE MAP"
        mapSourceBadge.setTextColor(ACCENT_GREEN)
        mapSourceBadge.background = pill(
            SURFACE_SOFT, dp(14), withAlpha(ACCENT_GREEN, 150)
        )
        recenterButton.visibility = View.VISIBLE
    }

    private fun showLocalFallback() {
        if (googleMapReady) { showGoogleBasemap(); return }
        if (mapLibreReady) { showMapLibreBasemap(); return }
        googleMapActive = false
        mapLibreActive = false
        googleMapRenderer?.view?.visibility = View.INVISIBLE
        mapLibreRenderer?.view?.visibility = View.INVISIBLE
        map.visibility = View.GONE
        refreshMapAttribution()
        mapSourceBadge.text = "MAP UNAVAILABLE · TAP"
        mapSourceBadge.setTextColor(TEXT_MUTED)
        mapSourceBadge.background = pill(SURFACE_SOFT, dp(14), BORDER_SUBTLE)
        recenterButton.visibility = View.GONE
    }

    private fun showMapLibreBasemap() {
        if (!mapLibreReady) return
        googleMapActive = false
        mapLibreActive = true
        googleMapRenderer?.view?.visibility = View.INVISIBLE
        mapLibreRenderer?.view?.visibility = View.VISIBLE
        map.visibility = View.INVISIBLE
        refreshMapAttribution()
        mapSourceBadge.text = "OFFLINE GN"
        mapSourceBadge.setTextColor(ACCENT_GREEN)
        mapSourceBadge.background = pill(
            SURFACE_SOFT, dp(14), withAlpha(ACCENT_GREEN, 150)
        )
        recenterButton.visibility = View.VISIBLE
        updateOfflineCoverageBadge()
    }

    private fun updateOfflineCoverageBadge() {
        if (!mapLibreActive) return
        val snapshot = latestMapSnapshot
        val outside = snapshot != null && org.neuronavx.logger.nav.offlineMapMarkerOrNull(snapshot) != null &&
            mapLibreRenderer?.covers(snapshot) == false
        val lock = when (roadLockStatus) {
            RoadSnapper.Status.ON_ROAD -> " · ROAD LOCK"
            RoadSnapper.Status.CORRECTING -> " · SNAPPING TO ROAD"
            else -> ""
        }
        mapSourceBadge.text = if (outside) "NO OFFLINE COVERAGE" else "OFFLINE GN$lock"
        mapSourceBadge.setTextColor(when {
            outside -> ACCENT_AMBER
            roadLockStatus == RoadSnapper.Status.CORRECTING -> ACCENT_AMBER
            else -> ACCENT_GREEN
        })
    }

    private fun refreshMapAttribution() {
        val hasRoute = plannedRoute != null && replayThread == null && !viewingSavedRide
        mapAttribution.text = if (mapLibreActive) "MapLibre · © OpenStreetMap · Protomaps"
            else "Routes: OSRM · © OpenStreetMap"
        mapAttribution.visibility = if (mapLibreActive || hasRoute) View.VISIBLE else View.GONE
    }

    private fun cycleMapRenderer() {
        mapSourceBadge.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        when {
            googleMapActive && mapLibreReady -> showMapLibreBasemap()
            mapLibreActive && googleMapReady -> showGoogleBasemap()
            googleMapActive || mapLibreActive -> Toast.makeText(
                this, "This is the available map. Long-press to import an offline map.", Toast.LENGTH_LONG
            ).show()
            googleMapReady -> showGoogleBasemap()
            mapLibreReady -> showMapLibreBasemap()
            preparingOfflineMap -> Toast.makeText(
                this, "Greater Noida offline map is being prepared", Toast.LENGTH_SHORT
            ).show()
            else -> chooseOfflineMap()
        }
    }

    private fun chooseOfflineMap() {
        offlineMapPicker.launch(arrayOf("application/octet-stream", "application/x-pmtiles", "*/*"))
    }

    private fun prepareBundledOfflineMap() {
        preparingOfflineMap = true
        thread(name = "neuronavx-offline-map-install") {
            runCatching {
                OfflineMapStore.ensureBundled(this) { percent ->
                    ui.post {
                        if (!googleMapActive && !mapLibreActive) {
                            mapSourceBadge.text = "OFFLINE PREPARING  $percent%"
                        }
                    }
                }
            }.onSuccess { installed ->
                ui.post {
                    if (isDestroyed) return@post
                    preparingOfflineMap = false
                    installMapLibreRenderer(installed)
                }
            }.onFailure { error ->
                ui.post {
                    preparingOfflineMap = false
                    if (!googleMapActive) showLocalFallback()
                    Toast.makeText(
                        this, "Offline map unavailable: ${error.message}", Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun importOfflineMap(uri: android.net.Uri) {
        preparingOfflineMap = true
        thread(name = "neuronavx-offline-map-import") {
            runCatching {
                OfflineMapStore.import(this, uri) { percent ->
                    ui.post { mapSourceBadge.text = "IMPORTING OFFLINE MAP  $percent%" }
                }
            }.onSuccess { installed ->
                ui.post {
                    preparingOfflineMap = false
                    installMapLibreRenderer(installed, selectWhenReady = true)
                    Toast.makeText(
                        this,
                        "Offline map installed (${OfflineMapStore.formatBytes(installed.bytes)})",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }.onFailure { error ->
                ui.post {
                    preparingOfflineMap = false
                    Toast.makeText(this, error.message ?: "Import failed", Toast.LENGTH_LONG).show()
                    when {
                        googleMapActive -> showGoogleBasemap()
                        mapLibreActive -> showMapLibreBasemap()
                        else -> showLocalFallback()
                    }
                }
            }
        }
    }

    private fun installMapLibreRenderer(
        installed: OfflineMapStore.InstalledMap,
        selectWhenReady: Boolean = false,
    ) {
        val old = mapLibreRenderer
        if (old != null) {
            if (activityResumed) old.onPause()
            if (activityStarted) old.onStop()
            old.onDestroy()
            root.removeView(old.view)
        }
        mapLibreReady = false
        mapLibreActive = false
        val renderer = MapLibreOfflineRenderer(
            context = this,
            archive = installed.file,
            topContentInsetPx = dp(76),
            bottomContentInsetPx = mapBottomInsetPx.takeIf { it > 0 } ?: dp(328),
            onBasemapReady = {
                ui.post {
                    mapLibreReady = true
                    if (selectWhenReady || !googleMapReady) showMapLibreBasemap()
                }
            },
            onCoverageChanged = { _ ->
                ui.post {
                    updateOfflineCoverageBadge()
                }
            },
        )
        mapLibreRenderer = renderer
        if (replayThread == null && !viewingSavedRide) plannedRoute?.let { renderer.setPlannedRoute(it.points) }
        renderer.view.visibility = View.INVISIBLE
        root.addView(renderer.view, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        renderer.onCreate(null)
        if (activityStarted) renderer.onStart()
        if (activityResumed) renderer.onResume()
        latestMapSnapshot?.let(renderer::submit)
    }

    // ------------------------------------------------------------------- replay

    private fun onSecondaryAction() {
        if (live != null) onFieldBlackoutTest() else onReplay()
    }

    private fun onFieldBlackoutTest() {
        val source = live ?: return
        when (source.blackoutTestSnapshot().phase) {
            FieldBlackoutPhase.OFF -> {
                if (source.armBlackoutTest(FIELD_TEST_DURATION_S, FIELD_TEST_LEAD_IN_S)) {
                    Toast.makeText(
                        this,
                        "60 s GNSS-loss test armed. It starts automatically after calibration.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            FieldBlackoutPhase.ARMED -> {
                source.cancelBlackoutTest()
                Toast.makeText(this, "GNSS-loss test cancelled", Toast.LENGTH_SHORT).show()
            }
            else -> Toast.makeText(
                this, "The field test is already running or complete", Toast.LENGTH_SHORT
            ).show()
        }
        updateLiveTestAction(source.blackoutTestSnapshot())
    }

    private fun onReplay() {
        if (replayThread != null) {
            stopReplay()
            showPausedState("Demo paused", "The estimated track remains visible on the map.")
            return
        }
        startDemoRide()
    }

    /** Recorded IO-VNBD log through the real estimator. Outside the offline map's coverage. */
    private fun onRecordedReplay() {
        stopLive()
        announcedMissionStart = false
        prevNavMode = NavMode.AIDED
        prevSentinelState = SentinelState.NOMINAL
        setActions(replayRunning = true, liveRunning = false)
        stopRequested = false
        failure = null
        map.clear()
        googleMapRenderer?.clear()
        mapLibreRenderer?.clear()
        googleMapRenderer?.setPlannedRoute(emptyList())
        mapLibreRenderer?.setPlannedRoute(emptyList())
        map.setPlannedRoute(emptyList(), Double.NaN, Double.NaN)
        routeButton.text = "RECORDED DEMO · NOT A LIVE DRIVE"
        showStartingState("DEMO STARTING", "Loading the recorded GNSS-loss route…")

        replayThread = thread(name = "neuronavx-replay") {
            try {
                val runtime = RuntimeConfig.fromAsset(this)
                val speed = SpeedModel(this, runtime).also { model = it }
                ReplaySource(assets.open(REPLAY_ASSET)).use { source ->
                    val nav = Navigator(runtime, speed, inputRateHz = runtime.rateHz)
                    navigator = nav
                    var animating = false
                    var lastSampleT = Double.NaN
                    while (!stopRequested) {
                        val sample = source.next() ?: break
                        val state = nav.addSample(
                            sample.t, sample.accel, sample.gravity, sample.gyro, sample.fix
                        ) ?: continue

                        if (!animating && state.mode != NavMode.AIDED) animating = true
                        if (animating) {
                            if (!lastSampleT.isNaN()) {
                                val waitMs =
                                    ((sample.t - lastSampleT) * 1000.0 / SPEED_FACTOR).toLong()
                                if (waitMs > 0) Thread.sleep(waitMs)
                            }
                            render(state, force = false)
                        } else if (nav.trackEast.size % 200 == 0) {
                            render(state, force = false)
                        }
                        lastSampleT = sample.t
                    }
                    nav.state?.let { render(it, force = true) }
                }
            } catch (e: Throwable) {
                android.util.Log.e("NeuroNavX", "replay failed", e)
                failure = "${e.javaClass.simpleName}: ${e.message ?: "unknown error"}"
                ui.post { showFailure(failure!!) }
            } finally {
                model?.close()
                model = null
                replayThread = null
                ui.post { setActions(replayRunning = false, liveRunning = false) }
            }
        }
    }

    private fun stopReplay() {
        savedReplayGeneration++
        voiceCoPilot.stopSpeaking()
        stopRequested = true
        replayThread?.join(2000)
        replayThread = null
        setActions(replayRunning = false, liveRunning = false)
    }

    /**
     * Simulated demo ride on a bundled OSRM route in Greater Noida, inside offline map
     * coverage. Not a recording and not an estimator run: the UI says so throughout.
     */
    private fun startDemoRide() {
        stopLive()
        stopReplay()
        val generation = savedReplayGeneration
        stopRequested = false
        failure = null
        navigator = null
        viewingSavedRide = false
        map.clear()
        googleMapRenderer?.clear()
        mapLibreRenderer?.clear()
        if (mapLibreReady && !mapLibreActive) showMapLibreBasemap()
        setActions(replayRunning = true, liveRunning = false)
        routeButton.text = "SIMULATED DEMO RIDE · NOT A RECORDING"
        showStartingState("DEMO RIDE", "Loading the Greater Noida demo route…")
        val session = Any()
        replayThread = thread(name = "waynova-demo-ride") {
            try {
                val plan = RoutePlan.fromOsrm(assets.open(DemoRide.ASSET).bufferedReader().use { it.readText() },
                    DemoRide.DESTINATION)
                val ride = DemoRide(plan)
                val frames = ride.frames()
                val guidanceModel = RouteGuidance(plan)
                val east = ArrayList<Double>(); val north = ArrayList<Double>(); val dark = ArrayList<Boolean>()
                var routeShownOn: MapLibreOfflineRenderer? = null
                var lastShown = Double.NEGATIVE_INFINITY
                for ((index, frame) in frames.withIndex()) {
                    if (stopRequested || generation != savedReplayGeneration) break
                    if (index > 0) Thread.sleep(((frame.t - frames[index - 1].t) * 1000 / SPEED_FACTOR).toLong())
                    east += frame.east; north += frame.north; dark += frame.mode != NavMode.AIDED
                    if (frame.t - lastShown < 0.2 && index != frames.lastIndex) continue
                    lastShown = frame.t
                    val e = east.toDoubleArray(); val n = north.toDoubleArray(); val d = dark.toBooleanArray()
                    val raw = NavigationMapSnapshot(ride.originLat, ride.originLon, e, n, e, n, d,
                        frame.east, frame.north, frame.headingRad, frame.sigmaM, frame.mode != NavMode.AIDED)
                    ui.post {
                        if (isDestroyed || generation != savedReplayGeneration || stopRequested) return@post
                        val renderer = mapLibreRenderer
                        if (renderer != null && renderer !== routeShownOn) {
                            renderer.setPlannedRoute(plan.points); routeShownOn = renderer
                        }
                        if (mapLibreReady && !mapLibreActive) showMapLibreBasemap()
                        val snapshot = applyRoadLock(raw, session, frame.t, frame.speedMps)
                        latestMapSnapshot = snapshot
                        renderer?.submit(snapshot); googleMapRenderer?.submit(snapshot)
                        showDemoFrame(frame, ride, plan, guidanceModel, snapshot)
                    }
                }
                ui.post {
                    if (!isDestroyed && generation == savedReplayGeneration && !stopRequested)
                        showPausedState("Demo ride finished",
                            "Simulated ride to ${DemoRide.DESTINATION}. Not a recording or an accuracy result.",
                            "SIMULATED  •  ${"%.1f".format(ride.lengthM / 1000)} KM  •  ROUTE: OSRM · © OPENSTREETMAP")
                }
            } catch (error: Exception) {
                android.util.Log.e("Waynova", "demo ride failed", error)
                ui.post { if (!isDestroyed && generation == savedReplayGeneration && !stopRequested)
                    showFailure(error.message ?: "Could not start the demo ride") }
            } finally {
                ui.post { if (!isDestroyed && generation == savedReplayGeneration) {
                    replayThread = null
                    setActions(replayRunning = false, liveRunning = false)
                } }
            }
        }
    }

    private fun showDemoFrame(frame: DemoRide.Frame, ride: DemoRide, plan: RoutePlan,
        guidanceModel: RouteGuidance, shown: NavigationMapSnapshot) {
        val modeColor = when (frame.mode) {
            NavMode.AIDED -> ACCENT_GREEN
            NavMode.BLACKOUT -> ACCENT_AMBER
            NavMode.REACQUIRING -> ACCENT_BLUE
        }
        setMode("DEMO · ${frame.mode}", modeColor)
        eyebrow.text = "SIMULATED DEMO RIDE · 4× · NOT A RECORDING"
        eyebrow.setTextColor(ACCENT_AMBER)
        val point = EnuMapProjection.toLatLng(ride.originLat, ride.originLon, shown.markerEast, shown.markerNorth)
        val step = guidanceModel.update(RoutePoint(point.latitude, point.longitude),
            reliable = frame.mode == NavMode.AIDED, sigmaM = frame.sigmaM, fixAgeS = 0.0,
            headingRad = frame.headingRad, speedMps = frame.speedMps)
        when {
            frame.mode == NavMode.BLACKOUT -> {
                headline.text = "GPS lost · dead reckoning"
                guidance.text = "Simulated GPS loss. The estimate drifts; road lock keeps the marker on the road."
            }
            frame.mode == NavMode.REACQUIRING -> {
                headline.text = "GPS back · re-converging"
                guidance.text = "Simulated recovery: the estimate eases back instead of jumping."
            }
            step.status == RouteGuidance.Status.READY -> {
                headline.text = step.instruction
                guidance.text = "In ${formatDistance(step.distanceM)} · ${formatDistance(step.remainingM)} to ${DemoRide.DESTINATION}"
            }
            step.status == RouteGuidance.Status.NEAR_DESTINATION -> {
                headline.text = "Arriving at ${DemoRide.DESTINATION}"
                guidance.text = "Simulated demo ride from ${DemoRide.START_LABEL}."
            }
            else -> {
                headline.text = "Driving to ${DemoRide.DESTINATION}"
                guidance.text = "Simulated demo ride from ${DemoRide.START_LABEL}."
            }
        }
        val elapsed = frame.t
        val total = ride.lengthM
        contextLine.text = "%.1f / %.1f KM  •  %d:%02d  •  ROUTE: OSRM · © OPENSTREETMAP".format(
            frame.progressM / 1000, total / 1000, (elapsed / 60).toInt(), (elapsed % 60).toInt())
        setMetric(speedMetric, "%.0f".format(Prefs.speedInPreferredUnit(this, frame.speedMps)), Prefs.speedLabel(this))
        setMetric(headingMetric, "%.0f".format((Math.toDegrees(shown.headingRad) + 360) % 360), "DEG")
        setMetric(uncertaintyMetric, "%.1f".format(frame.sigmaM), "M 1σ")
        calibrationProgress.visibility = View.GONE
    }

    private fun formatDistance(m: Double): String =
        if (m >= 1000) "%.1f km".format(m / 1000) else "${(m / 10).roundToInt() * 10} m"

    private fun startSavedRide(id: Long) {
        stopReplay()
        viewingSavedRide = true
        val generation = savedReplayGeneration
        stopRequested = false
        navigator = null
        mapLibreRenderer?.clear()
        googleMapRenderer?.clear()
        mapLibreRenderer?.setPlannedRoute(emptyList())
        googleMapRenderer?.setPlannedRoute(emptyList())
        setActions(replayRunning = true, liveRunning = false)
        replayButton.text = "Stop ride replay"
        routeButton.text = "SAVED RIDE · NOT LIVE"
        showStartingState("SAVED REPLAY", "Loading the original recorded estimates…")
        replayThread = thread(name = "waynova-saved-ride") {
            try {
                val ride = org.neuronavx.logger.nav.SavedRide.load(checkNotNull(getExternalFilesDir(null)), id)
                val east = ArrayList<Double>(); val north = ArrayList<Double>(); val dark = ArrayList<Boolean>()
                var previous = ride.frames.first().timeMs
                var lastShown = Double.NEGATIVE_INFINITY
                for ((index, frame) in ride.frames.withIndex()) {
                    if (stopRequested || generation != savedReplayGeneration) break
                    val gap = frame.timeMs - previous > 2000
                    if (gap) { east.clear(); north.clear(); dark.clear() }
                    if (index > 0) Thread.sleep(((frame.timeMs - previous) / 4).toLong().coerceIn(0,250))
                    previous = frame.timeMs
                    east += frame.east; north += frame.north; dark += (frame.mode != "AIDED")
                    if (east.size > 2500) { east.removeAt(0); north.removeAt(0); dark.removeAt(0) }
                    if (frame.timeMs-lastShown < 200 && index != ride.frames.lastIndex) continue
                    lastShown = frame.timeMs
                    val e = east.toDoubleArray(); val n = north.toDoubleArray()
                    val snapshot = NavigationMapSnapshot(ride.origin.lat,ride.origin.lon,e,n,e,n,dark.toBooleanArray(),
                        frame.east,frame.north,frame.heading,frame.sigma,frame.mode != "AIDED")
                    ui.post {
                        if (isDestroyed || generation != savedReplayGeneration || stopRequested) return@post
                        latestMapSnapshot = snapshot
                        mapLibreRenderer?.submit(snapshot); googleMapRenderer?.submit(snapshot)
                        setMode("REPLAY · ${frame.mode}", if (frame.mode == "AIDED") ACCENT_GREEN else ACCENT_AMBER)
                        eyebrow.text = "SAVED RIDE · 4× · NOT LIVE"
                        headline.text = "Replaying your recorded estimate"
                        guidance.text = "Original diagnostics, not a new accuracy test. Long recording gaps are shortened."
                        contextLine.text = "SESSION $id · %.0f / %.0f S · LAST 2500 POINTS".format(
                            (frame.timeMs-ride.frames.first().timeMs)/1000,
                            (ride.frames.last().timeMs-ride.frames.first().timeMs)/1000)
                        setMetric(speedMetric,"%.0f".format(Prefs.speedInPreferredUnit(this,frame.speed)),Prefs.speedLabel(this))
                        setMetric(headingMetric,"%.0f".format((Math.toDegrees(frame.heading)+360)%360),"DEG")
                        setMetric(uncertaintyMetric,"%.1f".format(frame.sigma),"M 1σ")
                        calibrationProgress.visibility = View.GONE
                    }
                }
                ui.post {
                    if (!isDestroyed && generation == savedReplayGeneration && !stopRequested)
                        showPausedState("Recorded ride finished", "This was the saved estimate, not live navigation or a new accuracy result.")
                }
            } catch (error: Exception) {
                ui.post { if (!isDestroyed && generation == savedReplayGeneration && !stopRequested)
                    showFailure(error.message ?: "Could not replay this ride") }
            } finally {
                ui.post { if (!isDestroyed && generation == savedReplayGeneration) {
                    replayThread = null
                    setActions(replayRunning = false, liveRunning = false)
                } }
            }
        }
    }

    // --------------------------------------------------------------------- live

    private fun onLive() {
        if (live != null) {
            val summary = stopLive()
            setActions(replayRunning = false, liveRunning = false)
            if (summary != null) showSavedSession(summary)
            else showPausedState(
                "Navigation paused", "Your last estimated position is still visible."
            )
            return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        if (!(getSystemService(Context.LOCATION_SERVICE) as LocationManager)
                .isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            showFailure("Android Location is off. Turn it on before starting; the controlled test hides GPS inside Waynova.")
            return
        }
        stopReplay()
        announcedMissionStart = false
        prevNavMode = NavMode.AIDED
        prevSentinelState = SentinelState.NOMINAL
        plannedRoute?.let { applyRoute(it, save = false) } ?: run {
            // Do not leave the demo ride's route drawn under a live drive.
            mapLibreRenderer?.setPlannedRoute(emptyList()); googleMapRenderer?.setPlannedRoute(emptyList())
        }
        try {
            val runtime = RuntimeConfig.fromAsset(this)
            val speed = SpeedModel(this, runtime).also { model = it }
            val nav = Navigator(
                runtime, speed, inputRateHz = SensorLoggerRate.TARGET_IMU_HZ.toDouble()
            )
            navigator = nav
            map.clear()
            googleMapRenderer?.clear()
            mapLibreRenderer?.clear()
            live = LiveSource(this, nav) { state -> render(state, force = false) }.also { it.setRoute(plannedRoute); it.start() }
            setActions(replayRunning = false, liveRunning = true)
            showStartingState("ACQUIRING", "Waiting for GNSS and motion calibration…")
            contextLine.text = "RECORDING RAW + DIAGNOSTICS  •  CHECKING SENSOR STREAM"
        } catch (e: Exception) {
            model?.close()
            model = null
            showFailure("${e.javaClass.simpleName}: ${e.message ?: "could not start"}")
            setActions(replayRunning = false, liveRunning = false)
        }
    }

    private fun stopLive(): LiveSessionSummary? {
        voiceCoPilot.stopSpeaking()
        val summary = live?.stop()
        live = null
        model?.close()
        model = null
        return summary
    }

    private fun showSavedSession(summary: LiveSessionSummary) {
        val minutes = summary.durationS / 60.0
        val test = summary.blackoutTest
        val testLine = if (test.phase == FieldBlackoutPhase.OFF) "" else {
            val error = if (test.endReferenceErrorM.isFinite())
                "%.1f m outage-end error".format(test.endReferenceErrorM)
            else "reference incomplete"
            "\n${test.withheldFixes} fixes withheld • $error"
        }
        showPausedState(
            "Navigation saved",
            "%.1f min • %,d IMU samples • %,d phone fixes%s\n%s".format(
                minutes, summary.imuSamples, summary.phoneFixes, testLine,
                summary.rawFile.name
            ),
            if (test.phase == FieldBlackoutPhase.OFF)
                "RAW + DIAGNOSTICS + SUMMARY SAVED  •  ${summary.fusedFixes} FUSED"
            else "CONTROLLED OUTAGE EVIDENCE SAVED  •  ${summary.fusedFixes} FUSED",
        )
        Toast.makeText(
            this,
            "Field session saved. Pull it with mobile/pull_drives.sh",
            Toast.LENGTH_LONG,
        ).show()
    }

    // ------------------------------------------------------------------ drawing

    /** Snapshot estimator buffers and hand them to the custom canvas on the UI thread. */
    private fun render(state: Navigator.State, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRendered < 50) return
        lastRendered = now
        val nav = navigator ?: return
        val liveSource = live
        val assistance = liveSource?.routeAssistance
        val e = nav.trackEast.toDoubleArray()
        val n = nav.trackNorth.toDoubleArray()
        val de = nav.displayEastTrack.toDoubleArray()
        val dn = nav.displayNorthTrack.toDoubleArray()
        val dark = nav.trackDark.toBooleanArray()
        ui.post {
            if (isDestroyed || navigator !== nav || (liveSource != null && live !== liveSource)) return@post
            if (live != null) plannedRoute?.let { map.setPlannedRoute(it.points, nav.originLat, nav.originLon) }
            map.submit(
                e, n, de, dn, dark, state.displayEast, state.displayNorth,
                state.heading, state.sigmaM, state.mode != NavMode.AIDED,
            )
            val originalSnapshot = NavigationMapSnapshot(
                originLat = nav.originLat,
                originLon = nav.originLon,
                estimateEast = e,
                estimateNorth = n,
                displayEast = de,
                displayNorth = dn,
                dark = dark,
                markerEast = state.displayEast,
                markerNorth = state.displayNorth,
                headingRad = state.heading,
                sigmaM = state.sigmaM,
                inOutage = state.mode != NavMode.AIDED,
            )
            val assistedPoint = assistance?.point
            val estimateSnapshot = if (assistedPoint != null) originalSnapshot.copy(
                markerEast = Math.toRadians(assistedPoint.lon-nav.originLon)*6378137.0*kotlin.math.cos(Math.toRadians(nav.originLat)),
                markerNorth = Math.toRadians(assistedPoint.lat-nav.originLat)*6378137.0,
                sigmaM = maxOf(state.sigmaM,assistance.uncertaintyM),
            ) else originalSnapshot
            val snapshot = if (state.phase == Navigator.Phase.NAVIGATING)
                applyRoadLock(estimateSnapshot, nav, state.t, state.speed)
            else { roadSnapper.reset(); setRoadLockStatus(null); estimateSnapshot }
            latestMapSnapshot = snapshot
            googleMapRenderer?.submit(snapshot)
            mapLibreRenderer?.submit(snapshot)
            if (failure == null) {
                updateShell(state)
                updateRouteGuidance(state)
                if (assistance?.status == "ASSISTED") {
                    setMetric(uncertaintyMetric, "%.1f".format(assistance.uncertaintyM), "M EST.")
                    if (lastRouteResult?.status == RouteGuidance.Status.READY) {
                        eyebrow.text = "ROUTE ESTIMATE · GPS UNAVAILABLE"
                        eyebrow.setTextColor(ACCENT_AMBER)
                    } else {
                        headline.text = "Route-assisted · GPS unavailable"
                        guidance.text = "Position assumes the selected road. Original estimate remains visible; distance error can still grow."
                    }
                } else if (assistance != null && state.mode != NavMode.AIDED) {
                    guidance.text = "Route assist paused: ${assistance.status.lowercase().replace('_',' ')}. Showing original estimate."
                }
            }
        }
    }

    /**
     * Keep the displayed marker on the nearest plausible drivable road (offline map only).
     * Display-only: Navigator, recordings and drift scoring keep the original estimate.
     */
    private fun applyRoadLock(
        snapshot: NavigationMapSnapshot, session: Any, t: Double, speedMps: Double,
    ): NavigationMapSnapshot {
        val renderer = mapLibreRenderer
        if (roadSnapSession !== session) {
            roadSnapper.reset(); roadSnapSession = session; roadSnapRoads = null
        }
        if (!Prefs.roadLock(this) || !mapLibreActive || renderer == null) {
            roadSnapper.reset(); setRoadLockStatus(null)
            return snapshot
        }
        val marker = org.neuronavx.logger.nav.offlineMapMarkerOrNull(snapshot) ?: return snapshot
        val roads = renderer.drivableRoadsNear(marker.latitude, marker.longitude)
        if (roads !== roadSnapRoads) {
            roadSnapper.setRoads(roads.orEmpty(), snapshot.originLat, snapshot.originLon)
            roadSnapRoads = roads
        }
        val result = roadSnapper.update(t, snapshot.markerEast, snapshot.markerNorth,
            snapshot.headingRad, speedMps, snapshot.sigmaM)
        setRoadLockStatus(result.status)
        // Released states still carry the easing-out correction, so the marker never jumps.
        return if (result.correctionM > 0.0 || result.locked) snapshot.copy(
            markerEast = result.east, markerNorth = result.north, headingRad = result.headingRad,
        ) else snapshot
    }

    private fun setRoadLockStatus(status: RoadSnapper.Status?) {
        if (status == roadLockStatus) return
        roadLockStatus = status
        updateOfflineCoverageBadge()
    }

    private fun updateRouteGuidance(s: Navigator.State) {
        if (live == null || replayThread != null || plannedRoute == null ||
            s.phase != Navigator.Phase.NAVIGATING) return
        val withholding = live?.blackoutTestSnapshot()?.phase == FieldBlackoutPhase.WITHHOLDING
        val aided = s.mode == NavMode.AIDED && !withholding
        val assistance = live?.routeAssistance
        val routeAssisted = !aided && assistance?.status == "ASSISTED" && assistance.point != null
        if (!aided && !routeAssisted) {
            voiceCoPilot.stopRouteSpeech()
            lastRouteResult = null
            lastRouteUpdateMs = 0L
            lastRouteWasAssisted = false
            return
        }
        val nav = navigator ?: return
        val position = if (routeAssisted) assistance!!.point!! else runCatching {
                EnuMapProjection.toLatLng(nav.originLat, nav.originLon, s.east, s.north)
                    .let { RoutePoint(it.latitude, it.longitude) }
            }.getOrNull() ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        // Geometry matching is a presentation task, not part of the sensor loop.
        if (now - lastRouteUpdateMs >= 500 || lastRouteResult == null ||
            lastRouteWasAssisted != routeAssisted || (!routeAssisted && (s.gnssFixAgeS > 2.5 || s.sigmaM > 20))) {
            lastRouteResult = if (routeAssisted) {
                routeGuidance?.updateAssisted(position, assistance!!.uncertaintyM, s.heading, s.speed)
            } else {
                routeGuidance?.update(position, true, s.sigmaM, s.gnssFixAgeS, s.heading, s.speed)
            }
            lastRouteUpdateMs = now
            lastRouteWasAssisted = routeAssisted
        }
        val result = lastRouteResult ?: return
        when (result.status) {
            RouteGuidance.Status.READY -> {
                val distance = if (result.distanceM >= 1000) "%.1f km".format(result.distanceM / 1000)
                    else "%.0f m".format(result.distanceM / 10 * 10)
                headline.text = "In $distance · ${result.instruction}"
                guidance.text = if (routeAssisted) {
                    "Route-assisted estimate · %.1f km remaining · Follow road signs".format(result.remainingM / 1000)
                } else {
                    "%.1f km remaining · Follow road signs".format(result.remainingM / 1000)
                }
                val cue = TurnCuePlanner.cue(result, routeAssisted)
                if (cue != null && s.speed > 1 && !voiceCoPilot.isMuted && cue.key !in spokenRouteCues) {
                    if (voiceCoPilot.speak(cue.text, flush = true, routeCue = true)) spokenRouteCues += cue.key
                }
            }
            RouteGuidance.Status.NEAR_DESTINATION -> {
                voiceCoPilot.stopRouteSpeech()
                headline.text = "Near your destination"
                guidance.text = "Check the entrance and park safely. Stop navigation to save your drive."
            }
            else -> {
                voiceCoPilot.stopRouteSpeech()
                headline.text = "Turn guidance paused"
                guidance.text = when (result.status) {
                    RouteGuidance.Status.NO_DETAIL -> "This saved route supports preview only. Choose a fresh, shorter route for live guidance."
                    RouteGuidance.Status.OFF_ROUTE -> "Away from the planned route. Park and choose another route; automatic rerouting is not enabled."
                    RouteGuidance.Status.WRONG_WAY -> "Travel direction differs from the route. Follow signs; reselect a route when parked."
                    RouteGuidance.Status.AMBIGUOUS -> "Route position is ambiguous near a crossing. Follow road signs."
                    else -> "Waiting for a fresh, reliable position. Follow road signs."
                }
            }
        }
    }

    private fun updateShell(s: Navigator.State) {
        val fieldTest = live?.blackoutTestSnapshot()
        if (fieldTest != null) updateLiveTestAction(fieldTest)
        outageBanner.visibility = if (s.mode == NavMode.BLACKOUT) View.VISIBLE else View.GONE
        // Speed stays in SI everywhere inside the estimator; the unit preference is applied
        // only at the moment of display.
        val speedDisplayed = Prefs.speedInPreferredUnit(this, s.speed.coerceAtLeast(0.0))
        setMetric(
            speedMetric,
            if (speedDisplayed.isFinite()) "%.0f".format(speedDisplayed) else "—",
            Prefs.speedLabel(this),
        )

        if (s.phase == Navigator.Phase.CALIBRATING) {
            val progress = (s.calibrationProgress * 100.0).roundToInt().coerceIn(0, 100)
            setMode("CALIBRATING", ACCENT_AMBER)
            eyebrow.text = "LEARNING VEHICLE ALIGNMENT"
            eyebrow.setTextColor(ACCENT_AMBER)
            if (progress >= 99) {
                headline.text = "Validating turn calibration"
                guidance.text = "Keep the mount fixed and include normal left and right turns; weak axis fits are no longer accepted."
            } else {
                headline.text = "Preparing resilient navigation"
                guidance.text = "Keep GPS available and drive normally while Waynova learns the phone mount."
            }
            setMetric(headingMetric, "—", "DEG")
            setMetric(uncertaintyMetric, progress.toString(), "% READY")
            calibrationProgress.visibility = View.VISIBLE
            calibrationProgress.progress = progress
            contextLine.text = if (progress >= 99)
                "EVIDENCE COLLECTED  •  WAITING FOR A STRONG YAW AXIS"
            else "CALIBRATION IN PROGRESS  •  ESTIMATOR ON DEVICE"
            if (s.sensorInterruptions > 0) {
                headline.text = "Sensor updates interrupted"
                guidance.text = "Keep the app open with GPS available to recalibrate. Start a new session for another test."
                contextLine.text = "PREVIOUS TEST INCOMPLETE"
            }
            return
        }

        calibrationProgress.visibility = View.GONE

        // AI Navigation Sentinel Telemetry Update
        val rep = s.sentinel
        if (rep != null) {
            val color = Color.parseColor(rep.badgeColorHex)
            sentinelChip.text = rep.shortBadgeText
            sentinelChip.setTextColor(color)
            sentinelChip.background = pill(SURFACE_SOFT, dp(10), withAlpha(color, 140))
        }

        // Avionics Voice Co-Pilot Event Triggers
        if (s.phase == Navigator.Phase.NAVIGATING && !announcedMissionStart) {
            announcedMissionStart = true
            voiceCoPilot.onMissionStart()
        }
        if (prevNavMode == NavMode.AIDED && s.mode == NavMode.BLACKOUT) {
            voiceCoPilot.onBlackoutEnter(s.speed.coerceAtLeast(0.0) * 3.6)
        } else if (prevNavMode == NavMode.BLACKOUT && s.mode == NavMode.REACQUIRING) {
            voiceCoPilot.onReacquiring()
        }
        if (rep != null) {
            if (rep.state == SentinelState.MULTIPATH_GUARDED && prevSentinelState != SentinelState.MULTIPATH_GUARDED) {
                voiceCoPilot.onMultipathRejected()
            } else if (rep.state == SentinelState.ROAD_TRANSIENT && prevSentinelState != SentinelState.ROAD_TRANSIENT) {
                voiceCoPilot.onRoadShock()
            }
            prevSentinelState = rep.state
        }
        prevNavMode = s.mode

        val headingDeg = if (s.heading.isFinite())
            ((Math.toDegrees(s.heading) + 360.0) % 360.0).roundToInt().toString() else "—"
        setMetric(headingMetric, headingDeg, "DEG")
        setMetric(
            uncertaintyMetric,
            if (s.sigmaM.isFinite()) "%.1f".format(s.sigmaM) else "—",
            "M 1σ",
        )

        val lag = hypot(s.east - s.displayEast, s.north - s.displayNorth)

        // The test begins withholding immediately, while the estimator correctly waits
        // three seconds before declaring a real outage. Make that intentional gap visible.
        if (fieldTest?.phase == FieldBlackoutPhase.WITHHOLDING && s.mode == NavMode.AIDED) {
            setMode("TEST WITHHOLDING", ACCENT_AMBER)
            eyebrow.text = "CONTROLLED GNSS LOSS"
            eyebrow.setTextColor(ACCENT_AMBER)
            headline.text = "Waiting for outage detection"
            guidance.text = "Real fixes are being saved as reference but hidden from the estimator."
            contextLine.text = "%.0f S LEFT  •  %d REFERENCE FIXES HIDDEN".format(
                fieldTest.remainingS, fieldTest.withheldFixes
            )
            return
        }

        when (s.mode) {
            NavMode.AIDED -> {
                setMode("GNSS AIDED", ACCENT_GREEN)
                eyebrow.text = "POSITION LOCKED"
                eyebrow.setTextColor(ACCENT_GREEN)
                headline.text = "GNSS + inertial fusion active"
                when (fieldTest?.phase) {
                    FieldBlackoutPhase.ARMED -> {
                        guidance.text = "Field outage is armed and will begin automatically."
                        contextLine.text = if (fieldTest.startsInS.isFinite())
                            "TEST STARTS IN %.0f S  •  KEEP DRIVING NORMALLY".format(
                                fieldTest.startsInS
                            ) else "TEST ARMED  •  WAITING FOR CALIBRATION"
                    }
                    FieldBlackoutPhase.COMPLETE -> {
                        guidance.text = "Controlled outage completed and satellite fusion recovered."
                        contextLine.text = "TEST COMPLETE  •  %.1f M END ERROR  •  %.1f S REACQUIRE".format(
                            fieldTest.endReferenceErrorM, fieldTest.reacquireS
                        )
                    }
                    else -> {
                        guidance.text = "Satellite fixes are strengthening the on-device estimate."
                        contextLine.text = "${s.gnssFixesReceived} PHONE FIXES  •  " +
                            "${s.gnssUpdates} FUSED  •  ${s.rejectedFixes} FILTER REJECTS"
                    }
                }
            }
            NavMode.BLACKOUT -> {
                setMode("GNSS DENIED", ACCENT_AMBER)
                eyebrow.text = "RESILIENT MODE"
                eyebrow.setTextColor(ACCENT_AMBER)
                headline.text = "Estimating through signal loss"
                guidance.text = "Position is estimated, not GPS-confirmed. Drift grows during the outage."
                contextLine.text = if (fieldTest?.phase == FieldBlackoutPhase.WITHHOLDING)
                    "%.0f S LEFT  •  %.1f M REF ERROR  •  %.0f M TRAVELLED".format(
                        fieldTest.remainingS, fieldTest.referenceErrorM, s.blackoutDistanceM
                    ) else "GNSS AGE %.1f S  •  DARK %.1f S  •  %.0f M TRAVELLED".format(
                        s.gnssFixAgeS, s.blackoutS, s.blackoutDistanceM
                    )
            }
            NavMode.REACQUIRING -> {
                setMode("RE-ACQUIRING", ACCENT_BLUE)
                eyebrow.text = "SIGNAL RETURNED"
                eyebrow.setTextColor(ACCENT_BLUE)
                headline.text = "Blending back to GNSS"
                guidance.text = "The estimate is corrected immediately while the display returns smoothly."
                contextLine.text = ("GNSS AGE %.1f S  •  DISPLAY CORRECTION %.1f M  •  " +
                    "${s.gnssUpdates} FUSED").format(s.gnssFixAgeS, lag)
            }
        }
    }

    private fun setMetric(metric: MetricViews, value: String, unit: String) {
        metric.value.text = value
        metric.unit.text = unit
    }

    private fun setMode(label: String, color: Int) {
        modeBadge.text = "●  $label"
        modeBadge.setTextColor(color)
        modeBadge.background = accentChip(color, 14)
        // The whole status panel picks up the mode colour, so a glance at the glass edge
        // is enough to tell aided from denied without reading any text.
        bottomPanel.background = glass(26, SURFACE_STRONG, Glass.alpha(color, 0.5f))
        bottomPanel.glow(color, 18f)
    }

    private fun showIdleState() {
        outageBanner.visibility = View.GONE
        setMode("STANDBY", TEXT_SECONDARY)
        eyebrow.text = "READY"
        eyebrow.setTextColor(ACCENT_BLUE)
        headline.text = "Ready to check your position"
        guidance.text = "Mount the phone while parked. Start navigation to check GPS and calibrate before relying on guidance."
        setMetric(speedMetric, "—", Prefs.speedLabel(this))
        setMetric(headingMetric, "—", "DEG")
        setMetric(uncertaintyMetric, "—", "M 1σ")
        contextLine.text = "GPS + CALIBRATION CHECKS START WITH NAVIGATION"
        calibrationProgress.visibility = View.GONE
        sentinelChip.text = "● SENTINEL: STANDBY"
        sentinelChip.setTextColor(TEXT_SECONDARY)
        sentinelChip.background = pill(SURFACE_SOFT, dp(10), BORDER_SUBTLE)
        prevNavMode = NavMode.AIDED
        prevSentinelState = SentinelState.NOMINAL
        announcedMissionStart = false
        setActions(replayRunning = false, liveRunning = false)
    }

    private fun showStartingState(mode: String, message: String) {
        setMode(mode, ACCENT_BLUE)
        eyebrow.text = "INITIALIZING"
        eyebrow.setTextColor(ACCENT_BLUE)
        headline.text = "Starting Waynova"
        guidance.text = message
        contextLine.text = "LOADING MODEL  •  CHECKING SENSOR STREAM"
        calibrationProgress.visibility = View.GONE
    }

    private fun showPausedState(
        title: String,
        message: String,
        detail: String = "ESTIMATOR STOPPED  •  TRACK RETAINED",
    ) {
        setMode("PAUSED", TEXT_SECONDARY)
        setMetric(speedMetric, "—", Prefs.speedLabel(this))
        setMetric(headingMetric, "—", "DEG")
        setMetric(uncertaintyMetric, "—", "M 1σ")
        eyebrow.text = "SESSION PAUSED"
        eyebrow.setTextColor(TEXT_SECONDARY)
        headline.text = title
        guidance.text = message
        contextLine.text = detail
        calibrationProgress.visibility = View.GONE
    }

    private fun showFailure(message: String) {
        setMode("ATTENTION", ACCENT_RED)
        eyebrow.text = "COULD NOT CONTINUE"
        eyebrow.setTextColor(ACCENT_RED)
        headline.text = "Navigation needs attention"
        guidance.text = message
        contextLine.text = "CHECK PERMISSIONS, MODEL ASSET, AND SENSOR AVAILABILITY"
        calibrationProgress.visibility = View.GONE
    }

    private fun setActions(replayRunning: Boolean, liveRunning: Boolean) {
        if ((replayRunning || liveRunning) && Prefs.keepScreenOn(this)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        liveButton.text = if (liveRunning) "Stop navigation" else "Start live navigation"
        liveButton.isEnabled = !replayRunning
        liveButton.alpha = if (liveButton.isEnabled) 1f else 0.42f
        if (liveRunning) {
            updateLiveTestAction(live?.blackoutTestSnapshot() ?: FieldBlackoutSnapshot())
        } else {
            replayButton.text = if (replayRunning) "Stop demo" else "Replay demo"
            replayButton.isEnabled = true
            replayButton.alpha = 1f
        }
    }

    private fun updateLiveTestAction(test: FieldBlackoutSnapshot) {
        when (test.phase) {
            FieldBlackoutPhase.OFF -> {
                replayButton.text = "Arm 60s test"
                replayButton.isEnabled = true
            }
            FieldBlackoutPhase.ARMED -> {
                replayButton.text = "Cancel test"
                replayButton.isEnabled = true
            }
            FieldBlackoutPhase.WITHHOLDING -> {
                replayButton.text = "Test ${test.remainingS.roundToInt()}s"
                replayButton.isEnabled = false
            }
            FieldBlackoutPhase.RECOVERING -> {
                replayButton.text = "Recovering"
                replayButton.isEnabled = false
            }
            FieldBlackoutPhase.COMPLETE -> {
                replayButton.text = "Test complete"
                replayButton.isEnabled = false
            }
            FieldBlackoutPhase.INTERRUPTED -> {
                replayButton.text = "Test interrupted"
                replayButton.isEnabled = false
            }
        }
        replayButton.alpha = if (replayButton.isEnabled) 1f else 0.56f
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    override fun onStart() {
        super.onStart()
        activityStarted = true
        googleMapRenderer?.onStart()
        mapLibreRenderer?.onStart()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        googleMapRenderer?.onResume()
        mapLibreRenderer?.onResume()
    }

    override fun onPause() {
        activityResumed = false
        mapLibreRenderer?.onPause()
        googleMapRenderer?.onPause()
        super.onPause()
    }

    override fun onStop() {
        // This prototype does not run a background navigation service. Close and save
        // explicitly instead of silently carrying stale IMU state while Android suspends it.
        if (live != null) {
            live?.interruptTest()
            stopLive()?.let(::showSavedSession)
            setActions(replayRunning = false, liveRunning = false)
            guidance.text = "Session saved because the app left the foreground. Start a new session when ready."
        }
        if (replayThread != null) {
            stopReplay()
            showPausedState("Demo paused", "Return to Replay demo to restart the recorded demonstration.")
        }
        activityStarted = false
        mapLibreRenderer?.onStop()
        googleMapRenderer?.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        googleMapRenderer?.onLowMemory()
        mapLibreRenderer?.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        googleMapRenderer?.onSaveInstanceState(outState)
        mapLibreRenderer?.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        stopRequested = true
        stopLive()
        replayThread?.join(1000)
        googleMapRenderer?.onDestroy()
        mapLibreRenderer?.onDestroy()
        voiceCoPilot.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val REPLAY_ASSET = "replay_M_seg00.csv"
        const val SPEED_FACTOR = 4.0
        const val FIELD_TEST_DURATION_S = 60.0
        const val FIELD_TEST_LEAD_IN_S = 15.0

        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        // Liquid-glass palette, shared with org.neuronavx.logger.ui.Glass. Panels that
        // float over the live map keep a high-opacity fill so route colours underneath
        // can never make the telemetry unreadable.
        val NAV_BACKGROUND = Glass.BG_DEEP
        val SURFACE_STRONG = Glass.PANEL_FILL_OVER_MAP
        val SURFACE_SOFT = Glass.PANEL_FILL
        val BORDER_SUBTLE = Glass.BORDER
        val BORDER_PROMINENT = Glass.BORDER_BRIGHT
        val TEXT_PRIMARY = Glass.TEXT_PRIMARY
        val TEXT_SECONDARY = Glass.TEXT_SECONDARY
        val TEXT_MUTED = Glass.TEXT_MUTED
        val ACCENT_BLUE = Glass.SKY
        val ACCENT_GREEN = Glass.EMERALD
        val ACCENT_AMBER = Glass.AMBER
        val ACCENT_RED = Glass.RED
        val ACCENT_WARM_GOLD = Glass.TEAL
    }
}
