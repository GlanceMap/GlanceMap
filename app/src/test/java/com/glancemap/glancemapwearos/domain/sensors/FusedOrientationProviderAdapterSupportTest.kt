package com.glancemap.glancemapwearos.domain.sensors

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class FusedOrientationProviderAdapterSupportTest {
    @Test
    fun relativeMotionRetainsSourceTimeAndCannotCrossARequestRestart() {
        val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = unsafeField.get(null) as Unsafe
        val adapter = unsafe.allocateInstance(FusedOrientationProviderAdapter::class.java)
        val engine = FusedHeadingIntegrityEngine(relativeSensorAvailable = true, magnetometerAvailable = true)
        field("started").set(adapter, true)
        field("orientationRequestGeneration").set(adapter, 2L)
        field("_useFallbackProvider").set(adapter, MutableStateFlow(false))
        field("headingIntegrityEngine").set(adapter, engine)
        field("providerType").set(adapter, CompassProviderType.GOOGLE_FUSED)
        val update =
            FusedOrientationProviderAdapter::class.java
                .getDeclaredMethod(
                    "updateRelativeIntegritySnapshot",
                    Long::class.javaPrimitiveType,
                    RelativeHeadingWitness::class.java,
                    Long::class.javaPrimitiveType,
                ).apply { isAccessible = true }

        update.invoke(adapter, 2L, RelativeHeadingWitness(20f, 0.9f, displayRotation = 1), 1_200L)
        val sample = field("latestRelativeMotionSample").get(adapter) as CompassRelativeMotionSample
        assertEquals(1_200L, sample.atElapsedMs)
        assertEquals(2L, sample.provenance.generation)
        assertEquals(1, sample.displayRotation)

        update.invoke(adapter, 1L, RelativeHeadingWitness(200f, 0.9f), 1_300L)
        assertEquals(sample, field("latestRelativeMotionSample").get(adapter))
        assertEquals(20f, requireNotNull(engine.snapshot().relativeHeadingDeg), 0f)

        update.invoke(adapter, 2L, RelativeHeadingWitness(null, 0.1f), 1_400L)
        assertNull(field("latestRelativeMotionSample").get(adapter))
    }

    @Test
    fun publicationAndIntegrityCallbacksRejectARequestAfterRestartOrStop() {
        assertTrue(
            isCurrentFusedRequest(
                requestGeneration = 7L,
                activeRequestGeneration = 7L,
                started = true,
                usingFallback = false,
            ),
        )
        assertFalse(
            isCurrentFusedRequest(
                requestGeneration = 7L,
                activeRequestGeneration = 8L,
                started = true,
                usingFallback = false,
            ),
        )
        assertFalse(
            isCurrentFusedRequest(
                requestGeneration = 7L,
                activeRequestGeneration = 7L,
                started = false,
                usingFallback = false,
            ),
        )
        assertFalse(
            isCurrentFusedRequest(
                requestGeneration = 7L,
                activeRequestGeneration = 7L,
                started = true,
                usingFallback = true,
            ),
        )
    }

    @Test
    fun lowConfidenceGoogleHeadingRemainsUsableForDegradedTracking() {
        assertTrue(isUsableGoogleFusedHeadingError(45f))
        assertTrue(isUsableGoogleFusedHeadingError(179.9f))
        assertFalse(isUsableGoogleFusedHeadingError(180f))
        assertFalse(isUsableGoogleFusedHeadingError(Float.NaN))
    }

    @Test
    fun fusedHeadingPublishingCoalescesOverDeliveredHighPowerCallbacks() {
        assertTrue(
            shouldPublishFusedHeading(
                nowElapsedMs = 1_020L,
                lastPublishAtElapsedMs = 1_000L,
                lowPowerMode = false,
                force = true,
            ),
        )
        assertFalse(
            shouldPublishFusedHeading(
                nowElapsedMs = 1_020L,
                lastPublishAtElapsedMs = 1_000L,
                lowPowerMode = false,
                force = false,
            ),
        )
        assertTrue(
            shouldPublishFusedHeading(
                nowElapsedMs = 1_040L,
                lastPublishAtElapsedMs = 1_000L,
                lowPowerMode = false,
                force = false,
            ),
        )
    }

    @Test
    fun freshProviderStartDoesNotRetainThePreviousSessionHeading() {
        assertFalse(
            shouldRetainCachedFusedHeading(
                cachedHeadingAgeMs = 2_000L,
                retainCachedHeading = false,
            ),
        )
        assertTrue(
            shouldRetainCachedFusedHeading(
                cachedHeadingAgeMs = 20L,
                retainCachedHeading = true,
            ),
        )
    }

    @Test
    fun activeTurnPublishesNineteenMsCallbacksWhileIdleKeepsItsNormalCadence() {
        assertFalse(
            shouldPublishFusedHeading(
                nowElapsedMs = 1_015L,
                lastPublishAtElapsedMs = 1_000L,
                lowPowerMode = false,
                activeTurn = true,
                force = false,
            ),
        )
        assertTrue(
            shouldPublishFusedHeading(
                nowElapsedMs = 1_019L,
                lastPublishAtElapsedMs = 1_000L,
                lowPowerMode = false,
                activeTurn = true,
                force = false,
            ),
        )
        assertFalse(
            shouldPublishFusedHeading(
                nowElapsedMs = 1_019L,
                lastPublishAtElapsedMs = 1_000L,
                lowPowerMode = false,
                activeTurn = false,
                force = false,
            ),
        )
        assertTrue(
            shouldPublishFusedHeading(
                nowElapsedMs = 1_040L,
                lastPublishAtElapsedMs = 1_000L,
                lowPowerMode = false,
                activeTurn = false,
                force = false,
            ),
        )
    }

    @Test
    fun lowPowerHeadingPublishingKeepsFiveHertzCadence() {
        assertFalse(
            shouldPublishFusedHeading(
                nowElapsedMs = 1_179L,
                lastPublishAtElapsedMs = 1_000L,
                lowPowerMode = true,
                force = false,
            ),
        )
        assertTrue(
            shouldPublishFusedHeading(
                nowElapsedMs = 1_180L,
                lastPublishAtElapsedMs = 1_000L,
                lowPowerMode = true,
                force = false,
            ),
        )
    }

    @Test
    fun unusableFusedHeadingRequiresSamplesAndDurationBeforeFallback() {
        val first = unusableUpdate(nowMs = 1_000L, previous = null)
        val second = unusableUpdate(nowMs = 1_500L, previous = first)
        val third = unusableUpdate(nowMs = 2_100L, previous = second)

        assertFalse(first.shouldFallback)
        assertFalse(second.shouldFallback)
        assertTrue(third.shouldFallback)
        assertEquals(3, third.state.consecutiveSamples)
        assertEquals(1_100L, third.durationMs)
    }

    @Test
    fun repeatedUnusableCallbacksAccumulateAndUsableCallbackClearsTheFallbackStreak() {
        val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = unsafeField.get(null) as Unsafe
        val adapter = unsafe.allocateInstance(FusedOrientationProviderAdapter::class.java)

        fun set(
            name: String,
            value: Any,
        ) {
            field(name).set(adapter, value)
        }

        set("started", true)
        set("orientationRequestGeneration", 1L)
        set("_useFallbackProvider", MutableStateFlow(false))
        val accept =
            FusedOrientationProviderAdapter::class.java
                .getDeclaredMethod(
                    "acceptFusedMeasurement",
                    Long::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                ).apply { isAccessible = true }
        val unusable =
            FusedOrientationProviderAdapter::class.java
                .getDeclaredMethod(
                    "handleSustainedUnusableHeading",
                    Long::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                ).apply { isAccessible = true }

        repeat(3) { index ->
            val now = 1_000L + index * 200L
            accept.invoke(adapter, 1L, now, now, false)
            unusable.invoke(adapter, 1L, now, 180f, 180f)
        }
        assertTrue(field("consecutiveUnusableFusedSamples").getInt(adapter) > 1)
        assertEquals(0L, field("lastConfirmedFusedSampleElapsedRealtimeMs").getLong(adapter))

        accept.invoke(adapter, 1L, 2_000L, 2_000L, true)

        assertEquals(0, field("consecutiveUnusableFusedSamples").getInt(adapter))
        assertEquals(2_000L, field("lastConfirmedFusedSampleElapsedRealtimeMs").getLong(adapter))
    }

    @Test
    fun stoppedProviderMakesItsPendingReadyTimeoutANoOp() {
        assertFalse(
            isCurrentFusedReadyTimeout(
                timeoutIsCurrent = true,
                started = false,
                usingFallback = false,
                awaitingFusedReady = true,
                timeoutRequestGeneration = 4L,
                activeRequestGeneration = 4L,
            ),
        )
    }

    @Test
    fun oldReadyTimeoutCannotAffectARestartedFusedRequest() {
        assertFalse(
            isCurrentFusedReadyTimeout(
                timeoutIsCurrent = false,
                started = true,
                usingFallback = false,
                awaitingFusedReady = true,
                timeoutRequestGeneration = 4L,
                activeRequestGeneration = 6L,
            ),
        )
    }

    @Test
    fun fusedMeasurementsRequireStrictlyIncreasingSourceTimestamps() {
        assertEquals(
            FusedMeasurementOrder.ACCEPTED,
            classifyFusedMeasurementTimestamp(
                sourceMeasurementAtElapsedMs = 1_001L,
                previousSourceMeasurementAtElapsedMs = 0L,
            ),
        )
        assertEquals(
            FusedMeasurementOrder.DUPLICATE,
            classifyFusedMeasurementTimestamp(
                sourceMeasurementAtElapsedMs = 1_001L,
                previousSourceMeasurementAtElapsedMs = 1_001L,
            ),
        )
        assertEquals(
            FusedMeasurementOrder.OUT_OF_ORDER,
            classifyFusedMeasurementTimestamp(
                sourceMeasurementAtElapsedMs = 1_000L,
                previousSourceMeasurementAtElapsedMs = 1_001L,
            ),
        )
        assertEquals(
            FusedMeasurementOrder.ACCEPTED,
            classifyFusedMeasurementTimestamp(
                sourceMeasurementAtElapsedMs = 1_002L,
                previousSourceMeasurementAtElapsedMs = 1_001L,
            ),
        )
        assertTrue(
            isFusedSourceMeasurementStale(
                sourceMeasurementAtElapsedMs = 1_000L,
                callbackArrivalAtElapsedMs = 2_500L,
            ),
        )
        assertEquals(
            FusedMeasurementOrder.STALE_SOURCE,
            classifyFusedMeasurementTimestamp(
                sourceMeasurementAtElapsedMs = 1_000L,
                previousSourceMeasurementAtElapsedMs = 0L,
                callbackArrivalAtElapsedMs = 2_500L,
            ),
        )
        assertEquals(
            FusedMeasurementOrder.ACCEPTED,
            classifyFusedMeasurementTimestamp(
                sourceMeasurementAtElapsedMs = 2_000L,
                previousSourceMeasurementAtElapsedMs = 1_000L,
                callbackArrivalAtElapsedMs = 2_500L,
            ),
        )
    }

    @Test
    fun fusedSourceFreshnessUsesTheStrictSourceDeadlineAtEveryBoundary() {
        val sourceAtElapsedMs = 1_000L

        assertEquals(
            FusedMeasurementOrder.ACCEPTED,
            classifyFusedMeasurementTimestamp(
                sourceMeasurementAtElapsedMs = sourceAtElapsedMs,
                previousSourceMeasurementAtElapsedMs = 0L,
                callbackArrivalAtElapsedMs = 1_000L,
            ),
        )
        assertEquals(
            FusedMeasurementOrder.ACCEPTED,
            classifyFusedMeasurementTimestamp(
                sourceMeasurementAtElapsedMs = sourceAtElapsedMs,
                previousSourceMeasurementAtElapsedMs = 0L,
                callbackArrivalAtElapsedMs = 2_400L,
            ),
        )
        assertEquals(
            FusedMeasurementOrder.ACCEPTED,
            classifyFusedMeasurementTimestamp(
                sourceMeasurementAtElapsedMs = sourceAtElapsedMs,
                previousSourceMeasurementAtElapsedMs = 0L,
                callbackArrivalAtElapsedMs = 2_499L,
            ),
        )
        assertEquals(
            FusedMeasurementOrder.STALE_SOURCE,
            classifyFusedMeasurementTimestamp(
                sourceMeasurementAtElapsedMs = sourceAtElapsedMs,
                previousSourceMeasurementAtElapsedMs = 0L,
                callbackArrivalAtElapsedMs = 2_500L,
            ),
        )
        assertEquals(
            FusedMeasurementOrder.STALE_SOURCE,
            classifyFusedMeasurementTimestamp(
                sourceMeasurementAtElapsedMs = sourceAtElapsedMs,
                previousSourceMeasurementAtElapsedMs = 0L,
                callbackArrivalAtElapsedMs = 2_501L,
            ),
        )
        assertEquals(
            FusedMeasurementOrder.FUTURE_SOURCE,
            classifyFusedMeasurementTimestamp(
                sourceMeasurementAtElapsedMs = 2_501L,
                previousSourceMeasurementAtElapsedMs = 0L,
                callbackArrivalAtElapsedMs = 2_500L,
            ),
        )
    }

    @Test
    fun nearStaleTimeoutUsesRemainingBudgetAndReadsTheNewestSourceAtExpiry() {
        assertEquals(
            100L,
            fusedSourceFreshnessRemainingMs(
                sourceMeasurementAtElapsedMs = 1_000L,
                nowElapsedMs = 2_400L,
            ),
        )
        assertEquals(
            1L,
            fusedSourceFreshnessRemainingMs(
                sourceMeasurementAtElapsedMs = 1_000L,
                nowElapsedMs = 2_499L,
            ),
        )
        assertEquals(
            0L,
            fusedSourceFreshnessRemainingMs(
                sourceMeasurementAtElapsedMs = 1_000L,
                nowElapsedMs = 2_500L,
            ),
        )

        // A's timer runs at 2500 ms, but the adapter reads B (source 2450 ms) as authoritative.
        assertEquals(
            1_450L,
            fusedSourceFreshnessRemainingMs(
                sourceMeasurementAtElapsedMs = 2_450L,
                nowElapsedMs = 2_500L,
            ),
        )
        assertTrue(
            isFusedHeadingSampleFresh(
                sourceMeasurementAtElapsedMs = 2_450L,
                nowElapsedMs = 2_500L,
            ),
        )
        assertFalse(
            isFusedHeadingSampleFresh(
                sourceMeasurementAtElapsedMs = 2_501L,
                nowElapsedMs = 2_500L,
            ),
        )
    }

    private fun unusableUpdate(
        nowMs: Long,
        previous: FusedUnusableHeadingUpdate?,
    ): FusedUnusableHeadingUpdate =
        computeFusedUnusableHeadingUpdate(
            nowElapsedMs = nowMs,
            consecutiveUnusableSamples = previous?.state?.consecutiveSamples ?: 0,
            firstUnusableSampleAtElapsedMs = previous?.state?.firstSampleAtElapsedMs ?: 0L,
            minSamples = 3,
            minDurationMs = 1_000L,
        )

    private fun field(name: String) =
        FusedOrientationProviderAdapter::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
}
