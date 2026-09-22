package com.glancemap.glancemapwearos.core.service.diagnostics

import com.glancemap.glancemapwearos.core.service.diagnostics.export.CompassHeadingTelemetrySummary
import com.glancemap.glancemapwearos.core.service.diagnostics.export.writeCompassDeepTraceSection
import com.glancemap.glancemapwearos.domain.sensors.CompassMagneticQuality
import com.glancemap.glancemapwearos.domain.sensors.CompassNorthBasis
import com.glancemap.glancemapwearos.domain.sensors.CompassTrackingReason
import com.glancemap.glancemapwearos.domain.sensors.CompassTrackingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(output.contains("schemaVersion=4"))
        assertTrue(output.contains("aggregateWindowCount=2"))
        assertTrue(output.contains("lastStopReason=manual"))
        assertEquals(1, output.lines().count { it.startsWith("window index=") })
    }

    @Test
    fun decisionEventRingIsBoundedOrderedAndCoalescesIdenticalRenderRecords() {
        val ring = CompassDeepTraceEventRing(capacity = 2)
        val render =
            CompassDeepTraceEvent.Render(
                atElapsedMs = 1_001L,
                sourceSampleId = 4L,
                targetHeadingDeg = 10f,
                renderedHeadingDeg = 9f,
                mapRotationDeg = -9f,
                provenance = null,
            )

        ring.record(CompassDeepTraceEvent.Telemetry(atElapsedMs = 1_000L, line = "start"))
        ring.record(render)
        ring.record(render.copy(atElapsedMs = 1_002L))
        ring.record(CompassDeepTraceEvent.Marker(atElapsedMs = 1_003L, type = "wrong", detail = "manual"))

        val records = ring.snapshot()

        assertEquals(listOf(2L, 3L), records.map { it.eventId })
        assertTrue(records[0].event is CompassDeepTraceEvent.Render)
        assertTrue(records[1].event is CompassDeepTraceEvent.Marker)
        assertEquals(1, ring.droppedEvents)
    }

    @Test
    fun preservedIncidentKeepsPreMarkerHistoryAndBoundedPostTailAfterLiveRingRotates() {
        val ring = CompassDeepTraceEventRing(capacity = 2)
        ring.record(CompassDeepTraceEvent.Telemetry(atElapsedMs = 1_000L, line = "before_one"))
        ring.record(CompassDeepTraceEvent.Telemetry(atElapsedMs = 1_001L, line = "before_two"))
        ring.record(CompassDeepTraceEvent.Telemetry(atElapsedMs = 1_002L, line = "before_three"))
        val capture =
            CompassDeepTraceIncidentCapture(
                preMarkerEvents = ring.snapshot(),
                preMarkerLiveRingDroppedEvents = ring.droppedEvents,
                postTailEndsAtElapsedMs = 3_000L,
                postEventCapacity = 2,
            )

        val marker =
            requireNotNull(
                ring.record(
                    CompassDeepTraceEvent.Marker(
                        atElapsedMs = 1_003L,
                        type = "heading_looks_wrong",
                        detail = "manual",
                    ),
                ),
            )
        capture.record(marker)
        val post = requireNotNull(ring.record(CompassDeepTraceEvent.Telemetry(1_004L, "after")))
        capture.record(post)
        capture.record(requireNotNull(ring.record(CompassDeepTraceEvent.Telemetry(1_005L, "dropped"))))

        repeat(4) { index ->
            ring.record(CompassDeepTraceEvent.Telemetry(2_000L + index, "live_$index"))
        }

        assertFalse(
            capture.record(
                requireNotNull(ring.record(CompassDeepTraceEvent.Telemetry(3_001L, "too_late"))),
            ),
        )
        val incident = capture.snapshot(postTailComplete = true)

        assertEquals(listOf(2L, 3L), incident.preMarkerEvents.map { it.eventId })
        assertEquals(listOf(4L, 5L), incident.markerAndPostEvents.map { it.eventId })
        assertEquals(1, incident.preMarkerLiveRingDroppedEvents)
        assertEquals(1, incident.droppedPostEvents)
        assertTrue(incident.postTailComplete)
    }

    @Test
    fun exportSectionLabelsTheFrozenIncidentPreHistoryMarkerAndPostTail() {
        val output = StringBuilder()
        val marker =
            CompassDeepTraceEventRecord(
                eventId = 2L,
                event = CompassDeepTraceEvent.Marker(1_010L, "heading_looks_wrong", "manual"),
            )

        output.writeCompassDeepTraceSection(
            CompassDeepTraceSnapshot(
                active = false,
                sessionCount = 1,
                windowCount = 0,
                droppedLines = 0,
                lastStopReason = "export",
                lines = emptyList(),
                incident =
                    CompassDeepTraceIncidentSnapshot(
                        preMarkerEvents =
                            listOf(
                                CompassDeepTraceEventRecord(
                                    eventId = 1L,
                                    event = CompassDeepTraceEvent.Telemetry(1_000L, "before"),
                                ),
                            ),
                        markerAndPostEvents = listOf(marker),
                        preMarkerLiveRingDroppedEvents = 0,
                        droppedPostEvents = 0,
                        postTailComplete = true,
                    ),
            ),
        )

        assertTrue(output.contains("Compass Deep Trace Preserved Incident"))
        assertTrue(output.contains("incident_pre trace_event id=1"))
        assertTrue(output.contains("incident_post trace_event id=2 type=marker"))
        assertTrue(output.contains("incidentPostTailComplete=true"))
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
    @Suppress("LongMethod") // Keeps the exported causal sequence fixture readable in one place.
    fun exportSectionKeepsProviderDecisionRenderAndUiConfidenceOrderingVisible() {
        val output = StringBuilder()
        val provider =
            CompassDeepTraceEvent.ProviderMeasurement(
                atElapsedMs = 1_000L,
                provider = "google_fused",
                headingDeg = 180f,
                sourceSampleId = 7L,
                sourceMeasurementAtElapsedMs = 1_000L,
                callbackArrivalAtElapsedMs = 1_002L,
                processingAtElapsedMs = 1_003L,
                measurementDisposition = "accepted",
                accuracy = 3,
                usable = true,
                provenance = null,
            )
        val decision =
            CompassDeepTraceEvent.IntegrityDecision(
                atElapsedMs = 1_003L,
                provider = "google_fused",
                sourceSampleId = 7L,
                headingDeg = 180f,
                liveHeadingErrorDeg = 8f,
                conservativeHeadingErrorDeg = 180f,
                trackingState = CompassTrackingState.TRACKING,
                trackingReason = CompassTrackingReason.ABSOLUTE_RELATIVE_DISAGREEMENT,
                relativeHeadingDeg = 0f,
                fusedRelativeDisagreementDeg = 180f,
                targetHeadingDeg = 0f,
                trusted = false,
                quarantineActive = true,
                recoveryActive = false,
                heldOutput = true,
                provenance = null,
            )
        output.writeCompassDeepTraceSection(
            CompassDeepTraceSnapshot(
                active = false,
                sessionCount = 1,
                windowCount = 1,
                droppedLines = 0,
                lastStopReason = "manual",
                lines = listOf("window index=1"),
                events =
                    listOf(
                        CompassDeepTraceEventRecord(1L, provider),
                        CompassDeepTraceEventRecord(2L, decision),
                        CompassDeepTraceEventRecord(
                            3L,
                            CompassDeepTraceEvent.Render(
                                atElapsedMs = 1_004L,
                                sourceSampleId = 7L,
                                targetHeadingDeg = 0f,
                                renderedHeadingDeg = 0f,
                                mapRotationDeg = 0f,
                                provenance = null,
                            ),
                        ),
                        CompassDeepTraceEventRecord(
                            4L,
                            CompassDeepTraceEvent.UiConfidence(
                                atElapsedMs = 1_005L,
                                provider = "google_fused",
                                quality = "medium",
                                accuracyColorsEnabled = false,
                                provenance = null,
                            ),
                        ),
                    ),
            ),
        )

        val providerIndex = output.indexOf("trace_event id=1 type=provider_measurement")
        val decisionIndex = output.indexOf("trace_event id=2 type=integrity_decision")
        val renderIndex = output.indexOf("trace_event id=3 type=render")
        val uiIndex = output.indexOf("trace_event id=4 type=ui_confidence")

        assertTrue(providerIndex >= 0)
        assertTrue(providerIndex < decisionIndex)
        assertTrue(decisionIndex < renderIndex)
        assertTrue(renderIndex < uiIndex)
        assertTrue(output.indexOf("Compass Deep Trace Aggregates") > uiIndex)
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
