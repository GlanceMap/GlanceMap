package com.glancemap.glancemapwearos.presentation.features.recording.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingHealthPolicyTest {
    @Test
    fun `hiking falls back to supported walking and selects only requested metrics`() {
        val selection =
            selectRecordingHealthConfiguration(
                RecordingHealthRequest(RecordingHealthActivity.HIKE, heartRate = false, steps = true, cadence = true),
                RecordingHealthDeviceCapabilities(
                    listOf(
                        RecordingHealthExerciseCapabilities(RecordingHealthExercise.WALKING, true, true, true, false),
                    ),
                    supportsFrequentHeartRate = true,
                ),
            )!!

        assertEquals(RecordingHealthExercise.WALKING, selection.exercise)
        assertEquals(setOf(RecordingHealthMetric.STEPS_TOTAL), selection.activeKinds)
        assertTrue(selection.stepIntervals)
        assertFalse(selection.frequentHeartRate)
    }

    @Test
    fun `biking never borrows walking steps or requests empty default metric set`() {
        val selection =
            selectRecordingHealthConfiguration(
                RecordingHealthRequest(RecordingHealthActivity.BIKE, heartRate = false, steps = true, cadence = true),
                RecordingHealthDeviceCapabilities(
                    listOf(
                        RecordingHealthExerciseCapabilities(RecordingHealthExercise.BIKING, true, false, false, false),
                        RecordingHealthExerciseCapabilities(RecordingHealthExercise.WALKING, true, true, true, true),
                    ),
                ),
            )

        assertNull(selection)
    }

    @Test
    fun `frequent heart rate requires the selected metric request and device support`() {
        val request = RecordingHealthRequest(RecordingHealthActivity.HIKE, true, false, false, true)
        val exercise = RecordingHealthExerciseCapabilities(RecordingHealthExercise.HIKING, true, false, false, false)

        assertFalse(
            selectRecordingHealthConfiguration(
                request,
                RecordingHealthDeviceCapabilities(listOf(exercise)),
            )!!.frequentHeartRate,
        )
        assertTrue(
            selectRecordingHealthConfiguration(
                request,
                RecordingHealthDeviceCapabilities(listOf(exercise), true),
            )!!.frequentHeartRate,
        )
    }

    @Test
    fun `late batched samples remain historical but cannot refresh live metrics`() {
        val timeline = RecordingHealthTimeline()
        val old = sample(1_000L, 80.0)
        val current = sample(150_000L, 100.0)

        val delivery = timeline.accept(listOf(current, old), arrivalElapsedRealtimeMillis = 155_000L)

        assertEquals(listOf(old, current), delivery.historical)
        assertEquals(listOf(current), delivery.live)
        assertTrue(timeline.accept(listOf(old, current), 156_000L).historical.isEmpty())
    }

    @Test
    fun `out of order recent measurement cannot replace newer live measurement`() {
        val timeline = RecordingHealthTimeline()
        timeline.accept(listOf(sample(30_000L, 100.0)), 31_000L)

        val delivery = timeline.accept(listOf(sample(29_000L, 80.0)), 32_000L)

        assertEquals(1, delivery.historical.size)
        assertTrue(delivery.live.isEmpty())
    }

    @Test
    fun `same metric and source timestamp deduplicate even if repeated batch changes value`() {
        val timeline = RecordingHealthTimeline()
        timeline.accept(listOf(sample(1_000L, 100.0)), 2_000L)

        assertTrue(timeline.accept(listOf(sample(1_000L, 101.0)), 2_000L).historical.isEmpty())
    }

    @Test
    fun `invalid and future measurements never enter historical or live data`() {
        val delivery =
            RecordingHealthTimeline().accept(
                listOf(sample(1_000L, Double.NaN), sample(1_000L, 0.0), sample(3_000L, 100.0)),
                arrivalElapsedRealtimeMillis = 2_000L,
            )

        assertTrue(delivery.historical.isEmpty())
        assertTrue(delivery.live.isEmpty())
    }

    @Test
    fun `unreliable and no contact heart rate are rejected without rejecting missing accuracy`() {
        assertFalse(isUsableRecordingHealthHeartRate(80.0, "UNRELIABLE"))
        assertFalse(isUsableRecordingHealthHeartRate(80.0, "NO_CONTACT"))
        assertFalse(isUsableRecordingHealthHeartRate(Double.POSITIVE_INFINITY, "ACCURACY_HIGH"))
        assertFalse(isUsableRecordingHealthHeartRate(0.0, null))
        assertTrue(isUsableRecordingHealthHeartRate(80.0, null))
        assertTrue(isUsableRecordingHealthHeartRate(80.0, "ACCURACY_LOW"))
    }

    private fun sample(
        elapsed: Long,
        value: Double,
    ) = RecordingHealthSample(RecordingHealthMetric.HEART_RATE, value, elapsed, 1_000_000L + elapsed)
}
