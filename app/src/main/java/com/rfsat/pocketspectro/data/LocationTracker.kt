package com.rfsat.pocketspectro.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GeoFix(
    val timeMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double?,
    val accuracyM: Float?,
    val speedMs: Float?,
    val bearingDeg: Float?,
)

/**
 * GPS position for tagging spectra and detections (replaces gpsd on the Pi). Uses the
 * platform LocationManager so the app needs no Google Play services.
 */
class LocationTracker(private val context: Context) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val state = MutableStateFlow<GeoFix?>(null)
    val fix: StateFlow<GeoFix?> = state.asStateFlow()
    private var running = false

    private val listener = LocationListener { loc -> state.value = loc.toFix() }

    fun hasPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (running || !hasPermission()) return
        running = true
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (!manager.allProviders.contains(p)) continue
            try {
                manager.getLastKnownLocation(p)?.let { if (state.value == null) state.value = it.toFix() }
                manager.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper())
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        try { manager.removeUpdates(listener) } catch (_: Exception) {}
    }

    /** Latest fix, or null when none or older than [maxAgeMs]. */
    fun current(maxAgeMs: Long = 15_000L): GeoFix? = state.value?.takeIf { System.currentTimeMillis() - it.timeMs <= maxAgeMs }

    private fun Location.toFix() = GeoFix(
        timeMs = System.currentTimeMillis(),
        latitude = latitude,
        longitude = longitude,
        altitudeM = if (hasAltitude()) altitude else null,
        accuracyM = if (hasAccuracy()) accuracy else null,
        speedMs = if (hasSpeed()) speed else null,
        bearingDeg = if (hasBearing()) bearing else null,
    )
}
