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
        accumulator.record(marker(referenceHeadingDeg = 180f, providerHeadingDeg = 183f, renderedHeadingDeg = 181f))
        accumulator.record(marker(referenceHeadingDeg = 270f, providerHeadingDeg = 265f, renderedHeadingDeg = 269f))

        val summary = accumulator.summary()

        assertEquals(4, summary.referenceSampleCount)
        assertEquals(-0.5f, summary.providerErrorAverageDeg!!, 0.01f)
        assertEquals(-5f, summary.providerErrorMinDeg!!, 0.01f)
        assertEquals(5f, summary.providerErrorMaxDeg!!, 0.01f)
        assertEquals(1f, summary.renderedErrorAverageDeg!!, 0.01f)
        assertTrue(summary.errorByReferenceHeading.contains("N:p5.0/r3.0/n1"))
        assertTrue(summary.errorByReferenceHeading.contains("E:p-5.0/r1.0/n1"))
        assertTrue(summary.errorByReferenceHeading.contains("S:p3.0/r1.0/n1"))
        assertTrue(summary.errorByReferenceHeading.contains("W:p-5.0/r-1.0/n1"))
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

    @Test
    fun `mark rejects when Navigate has no live compass sample`() {
        val sample = marker(0f, 0f, 0f)
        val missingProvider =
            validateHeadingReferenceMark(
                active = true,
                provider = null,
                render = null,
                capturedAtElapsedMs = 1_000L,
            )
        val unusableProvider =
            validateHeadingReferenceMark(
                active = true,
                provider = sample.provider.copy(usable = false),
                render = sample.render,
                capturedAtElapsedMs = 1_000L,
            )

        assertEquals(CompassHeadingReferenceMarkResult.PROVIDER_UNAVAILABLE, missingProvider)
        assertEquals(CompassHeadingReferenceMarkResult.PROVIDER_UNUSABLE, unusableProvider)
    }

    @Test
    fun `valid Navigate mark retains provider target rendered and map rotation`() {
        val marker = marker(referenceHeadingDeg = 90f, providerHeadingDeg = 93f, renderedHeadingDeg = 91f)

        val result =
            validateHeadingReferenceMark(
                active = true,
                provider = marker.provider,
                render = marker.render,
                capturedAtElapsedMs = 1_100L,
            )

        assertEquals(CompassHeadingReferenceMarkResult.RECORDED, result)
        assertEquals(93f, marker.provider.googleFusedHeadingDeg, 0.01f)
        assertEquals(93f, marker.provider.targetHeadingDeg!!, 0.01f)
        assertEquals(91f, marker.render.renderedHeadingDeg, 0.01f)
        assertEquals(-91f, marker.render.mapsforgeMapRotationDeg, 0.01f)
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
                    usable = true,
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
