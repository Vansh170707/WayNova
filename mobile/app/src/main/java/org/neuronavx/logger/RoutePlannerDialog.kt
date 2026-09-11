package org.neuronavx.logger

import android.app.Activity
import android.app.AlertDialog
import android.location.Geocoder
import android.text.Html
import android.text.method.LinkMovementMethod
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.neuronavx.logger.nav.RouteClient
import org.neuronavx.logger.nav.RoutePlan
import org.neuronavx.logger.nav.RoutePoint
import java.util.Locale
import kotlin.concurrent.thread

/** Explicit search, not autocomplete: address-provider and route-provider disclosures are visible. */
object RoutePlannerDialog {
    fun show(activity: Activity, startPoint: RoutePoint?, onRoute: (RoutePlan) -> Unit) {
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

        val bgSurface = android.graphics.Color.parseColor("#14161E")
        val inputSurface = android.graphics.Color.parseColor("#1B1F2A")
        val borderSubtle = android.graphics.Color.parseColor("#262C3B")
        val borderProminent = android.graphics.Color.parseColor("#3A4459")
        val textPrimary = android.graphics.Color.parseColor("#F7F5F0")
        val textSecondary = android.graphics.Color.parseColor("#9EA4B1")
        val textMuted = android.graphics.Color.parseColor("#697386")
        val accentAmber = android.graphics.Color.parseColor("#F59E0B")
        val accentSky = android.graphics.Color.parseColor("#38BDF8")

        fun inputPill() = android.graphics.drawable.GradientDrawable().apply {
            setColor(inputSurface)
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), borderProminent)
        }

        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(16), dp(22), dp(16))
            setBackgroundColor(bgSurface)
        }

        form.addView(TextView(activity).apply {
            text = "ORIGIN / START"
            textSize = 9f
            setTextColor(accentAmber)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            letterSpacing = 0.12f
        })

        val start = EditText(activity).apply {
            hint = "Current place, landmark, or lat,lon"
            setHintTextColor(textMuted)
            setTextColor(textPrimary)
            textSize = 13.5f
            setSingleLine()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = inputPill()
            startPoint?.let { setText(String.format(Locale.US, "%.6f,%.6f", it.lat, it.lon)) }
            contentDescription = "Starting place or coordinates"
        }
        form.addView(start, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
            bottomMargin = dp(14)
        })

        form.addView(TextView(activity).apply {
            text = "DESTINATION"
            textSize = 9f
            setTextColor(accentSky)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            letterSpacing = 0.12f
        })

        val end = EditText(activity).apply {
            hint = "Destination place, city, or lat,lon"
            setHintTextColor(textMuted)
            setTextColor(textPrimary)
            textSize = 13.5f
            setSingleLine()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = inputPill()
            contentDescription = "Destination place or coordinates"
        }
        form.addView(end, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
            bottomMargin = dp(12)
        })

        val status = TextView(activity).apply {
            text = "Online route calculation via OpenStreetMap (OSRM). Route coordinates are fetched securely; sensor logs remain privately stored on your device.\n\nOffline route geometry and maneuver steps will be cached for GPS-denied navigation."
            textSize = 11.5f
            setTextColor(textSecondary)
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(6), 0, dp(10))
        }
        form.addView(status)

        form.addView(TextView(activity).apply {
            text = Html.fromHtml("Routes: <a href='https://project-osrm.org/' style='color:#F59E0B;'>OSRM</a> · © <a href='https://www.openstreetmap.org/copyright' style='color:#F59E0B;'>OpenStreetMap</a> (ODbL)", Html.FROM_HTML_MODE_LEGACY)
            movementMethod = LinkMovementMethod.getInstance()
            textSize = 10.5f
            setTextColor(textMuted)
        })

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Plan Tactical Driving Route")
            .setView(ScrollView(activity).apply { addView(form) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Calculate Route", null)
            .create()

        fun fail(message: String) {
            if (dialog.isShowing && !activity.isDestroyed) {
                status.text = message
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
            }
        }

        fun resolve(query: String, then: (RoutePoint) -> Unit) {
            RoutePoint.coordinateInput(query)?.let { then(it); return }
            if (!Geocoder.isPresent()) { fail("Place search is unavailable on this device. Enter latitude,longitude coordinates instead."); return }
            thread(name = "waynova-place-search") {
                val result = runCatching {
                    @Suppress("DEPRECATION")
                    Geocoder(activity.applicationContext, Locale.getDefault()).getFromLocationName(query, 5).orEmpty()
                        .filter { it.hasLatitude() && it.hasLongitude() }
                }
                activity.runOnUiThread {
                    if (!dialog.isShowing || activity.isDestroyed) return@runOnUiThread
                    result.onSuccess { addresses ->
                        if (addresses.isEmpty()) { fail("No matches for '$query'. Add city or enter coordinates."); return@onSuccess }
                        AlertDialog.Builder(activity)
                            .setTitle("Select Location: $query")
                            .setItems(addresses.map { it.getAddressLine(0) ?: "${it.latitude}, ${it.longitude}" }.toTypedArray()) { _, index ->
                                then(RoutePoint(addresses[index].latitude, addresses[index].longitude))
                            }
                            .setNegativeButton("Cancel") { _, _ -> fail("Search cancelled.") }
                            .setOnCancelListener { fail("Search cancelled.") }
                            .show()
                    }.onFailure { fail("Place search failed. Check network or enter coordinates.") }
                }
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val from = start.text.toString().trim()
                val to = end.text.toString().trim()
                if (from.length < 3 || to.length < 3) { fail("Enter both starting point and destination."); return@setOnClickListener }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                status.text = "Resolving locations… Please wait."
                resolve(from) { origin -> resolve(to) { destination ->
                    if (!dialog.isShowing) return@resolve
                    AlertDialog.Builder(activity)
                        .setTitle("Download Driving Route?")
                        .setMessage("Coordinates will be sent to the OSRM routing service. Route geometry will be cached locally on-device.")
                        .setNegativeButton("Cancel") { _, _ -> fail("Route request cancelled.") }
                        .setOnCancelListener { fail("Route request cancelled.") }
                        .setPositiveButton("Proceed") { _, _ ->
                            status.text = "Calculating driving maneuvers…"
                            thread(name = "waynova-route-request") {
                                val result = runCatching { RouteClient.fetch(origin, destination, to) }
                                activity.runOnUiThread {
                                    if (!dialog.isShowing || activity.isDestroyed) return@runOnUiThread
                                    result.onSuccess { onRoute(it); dialog.dismiss() }
                                        .onFailure { fail(it.message ?: "Routing service failed. Check network connection.") }
                                }
                            }
                        }.show()
                } }
            }
        }
        dialog.show()
    }
}
