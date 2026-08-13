package com.glancemap.glancemapwearos.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingProgressVibrationIntervalsTest {
    @Test
    fun distanceOffersFiftyMeterStepsThroughOneKilometerThenKeepsLargerIntervals() {
        assertEquals(50, recordingProgressVibrationDistanceMetersOptions.first())
        assertEquals(1_000, recordingProgressVibrationDistanceMetersOptions[19])
        assertEquals(1_500, recordingProgressVibrationDistanceMetersOptions[20])
        assertEquals(10_000, recordingProgressVibrationDistanceMetersOptions.last())
    }

    @Test
    fun timeOffersEveryMinuteThroughTenThenUsesFiveMinuteSteps() {
        assertEquals((1..10).toList(), recordingProgressVibrationTimeMinutesOptions.take(10))
        assertEquals(15, recordingProgressVibrationTimeMinutesOptions[10])
        assertEquals(120, recordingProgressVibrationTimeMinutesOptions.last())
    }

    @Test
    fun unsupportedPersistedIntervalsSnapToTheNearestAvailableChoice() {
        assertEquals(
            100,
            nearestRecordingProgressVibrationInterval(
                value = 125,
                options = recordingProgressVibrationDistanceMetersOptions,
            ),
        )
        assertEquals(
            10,
            nearestRecordingProgressVibrationInterval(
                value = 12,
                options = recordingProgressVibrationTimeMinutesOptions,
            ),
        )
    }
}
