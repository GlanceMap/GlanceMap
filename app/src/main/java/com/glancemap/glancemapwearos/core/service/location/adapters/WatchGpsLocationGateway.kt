package com.glancemap.glancemapwearos.core.service.location.adapters

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import com.glancemap.glancemapwearos.core.service.location.policy.LocationSourceMode
import com.glancemap.glancemapwearos.core.service.location.telemetry.LocationServiceTelemetry
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.ArrayDeque
import java.util.concurrent.Executor
import kotlin.coroutines.resume

internal class WatchGpsLocationGateway(
    private val locationManager: LocationManager,
    private val packageManager: PackageManager,
    private val callbackExecutor: Executor,
    private val telemetry: LocationServiceTelemetry,
) : LocationGateway {
    private data class LocationSignature(
        val elapsedRealtimeNanos: Long,
        val timeMs: Long,
        val latitudeE7: Int,
        val longitudeE7: Int,
        val accuracyDeciMeters: Int,
    )

    private data class SanitizedBatch(
        val locations: List<Location>,
        val duplicateCount: Int,
    )

    private companion object {
        private const val MAX_RECENT_LOCATION_SIGNATURES = 64
    }

    private val activeListeners = LinkedHashSet<WatchGpsLocationListener>()
    private val requestMutex = Mutex()
    private val callbackTelemetryLock = Any()
    private val callbackTelemetrySession = WatchGpsCallbackTelemetrySession()

    @Volatile private var registeringListener: WatchGpsLocationListener? = null
    private val recentLocationSignatures = LinkedHashSet<LocationSignature>()
    private val recentLocationSignatureOrder = ArrayDeque<LocationSignature>()

    @SuppressLint("MissingPermission")
    override suspend fun getCurrentLocation(request: CurrentLocationRequestParams): Location? {
        ensureGpsProviderAvailable()
        val cachedLocation = getLastLocation()
        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (cachedLocation != null && locationAgeMs(cachedLocation, nowElapsedMs) <= request.maxUpdateAgeMs) {
            return cachedLocation
        }

        val currentLocation =
            withTimeoutOrNull(request.durationMs.coerceAtLeast(1L)) {
                suspendCancellableCoroutine { continuation ->
                    val cancellationSignal = CancellationSignal()
                    continuation.invokeOnCancellation { cancellationSignal.cancel() }
                    LocationManagerCompat.getCurrentLocation(
                        locationManager,
                        LocationManager.GPS_PROVIDER,
                        cancellationSignal,
                        callbackExecutor,
                    ) { location ->
                        if (continuation.isActive) {
                            continuation.resume(location)
                        }
                    }
                }
            }
        telemetry.logWatchGpsCurrentLocationResult(
            durationMs = (SystemClock.elapsedRealtime() - nowElapsedMs).coerceAtLeast(0L),
            returnedLocation = currentLocation != null,
        )
        return currentLocation
    }

    @SuppressLint("MissingPermission")
    override suspend fun getLastLocation(): Location? {
        ensureGpsProviderAvailable()
        return locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
    }

    @SuppressLint("MissingPermission")
    override suspend fun requestLocationUpdates(
        request: LocationUpdateRequestParams,
        sink: LocationUpdateSink,
    ) {
        requestMutex.withLock {
            ensureGpsProviderAvailable()
            val existingListener = activeListenerOrNull()
            val listener =
                existingListener?.also { it.sink = sink }
                    ?: run {
                        removeLocationUpdatesLocked(reason = "replace_listener")
                        clearRecentLocationSignatures()
                        WatchGpsLocationListener(sink)
                    }
            val isNewListener = existingListener == null

            sink.onLocationAvailability(isGpsProviderEnabled())
            registeringListener = listener
            try {
                if (isNewListener) {
                    startRawCallbackTelemetrySession()
                }
                locationManager.requestLocationUpdatesCompat(
                    LocationManager.GPS_PROVIDER,
                    request,
                    callbackExecutor,
                    listener,
                )
                synchronized(activeListeners) {
                    activeListeners += listener
                }
            } catch (error: Exception) {
                // Request registration may have succeeded before a cancellation/exception surfaced.
                runCatching { locationManager.removeLocationUpdatesCompat(listener) }
                synchronized(activeListeners) {
                    activeListeners.remove(listener)
                }
                if (isNewListener) {
                    finishRawCallbackTelemetrySession(reason = "registration_failed")
                }
                throw error
            } finally {
                if (registeringListener === listener) {
                    registeringListener = null
                }
            }
        }
    }

    override suspend fun removeLocationUpdates() {
        requestMutex.withLock {
            removeLocationUpdatesLocked(reason = "remove_requested")
        }
    }

    private fun removeLocationUpdatesLocked(reason: String) {
        val listeners = drainListeners(includeRegisteringListener = true)
        clearRecentLocationSignatures()
        var firstError: Exception? = null
        listeners.forEach { listener ->
            try {
                locationManager.removeLocationUpdatesCompat(listener)
            } catch (error: Exception) {
                if (firstError == null) {
                    firstError = error
                }
            }
        }
        finishRawCallbackTelemetrySession(reason = reason)
        firstError?.let { throw it }
    }

    override fun removeLocationUpdatesBestEffort() {
        clearRecentLocationSignatures()
        val listeners = drainListeners(includeRegisteringListener = true)
        listeners.forEach { listener ->
            val removed =
                runCatching {
                    locationManager.removeLocationUpdatesCompat(listener)
                }.isSuccess
            if (removed) {
                synchronized(activeListeners) {
                    activeListeners.remove(listener)
                }
            }
        }
        finishRawCallbackTelemetrySession(reason = "remove_best_effort")
    }

    fun availabilityReason(): WatchGpsAvailabilityReason =
        resolveWatchGpsAvailabilityReason(
            hasGpsHardwareFeature = hasGpsHardwareFeature(),
            isGpsProviderPresent = isGpsProviderPresent(),
            isGpsProviderEnabled = isGpsProviderEnabled(),
        )

    private inner class WatchGpsLocationListener(
        @Volatile var sink: LocationUpdateSink,
    ) : LocationListenerCompat {
        override fun onLocationChanged(location: Location) {
            sink.onLocationAvailability(true)
            recordRawCallback(rawLocationCount = 1)
            emitLocations(
                sink = sink,
                rawLocations = listOf(location),
            )
        }

        override fun onLocationChanged(locations: MutableList<Location>) {
            if (locations.isNotEmpty()) {
                sink.onLocationAvailability(true)
                recordRawCallback(rawLocationCount = locations.size)
                emitLocations(
                    sink = sink,
                    rawLocations = locations.toList(),
                )
            }
        }

        override fun onProviderEnabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) {
                sink.onLocationAvailability(true)
            }
        }

        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) {
                sink.onLocationAvailability(false)
            }
        }
    }

    private fun emitLocations(
        sink: LocationUpdateSink,
        rawLocations: List<Location>,
    ) {
        val sanitized = sanitizeLocations(rawLocations)
        recordDuplicatesDropped(sanitized.duplicateCount)
        if (sanitized.locations.isEmpty()) return
        sink.onLocations(
            LocationUpdateEvent(
                origin = LocationSourceMode.WATCH_GPS,
                candidates = sanitized.locations,
                rawCandidateCount = rawLocations.size,
                duplicateCandidatesDropped = sanitized.duplicateCount,
            ),
        )
    }

    private fun activeListenerOrNull(): WatchGpsLocationListener? {
        synchronized(activeListeners) {
            return activeListeners.singleOrNull()
        }
    }

    private fun drainListeners(includeRegisteringListener: Boolean): List<LocationListenerCompat> {
        synchronized(activeListeners) {
            val listeners = LinkedHashSet<WatchGpsLocationListener>()
            if (activeListeners.isNotEmpty()) {
                listeners += activeListeners
                activeListeners.clear()
            }
            if (includeRegisteringListener) {
                val pending = registeringListener
                if (pending != null) {
                    listeners += pending
                }
            }
            if (listeners.isEmpty()) return emptyList()
            return listeners.toList()
        }
    }

    private fun sanitizeLocations(locations: List<Location>): SanitizedBatch {
        if (locations.isEmpty()) return SanitizedBatch(emptyList(), 0)
        val uniqueLocations = ArrayList<Location>(locations.size)
        val batchSignatures = LinkedHashSet<LocationSignature>(locations.size)
        var duplicateCount = 0
        synchronized(recentLocationSignatures) {
            locations.forEach { location ->
                val signature = location.signature()
                val alreadySeen = signature in batchSignatures || signature in recentLocationSignatures
                if (alreadySeen) {
                    duplicateCount += 1
                } else {
                    batchSignatures += signature
                    rememberLocationSignature(signature)
                    uniqueLocations += location
                }
            }
        }
        return SanitizedBatch(
            locations = uniqueLocations,
            duplicateCount = duplicateCount,
        )
    }

    private fun rememberLocationSignature(signature: LocationSignature) {
        if (!recentLocationSignatures.add(signature)) return
        recentLocationSignatureOrder.addLast(signature)
        while (recentLocationSignatureOrder.size > MAX_RECENT_LOCATION_SIGNATURES) {
            val removed = recentLocationSignatureOrder.removeFirst()
            recentLocationSignatures.remove(removed)
        }
    }

    private fun clearRecentLocationSignatures() {
        synchronized(recentLocationSignatures) {
            recentLocationSignatures.clear()
            recentLocationSignatureOrder.clear()
        }
    }

    private fun startRawCallbackTelemetrySession() {
        synchronized(callbackTelemetryLock) {
            callbackTelemetrySession.start(nowElapsedMs = SystemClock.elapsedRealtime())
        }
    }

    private fun recordRawCallback(rawLocationCount: Int) {
        val firstCallbackDelayMs =
            synchronized(callbackTelemetryLock) {
                callbackTelemetrySession.recordRawCallback(
                    nowElapsedMs = SystemClock.elapsedRealtime(),
                    rawLocationCount = rawLocationCount,
                )
            }
        if (firstCallbackDelayMs != null) {
            telemetry.logWatchGpsFirstRawCallback(
                delayMs = firstCallbackDelayMs,
                rawLocationCount = rawLocationCount,
            )
        }
    }

    private fun recordDuplicatesDropped(duplicateCount: Int) {
        if (duplicateCount <= 0) return
        synchronized(callbackTelemetryLock) {
            callbackTelemetrySession.recordDuplicatesDropped(duplicateCount)
        }
    }

    private fun finishRawCallbackTelemetrySession(reason: String) {
        val summary =
            synchronized(callbackTelemetryLock) {
                callbackTelemetrySession.finish(
                    nowElapsedMs = SystemClock.elapsedRealtime(),
                    reason = reason,
                )
            } ?: return
        telemetry.logWatchGpsRawCallbackSummary(
            reason = summary.reason,
            runtimeMs = summary.runtimeMs,
            rawCallbackCount = summary.rawCallbackCount,
            rawLocationCount = summary.rawLocationCount,
            duplicatesDropped = summary.duplicatesDropped,
            firstRawCallbackDelayMs = summary.firstRawCallbackDelayMs,
        )
    }

    private fun ensureGpsProviderAvailable() {
        val availabilityReason =
            resolveWatchGpsAvailabilityReason(
                hasGpsHardwareFeature = hasGpsHardwareFeature(),
                isGpsProviderPresent = isGpsProviderPresent(),
                isGpsProviderEnabled = isGpsProviderEnabled(),
            )
        if (availabilityReason != WatchGpsAvailabilityReason.AVAILABLE) {
            throw WatchGpsUnavailableException(reason = availabilityReason)
        }
    }

    private fun hasGpsHardwareFeature(): Boolean =
        runCatching { packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS) }
            .getOrDefault(false)

    private fun isGpsProviderPresent(): Boolean =
        runCatching { locationManager.allProviders.contains(LocationManager.GPS_PROVIDER) }
            .getOrDefault(false)

    private fun isGpsProviderEnabled(): Boolean =
        runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }
            .getOrDefault(false)

    private fun locationAgeMs(
        location: Location,
        nowElapsedMs: Long,
    ): Long {
        val locationElapsedMs = location.elapsedRealtimeNanos / 1_000_000L
        if (locationElapsedMs <= 0L) return Long.MAX_VALUE
        return (nowElapsedMs - locationElapsedMs).coerceAtLeast(0L)
    }

    private fun Location.signature(): LocationSignature =
        LocationSignature(
            elapsedRealtimeNanos = elapsedRealtimeNanos,
            timeMs = time,
            latitudeE7 = (latitude * 1e7).toInt(),
            longitudeE7 = (longitude * 1e7).toInt(),
            accuracyDeciMeters = (accuracy * 10f).toInt(),
        )
}

