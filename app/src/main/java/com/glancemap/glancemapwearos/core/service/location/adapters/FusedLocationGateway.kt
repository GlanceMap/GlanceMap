package com.glancemap.glancemapwearos.core.service.location.adapters

import android.annotation.SuppressLint
import android.location.Location
import com.glancemap.glancemapwearos.core.service.location.policy.LocationSourceMode
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class FusedLocationGateway(
    private val client: FusedLocationProviderClient,
    private val callbackExecutor: Executor,
) : LocationGateway {
    private val requestMutex = Mutex()
    private val activeCallbacks = LinkedHashSet<LocationCallback>()

    @Volatile private var registeringCallback: LocationCallback? = null

    @SuppressLint("MissingPermission")
    override suspend fun getCurrentLocation(request: CurrentLocationRequestParams): Location? {
        val currentRequest =
            CurrentLocationRequest
                .Builder()
                .setPriority(request.priority)
                .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
                .setMaxUpdateAgeMillis(request.maxUpdateAgeMs)
                .setDurationMillis(request.durationMs)
                .build()
        return suspendCancellableCoroutine { continuation ->
            val cancellationTokenSource = CancellationTokenSource()
            continuation.invokeOnCancellation {
                cancellationTokenSource.cancel()
            }
            client
                .getCurrentLocation(currentRequest, cancellationTokenSource.token)
                .addOnSuccessListener { location ->
                    if (continuation.isActive) {
                        continuation.resume(location)
                    }
                }.addOnFailureListener { error ->
                    if (continuation.isActive) {
                        continuation.resumeWithException(error)
                    }
                }.addOnCanceledListener {
                    if (continuation.isActive) {
                        continuation.cancel()
                    }
                }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun getLastLocation(): Location? = client.lastLocation.await()

    @SuppressLint("MissingPermission")
    override suspend fun requestLocationUpdates(
        request: LocationUpdateRequestParams,
        sink: LocationUpdateSink,
    ) {
        requestMutex.withLock {
            removeLocationUpdatesLocked()
            val callback =
                object : LocationCallback() {
                    override fun onLocationAvailability(locationAvailability: LocationAvailability) {
                        sink.onLocationAvailability(locationAvailability.isLocationAvailable)
                    }

                    override fun onLocationResult(result: LocationResult) {
                        sink.onLocations(
                            LocationUpdateEvent(
                                origin = LocationSourceMode.AUTO_FUSED,
                                candidates = result.locations,
                                lastCandidate = result.lastLocation,
                                rawCandidateCount = result.locations.size,
                                maxUpdateDelayMs = request.maxUpdateDelayMs,
                            ),
                        )
                    }
                }
            val locationRequestBuilder =
                LocationRequest
                    .Builder(request.priority, request.intervalMs)
                    .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
                    .setMinUpdateIntervalMillis(request.intervalMs)
                    .setMinUpdateDistanceMeters(request.minDistanceMeters)
                    .setWaitForAccurateLocation(request.waitForAccurateLocation)
                    .setMaxUpdateDelayMillis(request.maxUpdateDelayMs)
            request.durationMs?.let(locationRequestBuilder::setDurationMillis)
            val locationRequest = locationRequestBuilder.build()
            registeringCallback = callback
            try {
                client.requestLocationUpdates(locationRequest, callbackExecutor, callback).await()
                synchronized(activeCallbacks) {
                    activeCallbacks += callback
                }
            } catch (error: Exception) {
                // Registration can still succeed even if coroutine is cancelled while awaiting the Task.
                runCatching {
                    withContext(NonCancellable) {
                        client.removeLocationUpdates(callback).await()
                    }
                }
                throw error
            } finally {
                if (registeringCallback === callback) {
                    registeringCallback = null
                }
            }
        }
    }

    override suspend fun removeLocationUpdates() {
        requestMutex.withLock {
            removeLocationUpdatesLocked()
        }
    }

    override suspend fun flushLocations() {
        client.flushLocations().await()
    }

    private suspend fun removeLocationUpdatesLocked() {
        val callbacks = drainCallbacks(includeRegisteringCallback = true)
        var firstError: Exception? = null
        callbacks.forEach { callback ->
            try {
                client.removeLocationUpdates(callback).await()
            } catch (error: Exception) {
                if (firstError == null) {
                    firstError = error
                }
            }
        }
        firstError?.let { throw it }
    }

    override fun removeLocationUpdatesBestEffort() {
        val callbacks = drainCallbacks(includeRegisteringCallback = true)
        callbacks.forEach { callback ->
            val task = runCatching { client.removeLocationUpdates(callback) }.getOrNull() ?: return@forEach
            task.addOnCompleteListener {
                synchronized(activeCallbacks) {
                    activeCallbacks.remove(callback)
                }
            }
        }
    }

    private fun drainCallbacks(includeRegisteringCallback: Boolean): List<LocationCallback> {
        synchronized(activeCallbacks) {
            val callbacks = LinkedHashSet<LocationCallback>()
            if (activeCallbacks.isNotEmpty()) {
                callbacks += activeCallbacks
                activeCallbacks.clear()
            }
            if (includeRegisteringCallback) {
                val pending = registeringCallback
                if (pending != null) {
                    callbacks += pending
                }
            }
            if (callbacks.isEmpty()) return emptyList()
            return callbacks.toList()
        }
    }
}
