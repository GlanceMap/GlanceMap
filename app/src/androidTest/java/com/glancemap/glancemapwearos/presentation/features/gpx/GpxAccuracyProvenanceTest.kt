package com.glancemap.glancemapwearos.presentation.features.gpx

import com.glancemap.glancemapwearos.presentation.features.recording.RECORDING_ACCURACY_INTERPRETATION_SUSPECT_CONSTANT_WATCH_GPS
import com.glancemap.glancemapwearos.presentation.features.recording.RecordedTracePoint
import com.glancemap.glancemapwearos.presentation.features.recording.encodeRecordedTraceAsGpx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong
import java.io.File

class GpxAccuracyProvenanceTest {
    @Test
    fun exportedAccuracyProvenanceReloadKeepsRawAccuracySeparate() {
        val sourcePoint =
            RecordedTracePoint(
                latLong = LatLong(45.0, 6.0),
                elevationMeters = null,
                timeMillis = 1_000L,
                accuracyMeters = 125f,
                speedMps = 1f,
                effectiveAccuracyMeters = 18f,
                accuracyInterpretation = RECORDING_ACCURACY_INTERPRETATION_SUSPECT_CONSTANT_WATCH_GPS,
            )
        val file = File.createTempFile("accuracy-provenance", ".gpx")
        try {
            val xml = encodeRecordedTraceAsGpx("Accuracy", listOf(sourcePoint)).toString(Charsets.UTF_8)
            assertTrue(xml.contains("<gmap:accuracyMeters>125.00</gmap:accuracyMeters>"))
            assertTrue(xml.contains("<gmap:effectiveAccuracyMeters>18.00</gmap:effectiveAccuracyMeters>"))
            assertTrue(
                xml.contains(
                    "<gmap:accuracyInterpretation>suspect_constant_watch_gps</gmap:accuracyInterpretation>",
                ),
            )
            file.writeText(xml)

            val reloaded = parseGpxData(file).points.single()

            assertEquals(125f, reloaded.accuracyMeters)
            assertEquals(18f, reloaded.effectiveAccuracyMeters)
            assertEquals(
                RECORDING_ACCURACY_INTERPRETATION_SUSPECT_CONSTANT_WATCH_GPS,
                reloaded.accuracyInterpretation,
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun legacyGpxRawAccuracyHasNoImplicitEffectiveOverride() {
        val file = File.createTempFile("legacy-accuracy", ".gpx")
        try {
            file.writeText(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1" xmlns:gmap="https://glancemap.app/gpx/extensions/1">
                  <trk><trkseg><trkpt lat="45.0" lon="6.0">
                    <time>1970-01-01T00:00:01Z</time>
                    <extensions><gmap:accuracyMeters>125.00</gmap:accuracyMeters></extensions>
                  </trkpt></trkseg></trk>
                </gpx>
                """.trimIndent(),
            )

            val reloaded = parseGpxData(file).points.single()

            assertEquals(125f, reloaded.accuracyMeters)
            assertEquals(null, reloaded.effectiveAccuracyMeters)
            assertEquals(null, reloaded.accuracyInterpretation)
        } finally {
            file.delete()
        }
    }
}