internal data class WatchGpsRawCallbackSummary(
    val reason: String,
    val runtimeMs: Long,
    val rawCallbackCount: Int,
    val rawLocationCount: Int,
    val duplicatesDropped: Int,
    val firstRawCallbackDelayMs: Long?,
)

internal class WatchGpsCallbackTelemetrySession {
    private var startedAtElapsedMs: Long? = null
    private var rawCallbackCount: Int = 0
    private var rawLocationCount: Int = 0
    private var duplicatesDropped: Int = 0
    private var firstRawCallbackDelayMs: Long? = null

    fun start(nowElapsedMs: Long) {
        startedAtElapsedMs = nowElapsedMs
        rawCallbackCount = 0
        rawLocationCount = 0
        duplicatesDropped = 0
        firstRawCallbackDelayMs = null
    }

    fun recordRawCallback(
        nowElapsedMs: Long,
        rawLocationCount: Int,
    ): Long? {
        val startedAt = startedAtElapsedMs ?: return null
        rawCallbackCount += 1
        this.rawLocationCount += rawLocationCount.coerceAtLeast(0)
        if (firstRawCallbackDelayMs != null) return null
        return (nowElapsedMs - startedAt).coerceAtLeast(0L).also { delayMs ->
            firstRawCallbackDelayMs = delayMs
        }
    }

    fun recordDuplicatesDropped(count: Int) {
        duplicatesDropped += count.coerceAtLeast(0)
    }

    fun finish(
        nowElapsedMs: Long,
        reason: String,
    ): WatchGpsRawCallbackSummary? {
        val startedAt = startedAtElapsedMs ?: return null
        val summary =
            WatchGpsRawCallbackSummary(
                reason = reason,
                runtimeMs = (nowElapsedMs - startedAt).coerceAtLeast(0L),
                rawCallbackCount = rawCallbackCount,
                rawLocationCount = rawLocationCount,
                duplicatesDropped = duplicatesDropped,
                firstRawCallbackDelayMs = firstRawCallbackDelayMs,
            )
        startedAtElapsedMs = null
        return summary
    }
}
