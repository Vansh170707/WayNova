package org.neuronavx.logger

import org.json.JSONObject
import java.io.File

/** A saved recording is evidence, not a claim that the accuracy target passed. */
data class SessionRecord(
    val id: Long,
    val durationS: Double,
    val phase: String,
    val outageS: Double?,
    val endErrorM: Double?,
    val recoveryS: Double?,
    val recovered: Boolean,
    val imuSamples: Long,
    val phoneFixes: Long,
    val writerError: Boolean,
) {
    val complete: Boolean get() = phase == "COMPLETE" && recovered && !writerError
    val status: String get() = when {
        writerError -> "Recording needs attention"
        complete -> "Outage + recovery recorded"
        phase == "OFF" -> "Drive recorded · no controlled test"
        else -> "Incomplete test · $phase"
    }

    companion object {
        fun parse(text: String): SessionRecord? = runCatching {
            val json = JSONObject(text)
            val test = json.optJSONObject("controlled_blackout") ?: JSONObject()
            fun number(key: String): Double? = test.optDouble(key, Double.NaN)
                .takeIf { it.isFinite() && it >= 0 }
            val id = json.getLong("session_id")
            require(id > 0)
            SessionRecord(id, json.optDouble("duration_s", 0.0)
                .takeIf { it.isFinite() && it >= 0 } ?: 0.0,
                test.optString("phase", "OFF"), number("actual_duration_s"),
                number("end_reference_error_m"), number("reacquire_s"),
                test.optBoolean("recovered", false), json.optLong("imu_samples", 0),
                json.optLong("phone_fixes", 0),
                !json.isNull("raw_writer_error") || !json.isNull("diagnostics_writer_error"))
        }.getOrNull()

        fun recent(directory: File?, limit: Int = 20): List<SessionRecord> =
            directory?.listFiles()?.asSequence()
                ?.filter { it.isFile && it.name.matches(Regex("nav_summary_[0-9]+\\.json")) }
                ?.sortedByDescending { it.name }
                ?.mapNotNull { file -> runCatching {
                    if (file.length() > 128_000) null else parse(file.readText())
                }.getOrNull() }
                ?.take(limit)?.toList().orEmpty()
    }
}
