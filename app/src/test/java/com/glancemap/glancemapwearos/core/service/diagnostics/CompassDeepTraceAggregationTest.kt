package com.glancemap.glancemapwearos.core.service.diagnostics

import com.glancemap.glancemapwearos.core.service.diagnostics.export.CompassHeadingTelemetrySummary
import com.glancemap.glancemapwearos.core.service.diagnostics.export.writeCompassDeepTraceSection
import com.glancemap.glancemapwearos.domain.sensors.CompassMagneticQuality
import com.glancemap.glancemapwearos.domain.sensors.CompassNorthBasis
import com.glancemap.glancemapwearos.domain.sensors.CompassTrackingReason
import com.glancemap.glancemapwearos.domain.sensors.CompassTrackingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompassDeepTraceAggregationTest {
    @Test
    fun aggregatesWraparoundReversalsRawSensorsAndRenderLag() {
        val accumulator = CompassDeepTraceWindowAccumulator(startedAtElapsedMs = 1_000L)
        accumulator.recordProvider(providerSample(headingDeg = 350f, atElapsedMs = 1_000L))
        accumulator.recordProvider(providerSample(headingDeg = 5f, atElapsedMs = 1_100L))
        accumulator.recordProvider(providerSample(headingDeg = 355f, atElapsedMs = 1_200L))
        accumulator.recordRawSensor(
            sensor = CompassDeepTraceRawSensor.GYROSCOPE,
            x = 3f,
            y = 4f,
            z = -2f,
        )
        accumulator.recordRawSensor(
            sensor = CompassDeepTraceRawSensor.ACCELEROMETER,
            x = 0f,
            y = 0f,
            z = 9.8f,
        )
        accumulator.recordRawSensor(
            sensor = CompassDeepTraceRawSensor.MAGNETOMETER,
            x = 30f,
            y = 40f,
            z = 0f,
        )
        accumulator.recordRender(renderSample(target = 10f, rendered = 5f, mapRotation = -5f, atElapsedMs = 1_100L))
        accumulator.recordRender(renderSample(target = 20f, rendered = 15f, mapRotation = -15f, atElapsedMs = 1_200L))

        val line = accumulator.toTelemetryLine(index = 1, endedAtElapsedMs = 6_000L)

        assertTrue(line.contains("providerSamples=3"))
        assertTrue(line.contains("fusedStepAvgDeg=12.5"))
        assertTrue(line.contains("fusedReversals=1"))
        assertTrue(line.contains("accuracyHigh=3"))
        assertTrue(line.contains("gyroSamples=1"))
        assertTrue(line.contains("accelSamples=1"))
        assertTrue(line.contains("magSamples=1"))
        assertTrue(line.contains("magMagnitudeMinUt=50.0"))
        assertTrue(line.contains("fusedLiveErrorAvgDeg=6.0"))
        assertTrue(line.contains("fusedConservativeErrorAvgDeg=18.0"))
        assertTrue(line.contains("trackingSamples=3"))
        assertTrue(line.contains("lastNorthBasis=google_automatic"))
        assertTrue(line.contains("disagreementMaxDeg=4.0"))
        assertTrue(line.contains("renderSamples=2"))
        assertTrue(line.contains("targetRenderDeltaAvgDeg=5.0"))
    }

    @Test
    fun exportSectionMakesTraceLifecycleAndAggregationExplicit() {
        val output = StringBuilder()

        output.writeCompassDeepTraceSection(
            CompassDeepTraceSnapshot(
                active = false,
                sessionCount = 1,
                windowCount = 2,
                droppedLines = 0,
                lastStopReason = "manual",
                lines = listOf("window index=1 providerSamples=20"),
            ),
        )

        assertTrue(output.contains("Compass Deep Trace"))
        assertTrue(output.contains("schemaVersion=2"))
        assertTrue(output.contains("aggregateWindowCount=2"))
        assertTrue(output.contains("lastStopReason=manual"))
        assertEquals(1, output.lines().count { it.startsWith("window index=") })
    }

    @Test
    fun exportSectionIncludesDetailedCompassSummariesOnlyInDeepTrace() {
        val output = StringBuilder()

        output.writeCompassDeepTraceSection(
            snapshot =
                CompassDeepTraceSnapshot(
                    active = false,
                    sessionCount = 1,
                    windowCount = 1,
                    droppedLines = 0,
                    lastStopReason = "manual",
                    lines = listOf("[CompassTelemetry] heading_engine window samples=4"),
                ),
            eventSummary =
                DiagnosticsExporter.CompassTelemetryInsights(
                    headingLooksWrongReportCount = 2,
                    rotationSettleReleaseCount = 1,
                ),
            headingSummary =
                CompassHeadingTelemetrySummary(
                    sampleCount = 4,
                    degradedSamples = 1,
                ),
        )

        assertTrue(output.contains("Compass Deep Trace Event Summary"))
        assertTrue(output.contains("headingLooksWrongReportCount=2"))
        assertTrue(output.contains("Compass Heading Engine Summary"))
        assertTrue(output.contains("engineSampleCount=4"))
    }

    @Test
    fun fusedAndFallbackHeadingsAreAggregatedIndependently() {
        val accumulator = CompassDeepTraceWindowAccumulator(startedAtElapsedMs = 1_000L)
        accumulator.recordProvider(providerSample(headingDeg = 0f, atElapsedMs = 1_000L))
        accumulator.recordProvider(
            providerSample(headingDeg = 180f, atElapsedMs = 1_010L).copy(provider = "sensor_manager"),
        )
        accumulator.recordProvider(providerSample(headingDeg = 10f, atElapsedMs = 1_100L))
        accumulator.recordProvider(
            providerSample(headingDeg = 190f, atElapsedMs = 1_110L).copy(provider = "sensor_manager"),
        )

        val line = accumulator.toTelemetryLine(index = 1, endedAtElapsedMs = 2_000L)

        assertTrue(line.contains("fusedStepMaxDeg=10.0"))
        assertTrue(line.contains("sensorManagerStepMaxDeg=10.0"))
    }

    private fun providerSample(
        headingDeg: Float,
        atElapsedMs: Long,
    ) = CompassDeepTraceProviderSample(
        provider = "google_fused",
        headingDeg = headingDeg,
        headingErrorDeg = 8f,
        liveHeadingErrorDeg = 6f,
        conservativeHeadingErrorDeg = 18f,
        accuracy = 3,
        startupWarmup = false,
        usable = true,
        trackingState = CompassTrackingState.TRACKING,
        trackingReason = CompassTrackingReason.STABLE,
        northBasis = CompassNorthBasis.GOOGLE_AUTOMATIC,
        magneticQuality = CompassMagneticQuality.GOOD,
        magneticFieldUt = 50f,
        relativeHeadingDeg = headingDeg - 2f,
        fusedRelativeDisagreementDeg = 4f,
        targetHeadingDeg = headingDeg,
        atElapsedMs = atElapsedMs,
    )

    private fun renderSample(
        target: Float,
        rendered: Float,
        mapRotation: Float,
        atElapsedMs: Long,
    ) = CompassDeepTraceRenderSample(
        targetHeadingDeg = target,
        renderedHeadingDeg = rendered,
        mapRotationDeg = mapRotation,
        continuityActive = false,
        continuityOffsetDeg = 0f,
        atElapsedMs = atElapsedMs,
    )
}
