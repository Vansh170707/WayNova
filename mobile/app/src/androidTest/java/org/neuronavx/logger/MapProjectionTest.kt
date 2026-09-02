package org.neuronavx.logger

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.neuronavx.logger.nav.EnuMapProjection
import org.neuronavx.logger.nav.NavigationMapSnapshot
import org.neuronavx.logger.nav.Navigator
import org.neuronavx.logger.nav.RuntimeConfig
import org.neuronavx.logger.nav.offlineMapMarkerOrNull

/** The Maps adapter must be an inverse presentation of Navigator's local ENU frame. */
@RunWith(AndroidJUnit4::class)
class MapProjectionTest {

    @Test
    fun mapProjectionRoundTripsNavigatorEnu() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val navigator = Navigator(RuntimeConfig.fromAsset(context), model = null)
        navigator.setOrigin(28.6139, 77.2090, 216.0)

        val cases = listOf(
            0.0 to 0.0,
            850.0 to 320.0,
            -1450.0 to 975.0,
        )
        for ((east, north) in cases) {
            val point = EnuMapProjection.toLatLng(
                navigator.originLat, navigator.originLon, east, north
            )
            val roundTrip = navigator.toEnu(point.latitude, point.longitude)
            assertEquals("east", east, roundTrip.first, 1e-6)
            assertEquals("north", north, roundTrip.second, 1e-6)
        }
    }

    @Test
    fun offlineMapRejectsUnavailableCalibrationPositionWithoutCrashing() {
        fun snapshot(originLat: Double, markerEast: Double) = NavigationMapSnapshot(
            originLat = originLat,
            originLon = 77.50,
            estimateEast = doubleArrayOf(),
            estimateNorth = doubleArrayOf(),
            displayEast = doubleArrayOf(),
            displayNorth = doubleArrayOf(),
            dark = booleanArrayOf(),
            markerEast = markerEast,
            markerNorth = 0.0,
            headingRad = Double.NaN,
            sigmaM = Double.NaN,
            inOutage = false,
        )

        // CALIBRATING emits NaN position by design. This exact state used to reach the
        // MapLibre LatLng constructor when the source badge was tapped and kill recording.
        assertNull(offlineMapMarkerOrNull(snapshot(Double.NaN, Double.NaN)))
        assertNull(offlineMapMarkerOrNull(snapshot(28.50, Double.NaN)))
        assertNotNull(offlineMapMarkerOrNull(snapshot(28.50, 0.0)))
    }
}
