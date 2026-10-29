package com.glancemap.glancemapwearos.core.service.location.adapters

import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.location.LocationListenerCompat
import com.glancemap.glancemapwearos.core.service.location.policy.LocationFixPolicy
import com.glancemap.glancemapwearos.core.service.location.policy.LocationSourceMode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executor

internal class PassiveExternalLocationGateway(
    private val locationManager: LocationManager,
    private val callbackExecutor: Executor,
) : LocationGateway {
    private val requestMutex = Mutex()
    private val activeListeners = LinkedHashSet<LocationListenerCompat>()

    @Volatile private var registeringListener: LocationListenerCompat? = null

    @SuppressLint("MissingPermission")
    override suspend fun getCurrentLocation(request: CurrentLocationRequestParams): Location? {
        val location = getLastLocation() ?: return null
        val ageMs = LocationFixPolicy.locationAgeMs(location, SystemClock.elapsedRealtime())
        return location.takeIf { ageMs <= request.maxUpdateAgeMs }
    }

    @SuppressLint("MissingPermission")
    override suspend fun getLastLocation(): Location? {
        ensurePassiveProviderPresent()
        return locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
    }

    @SuppressLint("MissingPermission")
    @Suppress("TooGenericExceptionCaught")
    override suspend fun requestLocationUpdates(
        request: LocationUpdateRequestParams,
        sink: LocationUpdateSink,
    ) {
        requestMutex.withLock {
            ensurePassiveProviderPresent()
            removeLocationUpdatesLocked()
            val listener =
                object : LocationListenerCompat {
                    override fun onLocationChanged(location: Location) {
                        sink.onLocationAvailability(true)
                        sink.onLocations(
                            LocationUpdateEvent(
                                origin = LocationSourceMode.PASSIVE_EXTERNAL,
                                candidates = listOf(location),
                            ),
                        )
                    }

                    override fun onLocationChanged(locations: MutableList<Location>) {
                        if (locations.isEmpty()) return
                        sink.onLocationAvailability(true)
                        sink.onLocations(
                            LocationUpdateEvent(
                                origin = LocationSourceMode.PASSIVE_EXTERNAL,
                                candidates = locations.toList(),
                                rawCandidateCount = locations.size,
                            ),
                        )
                    }
                }

            registeringListener = listener
            try {
                locationManager.requestLocationUpdatesCompat(
                    LocationManager.PASSIVE_PROVIDER,
                    request,
                    callbackExecutor,
                    listener,
                )
                synchronized(activeListeners) {
                    activeListeners += listener
                }
            } catch (error: RuntimeException) {
                runCatching { locationManager.removeLocationUpdatesCompat(listener) }
                synchronized(activeListeners) {
                    activeListeners.remove(listener)
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
            removeLocationUpdatesLocked()
        }
    }

    override fun removeLocationUpdatesBestEffort() {
        drainListeners(includeRegisteringListener = true).forEach { listener ->
            val removed = runCatching { locationManager.removeLocationUpdatesCompat(listener) }.isSuccess
            if (removed) {
                synchronized(activeListeners) {
                    activeListeners.remove(listener)
                }
            }
        }
    }

    fun isPassiveProviderPresent(): Boolean =
        runCatching {
            locationManager.allProviders.contains(LocationManager.PASSIVE_PROVIDER)
        }.getOrDefault(false)

    @Suppress("TooGenericExceptionCaught")
    private fun removeLocationUpdatesLocked() {
        val listeners = drainListeners(includeRegisteringListener = true)
        var firstError: RuntimeException? = null
        listeners.forEach { listener ->
            try {
                locationManager.removeLocationUpdatesCompat(listener)
            } catch (error: RuntimeException) {
                if (firstError == null) {
                    firstError = error
                }
            }
        }
        firstError?.let { throw it }
    }

    private fun drainListeners(includeRegisteringListener: Boolean): List<LocationListenerCompat> {
        synchronized(activeListeners) {
            val listeners = LinkedHashSet<LocationListenerCompat>()
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
            return listeners.toList()
        }
    }

    private fun ensurePassiveProviderPresent() {
        check(isPassiveProviderPresent()) { "Passive location provider is not present" }
    }
}
