package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneMapDistanceMeasurementTest {
    @Test
    fun distanceUsesGreatCircleMeters() {
        val distance =
            phoneMapDistanceMeters(
                PhoneMapCoordinate(latitude = 0.0, longitude = 0.0),
                PhoneMapCoordinate(latitude = 0.0, longitude = 1.0),
            )

        assertEquals(111_195.0, distance, 100.0)
    }

    @Test
    fun distanceFormatsUsingSelectedUnits() {
        assertEquals("1.5 km", formatPhoneMapMeasuredDistance(1_500.0, isMetric = true))
        assertEquals("1.0 mi", formatPhoneMapMeasuredDistance(1_609.344, isMetric = false))
    }
}
