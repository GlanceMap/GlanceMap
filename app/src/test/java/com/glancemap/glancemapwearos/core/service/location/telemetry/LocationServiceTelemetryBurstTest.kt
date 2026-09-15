package com.glancemap.glancemapwearos.core.service.location.telemetry

import com.glancemap.glancemapwearos.core.service.location.activity.LocationActivityState
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationServiceTelemetryBurstTest {
    @Test
    fun burstSummaryIncludesPriorAndFirstAcceptedFixDetails() {
        val messages = mutableListOf<String>()
        val telemetry =
            LocationServiceTelemetry(
                tag = "LocTelemetryTest",
                summaryIntervalMs = 60_000L,
                logSink = messages::add,
                telemetryEnabled = { true },
            )
        telemetry.onCallbackFixAccepted(
            nowElapsedMs = 1_000L,
            activityState = LocationActivityState.ACTIVE,
            burst = false,
            source = "callback_candidate",
            ageMs = 0L,
            accuracyM = 9f,
            provider = "fused",
            origin = "auto_fused",
        )
        telemetry.onImmediateRequestStarted(
            nowElapsedMs = 2_000L,
            durationMs = 8_000L,
            burstId = 7L,
            source = "ui_startup_fresh_fix",
        )
        telemetry.onImmediateFixAccepted(
            nowElapsedMs = 2_600L,
            activityState = LocationActivityState.ACTIVE,
            burst = true,
            source = "getCurrentLocation_ui_startup_fresh_fix",
            ageMs = 0L,
            accuracyM = 6f,
            provider = "fused",
            origin = "auto_fused",
        )
        telemetry.logBurstSummary(
            burstId = 7L,
            source = "ui_startup_fresh_fix",
            reason = "early_fix",
            endedAtElapsedMs = 2_700L,
        )

        val summary = messages.single { it.contains("burstSummary id=7") }

        assertTrue(summary.contains("reason=early_fix"))
        assertTrue(summary.contains("priorFixAgeMs=1000"))
        assertTrue(summary.contains("priorFixAccuracyM=9.0"))
        assertTrue(summary.contains("firstFixSource=immediate"))
        assertTrue(summary.contains("firstFixDelayMs=600"))
        assertTrue(summary.contains("firstFixAccuracyM=6.0"))
    }
}
