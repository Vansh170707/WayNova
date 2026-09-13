package org.neuronavx.logger.nav

import kotlin.math.roundToInt

/** Pure policy for one-shot, distance-aware spoken maneuver cues. */
object TurnCuePlanner {
    data class Cue(val key: String, val text: String)

    fun cue(result: RouteGuidance.Result, routeAssisted: Boolean): Cue? {
        if (result.status != RouteGuidance.Status.READY || result.stepIndex < 0 ||
            !result.distanceM.isFinite() || result.distanceM < 0) return null
        val stage = when {
            result.distanceM <= 25 -> "now"
            result.distanceM <= 80 -> "near"
            result.distanceM <= 250 -> "approach"
            else -> return null
        }
        val instruction = result.instruction.trim().replace(" · ", " onto ")
        if (instruction.isBlank()) return null
        val prefix = if (routeAssisted) "Route estimate. " else ""
        val text = if (stage == "now") {
            "$prefix$instruction now."
        } else {
            val distance = (result.distanceM / 10.0).roundToInt().times(10).coerceAtLeast(10)
            "${prefix}In $distance meters, $instruction."
        }
        return Cue("${result.stepIndex}:$stage", text)
    }
}
