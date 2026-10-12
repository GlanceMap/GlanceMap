package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMapLocationTest {
    @Test
    fun androidLocationMetadataPreservesAccuracyElapsedRealtimeAndAltitude() {
        val metadata =
            PhoneMapLocation(
                latitude = 46.0,
                longitude = 7.0,
                accuracyMeters = 4.5f,
                fixElapsedRealtimeMillis = 1_234L,
                altitudeMeters = 1_250.0,
            ).toAndroidLocationMetadata()

        assertEquals(4.5f, metadata.accuracyMeters)
        assertEquals(1_234_000_000L, metadata.elapsedRealtimeNanos)
        assertEquals(1_250.0, metadata.altitudeMeters)
    }

    @Test
    fun androidLocationMetadataDropsInvalidOptionalValues() {
        val metadata =
            PhoneMapLocation(
                latitude = 46.0,
                longitude = 7.0,
                accuracyMeters = Float.NaN,
                fixElapsedRealtimeMillis = 0L,
                altitudeMeters = Double.POSITIVE_INFINITY,
            ).toAndroidLocationMetadata()

        assertNull(metadata.accuracyMeters)
        assertNull(metadata.elapsedRealtimeNanos)
        assertNull(metadata.altitudeMeters)
    }

    @Test
    fun olderCachedFixCannotReplaceNewerLiveFix() {
        val active = PhoneMapLocationSubscription().start()
        val liveFix = phoneMapLocation(latitude = 47.0, longitude = 8.0, elapsedMillis = 2_000L)
        val cachedFix = phoneMapLocation(latitude = 46.0, longitude = 7.0, elapsedMillis = 1_000L)

        val afterLive = active.update(active.generation, liveFix)
        val afterCached = afterLive.update(active.generation, cachedFix)

        assertEquals(liveFix, afterCached.latestLocation)
    }

    @Test
    fun callbackFromEarlierSubscriptionIsIgnoredAfterRestart() {
        val firstSubscription = PhoneMapLocationSubscription().start()
        val firstFix = phoneMapLocation(latitude = 46.0, longitude = 7.0, elapsedMillis = 1_000L)
        val restarted = firstSubscription.update(firstSubscription.generation, firstFix).stop().start()
        val currentFix = phoneMapLocation(latitude = 47.0, longitude = 8.0, elapsedMillis = 2_000L)
        val currentSubscription = restarted.update(restarted.generation, currentFix)
        val obsoleteFix = phoneMapLocation(latitude = 48.0, longitude = 9.0, elapsedMillis = 3_000L)

        val afterObsoleteCallback = currentSubscription.update(firstSubscription.generation, obsoleteFix)

        assertEquals(currentSubscription, afterObsoleteCallback)
        assertEquals(currentFix, afterObsoleteCallback.latestLocation)
    }

    @Test
    fun callbackWhileStoppedIsIgnoredAndLatestLocationIsRetained() {
        val active = PhoneMapLocationSubscription().start()
        val retainedFix = phoneMapLocation(latitude = 47.0, longitude = 8.0, elapsedMillis = 2_000L)
        val stopped = active.update(active.generation, retainedFix).stop()
        val lateFix = phoneMapLocation(latitude = 48.0, longitude = 9.0, elapsedMillis = 3_000L)

        val afterLateCallback = stopped.update(active.generation, lateFix)

        assertFalse(afterLateCallback.isActive)
        assertEquals(retainedFix, afterLateCallback.latestLocation)
    }

    @Test
    fun currentSubscriptionAcceptsNewerFixes() {
        val active = PhoneMapLocationSubscription().start()
        val previousFix = phoneMapLocation(latitude = 46.0, longitude = 7.0, elapsedMillis = 1_000L)
        val newerFix = phoneMapLocation(latitude = 47.0, longitude = 8.0, elapsedMillis = 2_000L)

        val previous = active.update(active.generation, previousFix)
        val current = previous.update(active.generation, newerFix)

        assertEquals(newerFix, current.latestLocation)
    }

    @Test
    fun invalidElapsedRealtimeDoesNotMakeAFixAppearNewer() {
        val active = PhoneMapLocationSubscription().start()
        val acceptedFix = phoneMapLocation(latitude = 47.0, longitude = 8.0, elapsedMillis = 2_000L)
        val previous = active.update(active.generation, acceptedFix)
        val missingTimestamp = phoneMapLocation(latitude = 48.0, longitude = 9.0, elapsedMillis = 0L)
        val invalidTimestamp = phoneMapLocation(latitude = 49.0, longitude = 10.0, elapsedMillis = -1L)

        assertEquals(previous, previous.update(active.generation, missingTimestamp))
        assertEquals(previous, previous.update(active.generation, invalidTimestamp))
    }

    @Test
    fun callbackReasonsAreAggregatedWithoutLocationDetails() {
        val active = PhoneMapLocationSubscription().start()
        val newest = phoneMapLocation(latitude = 47.0, longitude = 8.0, elapsedMillis = 2_000L)
        val older = phoneMapLocation(latitude = 46.0, longitude = 7.0, elapsedMillis = 1_000L)
        val counts =
            PhoneMapLocationCallbackCounts()
                .record(PhoneMapLocationUpdateReason.ACCEPTED)
                .record(PhoneMapLocationUpdateReason.INACTIVE_SUBSCRIPTION)
                .record(PhoneMapLocationUpdateReason.INVALID_TIMESTAMP)
                .record(PhoneMapLocationUpdateReason.OLDER_THAN_LATEST)

        assertEquals(PhoneMapLocationUpdateReason.ACCEPTED, active.updateReason(active.generation, newest))
        assertEquals(
            PhoneMapLocationUpdateReason.INACTIVE_SUBSCRIPTION,
            active.stop().updateReason(active.generation, newest),
        )
        assertEquals(
            PhoneMapLocationUpdateReason.INVALID_TIMESTAMP,
            active.updateReason(active.generation, newest.copy(fixElapsedRealtimeMillis = 0L)),
        )
        assertEquals(
            PhoneMapLocationUpdateReason.OLDER_THAN_LATEST,
            active.update(active.generation, newest).updateReason(active.generation, older),
        )
        assertEquals(1, counts.accepted)
        assertEquals(1, counts.rejectedInactiveSubscription)
        assertEquals(1, counts.rejectedInvalidTimestamp)
        assertEquals(1, counts.rejectedOlderFix)
        val line = counts.toCaptureLine(generation = active.generation, windowMillis = 3_000L)
        assertTrue(line.contains("accepted=1"))
        assertTrue(line.contains("rejected_older_fix=1"))
        assertFalse(line.contains("47.0"))
        assertFalse(line.contains("8.0"))
    }

    private fun phoneMapLocation(
        latitude: Double,
        longitude: Double,
        elapsedMillis: Long,
    ): PhoneMapLocation =
        PhoneMapLocation(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = 3f,
            fixElapsedRealtimeMillis = elapsedMillis,
            altitudeMeters = 900.0,
        )
}
