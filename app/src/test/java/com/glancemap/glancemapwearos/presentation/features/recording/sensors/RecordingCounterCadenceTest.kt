package com.glancemap.glancemapwearos.presentation.features.recording.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingCounterCadenceTest {
    @Test
    fun compressedCounterUpdatesCannotPublishThousandsOfStepsPerMinute() {
        val runtime = RecordingSensorRuntimeState()
        runtime.updateStepCounter(100f, 1_000L)

        val firstBurst = runtime.updateStepCounter(108f, 1_180L)
        val secondBurst = runtime.updateStepCounter(118f, 1_360L)

        assertEquals(8, firstBurst.steps)
        assertEquals(18, secondBurst.steps)
        assertNull(firstBurst.cadenceSpm)
        assertNull(secondBurst.cadenceSpm)
    }

    @Test
    fun shortCounterChangesAccumulateUntilRealElapsedTimeSupportsCadence() {
        val runtime = RecordingSensorRuntimeState()
        runtime.updateStepCounter(100f, 1_000L)
        assertNull(runtime.updateStepCounter(108f, 1_180L).cadenceSpm)
        assertNull(runtime.updateStepCounter(110f, 1_360L).cadenceSpm)

        val recovered = runtime.updateStepCounter(113f, 7_000L)

        assertEquals(13, recovered.steps)
        assertEquals(130, recovered.cadenceSpm)
    }

    @Test
    fun shortUpdateAfterCredibleEstimateCannotOverwriteOrRefreshIt() {
        val runtime = RecordingSensorRuntimeState()
        runtime.updateStepCounter(100f, 1_000L)
        assertEquals(130, runtime.updateStepCounter(113f, 7_000L).cadenceSpm)

        assertNull(runtime.updateStepCounter(123f, 7_180L).cadenceSpm)
        assertNull(runtime.updateStepCounter(123f, 9_500L).cadenceSpm)
        assertEquals(2, runtime.updateStepDetector(22_001L))
    }

    @Test
    fun implausibleCounterRateWaitsForCredibleTimingWithoutLosingSteps() {
        val runtime = RecordingSensorRuntimeState()
        runtime.updateStepCounter(100f, 1_000L)
        val burst = runtime.updateStepCounter(150f, 6_000L)

        assertEquals(50, burst.steps)
        assertNull(burst.cadenceSpm)
        val recovered = runtime.updateStepCounter(152f, 25_000L)
        assertEquals(52, recovered.steps)
        assertEquals(130, recovered.cadenceSpm)
    }

    @Test
    fun outOfOrderCounterCannotReplaceTheAccumulatedWindow() {
        val runtime = RecordingSensorRuntimeState()
        runtime.updateStepCounter(100f, 1_000L)
        assertNull(runtime.updateStepCounter(108f, 4_000L).cadenceSpm)
        assertNull(runtime.updateStepCounter(107f, 3_000L).cadenceSpm)

        assertEquals(130, runtime.updateStepCounter(113f, 7_000L).cadenceSpm)
    }

    @Test
    fun observedStationaryWindowDoesNotDiluteCadenceAfterWalkingResumes() {
        val runtime = RecordingSensorRuntimeState()
        runtime.updateStepCounter(100f, 1_000L)
        assertEquals(130, runtime.updateStepCounter(113f, 7_000L).cadenceSpm)
        assertNull(runtime.updateStepCounter(113f, 37_000L).cadenceSpm)

        assertEquals(130, runtime.updateStepCounter(126f, 43_000L).cadenceSpm)
    }

    @Test
    fun counterDecreaseAboveCadenceAnchorStartsFreshWindow() {
        val runtime = RecordingSensorRuntimeState()
        runtime.updateStepCounter(100f, 1_000L)
        runtime.updateStepCounter(120f, 2_000L)
        runtime.updateStepCounter(110f, 3_000L)

        assertEquals(130, runtime.updateStepCounter(123f, 9_000L).cadenceSpm)
    }

    @Test
    fun counterResetRebasesCadenceAndNewSessionClearsPendingBurst() {
        val runtime = RecordingSensorRuntimeState()
        runtime.updateStepCounter(100f, 1_000L)
        runtime.updateStepCounter(113f, 7_000L)
        assertNull(runtime.updateStepCounter(10f, 8_000L).cadenceSpm)
        assertEquals(130, runtime.updateStepCounter(23f, 14_000L).cadenceSpm)

        runtime.updateStepCounter(33f, 14_180L)
        runtime.reset()
        assertNull(runtime.updateStepCounter(5f, 15_000L).cadenceSpm)
        val nextSession = runtime.updateStepCounter(18f, 21_000L)
        assertEquals(13, nextSession.steps)
        assertEquals(130, nextSession.cadenceSpm)
    }
}
