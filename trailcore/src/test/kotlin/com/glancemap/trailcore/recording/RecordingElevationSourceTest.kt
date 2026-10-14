package com.glancemap.trailcore.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingElevationSourceTest {
    @Test
    fun sourceSelectionMatchesTheOriginalWatchIncludingMissingDemAndSeaLevel() {
        for (gps in listOf(null, 123.0)) {
            assertEquals(
                RecordingAbsoluteElevation(null, "DEM_MISSING"),
                resolveRecordingAbsoluteElevation("DEM", demElevationMeters = null, gpsElevationMeters = gps),
            )
            assertEquals(
                RecordingAbsoluteElevation(0.0, "DEM"),
                resolveRecordingAbsoluteElevation("DEM", demElevationMeters = 0.0, gpsElevationMeters = gps),
            )
            assertEquals(
                RecordingAbsoluteElevation(0.0, "DEM"),
                resolveRecordingAbsoluteElevation("AUTO", demElevationMeters = 0.0, gpsElevationMeters = gps),
            )
            assertEquals(
                RecordingAbsoluteElevation(gps, if (gps == null) "AUTO" else "GPS"),
                resolveRecordingAbsoluteElevation("AUTO", demElevationMeters = null, gpsElevationMeters = gps),
            )
            assertEquals(
                RecordingAbsoluteElevation(gps, "GPS"),
                resolveRecordingAbsoluteElevation("GPS", demElevationMeters = 100.0, gpsElevationMeters = gps),
            )
            assertEquals(
                RecordingAbsoluteElevation(null, "DISABLED"),
                resolveRecordingAbsoluteElevation("DISABLED", demElevationMeters = 100.0, gpsElevationMeters = gps),
            )
        }
    }
}
