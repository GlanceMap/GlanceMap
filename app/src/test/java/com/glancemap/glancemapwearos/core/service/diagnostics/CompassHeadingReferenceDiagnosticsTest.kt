package com.glancemap.glancemapwearos.core.service.diagnostics

import com.glancemap.glancemapwearos.domain.sensors.CompassNorthBasis
import com.glancemap.glancemapwearos.domain.sensors.CompassTrackingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `magnetic reference separately reports raw and true-north provider errors`() {
        val marker =
            marker(referenceHeadingDeg = 0f, providerHeadingDeg = 356.2f, renderedHeadingDeg = 356.1f)
                .copy(
                    referenceBasis = CompassHeadingReferenceBasis.MAGNETIC_NORTH,
                    declination =
                        CompassHeadingReferenceDeclination(
                            expectedGeomagneticDeclinationDeg = 4.27f,
                        ),
                )

        assertEquals(-3.8f, marker.signedProviderErrorDeg!!, 0.01f)
        assertEquals(4.27f, marker.expectedTrueReferenceHeadingDeg!!, 0.01f)
        assertEquals(-8.07f, marker.signedProviderErrorAgainstExpectedTrueDeg!!, 0.01f)

        val line = marker.toTelemetryLine()
        assertTrue(line.contains("referenceBasis=magnetic_north"))
        assertTrue(line.contains("rawProviderErrorAgainstReferenceDeg=-3.8"))
        assertTrue(line.contains("expectedTrueReferenceHeadingDeg=4.3"))
        assertTrue(line.contains("providerErrorAgainstExpectedTrueDeg=-8.1"))
    }

    @Test
    fun `true and unknown references do not conflate supplied heading bases`() {
        val trueNorth =
            marker(referenceHeadingDeg = 90f, providerHeadingDeg = 93f, renderedHeadingDeg = 91f)
                .copy(referenceBasis = CompassHeadingReferenceBasis.TRUE_NORTH)
        val unknown = trueNorth.copy(referenceBasis = CompassHeadingReferenceBasis.UNKNOWN)

        assertEquals(90f, trueNorth.expectedTrueReferenceHeadingDeg!!, 0.01f)
        assertEquals(3f, trueNorth.signedProviderErrorAgainstExpectedTrueDeg!!, 0.01f)
        assertNull(unknown.expectedTrueReferenceHeadingDeg)
        assertNull(unknown.signedProviderErrorAgainstExpectedTrueDeg)
        assertEquals(
            CompassHeadingReferenceBasis.MAGNETIC_NORTH,
            CompassHeadingReferenceBasis.UNKNOWN.next(),
        )
    }

    @Test
    fun `summary includes source ranges and fused minus rotation vector for a magnetic test`() {
        val accumulator =
            CompassHeadingReferenceAccumulator(
                referenceBasis = CompassHeadingReferenceBasis.MAGNETIC_NORTH,
            )
        accumulator.record(
            marker(referenceHeadingDeg = 0f, providerHeadingDeg = 356.2f, renderedHeadingDeg = 356.1f)
                .copy(
                    independent =
                        CompassHeadingReferenceIndependentSamples(
                            rotationVector = source(headingDeg = 351.9f),
                            geomagneticRotationVector = source(headingDeg = 349.1f),
                            accelMag = source(headingDeg = 346.9f),
                        ),
                    referenceBasis = CompassHeadingReferenceBasis.MAGNETIC_NORTH,
                    declination =
                        CompassHeadingReferenceDeclination(
                            expectedGeomagneticDeclinationDeg = 4.27f,
                        ),
                ),
        )

        val summary = accumulator.summary()

        assertEquals(CompassHeadingReferenceBasis.MAGNETIC_NORTH, summary.referenceBasis)
        assertEquals(-8.07f, summary.providerErrorAgainstExpectedTrue.averageDeg!!, 0.01f)
        assertEquals(-8.1f, summary.rotationVectorRawReferenceError.averageDeg!!, 0.01f)
        assertEquals(-10.9f, summary.geomagneticRotationVectorRawReferenceError.averageDeg!!, 0.01f)
        assertEquals(-13.1f, summary.accelMagRawReferenceError.averageDeg!!, 0.01f)
        assertEquals(4.3f, summary.fusedMinusRotationVector.averageDeg!!, 0.01f)
        assertEquals(4.27f, summary.expectedGeomagneticDeclination.averageDeg!!, 0.01f)
        assertTrue(summary.toTelemetryLine().contains("fusedMinusRotationVectorAverageDeg=4.3"))
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

    @Test
    fun `stationary but aligned render remains a valid reference mark`() {
        val marker = marker(referenceHeadingDeg = 0f, providerHeadingDeg = 2f, renderedHeadingDeg = 1f)

        val result =
            validateHeadingReferenceMark(
                active = true,
                provider = marker.provider.copy(atElapsedMs = 4_000L),
                render = marker.render,
                capturedAtElapsedMs = 4_100L,
            )

        assertEquals(CompassHeadingReferenceMarkResult.RECORDED, result)
    }

    @Test
    fun `stale provider still rejects a reference mark`() {
        val marker = marker(referenceHeadingDeg = 0f, providerHeadingDeg = 2f, renderedHeadingDeg = 1f)

        val result =
            validateHeadingReferenceMark(
                active = true,
                provider = marker.provider,
                render = marker.render,
                capturedAtElapsedMs = 3_001L,
            )

        assertEquals(CompassHeadingReferenceMarkResult.PROVIDER_STALE, result)
    }

    @Test
    fun `independent available sources are recorded without declination correction`() {
        val line =
            marker(referenceHeadingDeg = 0f, providerHeadingDeg = 359f, renderedHeadingDeg = 0f)
                .copy(
                    independent =
                        CompassHeadingReferenceIndependentSamples(
                            rotationVector = source(headingDeg = 1f),
                            geomagneticRotationVector = source(headingDeg = 2f),
                            accelMag = source(headingDeg = 358f),
                            magneticField =
                                CompassHeadingReferenceMagneticFieldSample(
                                    available = true,
                                    strengthUt = 49.5f,
                                    atElapsedMs = 1_000L,
                                ),
                        ),
                ).toTelemetryLine()

        assertTrue(line.contains("rotationVectorHeadingDeg=1.0"))
        assertTrue(line.contains("rotationVectorNorthBasis=magnetic"))
        assertTrue(line.contains("rotationVectorGoogleFusedDeltaDeg=2.0"))
        assertTrue(line.contains("geomagneticRotationVectorHeadingDeg=2.0"))
        assertTrue(line.contains("accelMagHeadingDeg=358.0"))
        assertTrue(line.contains("accelMagGoogleFusedDeltaDeg=-1.0"))
        assertTrue(line.contains("directMagneticFieldUt=49.5"))
    }

    @Test
    fun `missing independent source does not block a mark and is logged unavailable`() {
        val marker = marker(referenceHeadingDeg = 90f, providerHeadingDeg = 91f, renderedHeadingDeg = 91f)

        val result =
            validateHeadingReferenceMark(
                active = true,
                provider = marker.provider,
                render = marker.render,
                capturedAtElapsedMs = 1_100L,
            )

        assertEquals(CompassHeadingReferenceMarkResult.RECORDED, result)
        assertTrue(marker.toTelemetryLine().contains("rotationVectorStatus=unavailable"))
    }

    @Test
    fun `sensor session rejects callbacks after dispose and from a previous generation`() {
        val session = CompassHeadingReferenceNavigateSession()
        val firstGeneration = session.attach()

        assertTrue(session.accepts(firstGeneration))
        session.detach()
        assertTrue(!session.accepts(firstGeneration))

        val secondGeneration = session.attach()

        assertTrue(secondGeneration != firstGeneration)
        assertTrue(!session.accepts(firstGeneration))
        assertTrue(session.accepts(secondGeneration))
    }

    private fun source(headingDeg: Float) =
        CompassHeadingReferenceIndependentSource(
            available = true,
            headingDeg = headingDeg,
            pitchDeg = 1f,
            rollDeg = -2f,
            atElapsedMs = 1_000L,
        )

    private fun marker(
        referenceHeadingDeg: Float,
        providerHeadingDeg: Float,
        renderedHeadingDeg: Float,
    ) = CompassHeadingReferenceMarker(
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
