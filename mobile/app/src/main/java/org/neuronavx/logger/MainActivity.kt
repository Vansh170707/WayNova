package org.neuronavx.logger

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.concurrent.thread

/**
 * Two jobs, both of which the desktop work is blocked on:
 *
 *  1. **Log a drive** in the exact schema the offline pipeline reads. The field audit
 *     showed IO-VNBD's phone GNSS is often 0.1 Hz and its accelerometer frequently
 *     filtered; own-drive data with a live accelerometer and ~1 Hz GNSS is the fix.
 *  2. **Benchmark the model on real hardware**, which is the only measurement that can
 *     close the blueprint's edge acceptance gate.
 */
class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var benchmark: Button
    private var logger: SensorLogger? = null
    private var currentFile: File? = null
    private val ui = Handler(Looper.getMainLooper())

    private val permissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        status = TextView(this).apply {
            textSize = 15f
            text = "Waynova · Developer tools\n\nFor normal drives, use the home screen. These tools collect raw data and measure performance."
        }
        toggle = Button(this).apply {
            text = "Start logging"
            setOnClickListener { onToggle() }
        }
        benchmark = Button(this).apply {
            text = "Benchmark speed model only"
            setOnClickListener { onBenchmark() }
        }
        val fullBenchmark = Button(this).apply {
            text = "Benchmark full navigation loop"
            setOnClickListener {
                startActivity(android.content.Intent(
                    this@MainActivity, FullLoopBenchmarkActivity::class.java))
            }
        }
        val navigate = Button(this).apply {
            text = "Navigation view"
            setOnClickListener {
                startActivity(android.content.Intent(
                    this@MainActivity, NavigationActivity::class.java))
            }
        }

        root.addView(status)
        root.addView(toggle)
        root.addView(benchmark)
        root.addView(fullBenchmark)
        root.addView(navigate)
        setContentView(root)

        if (permissions.any {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }) {
            ActivityCompat.requestPermissions(this, permissions, 1)
        }
        logger = SensorLogger(this)
        startStatusUpdates()
    }

    private fun onToggle() {
        val current = logger ?: return
        if (current.isRunning()) {
            current.stop()
            toggle.text = "Start logging"
            status.text = "Stopped.\n\nSaved to:\n${currentFile?.absolutePath}\n\n" +
                "Pull it with:\nadb pull ${currentFile?.absolutePath}"
        } else {
            currentFile = current.start()
            toggle.text = "Stop logging"
        }
    }

    /** Refresh counters so a drive can be sanity-checked without stopping it. */
    private fun startStatusUpdates() {
        ui.postDelayed(object : Runnable {
            override fun run() {
                val current = logger
                if (current != null && current.isRunning()) {
                    val samples = current.sampleCount.get()
                    val fixes = current.fixCount.get()
                    val gnss = if (current.locationAvailable) "GNSS fixes: $fixes"
                        else "GNSS UNAVAILABLE — grant location permission"
                    status.text = "Logging…\n\nIMU samples: $samples\n$gnss\n\n" +
                        "Keep the phone rigidly mounted and drive normally.\n" +
                        "Include turns, braking and stops."
                }
                ui.postDelayed(this, 1000)
            }
        }, 1000)
    }

    private fun onBenchmark() {
        benchmark.isEnabled = false
        status.text = "Benchmarking…"
        thread {
            val text = try {
                val bench = SpeedModelBenchmark(this)
                val result = bench.run()
                bench.close()
                "Speed TCN on this device\n\n$result\n\n" +
                    "Gate 5 needs this sustained with the full sensor loop running."
            } catch (e: Exception) {
                "Benchmark failed: ${e.javaClass.simpleName}: ${e.message}\n\n" +
                    "Is speed_tcn.onnx in app/src/main/assets?"
            }
            ui.post {
                status.text = text
                benchmark.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        logger?.stop()
        super.onDestroy()
    }
}
