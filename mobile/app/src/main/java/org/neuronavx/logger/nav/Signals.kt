package org.neuronavx.logger.nav

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Derived inertial signals, in the conventions the desktop pipeline validated empirically
 * against IO-VNBD (see `src/neuronav/calibration/signals.py` and `docs/data_audit.md`).
 *
 * The two that matter and are easy to get wrong:
 *  - the reported GRAVITY vector points UP (it is the specific-force direction), so
 *    linear acceleration is `accel - gravity`, and the ORIENTATION/rotation-vector
 *    channels are never used -- they were shown to be inconsistent with gravity.
 *  - a right-handed rotation about "up" is counter-clockwise while compass heading grows
 *    clockwise, which is why heading rate carries a negation on the desktop side. Here the
 *    yaw rate comes from a single calibrated gyro channel instead, because the correct
 *    channel differs per device and matches no axis label.
 *
 * Everything is written for one sample at a time: this runs inside a sensor callback, not
 * over an array.
 */
object Signals {

    const val GRAVITY_MAGNITUDE = 9.80665

    /** Unit vector along the device-frame gravity ("up") direction. */
    fun gravityUnit(g: DoubleArray, out: DoubleArray) {
        val norm = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]).coerceAtLeast(1e-9)
        out[0] = g[0] / norm
        out[1] = g[1] / norm
        out[2] = g[2] / norm
    }

    /**
     * Two orthonormal device-frame vectors spanning the local horizontal plane.
     *
     * Built by projecting the device X axis onto the plane perpendicular to gravity, and
     * falling back to Y when X is near-vertical -- otherwise the projection degenerates and
     * the basis spins arbitrarily, which would make the alignment angle meaningless.
     */
    fun horizontalBasis(up: DoubleArray, e1: DoubleArray, e2: DoubleArray) {
        val useY = abs(up[0]) > 0.9
        val refX = if (useY) 0.0 else 1.0
        val refY = if (useY) 1.0 else 0.0

        val dot = refX * up[0] + refY * up[1]
        e1[0] = refX - up[0] * dot
        e1[1] = refY - up[1] * dot
        e1[2] = 0.0 - up[2] * dot
        val norm = sqrt(e1[0] * e1[0] + e1[1] * e1[1] + e1[2] * e1[2]).coerceAtLeast(1e-9)
        e1[0] /= norm; e1[1] /= norm; e1[2] /= norm

        // e2 = up x e1
        e2[0] = up[1] * e1[2] - up[2] * e1[1]
        e2[1] = up[2] * e1[0] - up[0] * e1[2]
        e2[2] = up[0] * e1[1] - up[1] * e1[0]
    }

    /**
     * Horizontal components of the linear (gravity-removed) acceleration, in the (e1, e2)
     * basis. Returns into [out] as (h1, h2).
     */
    fun horizontalAcceleration(accel: DoubleArray, gravity: DoubleArray, out: DoubleArray) {
        val up = DoubleArray(3)
        val e1 = DoubleArray(3)
        val e2 = DoubleArray(3)
        gravityUnit(gravity, up)
        horizontalBasis(up, e1, e2)

        val lx = accel[0] - gravity[0]
        val ly = accel[1] - gravity[1]
        val lz = accel[2] - gravity[2]
        out[0] = lx * e1[0] + ly * e1[1] + lz * e1[2]
        out[1] = lx * e2[0] + ly * e2[1] + lz * e2[2]
    }

    /** Specific force along gravity, minus 1 g -- the vertical feature channel. */
    fun verticalAcceleration(accel: DoubleArray, gravity: DoubleArray): Double {
        val up = DoubleArray(3)
        gravityUnit(gravity, up)
        return accel[0] * up[0] + accel[1] * up[1] + accel[2] * up[2] - GRAVITY_MAGNITUDE
    }

    fun magnitude(v: DoubleArray): Double = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    /**
     * Vehicle heading rate from Android-native sensors, positive clockwise from North.
     *
     * Android's gyroscope and gravity sensors use the same device coordinate frame. A
     * vehicle yaw is therefore the angular-rate component about local vertical; the minus
     * sign converts Android's right-handed counter-clockwise convention to compass heading.
     * Unlike a frozen single-axis gain, this remains correct when the phone's pitch/roll in
     * its cradle changes while driving.
     *
     * IO-VNBD replay columns do not share this axis contract, so replay navigation retains
     * its calibrated-channel path in [Navigator].
     */
    fun gravityProjectedHeadingRate(gyro: DoubleArray, gravity: DoubleArray): Double {
        val norm = magnitude(gravity)
        if (!norm.isFinite() || norm < 1.0) return Double.NaN
        return -(gyro[0] * gravity[0] + gyro[1] * gravity[1] + gyro[2] * gravity[2]) / norm
    }

    fun hypot2(x: Double, y: Double): Double = hypot(x, y)

    /** Wrap to (-pi, pi]. */
    fun wrapAngle(a: Double): Double {
        val twoPi = 2.0 * Math.PI
        var x = (a + Math.PI) % twoPi
        if (x < 0) x += twoPi
        return x - Math.PI
    }
}
