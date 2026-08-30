package com.glancemap.glancemapcompanionapp.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.os.SystemClock
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
    val accuracyMeters: Float?,
    val fixElapsedRealtimeMillis: Long,
)

/** Keeps the latest foreground-map fix while location updates are temporarily unsubscribed. */
internal data class PhoneMapLocationSubscription(
    val isActive: Boolean = false,
    val latestLocation: PhoneMapLocation? = null,
) {
    fun start(): PhoneMapLocationSubscription = copy(isActive = true)

    fun stop(): PhoneMapLocationSubscription = copy(isActive = false)

    fun update(location: PhoneMapLocation): PhoneMapLocationSubscription = copy(latestLocation = location)
}

/** Foreground map-only location stream shared by the online and offline renderers. */
internal class PhoneMapLocationSource(
    context: Context,
) {
    private val locationClient = LocationServices.getFusedLocationProviderClient(context.applicationContext)
    private val _location = MutableStateFlow<PhoneMapLocation?>(null)
    val location: StateFlow<PhoneMapLocation?> = _location.asStateFlow()
    private var subscription = PhoneMapLocationSubscription()
    private val callback =
        object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (subscription.isActive) result.lastLocation?.let(::publish)
            }
        }

    @SuppressLint("MissingPermission")
    fun start() {
        if (subscription.isActive) return
        subscription = subscription.start()
        locationClient.lastLocation.addOnSuccessListener { location ->
            if (subscription.isActive) location?.let(::publish)
        }
        locationClient.requestLocationUpdates(
            LocationRequest
                .Builder(Priority.PRIORITY_HIGH_ACCURACY, PHONE_MAP_LOCATION_INTERVAL_MS)
                .setMinUpdateIntervalMillis(PHONE_MAP_LOCATION_MIN_INTERVAL_MS)
                .build(),
            callback,
            Looper.getMainLooper(),
        )
    }

    fun stop() {
        if (!subscription.isActive) return
        subscription = subscription.stop()
        locationClient.removeLocationUpdates(callback)
    }

    private fun publish(location: Location) {
        val fix =
            PhoneMapLocation(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
                fixElapsedRealtimeMillis =
                    (location.elapsedRealtimeNanos / NANOSECONDS_PER_MILLISECOND)
                        .takeIf { it > 0L }
                        ?: SystemClock.elapsedRealtime(),
            )
        subscription = subscription.update(fix)
        _location.value = subscription.latestLocation
    }
}

private const val PHONE_MAP_LOCATION_INTERVAL_MS = 2_000L
private const val PHONE_MAP_LOCATION_MIN_INTERVAL_MS = 1_000L
private const val NANOSECONDS_PER_MILLISECOND = 1_000_000L
