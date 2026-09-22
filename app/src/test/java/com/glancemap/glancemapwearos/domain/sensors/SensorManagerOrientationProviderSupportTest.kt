package com.glancemap.glancemapwearos.domain.sensors

import android.hardware.SensorManager
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorManagerOrientationProviderSupportTest {
    @Test
    fun initialHeadingHasNoTimestampAndIsStale() {
        val freshness = SensorHeadingSampleFreshness()

        assertNull(freshness.sampleAtElapsedRealtimeMs)
        assertTrue(freshness.stale)
    }

    @Test
    fun publishedHeadingCarriesItsElapsedRealtimeAndIsFresh() {
        val freshness =
            SensorHeadingSampleFreshness.afterPublish(
                sampleAtElapsedRealtimeMs = 12_345L,
                arrivalAtElapsedRealtimeMs = 12_350L,
                sequenceId = 7L,
                heldOutput = true,
            )

        assertEquals(12_345L, freshness.sampleAtElapsedRealtimeMs)
        assertEquals(12_350L, freshness.arrivalAtElapsedRealtimeMs)
        assertEquals(7L, freshness.sequenceId)
        assertTrue(freshness.heldOutput)
        assertFalse(freshness.stale)
    }

    @Test
    fun equalNumericSensorEventsRetainDistinctSequenceIdentity() =
        runBlocking {
            val flow = MutableSharedFlow<SensorRawHeadingSample>(extraBufferCapacity = 8)
            val received = mutableListOf<SensorRawHeadingSample>()
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    flow.take(3).toList(received)
                }

            repeat(3) { index ->
                flow.emit(
                    SensorRawHeadingSample(
                        headingDeg = 42f,
                        sourceMeasurementAtElapsedRealtimeMs = 1_000L + index,
                        callbackArrivalAtElapsedRealtimeMs = 1_010L + index,
                        sequenceId = index + 1L,
                    ),
                )
            }
            collector.join()

            assertEquals(listOf(1L, 2L, 3L), received.map { it.sequenceId })
            assertEquals(
                listOf(1_000L, 1_001L, 1_002L),
                received.map { it.sourceMeasurementAtElapsedRealtimeMs },
            )
        }

    @Test
    fun sensorHeadingStalenessUsesSourceMeasurementTime() {
        assertFalse(isSensorHeadingSampleStale(sampleAtElapsedRealtimeMs = 1_000L, nowElapsedRealtimeMs = 2_499L))
        assertTrue(isSensorHeadingSampleStale(sampleAtElapsedRealtimeMs = 1_000L, nowElapsedRealtimeMs = 2_500L))
        assertTrue(isSensorHeadingSampleStale(sampleAtElapsedRealtimeMs = 1_000L, nowElapsedRealtimeMs = 999L))
    }

    @Test
    fun poorRotationVectorUncertaintyImmediatelyCapsCombinedAccuracy() {
        val update =
            computeCompassRotationVectorUpdate(
                previousUncertaintyDeg = 8f,
                values = floatArrayOf(0f, 0f, 0f, 0f, Math.toRadians(45.0).toFloat()),
                sensorAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
                inferredAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
                usingRotationVector = true,
                usingHeadingSensor = false,
                hasMagneticInterference = false,
            )

        assertTrue(update.uncertaintyDeg >= 40f)
        assertEquals(SensorManager.SENSOR_STATUS_ACCURACY_LOW, update.combinedAccuracy)
    }

    @Test
    fun lifecycleStopRetainsTimestampButMarksHeadingStale() {
        val stoppedFreshness =
            SensorHeadingSampleFreshness
                .afterPublish(sampleAtElapsedRealtimeMs = 12_345L)
                .markStale()

        assertEquals(12_345L, stoppedFreshness.sampleAtElapsedRealtimeMs)
        assertTrue(stoppedFreshness.stale)
    }

    @Test
    fun shutdownSensorThreadIsNeverReusedForFallbackRegistration() {
        assertFalse(
            shouldReuseSensorCallbackHandler(
                callbackThreadAlive = true,
                callbackThreadStopping = true,
            ),
        )
        assertTrue(
            shouldReuseSensorCallbackHandler(
                callbackThreadAlive = true,
                callbackThreadStopping = false,
            ),
        )
    }

    @Test
    fun rapidStopStartRejectsBothTheOldTimeoutAndStoppingHandler() {
        assertFalse(
            isCurrentFusedReadyTimeout(
                timeoutIsCurrent = false,
                started = true,
                usingFallback = false,
                awaitingFusedReady = true,
                timeoutRequestGeneration = 10L,
                activeRequestGeneration = 12L,
            ),
        )
        assertFalse(
            shouldReuseSensorCallbackHandler(
                callbackThreadAlive = true,
                callbackThreadStopping = true,
            ),
        )
    }
}
