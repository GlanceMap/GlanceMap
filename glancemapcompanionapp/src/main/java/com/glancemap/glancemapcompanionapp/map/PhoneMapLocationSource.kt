package com.glancemap.glancemapcompanionapp.map

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
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

internal enum class PhoneMapLocationUpdateReason {
    ACCEPTED,
    INACTIVE_SUBSCRIPTION,
    INVALID_TIMESTAMP,
    OLDER_THAN_LATEST,
}

internal data class PhoneMapLocationCallbackCounts(
    val accepted: Int = 0,
    val rejectedInactiveSubscription: Int = 0,
    val rejectedInvalidTimestamp: Int = 0,
    val rejectedOlderFix: Int = 0,
) {
    fun record(reason: PhoneMapLocationUpdateReason): PhoneMapLocationCallbackCounts =
        when (reason) {
            PhoneMapLocationUpdateReason.ACCEPTED -> copy(accepted = accepted + 1)
            PhoneMapLocationUpdateReason.INACTIVE_SUBSCRIPTION ->
                copy(rejectedInactiveSubscription = rejectedInactiveSubscription + 1)
            PhoneMapLocationUpdateReason.INVALID_TIMESTAMP ->
                copy(rejectedInvalidTimestamp = rejectedInvalidTimestamp + 1)
            PhoneMapLocationUpdateReason.OLDER_THAN_LATEST -> copy(rejectedOlderFix = rejectedOlderFix + 1)
        }

    val total: Int
        get() = accepted + rejectedInactiveSubscription + rejectedInvalidTimestamp + rejectedOlderFix

    fun toCaptureLine(
        generation: Long,
        windowMillis: Long,
    ): String =
        "event=location_callback_summary generation=$generation window_ms=${windowMillis.coerceAtLeast(0L)} " +
            "accepted=$accepted rejected_inactive_subscription=$rejectedInactiveSubscription " +
            "rejected_invalid_timestamp=$rejectedInvalidTimestamp rejected_older_fix=$rejectedOlderFix"
}

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
    ): PhoneMapLocationSubscription =
        if (updateReason(generation, location) == PhoneMapLocationUpdateReason.ACCEPTED) {
            copy(latestLocation = location)
        } else {
            this
        }

    fun updateReason(
        generation: Long,
        location: PhoneMapLocation,
    ): PhoneMapLocationUpdateReason =
        when {
            !isActive || generation != this.generation -> PhoneMapLocationUpdateReason.INACTIVE_SUBSCRIPTION
            location.fixElapsedRealtimeMillis <= 0L -> PhoneMapLocationUpdateReason.INVALID_TIMESTAMP
            latestLocation?.let { location.fixElapsedRealtimeMillis < it.fixElapsedRealtimeMillis } == true ->
                PhoneMapLocationUpdateReason.OLDER_THAN_LATEST
            else -> PhoneMapLocationUpdateReason.ACCEPTED
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
    private var activeRequestProfile: PhoneMapLocationRequestProfile? = null
    private var callbackCounts = PhoneMapLocationCallbackCounts()
    private var callbackWindowStartedAtMs: Long? = null

    @SuppressLint("MissingPermission")
    fun start(profile: PhoneMapLocationRequestProfile) {
        if (subscription.isActive && activeRequestProfile == profile) return
        if (subscription.isActive) stop()
        flushCallbackSummary()
        subscription = subscription.start()
        activeRequestProfile = profile
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
            profile.locationRequest(),
            callback,
            Looper.getMainLooper(),
        )
        PhoneDebugCapture.log(
            TAG,
            "event=location_subscription_started generation=$generation profile=${profile.name.lowercase()}",
        )
    }

    fun stop() {
        if (!subscription.isActive) return
        flushCallbackSummary()
        PhoneDebugCapture.log(
            TAG,
            "event=location_subscription_stopped generation=${subscription.generation} " +
                "profile=${activeRequestProfile?.name?.lowercase()}",
        )
        subscription = subscription.stop()
        activeCallback?.let(locationClient::removeLocationUpdates)
        activeCallback = null
        activeRequestProfile = null
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
        val reason = subscription.updateReason(generation, fix)
        recordCallback(reason)
        subscription = subscription.update(generation, fix)
        _location.value = subscription.latestLocation
    }

    /** Seed active-capture logs when map recording starts after the location lifecycle already began. */
    fun publishCaptureSnapshot() {
        callbackCounts = PhoneMapLocationCallbackCounts()
        callbackWindowStartedAtMs = null
        PhoneDebugCapture.log(
            TAG,
            "event=location_subscription_snapshot active=${subscription.isActive} " +
                "generation=${subscription.generation}",
        )
    }

    /** Include the current aggregate when a short manual capture ends before the throttle interval. */
    fun finishCaptureDiagnostics() {
        flushCallbackSummary()
        callbackCounts = PhoneMapLocationCallbackCounts()
        callbackWindowStartedAtMs = null
    }

    private fun recordCallback(reason: PhoneMapLocationUpdateReason) {
        val nowMillis = monotonicMillis()
        if (callbackWindowStartedAtMs == null) callbackWindowStartedAtMs = nowMillis
        callbackCounts = callbackCounts.record(reason)
        if (nowMillis - requireNotNull(callbackWindowStartedAtMs) >= CALLBACK_SUMMARY_INTERVAL_MILLIS) {
            flushCallbackSummary(nowMillis)
        }
    }

    private fun flushCallbackSummary(nowMillis: Long = monotonicMillis()) {
        val windowStartedAtMs = callbackWindowStartedAtMs ?: return
        if (callbackCounts.total == 0) return
        PhoneDebugCapture.log(
            TAG,
            callbackCounts.toCaptureLine(subscription.generation, nowMillis - windowStartedAtMs),
        )
        callbackCounts = PhoneMapLocationCallbackCounts()
        callbackWindowStartedAtMs = nowMillis
    }

    private fun monotonicMillis(): Long = System.nanoTime() / NANOS_PER_MILLISECOND

    private fun PhoneMapLocationRequestProfile.locationRequest(): LocationRequest =
        when (this) {
            PhoneMapLocationRequestProfile.BROWSE ->
                LocationRequest
                    .Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, PHONE_MAP_BROWSE_LOCATION_INTERVAL_MS)
                    .setMinUpdateIntervalMillis(PHONE_MAP_BROWSE_LOCATION_MIN_INTERVAL_MS)
                    // A puck need not move for sensor jitter; 25 m is a useful browse-scale displacement.
                    .setMinUpdateDistanceMeters(PHONE_MAP_BROWSE_MIN_DISTANCE_METERS)
                    .build()
            PhoneMapLocationRequestProfile.FOLLOW ->
                LocationRequest
                    .Builder(Priority.PRIORITY_HIGH_ACCURACY, PHONE_MAP_FOLLOW_LOCATION_INTERVAL_MS)
                    .setMinUpdateIntervalMillis(PHONE_MAP_FOLLOW_LOCATION_MIN_INTERVAL_MS)
                    .build()
        }

    private companion object {
        const val TAG = "PhoneMapLocation"
        const val CALLBACK_SUMMARY_INTERVAL_MILLIS = 30_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

// Browsing updates are five times slower and balanced-power; the distance gate avoids waking on small drift.
private const val PHONE_MAP_BROWSE_LOCATION_INTERVAL_MS = 10_000L
private const val PHONE_MAP_BROWSE_LOCATION_MIN_INTERVAL_MS = 5_000L
private const val PHONE_MAP_BROWSE_MIN_DISTANCE_METERS = 25f
private const val PHONE_MAP_FOLLOW_LOCATION_INTERVAL_MS = 2_000L
private const val PHONE_MAP_FOLLOW_LOCATION_MIN_INTERVAL_MS = 1_000L
private const val NANOSECONDS_PER_MILLISECOND = 1_000_000L
