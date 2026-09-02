package org.neuronavx.logger.nav

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.Property.LINE_CAP_ROUND
import org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.File
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Fully offline MapLibre presentation adapter backed by one local PMTiles archive.
 *
 * As with [GoogleMapRenderer], MapLibre is only a basemap. The route, vehicle heading and
 * uncertainty polygon below are derived exclusively from NeuroNav's estimator snapshot.
 */
class MapLibreOfflineRenderer(
    private val context: Context,
    private val archive: File,
    private val topContentInsetPx: Int,
    private val bottomContentInsetPx: Int,
    private val onBasemapReady: () -> Unit,
    private val onCoverageChanged: (covered: Boolean) -> Unit,
) {
    val view: MapView

    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var pending: NavigationMapSnapshot? = null
    private var following = true
    private var cameraInitialized = false
    private var lastCameraUpdateMs = 0L
    private var lastCoverage: Boolean? = null
    private var lastOutage = false

    private var rawSource: GeoJsonSource? = null
    private var aidedSource: GeoJsonSource? = null
    private var deniedSource: GeoJsonSource? = null
    private var uncertaintySource: GeoJsonSource? = null
    private var headingSource: GeoJsonSource? = null
    private var markerSource: GeoJsonSource? = null
    private var uncertaintyFill: FillLayer? = null
    private var uncertaintyLine: LineLayer? = null
    private var headingLine: LineLayer? = null
    private var markerLayer: CircleLayer? = null

    init {
        MapLibre.getInstance(context.applicationContext)
        view = MapView(context)
    }

    fun onCreate(savedState: Bundle?) {
        view.onCreate(savedState)
        view.getMapAsync { map ->
            this.map = map
            configure(map)
            map.setStyle(Style.Builder().fromJson(localStyleJson())) { loadedStyle ->
                style = loadedStyle
                installEstimatorLayers(loadedStyle)
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(GREATER_NOIDA_CENTER_LAT, GREATER_NOIDA_CENTER_LON))
                    .zoom(CITY_ZOOM)
                    .build()
                pending?.let(::draw)
                onBasemapReady()
            }
        }
    }

    private fun configure(map: MapLibreMap) {
        map.setPadding(0, topContentInsetPx, 0, bottomContentInsetPx)
        map.uiSettings.apply {
            setAllGesturesEnabled(true)
            setCompassEnabled(true)
            setCompassGravity(Gravity.TOP or Gravity.END)
            setCompassMargins(0, topContentInsetPx, dp(18), 0)
            // NavigationActivity supplies a compact, always-visible combined attribution.
            setLogoEnabled(false)
            setAttributionEnabled(false)
        }
        map.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                following = false
            }
        }
    }

    private fun localStyleJson(): String {
        val root = context.assets.open(STYLE_ASSET).bufferedReader().use { it.readText() }
        val json = JSONObject(root)
        json.getJSONObject("sources").getJSONObject("protomaps").apply {
            remove("tiles")
            put("url", "pmtiles://file://${archive.absolutePath}")
        }
        json.put("glyphs", "asset://offline_map/glyphs/{fontstack}/{range}.pbf")
        json.put("sprite", "asset://offline_map/sprites/dark")
        return json.toString()
    }

    private fun installEstimatorLayers(style: Style) {
        rawSource = GeoJsonSource(RAW_SOURCE, EMPTY_FEATURE_COLLECTION).also(style::addSource)
        aidedSource = GeoJsonSource(AIDED_SOURCE, EMPTY_FEATURE_COLLECTION).also(style::addSource)
        deniedSource = GeoJsonSource(DENIED_SOURCE, EMPTY_FEATURE_COLLECTION).also(style::addSource)
        uncertaintySource = GeoJsonSource(UNCERTAINTY_SOURCE, EMPTY_FEATURE_COLLECTION)
            .also(style::addSource)
        headingSource = GeoJsonSource(HEADING_SOURCE, EMPTY_FEATURE_COLLECTION).also(style::addSource)
        markerSource = GeoJsonSource(MARKER_SOURCE, EMPTY_FEATURE_COLLECTION).also(style::addSource)

        style.addLayer(LineLayer(RAW_LAYER, RAW_SOURCE).withProperties(
            lineColor(withAlpha(AIDED_COLOR, 145)), lineWidth(2f), lineOpacity(0.75f),
            lineCap(LINE_CAP_ROUND), lineJoin(LINE_JOIN_ROUND),
        ))
        style.addLayer(LineLayer(AIDED_GLOW_LAYER, AIDED_SOURCE).withProperties(
            lineColor(withAlpha(AIDED_COLOR, 65)), lineWidth(15f),
            lineCap(LINE_CAP_ROUND), lineJoin(LINE_JOIN_ROUND),
        ))
        style.addLayer(LineLayer(DENIED_GLOW_LAYER, DENIED_SOURCE).withProperties(
            lineColor(withAlpha(DENIED_COLOR, 65)), lineWidth(15f),
            lineCap(LINE_CAP_ROUND), lineJoin(LINE_JOIN_ROUND),
        ))
        style.addLayer(LineLayer(AIDED_LAYER, AIDED_SOURCE).withProperties(
            lineColor(AIDED_COLOR), lineWidth(7f),
            lineCap(LINE_CAP_ROUND), lineJoin(LINE_JOIN_ROUND),
        ))
        style.addLayer(LineLayer(DENIED_LAYER, DENIED_SOURCE).withProperties(
            lineColor(DENIED_COLOR), lineWidth(7f),
            lineCap(LINE_CAP_ROUND), lineJoin(LINE_JOIN_ROUND),
        ))
        uncertaintyFill = FillLayer(UNCERTAINTY_FILL_LAYER, UNCERTAINTY_SOURCE).withProperties(
            fillColor(AIDED_COLOR), fillOpacity(0.18f),
        ).also(style::addLayer)
        uncertaintyLine = LineLayer(UNCERTAINTY_LINE_LAYER, UNCERTAINTY_SOURCE).withProperties(
            lineColor(withAlpha(AIDED_COLOR, 190)), lineWidth(2f),
        ).also(style::addLayer)
        headingLine = LineLayer(HEADING_LAYER, HEADING_SOURCE).withProperties(
            lineColor(Color.WHITE), lineWidth(3f),
            lineCap(LINE_CAP_ROUND), lineJoin(LINE_JOIN_ROUND),
        ).also(style::addLayer)
        markerLayer = CircleLayer(MARKER_LAYER, MARKER_SOURCE).withProperties(
            circleColor(AIDED_COLOR), circleRadius(9f), circleOpacity(1f),
            circleStrokeColor(Color.WHITE), circleStrokeWidth(3f),
        ).also(style::addLayer)
    }

    fun submit(snapshot: NavigationMapSnapshot) {
        pending = snapshot
        draw(snapshot)
    }

    private fun draw(snapshot: NavigationMapSnapshot) {
        val style = style ?: return
        val marker = offlineMapMarkerOrNull(snapshot) ?: run {
            updateCoverage(false)
            return
        }
        val covered = marker.latitude in SOUTH..NORTH && marker.longitude in WEST..EAST
        updateCoverage(covered)
        if (!covered) return

        rawSource?.setGeoJson(lineFeature(projectPath(
            snapshot.originLat, snapshot.originLon,
            snapshot.estimateEast, snapshot.estimateNorth,
        )))
        val segments = projectSegments(snapshot)
        aidedSource?.setGeoJson(linesFeatureCollection(segments.filterNot { it.dark }.map { it.points }))
        deniedSource?.setGeoJson(linesFeatureCollection(segments.filter { it.dark }.map { it.points }))

        val radius = snapshot.sigmaM.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        uncertaintySource?.setGeoJson(
            if (radius > 0.0) polygonFeature(circle(marker, radius)) else EMPTY_FEATURE_COLLECTION
        )
        markerSource?.setGeoJson(pointFeature(marker))
        val heading = snapshot.headingRad.takeIf(Double::isFinite) ?: 0.0
        headingSource?.setGeoJson(lineFeature(listOf(
            marker,
            projected(
                marker.latitude, marker.longitude,
                HEADING_LENGTH_M * sin(heading),
                HEADING_LENGTH_M * cos(heading),
            ),
        )))
        updateOutageColors(snapshot.inOutage)
        updateCamera(marker, snapshot.headingRad)
        // Keep a strong reference to the loaded style while the native peer uses its sources.
        this.style = style
    }

    private fun updateOutageColors(inOutage: Boolean) {
        if (inOutage == lastOutage && cameraInitialized) return
        lastOutage = inOutage
        val color = if (inOutage) DENIED_COLOR else AIDED_COLOR
        uncertaintyFill?.setProperties(fillColor(color))
        uncertaintyLine?.setProperties(lineColor(withAlpha(color, 190)))
        markerLayer?.setProperties(circleColor(color))
    }

    private fun updateCamera(position: LatLng, headingRad: Double) {
        val map = map ?: return
        if (!following) return
        val now = System.currentTimeMillis()
        if (cameraInitialized && now - lastCameraUpdateMs < CAMERA_PERIOD_MS) return
        val camera = CameraPosition.Builder()
            .target(position)
            .zoom(NAVIGATION_ZOOM)
            .bearing(if (headingRad.isFinite()) Math.toDegrees(headingRad) else 0.0)
            .tilt(NAVIGATION_TILT)
            .build()
        val update = CameraUpdateFactory.newCameraPosition(camera)
        if (cameraInitialized) map.animateCamera(update, CAMERA_PERIOD_MS.toInt())
        else {
            map.moveCamera(update)
            cameraInitialized = true
        }
        lastCameraUpdateMs = now
    }

    fun recenter() {
        following = true
        cameraInitialized = false
        pending?.let(::draw)
    }

    fun clear() {
        pending = null
        listOf(rawSource, aidedSource, deniedSource, uncertaintySource, headingSource, markerSource)
            .forEach { it?.setGeoJson(EMPTY_FEATURE_COLLECTION) }
        following = true
        cameraInitialized = false
        lastCoverage = null
    }

    fun covers(snapshot: NavigationMapSnapshot): Boolean {
        val marker = offlineMapMarkerOrNull(snapshot) ?: return false
        return marker.latitude in SOUTH..NORTH && marker.longitude in WEST..EAST
    }

    private fun updateCoverage(covered: Boolean) {
        if (covered == lastCoverage) return
        lastCoverage = covered
        onCoverageChanged(covered)
    }

    private data class RouteSegment(val dark: Boolean, val points: List<LatLng>)

    private fun projectPath(
        originLat: Double,
        originLon: Double,
        east: DoubleArray,
        north: DoubleArray,
    ): List<LatLng> {
        val size = minOf(east.size, north.size)
        val stride = max(1, size / MAX_MAP_POINTS)
        return buildList {
            for (i in 0 until size) {
                if (i % stride != 0 && i != size - 1) continue
                if (!east[i].isFinite() || !north[i].isFinite()) continue
                add(projected(originLat, originLon, east[i], north[i]))
            }
        }
    }

    private fun projectSegments(snapshot: NavigationMapSnapshot): List<RouteSegment> {
        val size = minOf(snapshot.displayEast.size, snapshot.displayNorth.size)
        if (size == 0) return emptyList()
        val stride = max(1, size / MAX_MAP_POINTS)
        val segments = ArrayList<RouteSegment>()
        var dark = snapshot.dark.getOrElse(0) { false }
        var points = ArrayList<LatLng>()
        var previous: LatLng? = null
        for (i in 0 until size) {
            val east = snapshot.displayEast[i]
            val north = snapshot.displayNorth[i]
            if (!east.isFinite() || !north.isFinite()) continue
            val nextDark = snapshot.dark.getOrElse(i) { false }
            val changed = nextDark != dark
            if (changed) {
                if (points.isNotEmpty()) segments += RouteSegment(dark, points)
                points = ArrayList<LatLng>().apply { previous?.let(::add) }
                dark = nextDark
            }
            if (changed || i % stride == 0 || i == size - 1) {
                projected(snapshot.originLat, snapshot.originLon, east, north).also {
                    points += it
                    previous = it
                }
            }
        }
        if (points.isNotEmpty()) segments += RouteSegment(dark, points)
        return segments
    }

    private fun circle(center: LatLng, radiusM: Double): List<LatLng> = buildList {
        for (i in 0..CIRCLE_STEPS) {
            val angle = i * Math.PI * 2.0 / CIRCLE_STEPS
            add(projected(center.latitude, center.longitude, radiusM * sin(angle), radiusM * cos(angle)))
        }
    }

    private fun projected(lat: Double, lon: Double, east: Double, north: Double): LatLng {
        val point = EnuMapProjection.toLatLng(lat, lon, east, north)
        return LatLng(point.latitude, point.longitude)
    }

    private fun pointFeature(point: LatLng): String = JSONObject().apply {
        put("type", "Feature")
        put("properties", JSONObject())
        put("geometry", geometry("Point", JSONArray().put(point.longitude).put(point.latitude)))
    }.toString()

    private fun lineFeature(points: List<LatLng>): String =
        if (points.size < 2) EMPTY_FEATURE_COLLECTION else JSONObject().apply {
            put("type", "Feature")
            put("properties", JSONObject())
            put("geometry", geometry("LineString", coordinates(points)))
        }.toString()

    private fun polygonFeature(points: List<LatLng>): String = JSONObject().apply {
        put("type", "Feature")
        put("properties", JSONObject())
        put("geometry", geometry("Polygon", JSONArray().put(coordinates(points))))
    }.toString()

    private fun linesFeatureCollection(lines: List<List<LatLng>>): String = JSONObject().apply {
        put("type", "FeatureCollection")
        put("features", JSONArray().apply {
            lines.filter { it.size >= 2 }.forEach { points ->
                put(JSONObject().apply {
                    put("type", "Feature")
                    put("properties", JSONObject())
                    put("geometry", geometry("LineString", coordinates(points)))
                })
            }
        })
    }.toString()

    private fun coordinates(points: List<LatLng>) = JSONArray().apply {
        points.forEach { put(JSONArray().put(it.longitude).put(it.latitude)) }
    }

    private fun geometry(type: String, coordinates: JSONArray) = JSONObject().apply {
        put("type", type)
        put("coordinates", coordinates)
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun onStart() = view.onStart()
    fun onResume() = view.onResume()
    fun onPause() = view.onPause()
    fun onStop() = view.onStop()
    fun onLowMemory() = view.onLowMemory()
    fun onSaveInstanceState(outState: Bundle) = view.onSaveInstanceState(outState)
    fun onDestroy() = view.onDestroy()

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private companion object {
        const val STYLE_ASSET = "offline_map/style.json"
        const val SOUTH = 28.34
        const val WEST = 77.30
        const val NORTH = 28.68
        const val EAST = 77.70
        const val GREATER_NOIDA_CENTER_LAT = 28.51
        const val GREATER_NOIDA_CENTER_LON = 77.50
        const val CITY_ZOOM = 11.8
        const val NAVIGATION_ZOOM = 17.2
        const val NAVIGATION_TILT = 48.0
        const val CAMERA_PERIOD_MS = 450L
        const val HEADING_LENGTH_M = 20.0
        const val CIRCLE_STEPS = 48
        const val MAX_MAP_POINTS = 2500

        const val RAW_SOURCE = "nx-raw-source"
        const val AIDED_SOURCE = "nx-aided-source"
        const val DENIED_SOURCE = "nx-denied-source"
        const val UNCERTAINTY_SOURCE = "nx-uncertainty-source"
        const val HEADING_SOURCE = "nx-heading-source"
        const val MARKER_SOURCE = "nx-marker-source"
        const val RAW_LAYER = "nx-raw-layer"
        const val AIDED_GLOW_LAYER = "nx-aided-glow-layer"
        const val DENIED_GLOW_LAYER = "nx-denied-glow-layer"
        const val AIDED_LAYER = "nx-aided-layer"
        const val DENIED_LAYER = "nx-denied-layer"
        const val UNCERTAINTY_FILL_LAYER = "nx-uncertainty-fill-layer"
        const val UNCERTAINTY_LINE_LAYER = "nx-uncertainty-line-layer"
        const val HEADING_LAYER = "nx-heading-layer"
        const val MARKER_LAYER = "nx-marker-layer"

        const val EMPTY_FEATURE_COLLECTION = "{\"type\":\"FeatureCollection\",\"features\":[]}"
        val AIDED_COLOR = Color.parseColor("#4C9BFF")
        val DENIED_COLOR = Color.parseColor("#FF9F4A")
    }
}

/**
 * Convert a snapshot marker only after validating every value MapLibre's [LatLng] requires.
 *
 * Calibration states deliberately carry no local position. The map-source button can still
 * be pressed in that state, so this boundary must return null instead of passing NaN into
 * MapLibre and killing the live recorder before it writes its summary.
 */
internal fun offlineMapMarkerOrNull(snapshot: NavigationMapSnapshot): LatLng? {
    if (!snapshot.originLat.isFinite() || snapshot.originLat !in -90.0..90.0 ||
        !snapshot.originLon.isFinite() ||
        !snapshot.markerEast.isFinite() || !snapshot.markerNorth.isFinite()) return null
    val point = EnuMapProjection.toLatLng(
        snapshot.originLat, snapshot.originLon, snapshot.markerEast, snapshot.markerNorth,
    )
    if (!point.latitude.isFinite() || point.latitude !in -90.0..90.0 ||
        !point.longitude.isFinite()) return null
    return LatLng(point.latitude, point.longitude)
}
