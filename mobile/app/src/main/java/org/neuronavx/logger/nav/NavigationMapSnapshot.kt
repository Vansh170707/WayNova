package org.neuronavx.logger.nav

/** Immutable presentation snapshot shared by every basemap renderer. */
data class NavigationMapSnapshot(
    val originLat: Double,
    val originLon: Double,
    val estimateEast: DoubleArray,
    val estimateNorth: DoubleArray,
    val displayEast: DoubleArray,
    val displayNorth: DoubleArray,
    val dark: BooleanArray,
    val markerEast: Double,
    val markerNorth: Double,
    val headingRad: Double,
    val sigmaM: Double,
    val inOutage: Boolean,
)
