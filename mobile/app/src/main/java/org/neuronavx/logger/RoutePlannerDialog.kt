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
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val start = EditText(activity).apply {
            hint = "Start: place + city, or latitude,longitude"
            setSingleLine()
            startPoint?.let { setText(String.format(Locale.US, "%.6f,%.6f", it.lat, it.lon)) }
            contentDescription = "Starting place or coordinates"
        }
        val end = EditText(activity).apply {
            hint = "Destination: place + city, or latitude,longitude"
            setSingleLine(); contentDescription = "Destination place or coordinates"
        }
        val status = TextView(activity).apply {
            text = "Search needs internet. Place names go to Android's geocoding provider. Route coordinates go to the OSRM demo service and may be logged there. No drive logs are uploaded.\n\nDriving route preview; no traffic or automatic rerouting. Check road signs. Set up only while parked."
            textSize = 12f; setPadding(0, dp(12), 0, dp(12))
        }
        form.addView(start); form.addView(end); form.addView(status)
        form.addView(TextView(activity).apply {
            text = Html.fromHtml("Routes: <a href='https://project-osrm.org/'>OSRM</a> · © <a href='https://www.openstreetmap.org/copyright'>OpenStreetMap</a> (ODbL) · <a href='https://www.openstreetmap.org/fixthemap'>Fix the map</a>", Html.FROM_HTML_MODE_LEGACY)
            movementMethod = LinkMovementMethod.getInstance(); textSize = 11f
        })
        val dialog = AlertDialog.Builder(activity).setTitle("Plan a driving route")
            .setView(ScrollView(activity).apply { addView(form) })
            .setNegativeButton("Cancel", null).setPositiveButton("Search places", null).create()

        fun fail(message: String) {
            if (dialog.isShowing && !activity.isDestroyed) {
                status.text = message
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
            }
        }
        fun resolve(query: String, then: (RoutePoint) -> Unit) {
            RoutePoint.coordinateInput(query)?.let { then(it); return }
            if (!Geocoder.isPresent()) { fail("Place search is unavailable on this phone. Enter latitude,longitude instead."); return }
            thread(name = "waynova-place-search") {
                val result = runCatching {
                    @Suppress("DEPRECATION")
                    Geocoder(activity.applicationContext, Locale.getDefault()).getFromLocationName(query, 5).orEmpty()
                        .filter { it.hasLatitude() && it.hasLongitude() }
                }
                activity.runOnUiThread {
                    if (!dialog.isShowing || activity.isDestroyed) return@runOnUiThread
                    result.onSuccess { addresses ->
                        if (addresses.isEmpty()) { fail("No results for '$query'. Add the city or use coordinates."); return@onSuccess }
                        AlertDialog.Builder(activity).setTitle("Choose: $query")
                            .setItems(addresses.map { it.getAddressLine(0) ?: "${it.latitude}, ${it.longitude}" }.toTypedArray()) { _, index ->
                                then(RoutePoint(addresses[index].latitude, addresses[index].longitude))
                            }.setNegativeButton("Cancel") { _, _ -> fail("Search cancelled. You can edit the places.") }
                            .setOnCancelListener { fail("Search cancelled. You can edit the places.") }.show()
                    }.onFailure { fail("Place search failed. Check internet or enter coordinates.") }
                }
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val from = start.text.toString().trim()
                val to = end.text.toString().trim()
                if (from.length < 3 || to.length < 3) { fail("Enter both the starting place and destination."); return@setOnClickListener }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                status.text = "Finding places… Confirm the matching address before routing."
                resolve(from) { origin -> resolve(to) { destination ->
                    if (!dialog.isShowing) return@resolve
                    AlertDialog.Builder(activity).setTitle("Get this route online?")
                        .setMessage("OSRM will receive the selected start and destination coordinates. Your saved drive logs stay on the phone.")
                        .setNegativeButton("Cancel") { _, _ -> fail("Route request cancelled.") }
                        .setOnCancelListener { fail("Route request cancelled.") }
                        .setPositiveButton("Get driving route") { _, _ ->
                            status.text = "Calculating driving route…"
                            thread(name = "waynova-route-request") {
                                val result = runCatching { RouteClient.fetch(origin, destination, to) }
                                activity.runOnUiThread {
                                    if (!dialog.isShowing || activity.isDestroyed) return@runOnUiThread
                                    result.onSuccess { onRoute(it); dialog.dismiss() }
                                        .onFailure { fail(it.message ?: "Routing failed. Check internet and try again.") }
                                }
                            }
                        }.show()
                } }
            }
        }
        dialog.show()
    }
}
