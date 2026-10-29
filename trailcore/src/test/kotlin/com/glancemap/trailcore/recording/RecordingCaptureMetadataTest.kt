package com.glancemap.trailcore.recording

import com.glancemap.trailcore.geo.GeoPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingCaptureMetadataTest {
    @Test
    fun gpxPreservesMeasuredLocationQualityWithoutInventingValues() {
        val point =
            RecordingPoint(
                GeoPoint(46.0, 6.0),
                100.0,
                1000,
                5f,
                null,
                locationProvider = "gps",
                gpsVerticalAccuracyMeters = 7f,
                gpsSpeedAccuracyMps = 0.5f,
                locationAgeMillis = 1200,
            )
        val gpx = encodeRecordedTraceAsGpx("Walk", listOf(point)).toString(Charsets.UTF_8)
        assertTrue(gpx.contains("<gmap:locationProvider>gps</gmap:locationProvider>"))
        assertTrue(gpx.contains("<gmap:gpsVerticalAccuracyMeters>7.00</gmap:gpsVerticalAccuracyMeters>"))
        assertTrue(gpx.contains("<gmap:locationAgeMillis>1200</gmap:locationAgeMillis>"))
        assertFalse(gpx.contains("satelliteCount"))
    }

    @Test
    fun gpxIncludesKnownCaptureMetadataAndEscapesValues() {
        val point = RecordingPoint(GeoPoint(46.0, 6.0), 100.0, 1000, 5f, null)
        val gpx =
            encodeRecordedTraceAsGpx(
                "Walk",
                listOf(point),
                captureMetadata =
                    RecordingCaptureMetadata(
                        appVersion = "1.3",
                        deviceManufacturer = "A & B",
                        deviceModel = "Model",
                        sampleIntervalSecondsAtStart = 5,
                        powerSaveModeAtStart = false,
                    ),
            ).toString(Charsets.UTF_8)
        assertTrue(gpx.contains("<gmap:recordingAppVersion>1.3</gmap:recordingAppVersion>"))
        assertTrue(gpx.contains("creator=\"GlanceMap 1.3\""))
        assertTrue(gpx.contains("<src>A &amp; B Model</src>"))
        assertTrue(gpx.contains("<gmap:recordingDeviceManufacturer>A &amp; B</gmap:recordingDeviceManufacturer>"))
        assertTrue(gpx.contains("<gmap:recordingPowerSaveModeAtStart>false</gmap:recordingPowerSaveModeAtStart>"))
        assertFalse(gpx.contains("recordingAndroidApiLevel"))
    }
}
