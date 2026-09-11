package org.neuronavx.logger

import android.content.res.ColorStateList
import android.Manifest
import android.app.AlertDialog
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
import org.neuronavx.logger.nav.EnuMapProjection
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
    private var plannedRoute: RoutePlan? = null
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
        configureWindow()
        setContentView(buildNavigationShell())
        googleMapRenderer?.onCreate(savedInstanceState)
        showIdleState()
        prepareBundledOfflineMap()
        runCatching {
            val cache = File(filesDir, "planned_route.json")
            if (cache.exists() && cache.length() <= 2_000_000)
                RoutePlan.fromCache(JSONObject(cache.readText())) else null
        }.getOrNull()?.let { applyRoute(it, save = false) }
        if (savedInstanceState == null && intent.getBooleanExtra("start_demo", false)) onReplay()
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
                topContentInsetPx = dp(126),
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
            // The canvas remains visible behind the overlays but keeps important content
            // out from under the header and the telemetry panel.
            setPadding(dp(18), dp(126), dp(18), dp(328))
            contentDescription = "Waynova estimated track and uncertainty map"
        }
        root.addView(map, FrameLayout.LayoutParams(MATCH, MATCH))

        root.addView(buildHeader(), FrameLayout.LayoutParams(MATCH, dp(68)).apply {
            gravity = Gravity.TOP
            setMargins(dp(16), dp(12), dp(16), 0)
        })

        modeBadge = label("●  STANDBY", 11f, TEXT_PRIMARY, Typeface.BOLD).apply {
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            setPadding(dp(13), 0, dp(13), 0)
            background = pill(SURFACE_STRONG, dp(18), BORDER_SUBTLE)
        }
        root.addView(modeBadge, FrameLayout.LayoutParams(WRAP, dp(36)).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(dp(18), dp(92), 0, 0)
        })

        mapSourceBadge = label(
            if (googleMapRenderer != null) "MAP CONNECTING" else "OFFLINE PREPARING",
            10f, TEXT_MUTED, Typeface.BOLD,
        ).apply {
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            background = pill(Color.argb(205, 11, 18, 29), dp(18), BORDER_SUBTLE)
            isClickable = true
            isFocusable = true
            contentDescription = "Switch map renderer; long press to import an offline map"
            setOnClickListener { cycleMapRenderer() }
            setOnLongClickListener {
                chooseOfflineMap()
                true
            }
        }
        root.addView(mapSourceBadge, FrameLayout.LayoutParams(WRAP, dp(36)).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(0, dp(92), dp(18), 0)
        })

        recenterButton = actionText("⌖", 25f, dp(48)).apply {
            visibility = View.GONE
            contentDescription = "Recenter map on Waynova position"
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                if (googleMapActive) googleMapRenderer?.recenter()
                if (mapLibreActive) mapLibreRenderer?.recenter()
            }
        }
        root.addView(recenterButton, FrameLayout.LayoutParams(dp(48), dp(48)).apply {
            gravity = Gravity.END or Gravity.BOTTOM
            setMargins(0, 0, dp(18), dp(342))
        })

        mapAttribution = label(
            "MapLibre  ·  © OpenStreetMap  ·  Protomaps",
            9f, TEXT_SECONDARY, Typeface.BOLD,
        ).apply {
            letterSpacing = 0.03f
            gravity = Gravity.CENTER
            setPadding(dp(9), 0, dp(9), 0)
            background = pill(Color.argb(205, 11, 18, 29), dp(12), BORDER_SUBTLE)
            visibility = View.GONE
            contentDescription = "Map data OpenStreetMap; map tiles Protomaps; renderer MapLibre"
        }
        root.addView(mapAttribution, FrameLayout.LayoutParams(WRAP, dp(28)).apply {
            gravity = Gravity.START or Gravity.BOTTOM
            setMargins(dp(18), 0, 0, dp(344))
        })

        routeButton = actionButton("⌕  Find destination / directions", primary = false).apply {
            contentDescription = "Find destination or view saved driving directions"
            setOnClickListener { onRouteAction() }
        }
        root.addView(routeButton, FrameLayout.LayoutParams(MATCH, dp(48)).apply {
            gravity = Gravity.TOP
            setMargins(dp(18), dp(140), dp(18), 0)
        })
        val bottomPanel = buildBottomPanel()
        root.addView(bottomPanel, FrameLayout.LayoutParams(MATCH, WRAP).apply {
            gravity = Gravity.BOTTOM
            setMargins(dp(12), 0, dp(12), dp(12))
        })
        bottomPanel.addOnLayoutChangeListener { _, _, top, _, _, _, _, _, _ ->
            val bottomInset = root.height - root.paddingBottom - top + dp(12)
            mapBottomInsetPx = bottomInset
            map.setPadding(dp(18), dp(198), dp(18), bottomInset)
            googleMapRenderer?.setContentInsets(dp(198), bottomInset)
            mapLibreRenderer?.setContentInsets(dp(198), bottomInset)
            (recenterButton.layoutParams as FrameLayout.LayoutParams).also {
                it.bottomMargin = bottomInset + dp(12); recenterButton.layoutParams = it
            }
            (mapAttribution.layoutParams as FrameLayout.LayoutParams).also {
                it.bottomMargin = bottomInset + dp(18); mapAttribution.layoutParams = it
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
            setPadding(dp(16), 0, dp(16), 0)
            addHeaderView(label("%.1f km · about %.0f min\nSaved plan · no live turn alerts or traffic".format(
                route.distanceM / 1000, route.durationS / 60), 14f, ACCENT_GREEN, Typeface.BOLD).apply {
                setPadding(dp(8), dp(10), dp(8), dp(16))
            }, null, false)
            addFooterView(label("Mint = planned route · blue/orange = estimated track\nRoutes: OSRM · © OpenStreetMap (ODbL)\nFollow road signs. Set up only while parked.",
                11f, TEXT_MUTED, Typeface.NORMAL).apply {
                setPadding(dp(8), dp(14), dp(8), dp(10))
            }, null, false)
            adapter = object : android.widget.BaseAdapter() {
                override fun getCount() = route.steps.size
                override fun getItem(position: Int) = route.steps[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun isEnabled(position: Int) = false
                override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                    val row = (convertView as? LinearLayout) ?: LinearLayout(this@NavigationActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), dp(14), dp(12), dp(14))
                        addView(label("", 14f, TEXT_PRIMARY, Typeface.BOLD))
                        addView(label("", 12f, TEXT_SECONDARY, Typeface.NORMAL).apply { setPadding(0, dp(5), 0, 0) })
                    }
                    val step = route.steps[position]
                    (row.getChildAt(0) as TextView).text = "${position + 1}. ${step.instruction}"
                    (row.getChildAt(1) as TextView).text = if (step.distanceM > 0)
                        "Continue for %.0f m".format(step.distanceM) else "Destination"
                    row.background = pill(if (position % 2 == 0) SURFACE_SOFT else NAV_BACKGROUND, dp(12))
                    return row
                }
            }
        }
        AlertDialog.Builder(this).setTitle(route.destination)
            .setView(directions)
            .setPositiveButton("Done", null)
            .setNeutralButton("New route") { _, _ -> openRouteSearch() }
            .setNegativeButton("Clear") { _, _ ->
                plannedRoute = null
                File(filesDir, "planned_route.json").delete()
                googleMapRenderer?.setPlannedRoute(emptyList())
                mapLibreRenderer?.setPlannedRoute(emptyList())
                map.setPlannedRoute(emptyList(), Double.NaN, Double.NaN)
                routeButton.text = "⌕  Find destination / directions"
                refreshMapAttribution()
            }.show()
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
        plannedRoute = route
        if (live == null && replayThread == null) {
            latestMapSnapshot = null
            googleMapRenderer?.clear(); mapLibreRenderer?.clear(); map.clear()
            if (mapLibreReady) showMapLibreBasemap()
        }
        googleMapRenderer?.setPlannedRoute(route.points)
        mapLibreRenderer?.setPlannedRoute(route.points)
        map.setPlannedRoute(route.points, route.points.first().lat, route.points.first().lon)
        routeButton.text = "↗  OSRM plan · %.1f km · Directions".format(route.distanceM / 1000)
        refreshMapAttribution()
        if (save) runCatching { File(filesDir, "planned_route.json").writeText(route.toJson().toString()) }
            .onFailure { Toast.makeText(this, "Route loaded but could not be saved for later.", Toast.LENGTH_LONG).show() }
    }

    private fun buildHeader(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(14), dp(8))
            background = pill(Color.argb(238, 13, 21, 33), dp(22), BORDER_SUBTLE)
            elevation = dp(8).toFloat()

            addView(actionText("‹", 29f, dp(44)).apply {
                contentDescription = "Back"
                setOnClickListener { finish() }
            })

            addView(LinearLayout(this@NavigationActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, 0, 0)
                addView(label("WAYNOVA", 17f, TEXT_PRIMARY, Typeface.BOLD).apply {
                    letterSpacing = 0.11f
                })
                addView(label("RESILIENT POSITIONING", 9f, TEXT_MUTED, Typeface.BOLD).apply {
                    letterSpacing = 0.18f
                })
            }, LinearLayout.LayoutParams(0, MATCH, 1f))

            addView(label("W", 18f, ACCENT_BLUE, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                letterSpacing = 0.08f
                background = pill(Color.parseColor("#162A45"), dp(14), Color.parseColor("#31547C"))
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
    }

    private fun buildBottomPanel(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(16))
            background = pill(Color.argb(250, 14, 22, 34), dp(28), BORDER_SUBTLE)
            elevation = dp(16).toFloat()

            addView(View(this@NavigationActivity).apply {
                background = pill(Color.parseColor("#40516A"), dp(3))
            }, LinearLayout.LayoutParams(dp(38), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12)
            })

            eyebrow = label("READY", 10f, ACCENT_BLUE, Typeface.BOLD).apply {
                letterSpacing = 0.16f
            }
            addView(eyebrow)

            headline = label("Positioning that keeps going", 23f, TEXT_PRIMARY, Typeface.BOLD)
            addView(headline, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })

            guidance = label(
                "Start a live session or run the demonstration route.",
                13f, TEXT_SECONDARY, Typeface.NORMAL
            ).apply { setLineSpacing(0f, 1.15f) }
            addView(guidance, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })

            calibrationProgress = ProgressBar(
                this@NavigationActivity, null, android.R.attr.progressBarStyleHorizontal
            ).apply {
                max = 100
                progress = 0
                progressTintList = ColorStateList.valueOf(ACCENT_AMBER)
                progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#26364A"))
                visibility = View.GONE
            }
            addView(calibrationProgress, LinearLayout.LayoutParams(MATCH, dp(4)).apply {
                topMargin = dp(12)
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
            addView(metrics, LinearLayout.LayoutParams(MATCH, dp(72)).apply { topMargin = dp(14) })

            contextLine = label("ESTIMATOR READY  •  TRACK 0 PTS", 10f, TEXT_MUTED, Typeface.BOLD).apply {
                letterSpacing = 0.08f
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 3
            }
            addView(contextLine, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dp(8); bottomMargin = dp(8)
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
            }
            actions.addView(liveButton, LinearLayout.LayoutParams(0, dp(54), 1f))
            actions.addView(Space(this@NavigationActivity), LinearLayout.LayoutParams(dp(10), 1))
            actions.addView(replayButton, LinearLayout.LayoutParams(dp(126), dp(54)))
            addView(actions, LinearLayout.LayoutParams(MATCH, dp(54)).apply { topMargin = dp(6) })
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
            setPadding(dp(11), dp(8), dp(8), dp(7))
            background = pill(SURFACE_SOFT, dp(16), BORDER_SUBTLE)
            addView(label(title, 8f, TEXT_MUTED, Typeface.BOLD).apply { letterSpacing = 0.13f })
            val line = LinearLayout(this@NavigationActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                value = label("—", 21f, TEXT_PRIMARY, Typeface.BOLD)
                unit = label("", 8f, TEXT_MUTED, Typeface.BOLD).apply {
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
            background = ripple(SURFACE_SOFT, dp(15), BORDER_SUBTLE)
            layoutParams = LinearLayout.LayoutParams(width, dp(44))
        }

    private fun actionButton(text: String, primary: Boolean): TextView =
        label(text, 13f, if (primary) Color.WHITE else TEXT_PRIMARY, Typeface.BOLD).apply {
            this.text = text
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            letterSpacing = 0.02f
            background = if (primary) ripple(ACCENT_BLUE, dp(17), Color.parseColor("#74AFFF"))
                else ripple(SURFACE_SOFT, dp(17), Color.parseColor("#3C5069"))
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
        mapSourceBadge.text = "GOOGLE MAP  ◆"
        mapSourceBadge.setTextColor(ACCENT_GREEN)
        mapSourceBadge.background = pill(
            Color.argb(225, 13, 21, 33), dp(18), withAlpha(ACCENT_GREEN, 125)
        )
        recenterButton.visibility = View.VISIBLE
    }

    private fun showLocalFallback() {
        googleMapActive = false
        mapLibreActive = false
        googleMapRenderer?.view?.visibility = View.INVISIBLE
        mapLibreRenderer?.view?.visibility = View.INVISIBLE
        map.visibility = View.VISIBLE
        refreshMapAttribution()
        mapSourceBadge.text = "LOCAL FALLBACK"
        mapSourceBadge.setTextColor(TEXT_MUTED)
        mapSourceBadge.background = pill(Color.argb(225, 13, 21, 33), dp(18), BORDER_SUBTLE)
        recenterButton.visibility = View.GONE
    }

    private fun showMapLibreBasemap() {
        if (!mapLibreReady) return
        val snapshot = latestMapSnapshot
        if (snapshot != null && mapLibreRenderer?.covers(snapshot) == false) {
            showLocalFallback()
            mapSourceBadge.text = "OUTSIDE OFFLINE AREA"
            return
        }
        googleMapActive = false
        mapLibreActive = true
        googleMapRenderer?.view?.visibility = View.INVISIBLE
        mapLibreRenderer?.view?.visibility = View.VISIBLE
        map.visibility = View.INVISIBLE
        refreshMapAttribution()
        val size = OfflineMapStore.installed(this)?.bytes ?: 0L
        mapSourceBadge.text = "OFFLINE GN  ·  ${OfflineMapStore.formatBytes(size)}"
        mapSourceBadge.setTextColor(ACCENT_GREEN)
        mapSourceBadge.background = pill(
            Color.argb(225, 13, 21, 33), dp(18), withAlpha(ACCENT_GREEN, 125)
        )
        recenterButton.visibility = View.VISIBLE
    }

    private fun refreshMapAttribution() {
        val hasRoute = plannedRoute != null && replayThread == null
        mapAttribution.text = if (mapLibreActive) "MapLibre · © OpenStreetMap · Protomaps"
            else "Routes: OSRM · © OpenStreetMap"
        mapAttribution.visibility = if (mapLibreActive || hasRoute) View.VISIBLE else View.GONE
    }

    private fun cycleMapRenderer() {
        mapSourceBadge.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        when {
            googleMapActive && mapLibreReady -> showMapLibreBasemap()
            googleMapActive -> showLocalFallback()
            mapLibreActive -> showLocalFallback()
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
            topContentInsetPx = dp(198),
            bottomContentInsetPx = mapBottomInsetPx.takeIf { it > 0 } ?: dp(328),
            onBasemapReady = {
                ui.post {
                    mapLibreReady = true
                    if (selectWhenReady || !googleMapReady) showMapLibreBasemap()
                }
            },
            onCoverageChanged = { covered ->
                ui.post {
                    if (!covered && mapLibreActive) {
                        showLocalFallback()
                        mapSourceBadge.text = "OUTSIDE OFFLINE AREA"
                    }
                }
            },
        )
        mapLibreRenderer = renderer
        if (replayThread == null) plannedRoute?.let { renderer.setPlannedRoute(it.points) }
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
        stopLive()
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
        stopRequested = true
        replayThread?.join(2000)
        replayThread = null
        setActions(replayRunning = false, liveRunning = false)
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
        plannedRoute?.let { applyRoute(it, save = false) }
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
            live = LiveSource(this, nav) { state -> render(state, force = false) }.also { it.start() }
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
        val e = nav.trackEast.toDoubleArray()
        val n = nav.trackNorth.toDoubleArray()
        val de = nav.displayEastTrack.toDoubleArray()
        val dn = nav.displayNorthTrack.toDoubleArray()
        val dark = nav.trackDark.toBooleanArray()
        ui.post {
            if (isDestroyed) return@post
            if (live != null) plannedRoute?.let { map.setPlannedRoute(it.points, nav.originLat, nav.originLon) }
            map.submit(
                e, n, de, dn, dark, state.displayEast, state.displayNorth,
                state.heading, state.sigmaM, state.mode != NavMode.AIDED,
            )
            val snapshot = NavigationMapSnapshot(
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
            latestMapSnapshot = snapshot
            googleMapRenderer?.submit(snapshot)
            mapLibreRenderer?.submit(snapshot)
            if (failure == null) updateShell(state)
        }
    }

    private fun updateShell(s: Navigator.State) {
        val fieldTest = live?.blackoutTestSnapshot()
        if (fieldTest != null) updateLiveTestAction(fieldTest)
        val speedKmh = (s.speed.coerceAtLeast(0.0) * 3.6)
        setMetric(speedMetric, if (speedKmh.isFinite()) "%.0f".format(speedKmh) else "—", "KM/H")

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
        modeBadge.background = pill(Color.argb(225, 13, 21, 33), dp(18), withAlpha(color, 135))
    }

    private fun showIdleState() {
        setMode("STANDBY", TEXT_SECONDARY)
        eyebrow.text = "READY"
        eyebrow.setTextColor(ACCENT_BLUE)
        headline.text = "Positioning that keeps going"
        guidance.text = "Start a live session or run the demonstration route."
        setMetric(speedMetric, "—", "KM/H")
        setMetric(headingMetric, "—", "DEG")
        setMetric(uncertaintyMetric, "—", "M 1σ")
        contextLine.text = "ESTIMATOR READY  •  PROCESSING STAYS ON DEVICE"
        calibrationProgress.visibility = View.GONE
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
        setMetric(speedMetric, "—", "KM/H")
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
        if (replayRunning || liveRunning) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        super.onDestroy()
    }

    private companion object {
        const val REPLAY_ASSET = "replay_M_seg00.csv"
        const val SPEED_FACTOR = 4.0
        const val FIELD_TEST_DURATION_S = 60.0
        const val FIELD_TEST_LEAD_IN_S = 15.0

        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        val NAV_BACKGROUND = Color.parseColor("#070B12")
        val SURFACE_STRONG = Color.parseColor("#E60D1521")
        val SURFACE_SOFT = Color.parseColor("#172334")
        val BORDER_SUBTLE = Color.parseColor("#2A3A50")
        val TEXT_PRIMARY = Color.parseColor("#F5F8FC")
        val TEXT_SECONDARY = Color.parseColor("#B8C4D4")
        val TEXT_MUTED = Color.parseColor("#7F90A7")
        val ACCENT_BLUE = Color.parseColor("#4C9BFF")
        val ACCENT_GREEN = Color.parseColor("#45D69E")
        val ACCENT_AMBER = Color.parseColor("#FFAA4C")
        val ACCENT_RED = Color.parseColor("#FF657A")
    }
}
