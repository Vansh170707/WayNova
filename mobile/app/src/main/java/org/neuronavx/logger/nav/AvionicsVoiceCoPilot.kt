package org.neuronavx.logger.nav

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Offline Avionics Voice Co-Pilot for Waynova (SIH26168).
 *
 * Provides synthesized spoken telemetry during test drives and live GNSS blackouts,
 * operating 100% offline using Android's native Text-to-Speech engine with zero
 * external cloud dependencies or latency.
 */
class AvionicsVoiceCoPilot(context: Context) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var isReady = false
    var isMuted: Boolean = false

    // Throttling and deduplication
    private var lastSpokenCue = ""
    private var lastSpokenTimeMs = 0L
    private val minRepeatIntervalMs = 6000L

    init {
        try {
            tts = TextToSpeech(appContext, this)
        } catch (e: Exception) {
            Log.e("VoiceCoPilot", "Failed to initialize TextToSpeech", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.let { engine ->
                val result = engine.setLanguage(Locale.US)
                if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                    // Authoritative avionics flight computer voice profile
                    engine.setPitch(0.95f)
                    engine.setSpeechRate(1.06f)
                    isReady = true
                }
            }
        } else {
            Log.w("VoiceCoPilot", "TTS initialization returned status $status")
        }
    }

    /**
     * Speak an avionics alert or telemetry callout.
     *
     * @param text The sentence to synthesize.
     * @param flush If true, interrupts any ongoing queue to deliver immediate warning.
     * @param forceIfRecent If true, bypasses the deduplication throttle.
     */
    fun speak(text: String, flush: Boolean = false, forceIfRecent: Boolean = false) {
        if (isMuted || !isReady || text.isBlank()) return
        val now = System.currentTimeMillis()
        if (!forceIfRecent && text == lastSpokenCue && (now - lastSpokenTimeMs) < minRepeatIntervalMs) {
            return
        }

        lastSpokenCue = text
        lastSpokenTimeMs = now

        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(text, queueMode, null, "WAYNOVA_COPILOT_${now}")
    }

    fun onMissionStart() {
        speak("Waynova navigation online. Inertial neural filter calibrated at 100 Hertz.", flush = true)
    }

    fun onBlackoutEnter(speedKmh: Double) {
        val speedText = if (speedKmh.isFinite() && speedKmh >= 5.0) {
            " at %.0f kilometers per hour.".format(speedKmh)
        } else "."
        speak("Satellite lock lost. Autonomous dead reckoning engaged$speedText", flush = true)
    }

    fun onRoadShock() {
        speak("Road shock transient absorbed. Heading filter locked.")
    }

    fun onMultipathRejected() {
        speak("GNSS anomaly rejected. Maintaining inertial lane track.", flush = true)
    }

    fun onReacquiring() {
        speak("Satellites reacquired. Slew convergence applied. Position locked.", flush = true)
    }

    fun toggleMute(): Boolean {
        isMuted = !isMuted
        if (isMuted) {
            tts?.stop()
        } else {
            speak("Co-pilot audio enabled.")
        }
        return isMuted
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.w("VoiceCoPilot", "Error shutting down TTS", e)
        } finally {
            tts = null
            isReady = false
        }
    }
}
