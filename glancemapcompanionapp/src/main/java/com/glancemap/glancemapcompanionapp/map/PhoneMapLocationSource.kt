package com.glancemap.glancemapcompanionapp.map

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
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
    val altitudeMeters: Double? = null,
)

internal data class PhoneMapLocationAndroidMetadata(
    val accuracyMeters: Float?,
    val elapsedRealtimeNanos: Long?,
    val altitudeMeters: Double?,
)

internal fun PhoneMapLocation.toAndroidLocationMetadata(): PhoneMapLocationAndroidMetadata =
    PhoneMapLocationAndroidMetadata(
        accuracyMeters = accuracyMeters?.takeIf { it.isFinite() && it >= 0f },
        elapsedRealtimeNanos = fixElapsedRealtimeMillis.takeIf { it > 0L }?.times(1_000_000L),
        altitudeMeters = altitudeMeters?.takeIf { it.isFinite() },
    )

internal val phoneMapLocationPermissions =
    arrayOf(
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
    )

internal fun Context.hasPhoneMapLocationPermission(): Boolean =
    phoneMapLocationPermissions.any { permission ->
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

/** Keeps the latest foreground-map fix while location updates are temporarily unsubscribed. */
internal data class PhoneMapLocationSubscription(
    val generation: Long = 0L,
    val isActive: Boolean = false,
    val latestLocation: PhoneMapLocation? = null,
) {
    fun start(): PhoneMapLocationSubscription = copy(generation = generation + 1L, isActive = true)

    fun stop(): PhoneMapLocationSubscription = copy(isActive = false)

    fun update(
        generation: Long,
        location: PhoneMapLocation,
    ): PhoneMapLocationSubscription {
        val isCurrentSubscription = isActive && generation == this.generation
        val hasValidTimestamp = location.fixElapsedRealtimeMillis > 0L
        val isNotOlderThanLatest =
            latestLocation?.let { location.fixElapsedRealtimeMillis >= it.fixElapsedRealtimeMillis } ?: true
        return if (isCurrentSubscription && hasValidTimestamp && isNotOlderThanLatest) {
            copy(latestLocation = location)
        } else {
            this
        }
    }
}

/** Foreground map-only location stream shared by the online and offline renderers. */
internal class PhoneMapLocationSource(
    context: Context,
) {
    private val locationClient = LocationServices.getFusedLocationProviderClient(context.applicationContext)
    private val _location = MutableStateFlow<PhoneMapLocation?>(null)
    val location: StateFlow<PhoneMapLocation?> = _location.asStateFlow()
    private var subscription = PhoneMapLocationSubscription()
    private var activeCallback: LocationCallback? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (subscription.isActive) return
        subscription = subscription.start()
        val generation = subscription.generation
        val callback =
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { publish(it, generation) }
                }
            }
        activeCallback = callback
        locationClient.lastLocation.addOnSuccessListener { location ->
            location?.let { publish(it, generation) }
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
        activeCallback?.let(locationClient::removeLocationUpdates)
        activeCallback = null
    }

    private fun publish(
        location: Location,
        generation: Long,
    ) {
        val fix =
            PhoneMapLocation(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
                fixElapsedRealtimeMillis = location.elapsedRealtimeNanos / NANOSECONDS_PER_MILLISECOND,
                altitudeMeters = location.altitude.takeIf { location.hasAltitude() && it.isFinite() },
            )
        subscription = subscription.update(generation, fix)
        _location.value = subscription.latestLocation
    }
}

private const val PHONE_MAP_LOCATION_INTERVAL_MS = 2_000L
private const val PHONE_MAP_LOCATION_MIN_INTERVAL_MS = 1_000L
private const val NANOSECONDS_PER_MILLISECOND = 1_000_000L
