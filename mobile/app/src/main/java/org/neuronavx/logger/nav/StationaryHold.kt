package org.neuronavx.logger.nav

import kotlin.math.sqrt

/** Retain a GNSS-confirmed stop through an outage; quiet cruising alone cannot earn it. */
class StationaryHold {
    private var lastT = Double.NaN
    private var stoppedFixes = 0
    private var lastFixT = Double.NaN
    private val smooth = DoubleArray(3)
    private var motionS = 0.0
    var active = false; private set

    fun reset() {
        lastT = Double.NaN; lastFixT = Double.NaN
        stoppedFixes = 0; motionS = 0.0; active = false
        smooth.fill(0.0)
    }

    fun observeFix(t: Double, speed: Double, accuracy: Double) {
        if (!speed.isFinite() || !accuracy.isFinite() || accuracy !in 0.01..20.0) return
        if (speed < 0.3) {
            stoppedFixes = if (t - lastFixT in 0.0..2.0) stoppedFixes + 1 else 1
            if (stoppedFixes >= 3 && motionS == 0.0) active = true
        } else {
            stoppedFixes = 0; active = false
        }
        lastFixT = t
    }

    fun add(t: Double, accel: DoubleArray, gravity: DoubleArray, gyro: DoubleArray) {
        val dt = if (lastT.isFinite()) t - lastT else 0.0
        lastT = t
        if (dt !in 0.0..1.0) { reset(); lastT = t; return }
        val norm = sqrt(gravity.sumOf { it * it })
        if (norm !in 7.0..12.0 || !accel.all { it.isFinite() } || !gyro.all { it.isFinite() }) {
            active = false; stoppedFixes = 0; return
        }
        val unit = gravity.map { it / norm }
        val linear = DoubleArray(3) { accel[it] - gravity[it] }
        val vertical = (0..2).sumOf { linear[it] * unit[it] }
        val gain = dt / (0.3 + dt)
        for (i in 0..2) smooth[i] += gain * (linear[i] - vertical * unit[i] - smooth[i])
        val moving = sqrt(smooth.sumOf { it * it }) > 0.35 ||
            sqrt(gyro.sumOf { it * it }) > 0.12
        motionS = if (moving) motionS + dt else 0.0
        if (motionS >= 0.2) { active = false; stoppedFixes = 0 }
    }
}
