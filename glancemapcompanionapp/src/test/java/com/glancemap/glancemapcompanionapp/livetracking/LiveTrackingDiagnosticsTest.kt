package com.glancemap.glancemapcompanionapp.livetracking

import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTrackingDiagnosticsTest {
    @After
    fun clearDiagnostics() {
        LiveTrackingDiagnostics.clear()
        PhoneDebugCapture.stop()
    }

    @Test
    fun displaysOnlyRedactedRequestMetadata() {
        val event =
            LiveTrackingDiagnosticEvent(
                timestampEpochMs = 0L,
                request =
                    LiveTrackingDiagnosticRequest(
                        operation = LiveTrackingDiagnosticOperation.LOCATION_UPDATE,
                        alarmMinutes = 10,
                        notificationEmailCount = 1,
                        alertEmailCount = 2,
                        alertSmsCount = 1,
                        includesRecipientSummary = true,
                        start = true,
                        gpsAccuracyMeters = 4.2f,
                        fixAgeMillis = 120L,
                        distanceFromPreviousMeters = 18.0,
                        impliedSpeedMetersPerSecond = 1.5,
                        locationQualityResult = "ACCEPT",
                        locationQualityReason = "consistent_fix",
                        gsmSignalPercent = 75,
                    ),
                result = LiveTrackingDiagnosticResult.SUCCESS,
                httpCode = 200,
                durationMs = 125,
            )

        val displayText = event.toDisplayText()

        assertTrue(displayText.contains("GPS update"))
        assertTrue(displayText.contains("HTTP 200"))
        assertTrue(displayText.contains("alarm 10m"))
        assertTrue(displayText.contains("notify 1"))
        assertTrue(displayText.contains("alerts 2 email/1 SMS"))
        assertTrue(displayText.contains("acc 4.2m"))
        assertTrue(displayText.contains("age 120ms"))
        assertTrue(displayText.contains("from previous 18.0m"))
        assertTrue(displayText.contains("implied 1.5m/s"))
        assertTrue(displayText.contains("quality accept:consistent_fix"))
        assertTrue(displayText.contains("gsm 75%"))
        assertTrue(displayText.contains("real-time"))
        assertTrue(displayText.contains("start"))
    }

    @Test
    fun keepsOnlyTheMostRecentOneHundredEvents() {
        repeat(105) { index ->
            LiveTrackingDiagnostics.record(
                request =
                    LiveTrackingDiagnosticRequest(
                        operation = LiveTrackingDiagnosticOperation.LOCATION_UPDATE,
                    ),
                result = LiveTrackingDiagnosticResult.SUCCESS,
                durationMs = index.toLong(),
            )
        }

        val events = LiveTrackingDiagnostics.events.value

        assertEquals(100, events.size)
        assertEquals(5L, events.first().durationMs)
        assertEquals(104L, events.last().durationMs)
    }

    @Test
    fun writesRedactedEventsToActivePhoneCapture() {
        PhoneDebugCapture.start()

        LiveTrackingDiagnostics.record(
            request =
                LiveTrackingDiagnosticRequest(
                    operation = LiveTrackingDiagnosticOperation.LOCATION_UPDATE,
                    alarmMinutes = 10,
                    notificationEmailCount = 1,
                    alertEmailCount = 1,
                    alertSmsCount = 1,
                    includesRecipientSummary = true,
                    start = true,
                ),
            result = LiveTrackingDiagnosticResult.SUCCESS,
            httpCode = 200,
            timestampEpochMs = 0L,
            durationMs = 125L,
        )

        val capturedLine = PhoneDebugCapture.snapshot().single()
        assertTrue(capturedLine.contains("[LiveTracking]"))
        assertTrue(capturedLine.contains("GPS update"))
        assertTrue(capturedLine.contains("alarm 10m"))
        assertTrue(capturedLine.contains("alerts 1 email/1 SMS"))
        assertTrue(capturedLine.contains("start"))
    }

    @Test
    fun recordsRescueOutcomeAndCooldownWithoutLocationDetails() {
        PhoneDebugCapture.start()

        recordLiveTrackingRescue(
            LiveTrackingRescueDiagnostic(
                requested = true,
                trigger = "inconsistent_jump",
                outcome = "returned_previous_area",
                resultAgeMillis = 120L,
                accuracyMeters = 350f,
                speedMetersPerSecond = 4f,
            ),
        )
        recordLiveTrackingRescue(
            LiveTrackingRescueDiagnostic(
                requested = false,
                trigger = "repeated_suspect_area",
                skippedBecauseCooldown = true,
            ),
        )

        val capture = PhoneDebugCapture.snapshot().joinToString("\n")

        assertTrue(capture.contains("rescue requested=true trigger=inconsistent_jump"))
        assertTrue(capture.contains("resultAgeMs=120 accuracyM=350.0 speedMps=4.0 outcome=returned_previous_area"))
        assertTrue(capture.contains("rescue requested=false trigger=repeated_suspect_area skippedCooldown=true"))
        assertFalse(capture.contains("latitude"))
        assertFalse(capture.contains("longitude"))
    }

    @Test
    @Suppress("LongMethod")
    fun recordsBoundedFieldTestFixesTransmissionsAndSessionCounters() {
        PhoneDebugCapture.start()
        LiveTrackingDiagnostics.beginLiveTrackingSession(
            trackingUrl = ArkluzTrackingEndpoint.DEVELOPMENT.url,
            updateIntervalSeconds = 30,
            cellularMonitorAvailable = true,
        )
        LiveTrackingDiagnostics.recordLiveTrackingStartup(
            cachedLocationExists = true,
            cachedLocationAgeMillis = 220L,
            cachedLocationAccepted = true,
        )
        LiveTrackingDiagnostics.recordLiveTrackingFix(
            source = LiveTrackingFixSource.CALLBACK,
            decision =
                LiveTrackingLocationQualityDecision(
                    result = LiveTrackingLocationQualityResult.ACCEPT,
                    reason = "first_fix",
                    accuracyMeters = 7.4f,
                    fixAgeMillis = 220L,
                    distanceFromPreviousMeters = null,
                    impliedSpeedMetersPerSecond = null,
                    timeDeltaFromPreviousAcceptedFixMillis = null,
                ),
            androidSpeedMetersPerSecond = null,
            isMockLocation = false,
            gsmSignalPercent = 75,
            queueSize = 0,
        )
        LiveTrackingDiagnostics.recordLiveTrackingFix(
            source = LiveTrackingFixSource.CALLBACK,
            decision =
                LiveTrackingLocationQualityDecision(
                    result = LiveTrackingLocationQualityResult.SUSPECT,
                    reason = "inconsistent_jump",
                    accuracyMeters = 8.0f,
                    fixAgeMillis = 240L,
                    distanceFromPreviousMeters = 1212.0,
                    impliedSpeedMetersPerSecond = 19.2,
                    timeDeltaFromPreviousAcceptedFixMillis = 63_100L,
                    suspectResolution = LiveTrackingSuspectResolution.WAITING,
                    speedAccuracyMetersPerSecond = 2.0f,
                    effectiveJumpThresholdMeters = 400.0,
                    poorAccuracy = true,
                    speedEvidence = LiveTrackingSpeedEvidence.CONTRADICTED,
                ),
            androidSpeedMetersPerSecond = 2.0f,
            isMockLocation = false,
            gsmSignalPercent = 40,
            queueSize = 0,
        )
        LiveTrackingDiagnostics.recordLiveTrackingFix(
            source = LiveTrackingFixSource.CALLBACK,
            decision =
                LiveTrackingLocationQualityDecision(
                    result = LiveTrackingLocationQualityResult.ACCEPT,
                    reason = "confirmed_suspect_movement",
                    accuracyMeters = 7.8f,
                    fixAgeMillis = 260L,
                    distanceFromPreviousMeters = 130.0,
                    impliedSpeedMetersPerSecond = 2.1,
                    timeDeltaFromPreviousAcceptedFixMillis = 64_000L,
                    suspectResolution = LiveTrackingSuspectResolution.CONFIRMED,
                ),
            androidSpeedMetersPerSecond = 2.1f,
            isMockLocation = true,
            gsmSignalPercent = 75,
            queueSize = 2,
        )
        LiveTrackingDiagnostics.recordLiveTrackingFix(
            source = LiveTrackingFixSource.CACHED_STARTUP,
            decision =
                LiveTrackingLocationQualityDecision(
                    result = LiveTrackingLocationQualityResult.REJECT,
                    reason = "stale_startup_cache",
                    accuracyMeters = 10.0f,
                    fixAgeMillis = 31_000L,
                    distanceFromPreviousMeters = null,
                    impliedSpeedMetersPerSecond = null,
                ),
            androidSpeedMetersPerSecond = null,
            isMockLocation = null,
            gsmSignalPercent = -1,
            queueSize = 2,
        )
        LiveTrackingDiagnostics.recordLiveTrackingFix(
            source = LiveTrackingFixSource.CALLBACK,
            decision =
                LiveTrackingLocationQualityDecision(
                    result = LiveTrackingLocationQualityResult.REJECT,
                    reason = "stale_startup_callback",
                    accuracyMeters = 10.0f,
                    fixAgeMillis = 31_000L,
                    distanceFromPreviousMeters = null,
                    impliedSpeedMetersPerSecond = null,
                ),
            androidSpeedMetersPerSecond = null,
            isMockLocation = false,
            gsmSignalPercent = -1,
            queueSize = 2,
        )
        LiveTrackingDiagnostics.recordLiveTrackingTransmission(
            isCatchUp = true,
            fixTimestampEpochMillis = 1_000L,
            fixAgeMillis = 10_000L,
            gsmSignalPercent = 0,
            queueSizeBefore = 2,
            queueSizeAfter = 1,
            outcome = "success",
        )
        LiveTrackingDiagnostics.recordLiveTrackingPositionQueued(queueSize = 3)
        LiveTrackingDiagnostics.recordLiveTrackingCatchUpReplay(queueSizeAfter = 2)
        LiveTrackingDiagnostics.finishLiveTrackingSession()

        val capture = PhoneDebugCapture.snapshot().joinToString("\n")

        assertTrue(capture.contains("session_start endpoint=development intervalSec=30"))
        assertTrue(capture.contains("fix=1 source=callback"))
        assertTrue(capture.contains("source=callback ageMs=31000"))
        assertTrue(capture.contains("reason=stale_startup_callback"))
        assertTrue(capture.contains("androidSpeedReported=true androidSpeedMps=2.0"))
        assertTrue(
            capture.contains(
                "speedAccuracyMps=2.0 jumpThresholdM=400.0 " +
                    "poorAccuracy=true speedEvidence=contradicted",
            ),
        )
        assertTrue(capture.contains("confirmation=waiting suspectWaiting=true"))
        assertTrue(capture.contains("tx mode=catch_up"))
        assertTrue(capture.contains("gsm_signal=0 queueBefore=2 queueAfter=1 outcome=success"))
        assertTrue(capture.contains("session_end fixesReceived=5 fixesAccepted=2 fixesSuspect=1 fixesRejected=2"))
        assertTrue(capture.contains("suspectConfirmed=1 staleStartupRejected=2 positionsQueued=1 catchUpReplayed=1"))
        assertTrue(capture.contains("maxQueueDepth=3 gsmUnknown=2"))
        assertTrue(capture.contains("good:2"))
        assertFalse(capture.contains("latitude"))
        assertFalse(capture.contains("longitude"))
        assertFalse(capture.contains("participantPassword"))
        assertFalse(capture.contains("https://arkluz.com"))
    }
}
