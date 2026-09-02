package org.neuronavx.logger

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import kotlin.concurrent.thread

/** A user-facing entry point for taking the still-outstanding physical-device measurement. */
class FullLoopBenchmarkActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var runButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(40, 40, 40, 40)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        status = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.MONOSPACE
            text = "Full navigation-loop benchmark\n\n" +
                "Runs the bundled drive five times through online calibration, the 100 Hz " +
                "sensor callback path, TCN, ES-EKF, blackout manager and display smoother.\n\n" +
                "An emulator result is diagnostic only. Gate 5 closes only when this screen " +
                "is run on a physical Android phone."
        }
        runButton = Button(this).apply {
            text = "Run full-loop benchmark"
            setOnClickListener { runBenchmark() }
        }
        content.addView(status)
        content.addView(runButton)
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun runBenchmark() {
        runButton.isEnabled = false
        status.text = "Benchmark running…\n\nKeep this screen open and avoid using the device."
        thread(name = "neuronavx-full-loop-benchmark") {
            val result = try {
                FullNavigationLoopBenchmark(this).use { it.run() }.toString()
            } catch (e: Throwable) {
                android.util.Log.e("NeuroNavX", "full-loop benchmark failed", e)
                "Benchmark failed: ${e.javaClass.simpleName}: ${e.message}"
            }
            runOnUiThread {
                status.text = result
                runButton.isEnabled = true
            }
        }
    }
}
