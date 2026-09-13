package org.neuronavx.logger

import android.content.Context
import android.content.SharedPreferences

/**
 * The handful of user preferences the app actually honours.
 *
 * Nothing here reaches the estimator: these are display and convenience settings only.
 * Changing units or muting the co-pilot must never alter what the filter computes, which
 * is why speed is stored in SI everywhere and converted at the last moment.
 */
object Prefs {

    private const val FILE = "waynova_prefs"
    private const val KEY_MPH = "units_mph"
    private const val KEY_KEEP_AWAKE = "keep_screen_on"
    private const val KEY_HAPTICS = "haptics"
    private const val KEY_VOICE = "voice_enabled"

    private fun store(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Display speed in mph instead of km/h. */
    fun useMph(context: Context): Boolean = store(context).getBoolean(KEY_MPH, false)

    fun setUseMph(context: Context, value: Boolean) =
        store(context).edit().putBoolean(KEY_MPH, value).apply()

    /** Hold the screen on for the whole navigation session. */
    fun keepScreenOn(context: Context): Boolean = store(context).getBoolean(KEY_KEEP_AWAKE, true)

    fun setKeepScreenOn(context: Context, value: Boolean) =
        store(context).edit().putBoolean(KEY_KEEP_AWAKE, value).apply()

    fun haptics(context: Context): Boolean = store(context).getBoolean(KEY_HAPTICS, true)

    fun setHaptics(context: Context, value: Boolean) =
        store(context).edit().putBoolean(KEY_HAPTICS, value).apply()

    /** Whether the offline voice co-pilot starts unmuted. */
    fun voiceEnabled(context: Context): Boolean = store(context).getBoolean(KEY_VOICE, true)

    fun setVoiceEnabled(context: Context, value: Boolean) =
        store(context).edit().putBoolean(KEY_VOICE, value).apply()

    // -- Unit helpers -------------------------------------------------------------

    fun speedLabel(context: Context): String = if (useMph(context)) "MPH" else "KM/H"

    /** Converts an SI speed in m/s to whichever unit the user chose. */
    fun speedInPreferredUnit(context: Context, metresPerSecond: Double): Double =
        if (useMph(context)) metresPerSecond * 2.236936 else metresPerSecond * 3.6
}
