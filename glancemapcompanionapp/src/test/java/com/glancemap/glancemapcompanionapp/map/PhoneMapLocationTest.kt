package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
