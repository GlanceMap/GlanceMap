package com.glancemap.glancemapwearos.presentation.features.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

class RecordingGpxMetadataTest {
    @Test
    fun recordingIdentityStaysSeparateFromExporterVersionAndEscapesXml() {
        val metadata =
            RecordingCaptureMetadata(
                appVersion = "1.0 & beta",
                appBuild = 42,
                deviceManufacturer = "Example & Co",
                deviceModel = "Watch <test> \"2\"",
                androidVersion = "14",
                androidApiLevel = 34,
                androidBuild = "test-build",
                locationSourceModeAtStart = "WATCH_GPS",
                sampleIntervalSecondsAtStart = 3,
                effectiveLocationIntervalMillisAtStart = 1_000L,
                elevationSourceAtStart = "GPS",
                powerSaveModeAtStart = false,
            )
        val document = document(metadata = metadata, exporterVersion = "2.0 & test")

        assertEquals("GlanceMap 2.0 & test", document.documentElement.getAttribute("creator"))
        assertEquals("1.0 & beta", document.extension("recordingAppVersion"))
        assertEquals("42", document.extension("recordingAppBuild"))
        assertEquals("Example & Co", document.extension("recordingDeviceManufacturer"))
        assertEquals("Watch <test> \"2\"", document.extension("recordingDeviceModel"))
        assertEquals("14", document.extension("recordingAndroidVersion"))
        assertEquals("34", document.extension("recordingAndroidApiLevel"))
        assertEquals("test-build", document.extension("recordingAndroidBuild"))
        assertEquals("Example & Co Watch <test> \"2\"", document.getElementsByTagNameNS(GPX, "src").item(0).textContent)
        assertEquals("WATCH_GPS", document.extension("recordingLocationSourceModeAtStart"))
        assertEquals("3", document.extension("recordingSampleIntervalSecondsAtStart"))
        assertEquals("1000", document.extension("recordingEffectiveLocationIntervalMillisAtStart"))
        assertEquals("GPS", document.extension("recordingElevationSourceAtStart"))
        assertEquals("false", document.extension("recordingPowerSaveModeAtStart"))
    }

    @Test
    fun unknownRecordingIdentityAndInvalidValuesAreOmitted() {
        val legacy = document()
        val invalid =
            document(
                metadata =
                    RecordingCaptureMetadata(
                        deviceModel = "unknown",
                        appBuild = 0,
                        sampleIntervalSecondsAtStart = -1,
                    ),
                point =
                    point().copy(
                        locationQuality =
                            RecordedLocationQuality(
                                provider = "",
                                verticalAccuracyMeters = Float.NaN,
                                speedAccuracyMps = -1f,
                                ageMillis = -1L,
                            ),
                    ),
            )

        listOf(legacy, invalid).forEach { document ->
            listOf(
                "recordingDeviceModel",
                "recordingAppVersion",
                "recordingAppBuild",
                "recordingSampleIntervalSecondsAtStart",
                "locationProvider",
                "gpsVerticalAccuracyMeters",
                "gpsSpeedAccuracyMps",
                "locationAgeMillis",
            ).forEach { name ->
                assertEquals(name, 0, document.getElementsByTagNameNS(GMAP, name).length)
            }
        }
    }

    @Test
    fun pointQualityAndGarminHeartRateUseTheirOwnNamespacesWithoutReinterpretingSteps() {
        val document =
            document(
                point =
                    point().copy(
                        heartRateBpm = 142,
                        cadenceSpm = 164,
                        locationQuality = RecordedLocationQuality("gps", "WATCH_GPS", 4.5f, 0.25f, 120L),
                    ),
            )

        assertEquals("gps", document.extension("locationProvider"))
        assertEquals("WATCH_GPS", document.extension("locationSourceMode"))
        assertEquals("4.50", document.extension("gpsVerticalAccuracyMeters"))
        assertEquals("0.25", document.extension("gpsSpeedAccuracyMps"))
        assertEquals("120", document.extension("locationAgeMillis"))
        assertEquals("142", document.extension("heartRateBpm"))
        assertEquals("142", document.getElementsByTagNameNS(GARMIN, "hr").item(0).textContent)
        assertEquals("164", document.extension("cadenceSpm"))
        assertEquals(0, document.getElementsByTagNameNS(GARMIN, "cad").length)
        assertEquals(0, document.getElementsByTagNameNS(GPX, "hdop").length)
        assertEquals("1970-01-01T00:00:01Z", document.getElementsByTagNameNS(GPX, "time").item(0).textContent)
    }

    @Test
    fun outOfRangeHeartRateIsNotWrittenAsInvalidGarminData() {
        val document = document(point = point().copy(heartRateBpm = 300))

        assertEquals("300", document.extension("heartRateBpm"))
        assertEquals(0, document.getElementsByTagNameNS(GARMIN, "hr").length)
    }

    @Test
    fun exportCountsDescribeTheActualSegmentedGeometry() {
        val points = listOf(point(), point().copy(timeMillis = 5_000L, startsNewSegment = true))
        val xml = encodeRecordedTraceAsGpx("gaps", points).toString(Charsets.UTF_8)

        assertTrue(xml.contains("<gmap:exportedPointCount>2</gmap:exportedPointCount>"))
        assertTrue(xml.contains("<gmap:exportedSegmentCount>2</gmap:exportedSegmentCount>"))
        assertFalse(xml.contains("<gmap:recordingAppVersion>"))
    }

    private fun document(
        metadata: RecordingCaptureMetadata? = null,
        point: RecordedTracePoint = point(),
        exporterVersion: String = "2.0",
    ): Document =
        DocumentBuilderFactory
            .newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(
                ByteArrayInputStream(
                    encodeRecordedTraceAsGpx(
                        "test",
                        listOf(point),
                        recordingMetadata = metadata,
                        exporterVersion = exporterVersion,
                    ),
                ),
            )

    private fun point() = RecordedTracePoint(LatLong(45.0, 6.0), null, 1_000L, null, null)

    private fun Document.extension(name: String): String = getElementsByTagNameNS(GMAP, name).item(0).textContent

    private companion object {
        const val GPX = "http://www.topografix.com/GPX/1/1"
        const val GMAP = "https://glancemap.app/gpx/extensions/1"
        const val GARMIN = "http://www.garmin.com/xmlschemas/TrackPointExtension/v1"
    }
}
