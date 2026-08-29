package com.glancemap.glancemapcompanionapp.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class PhoneMapLocation(
    val latitude: Double,
    val longitude: Double,
)

/** Foreground map-only location stream shared by the online and offline renderers. */
internal class PhoneMapLocationSource(
    context: Context,
) {
    private val locationClient = LocationServices.getFusedLocationProviderClient(context.applicationContext)
    private val _location = MutableStateFlow<PhoneMapLocation?>(null)
    val location: StateFlow<PhoneMapLocation?> = _location.asStateFlow()
    private var started = false
    private val callback =
        object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (started) result.lastLocation?.let(::publish)
            }
        }

    @SuppressLint("MissingPermission")
    fun start() {
        if (started) return
        started = true
        locationClient.lastLocation.addOnSuccessListener { location ->
            if (started) location?.let(::publish)
        }
        locationClient.requestLocationUpdates(
            LocationRequest
                .Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, PHONE_MAP_LOCATION_INTERVAL_MS)
                .setMinUpdateIntervalMillis(PHONE_MAP_LOCATION_MIN_INTERVAL_MS)
                .build(),
            callback,
            Looper.getMainLooper(),
        )
    }

    fun stop() {
        if (!started) return
        started = false
        locationClient.removeLocationUpdates(callback)
        _location.value = null
    }

    private fun publish(location: Location) {
        _location.value = PhoneMapLocation(latitude = location.latitude, longitude = location.longitude)
    }
}

private const val PHONE_MAP_LOCATION_INTERVAL_MS = 2_000L
private const val PHONE_MAP_LOCATION_MIN_INTERVAL_MS = 1_000L
