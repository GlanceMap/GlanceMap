package com.glancemap.glancemapcompanionapp.livetracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveTrackingLocationPolicyTest {
    @Test
    fun startsWithHighAccuracyAndConfiguredMovingInterval() {
        val requests = RecordingLocationRequests()
        val updates = requests.controller()

        updates.start(updateIntervalSeconds = 60)

        assertEquals(
            LiveTrackingLocationRequest(
                priority = LiveTrackingLocationPriority.HIGH_ACCURACY,
                intervalMillis = 60_000L,
                minUpdateDistanceMeters = 0f,
            ),
            requests.active,
        )
    }

    @Test
    fun sustainedStationaryLocationSwitchesToBalancedPowerAtTwoMinuteCadence() {
        val requests = RecordingLocationRequests()
        val updates = requests.controller()
        updates.start(updateIntervalSeconds = 60)
        updates.onLocation(sample(elapsedRealtimeMillis = 0L), updateIntervalSeconds = 60)
        updates.onLocation(
            sample(elapsedRealtimeMillis = LiveTrackingLocationPolicySettings.STATIONARY_AFTER_MILLIS - 1L),
            updateIntervalSeconds = 60,
        )
        assertEquals(LiveTrackingLocationPriority.HIGH_ACCURACY, requests.active?.priority)

        updates.onLocation(
            sample(elapsedRealtimeMillis = LiveTrackingLocationPolicySettings.STATIONARY_AFTER_MILLIS),
            updateIntervalSeconds = 60,
        )

        assertEquals(
            LiveTrackingLocationRequest(
                priority = LiveTrackingLocationPriority.BALANCED_POWER,
                intervalMillis = 120_000L,
                minUpdateDistanceMeters = 25f,
            ),
            requests.active,
        )
    }

    @Test
    fun gpsJitterDoesNotResetStationaryTimerOrRestoreHighAccuracy() {
        val requests = RecordingLocationRequests()
        val updates = requests.controller()
        updates.start(updateIntervalSeconds = 60)
        updates.onLocation(sample(elapsedRealtimeMillis = 0L), updateIntervalSeconds = 60)
        updates.onLocation(
            sample(latitudeOffsetMeters = 9.0, elapsedRealtimeMillis = 9 * 60_000L),
            updateIntervalSeconds = 60,
        )
        updates.onLocation(
            sample(latitudeOffsetMeters = -8.0, elapsedRealtimeMillis = 10 * 60_000L),
            updateIntervalSeconds = 60,
        )
        assertEquals(LiveTrackingLocationPriority.BALANCED_POWER, requests.active?.priority)

        updates.onLocation(
            sample(latitudeOffsetMeters = 7.0, elapsedRealtimeMillis = 12 * 60_000L),
            updateIntervalSeconds = 60,
        )

        assertEquals(LiveTrackingLocationPriority.BALANCED_POWER, requests.active?.priority)
        assertEquals(2, requests.history.size)
    }

    @Test
    fun meaningfulMovementRestoresConfiguredHighAccuracyRequest() {
        val requests = RecordingLocationRequests()
        val updates = requests.controller()
        updates.start(updateIntervalSeconds = 60)
        updates.onLocation(sample(elapsedRealtimeMillis = 0L), updateIntervalSeconds = 60)
        updates.onLocation(
            sample(elapsedRealtimeMillis = LiveTrackingLocationPolicySettings.STATIONARY_AFTER_MILLIS),
            updateIntervalSeconds = 60,
        )
        updates.onLocation(
            sample(latitudeOffsetMeters = 40.0, elapsedRealtimeMillis = 11 * 60_000L),
            updateIntervalSeconds = 60,
        )

        assertEquals(
            LiveTrackingLocationRequest(
                priority = LiveTrackingLocationPriority.HIGH_ACCURACY,
                intervalMillis = 60_000L,
                minUpdateDistanceMeters = 0f,
            ),
            requests.active,
        )
    }

    @Test
    fun stoppingSessionReleasesRequestAndIgnoresLaterLocations() {
        val requests = RecordingLocationRequests()
        val updates = requests.controller()
        updates.start(updateIntervalSeconds = 60)

        updates.stop()
        updates.onLocation(
            sample(latitudeOffsetMeters = 100.0, elapsedRealtimeMillis = 20 * 60_000L),
            updateIntervalSeconds = 60,
        )

        assertNull(requests.active)
        assertEquals(1, requests.removeCount)
        assertEquals(1, requests.history.size)
    }

    @Test
    fun changingUserIntervalUpdatesAdaptiveRequestAndRetainsFrequentPreset() {
        val requests = RecordingLocationRequests()
        val updates = requests.controller()
        updates.start(updateIntervalSeconds = 60)
        updates.onLocation(sample(elapsedRealtimeMillis = 0L), updateIntervalSeconds = 60)
        updates.onLocation(
            sample(elapsedRealtimeMillis = LiveTrackingLocationPolicySettings.STATIONARY_AFTER_MILLIS),
            updateIntervalSeconds = 60,
        )

        updates.onLocation(
            sample(elapsedRealtimeMillis = 11 * 60_000L),
            updateIntervalSeconds = 300,
        )
        assertEquals(300_000L, requests.active?.intervalMillis)
        assertEquals(LiveTrackingLocationPriority.BALANCED_POWER, requests.active?.priority)

        updates.onLocation(
            sample(elapsedRealtimeMillis = 12 * 60_000L),
            updateIntervalSeconds = 15,
        )

        assertEquals(15_000L, requests.active?.intervalMillis)
        assertEquals(LiveTrackingLocationPriority.HIGH_ACCURACY, requests.active?.priority)
    }

    private fun sample(
        latitudeOffsetMeters: Double = 0.0,
        elapsedRealtimeMillis: Long,
    ) = LiveTrackingLocationSample(
        latitude = latitudeOffsetMeters / METERS_PER_DEGREE_LATITUDE,
        longitude = 0.0,
        elapsedRealtimeMillis = elapsedRealtimeMillis,
    )

    private class RecordingLocationRequests {
        val history = mutableListOf<LiveTrackingLocationRequest>()
        var active: LiveTrackingLocationRequest? = null
            private set
        var removeCount = 0
            private set

        fun controller() =
            LiveTrackingLocationUpdates(
                requestLocationUpdates = { request ->
                    history += request
                    active = request
                },
                removeLocationUpdates = {
                    removeCount += 1
                    active = null
                },
            )
    }

    private companion object {
        const val METERS_PER_DEGREE_LATITUDE = 111_195.0
    }
}
