package com.glancemap.glancemapwearos.core.service.diagnostics

import com.glancemap.glancemapwearos.domain.sensors.CompassNorthBasis
import com.glancemap.glancemapwearos.domain.sensors.CompassTrackingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompassHeadingReferenceDiagnosticsTest {
    @Test
    fun `summary retains signed errors and groups each supplied reference`() {
        val accumulator = CompassHeadingReferenceAccumulator()

        accumulator.record(marker(referenceHeadingDeg = 0f, providerHeadingDeg = 5f, renderedHeadingDeg = 3f))
        accumulator.record(marker(referenceHeadingDeg = 90f, providerHeadingDeg = 85f, renderedHeadingDeg = 91f))

        val summary = accumulator.summary()

        assertEquals(2, summary.referenceSampleCount)
        assertEquals(0f, summary.providerErrorAverageDeg!!, 0.01f)
        assertEquals(-5f, summary.providerErrorMinDeg!!, 0.01f)
        assertEquals(5f, summary.providerErrorMaxDeg!!, 0.01f)
        assertEquals(2f, summary.renderedErrorAverageDeg!!, 0.01f)
        assertTrue(summary.errorByReferenceHeading.contains("N:p5.0/r3.0/n1"))
        assertTrue(summary.errorByReferenceHeading.contains("E:p-5.0/r1.0/n1"))
    }

    @Test
    fun `signed error crosses north without becoming a full rotation`() {
        val marker = marker(referenceHeadingDeg = 0f, providerHeadingDeg = 358f, renderedHeadingDeg = 2f)

        assertEquals(-2f, marker.signedProviderErrorDeg!!, 0.01f)
        assertEquals(2f, marker.signedRenderedErrorDeg!!, 0.01f)
    }

    @Test
    fun `marker reports independent local declination without implying a fused correction`() {
        val line =
            marker(referenceHeadingDeg = 0f, providerHeadingDeg = 2f, renderedHeadingDeg = 1f)
                .copy(
                    declination =
                        CompassHeadingReferenceDeclination(
                            expectedGeomagneticDeclinationDeg = 3.25f,
                            locationAgeMs = 450L,
                        ),
                ).toTelemetryLine()

        assertTrue(line.contains("providerNorthBasis=google_automatic"))
        assertTrue(line.contains("referenceBasis=unknown"))
        assertTrue(line.contains("expectedGeomagneticDeclinationDeg=3.25"))
        assertTrue(line.contains("declinationLocationAgeMs=450"))
        assertTrue(line.contains("appDeclinationCorrectionApplied=false"))
    }

    private fun marker(
        referenceHeadingDeg: Float,
        providerHeadingDeg: Float,
        renderedHeadingDeg: Float,
    ) =
        CompassHeadingReferenceMarker(
            referenceHeadingDeg = referenceHeadingDeg,
            provider =
                CompassHeadingReferenceProviderSample(
                    googleFusedHeadingDeg = providerHeadingDeg,
                    targetHeadingDeg = providerHeadingDeg,
                    northBasis = CompassNorthBasis.GOOGLE_AUTOMATIC,
                    magneticFieldUt = 48f,
                    integrityState = CompassTrackingState.TRACKING,
                    pitchDeg = 0f,
                    rollDeg = 0f,
                    atElapsedMs = 1_000L,
                ),
            render =
                CompassHeadingReferenceRenderSample(
                    targetHeadingDeg = providerHeadingDeg,
                    renderedHeadingDeg = renderedHeadingDeg,
                    mapsforgeMapRotationDeg = -renderedHeadingDeg,
                    atElapsedMs = 1_000L,
                ),
            capturedAtElapsedMs = 1_010L,
        )
}
