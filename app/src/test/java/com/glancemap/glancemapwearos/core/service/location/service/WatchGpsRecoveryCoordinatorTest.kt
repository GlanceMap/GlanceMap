package com.glancemap.glancemapwearos.core.service.location.service

import android.location.Location
import com.glancemap.glancemapwearos.core.service.location.adapters.CurrentLocationRequestParams
import com.glancemap.glancemapwearos.core.service.location.adapters.LocationGateway
import com.glancemap.glancemapwearos.core.service.location.adapters.LocationUpdateRequestParams
import com.glancemap.glancemapwearos.core.service.location.adapters.LocationUpdateSink
import com.glancemap.glancemapwearos.core.service.location.telemetry.LocationServiceTelemetry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchGpsRecoveryCoordinatorTest {
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun recoveredWatchGpsPointUsesTheNormalCallbackProcessorAndRespectsCooldown() =
        runTest {
            val gateway = FakeLocationGateway(Location("gps"))
            var processedLocations = 0
            val coordinator =
                WatchGpsRecoveryCoordinator(
                    serviceScope = this,
                    telemetry = LocationServiceTelemetry(tag = "LocationTest", summaryIntervalMs = 60_000L),
                    locationGateway = gateway,
                    strictFreshMaxAgeMs = { 6_000L },
                    processRecoveredLocation = {
                        processedLocations += 1
                        true
                    },
                    elapsedRealtimeMs = { 20_100L },
                )

            assertTrue(
                coordinator.maybeRequest(
                    nowElapsedMs = 20_000L,
                    fixGapMs = 19_000L,
                    staleThresholdMs = 15_000L,
                    expectedIntervalMs = 3_000L,
                ),
            )
            advanceUntilIdle()

            assertEquals(1, gateway.currentLocationRequests)
            assertEquals(1, processedLocations)
            assertTrue(
                coordinator.maybeRequest(
                    nowElapsedMs = 30_000L,
                    fixGapMs = 29_000L,
                    staleThresholdMs = 15_000L,
                    expectedIntervalMs = 3_000L,
                ),
            )
            advanceUntilIdle()
            assertEquals(1, gateway.currentLocationRequests)
        }

    private class FakeLocationGateway(
        private val currentLocation: Location?,
    ) : LocationGateway {
        var currentLocationRequests: Int = 0

        override suspend fun getCurrentLocation(request: CurrentLocationRequestParams): Location? {
            currentLocationRequests += 1
            return currentLocation
        }

        override suspend fun getLastLocation(): Location? = null

        override suspend fun requestLocationUpdates(
            request: LocationUpdateRequestParams,
            sink: LocationUpdateSink,
        ) = Unit

        override suspend fun removeLocationUpdates() = Unit

        override fun removeLocationUpdatesBestEffort() = Unit
    }
}
