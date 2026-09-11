package org.neuronavx.logger.nav

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import org.neuronavx.logger.R
import kotlin.math.cos
import kotlin.math.max

/**
 * Google Maps presentation adapter for NeuroNav-X.
 *
 * The Maps SDK never supplies the vehicle location here. Every polyline, circle and marker
 * comes from [Navigator]'s ENU estimate, projected back to latitude/longitude around the
 * estimator's own origin. This keeps Maps as a basemap rather than quietly replacing the
 * GNSS-denied navigation result with Android's location provider.
 */
class GoogleMapRenderer(
    private val context: Context,
    private var topContentInsetPx: Int,
    private var bottomContentInsetPx: Int,
    private val onBasemapReady: () -> Unit,
) {
    val view = MapView(context)

    private var googleMap: GoogleMap? = null
    private var pending: NavigationMapSnapshot? = null
    private var mapLoaded = false
    private var hasPosition = false
    private var readyReported = false
    private var following = true
    private var cameraInitialized = false
    private var lastCameraUpdateMs = 0L

    private var rawEstimate: Polyline? = null
    private var plannedRoute: List<RoutePoint> = emptyList()
    private var plannedLine: Polyline? = null

    fun setContentInsets(top: Int, bottom: Int) {
        topContentInsetPx = top; bottomContentInsetPx = bottom
        googleMap?.setPadding(0, top, 0, bottom)
    }

    fun setPlannedRoute(points: List<RoutePoint>) {
        plannedRoute = points
        val map = googleMap ?: return
        val line = plannedLine ?: map.addPolyline(PolylineOptions().width(6f)
            .color(Color.parseColor("#57DCB5")).zIndex(1f)).also { plannedLine = it }
        line.points = points.map { LatLng(it.lat, it.lon) }
        if (points.isNotEmpty() && pending == null) {
            val bounds = com.google.android.gms.maps.model.LatLngBounds.builder()
            points.forEach { bounds.include(LatLng(it.lat, it.lon)) }
            view.post { if (view.width > 0 && view.height > 0 && pending == null)
                runCatching { map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 48)) } }
        }
    }
    private val routeGlows = ArrayList<Polyline>()
    private val routeLines = ArrayList<Polyline>()
    private var uncertainty: Circle? = null
    private var vehicle: Marker? = null
    private var markerIsDark = false
    private val aidedIcon by lazy { vehicleIcon(Color.parseColor("#4C9BFF")) }
    private val deniedIcon by lazy { vehicleIcon(Color.parseColor("#FF9F4A")) }

    fun onCreate(savedState: Bundle?) {
        view.onCreate(savedState)
        view.getMapAsync { map ->
            googleMap = map
            configure(map)
            setPlannedRoute(plannedRoute)
            pending?.let(::draw)
            map.setOnMapLoadedCallback {
                mapLoaded = true
                reportReadyIfPossible()
            }
        }
    }

    private fun configure(map: GoogleMap) {
        map.mapType = GoogleMap.MAP_TYPE_NORMAL
        map.isBuildingsEnabled = true
        map.isTrafficEnabled = false
        map.setMapStyle(MapStyleOptions.loadRawResourceStyle(context, R.raw.map_style_neuronavx))
        map.setPadding(0, topContentInsetPx, 0, bottomContentInsetPx)
        map.uiSettings.apply {
            isMapToolbarEnabled = false
            isMyLocationButtonEnabled = false
            isZoomControlsEnabled = false
            isCompassEnabled = true
            isIndoorLevelPickerEnabled = false
            isScrollGesturesEnabled = true
            isZoomGesturesEnabled = true
            isRotateGesturesEnabled = true
            isTiltGesturesEnabled = true
        }
        map.setOnCameraMoveStartedListener { reason ->
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) following = false
        }
    }

    fun submit(snapshot: NavigationMapSnapshot) {
        pending = snapshot
        draw(snapshot)
    }

    private fun draw(snapshot: NavigationMapSnapshot) {
        val map = googleMap ?: return
        if (!snapshot.originLat.isFinite() || !snapshot.originLon.isFinite() ||
            !snapshot.markerEast.isFinite() || !snapshot.markerNorth.isFinite()) return

        val markerPosition = EnuMapProjection.toLatLng(
            snapshot.originLat, snapshot.originLon, snapshot.markerEast, snapshot.markerNorth
        )
        hasPosition = true

        updateRawEstimate(map, snapshot)
        updateRouteSegments(map, snapshot)
        updateUncertainty(map, markerPosition, snapshot)
        updateVehicle(map, markerPosition, snapshot)
        updateCamera(map, markerPosition, snapshot.headingRad)
        reportReadyIfPossible()
    }

    private fun updateRawEstimate(map: GoogleMap, snapshot: NavigationMapSnapshot) {
        val points = projectPath(
            snapshot.originLat, snapshot.originLon,
            snapshot.estimateEast, snapshot.estimateNorth,
        )
        val line = rawEstimate ?: map.addPolyline(
            PolylineOptions()
                .width(2.5f)
                .color(Color.parseColor("#804C9BFF"))
                .zIndex(2f)
                .geodesic(false)
        ).also { rawEstimate = it }
        line.points = points
    }

    private fun updateRouteSegments(map: GoogleMap, snapshot: NavigationMapSnapshot) {
        val segments = projectSegments(snapshot)
        while (routeLines.size < segments.size) {
            routeGlows += map.addPolyline(
                PolylineOptions().width(15f).zIndex(3f).geodesic(false)
            )
            routeLines += map.addPolyline(
                PolylineOptions().width(7f).zIndex(4f).geodesic(false)
            )
        }
        while (routeLines.size > segments.size) {
            routeLines.removeAt(routeLines.lastIndex).remove()
            routeGlows.removeAt(routeGlows.lastIndex).remove()
        }
        segments.forEachIndexed { index, segment ->
            val color = if (segment.dark) DENIED_COLOR else AIDED_COLOR
            routeGlows[index].apply {
                points = segment.points
                this.color = withAlpha(color, 52)
            }
            routeLines[index].apply {
                points = segment.points
                this.color = color
            }
        }
    }

    private fun updateUncertainty(
        map: GoogleMap,
        position: LatLng,
        snapshot: NavigationMapSnapshot,
    ) {
        val radius = snapshot.sigmaM.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val color = if (snapshot.inOutage) DENIED_COLOR else AIDED_COLOR
        val circle = uncertainty ?: map.addCircle(
            CircleOptions().center(position).radius(radius).zIndex(1f)
        ).also { uncertainty = it }
        circle.center = position
        circle.radius = radius
        circle.fillColor = withAlpha(color, 38)
        circle.strokeColor = withAlpha(color, 155)
        circle.strokeWidth = 2f
        circle.isVisible = radius > 0.0
    }

    private fun updateVehicle(map: GoogleMap, position: LatLng, snapshot: NavigationMapSnapshot) {
        val rotation = if (snapshot.headingRad.isFinite())
            Math.toDegrees(snapshot.headingRad).toFloat() else 0f
        val icon = if (snapshot.inOutage) deniedIcon else aidedIcon
        val marker = vehicle ?: map.addMarker(
            MarkerOptions()
                .position(position)
                .anchor(0.5f, 0.5f)
                .flat(true)
                .zIndex(10f)
                .rotation(rotation)
                .icon(icon)
        )?.also { vehicle = it } ?: return
        marker.position = position
        marker.rotation = rotation
        marker.isVisible = true
        if (markerIsDark != snapshot.inOutage) {
            marker.setIcon(icon)
            markerIsDark = snapshot.inOutage
        }
    }

    private fun updateCamera(map: GoogleMap, position: LatLng, headingRad: Double) {
        if (!following) return
        val now = System.currentTimeMillis()
        if (cameraInitialized && now - lastCameraUpdateMs < CAMERA_PERIOD_MS) return
        val bearing = if (headingRad.isFinite()) Math.toDegrees(headingRad).toFloat() else 0f
        val camera = CameraPosition.Builder()
            .target(position)
            .zoom(NAVIGATION_ZOOM)
            .bearing(bearing)
            .tilt(NAVIGATION_TILT)
            .build()
        if (cameraInitialized) {
            map.animateCamera(CameraUpdateFactory.newCameraPosition(camera), CAMERA_PERIOD_MS.toInt(), null)
        } else {
            map.moveCamera(CameraUpdateFactory.newCameraPosition(camera))
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
        rawEstimate?.points = emptyList()
        routeLines.forEach { it.points = emptyList() }
        routeGlows.forEach { it.points = emptyList() }
        vehicle?.isVisible = false
        uncertainty?.isVisible = false
        hasPosition = false
        cameraInitialized = false
        following = true
    }

    private fun reportReadyIfPossible() {
        if (mapLoaded && hasPosition && !readyReported) {
            readyReported = true
            onBasemapReady()
        }
    }

    private fun projectPath(
        originLat: Double,
        originLon: Double,
        east: DoubleArray,
        north: DoubleArray,
    ): List<LatLng> {
        val size = minOf(east.size, north.size)
        if (size == 0) return emptyList()
        val stride = max(1, size / MAX_MAP_POINTS)
        val result = ArrayList<LatLng>(minOf(size, MAX_MAP_POINTS + 1))
        for (i in 0 until size) {
            if (i % stride != 0 && i != size - 1) continue
            if (!east[i].isFinite() || !north[i].isFinite()) continue
            result += EnuMapProjection.toLatLng(originLat, originLon, east[i], north[i])
        }
        return result
    }

    private data class RouteSegment(val dark: Boolean, val points: List<LatLng>)

    private fun projectSegments(snapshot: NavigationMapSnapshot): List<RouteSegment> {
        val size = minOf(snapshot.displayEast.size, snapshot.displayNorth.size)
        if (size == 0) return emptyList()
        val stride = max(1, size / MAX_MAP_POINTS)
        val segments = ArrayList<RouteSegment>()
        var segmentDark = snapshot.dark.getOrElse(0) { false }
        var points = ArrayList<LatLng>()
        var previous: LatLng? = null

        for (i in 0 until size) {
            val e = snapshot.displayEast[i]
            val n = snapshot.displayNorth[i]
            if (!e.isFinite() || !n.isFinite()) continue
            val dark = snapshot.dark.getOrElse(i) { false }
            val changed = dark != segmentDark
            if (changed) {
                if (points.isNotEmpty()) segments += RouteSegment(segmentDark, points)
                points = ArrayList<LatLng>().apply { previous?.let(::add) }
                segmentDark = dark
            }
            val include = changed || i % stride == 0 || i == size - 1
            if (include) {
                val point = EnuMapProjection.toLatLng(
                    snapshot.originLat, snapshot.originLon, e, n
                )
                points += point
                previous = point
            }
        }
        if (points.isNotEmpty()) segments += RouteSegment(segmentDark, points)
        return segments
    }

    private fun vehicleIcon(color: Int): BitmapDescriptor {
        val density = context.resources.displayMetrics.density
        val size = (48f * density).toInt().coerceAtLeast(48)
        val center = size / 2f
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = Color.argb(75, 0, 0, 0)
        }
        canvas.drawCircle(center + density, center + 2f * density, 15f * density, shadow)
        val path = Path().apply {
            moveTo(center, center - 17f * density)
            lineTo(center - 12f * density, center + 13f * density)
            lineTo(center, center + 8f * density)
            lineTo(center + 12f * density, center + 13f * density)
            close()
        }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 4f * density
            this.color = Color.WHITE
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = color
        }
        canvas.drawPath(path, outline)
        canvas.drawPath(path, fill)
        canvas.drawCircle(center, center + 2f * density, 2.3f * density, outline)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    fun onStart() = view.onStart()
    fun onResume() = view.onResume()
    fun onPause() = view.onPause()
    fun onStop() = view.onStop()
    fun onLowMemory() = view.onLowMemory()
    fun onSaveInstanceState(outState: Bundle) = view.onSaveInstanceState(outState)
    fun onDestroy() = view.onDestroy()

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    companion object {
        private const val MAX_MAP_POINTS = 2500
        private const val CAMERA_PERIOD_MS = 450L
        private const val NAVIGATION_ZOOM = 17.4f
        private const val NAVIGATION_TILT = 48f
        private val AIDED_COLOR = Color.parseColor("#4C9BFF")
        private val DENIED_COLOR = Color.parseColor("#FF9F4A")

        fun playServicesAvailable(context: Context): Boolean =
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) ==
                ConnectionResult.SUCCESS
    }
}

/** Inverse of [Navigator.toEnu], using the exact same local tangent-plane approximation. */
object EnuMapProjection {
    private const val EARTH_RADIUS_M = 6378137.0

    fun toLatLng(originLat: Double, originLon: Double, east: Double, north: Double): LatLng {
        val latitude = originLat + Math.toDegrees(north / EARTH_RADIUS_M)
        val longitudeScale = cos(Math.toRadians(originLat)).coerceAtLeast(1e-8)
        val longitude = originLon + Math.toDegrees(east / (EARTH_RADIUS_M * longitudeScale))
        return LatLng(latitude, longitude)
    }
}
