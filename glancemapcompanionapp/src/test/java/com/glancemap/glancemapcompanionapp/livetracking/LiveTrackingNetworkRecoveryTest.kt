package com.glancemap.glancemapcompanionapp.livetracking

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LiveTrackingNetworkRecoveryTest {
    @Test
    fun defaultNetworkMustBeValidatedBeforeUploadsAreAllowed() {
        val tracker = LiveTrackingNetworkStateTracker<String>()
        tracker.initialize(null, validated = false)
        assertFalse(tracker.state.value.canAttemptUpload())
        tracker.available("wifi")
        assertFalse(tracker.state.value.canAttemptUpload())
        tracker.capabilitiesChanged("wifi", validated = false)
        assertFalse(tracker.state.value.canAttemptUpload())
        tracker.capabilitiesChanged("wifi", validated = true)
        assertTrue(tracker.state.value.canAttemptUpload())
        tracker.capabilitiesChanged("wifi", validated = false)
        assertFalse(tracker.state.value.canAttemptUpload())
    }

    @Test
    fun oldNetworkEventsCannotInvalidateOrValidateTheReplacement() {
        val tracker = LiveTrackingNetworkStateTracker<String>()
        tracker.initialize("wifi", validated = true)
        tracker.available("cellular")
        tracker.capabilitiesChanged("wifi", validated = true)
        assertFalse(tracker.state.value.canAttemptUpload())
        tracker.capabilitiesChanged("cellular", validated = true)
        tracker.lost("wifi")
        tracker.capabilitiesChanged("wifi", validated = false)
        assertEquals(LiveTrackingNetworkState.VALIDATED, tracker.state.value)
        tracker.lost("cellular")
        assertEquals(LiveTrackingNetworkState.UNAVAILABLE, tracker.state.value)
    }

    @Test
    fun repeatedAvailabilityDoesNotResetKnownCapabilities() {
        val tracker = LiveTrackingNetworkStateTracker<String>()
        tracker.initialize("wifi", validated = true)
        tracker.available("wifi")
        tracker.capabilitiesChanged("wifi", validated = true)
        assertEquals(LiveTrackingNetworkState.VALIDATED, tracker.state.value)
    }

    @Test
    fun callbacksAfterMonitorShutdownCannotReviveIt() {
        val tracker = LiveTrackingNetworkStateTracker<String>()
        tracker.initialize("wifi", validated = true)
        tracker.stop()
        tracker.available("cellular")
        tracker.initialize("cellular", validated = true)
        tracker.capabilitiesChanged("cellular", validated = true)
        tracker.lost("cellular")
        assertEquals(LiveTrackingNetworkState.UNKNOWN, tracker.state.value)
    }

    @Test
    fun unavailableMonitoringPreservesTheExistingHttpFallback() {
        val tracker = LiveTrackingNetworkStateTracker<String>()
        tracker.initialize(null, validated = false)
        tracker.monitoringUnavailable()
        assertEquals(LiveTrackingNetworkState.UNKNOWN, tracker.state.value)
        assertTrue(tracker.state.value.canAttemptUpload())
    }

    @Test
    fun offlineRecoveryDoesNotAttemptControlsOrPositions() =
        runTest {
            val calls = mutableListOf<String>()
            val result =
                runCatching {
                    recoverLiveTrackingPendingUploads(
                        canAttemptUpload = { false },
                        flushControls = { calls += "control" },
                        replayPositions = { calls += "position" },
                    )
                }
            assertTrue(result.exceptionOrNull() is LiveTrackingOfflineException)
            assertTrue(result.exceptionOrNull()!!.isRetryableArkluzFailure())
            assertTrue(calls.isEmpty())
        }

    @Test
    fun successfulStartupRetryAcknowledgesOnlyItsPositionBeforeCatchUp() =
        runTest {
            val startup = point(1L).copy(start = true)
            val later = point(2L)
            var positions = listOf(startup.asStoredGpsPoint(), later)
            var controls = listOf(startup)
            val sent = mutableListOf<ArkluzLocationUpdate>()
            repeat(2) {
                recoverLiveTrackingPendingUploads(
                    canAttemptUpload = { true },
                    flushControls = {
                        controls.forEach { control ->
                            sent += control
                            positions = acknowledgedLiveTrackingPositions(positions, control)
                        }
                        controls = emptyList()
                    },
                    replayPositions = {
                        positions.forEach { sent += it.asCatchUpPoint() }
                        positions = emptyList()
                    },
                )
            }
            assertEquals(listOf(1L, 2L), sent.map { it.epochMilliseconds })
            assertTrue(sent[0].start)
            assertTrue(sent[1].isCatchUp)
            assertTrue(positions.isEmpty())
        }

    @Test
    fun failedControlLeavesAllPositionsPending() =
        runTest {
            val positions = listOf(point(1L), point(2L))
            var replayed = false
            val result =
                runCatching {
                    recoverLiveTrackingPendingUploads(
                        canAttemptUpload = { true },
                        flushControls = { throw IOException("simulated failure") },
                        replayPositions = { replayed = true },
                    )
                }
            assertTrue(result.isFailure)
            assertFalse(replayed)
            assertEquals(2, positions.size)
        }

    @Test
    fun lossOfValidationDuringControlRecoveryLeavesCatchUpPending() =
        runTest {
            var online = true
            var replayed = false
            val result =
                runCatching {
                    recoverLiveTrackingPendingUploads(
                        canAttemptUpload = { online },
                        flushControls = { online = false },
                        replayPositions = { replayed = true },
                    )
                }
            assertTrue(result.exceptionOrNull() is LiveTrackingOfflineException)
            assertFalse(replayed)
        }

    @Test
    fun stopConfirmationFollowsSuccessfulPendingUploads() =
        runTest {
            val order = mutableListOf<String>()
            recoverLiveTrackingPendingUploads(
                canAttemptUpload = { true },
                flushControls = { order += "start" },
                replayPositions = { order += "catch_up" },
            )
            order += "stop"
            assertEquals(listOf("start", "catch_up", "stop"), order)
        }

    @Test
    fun acknowledgementSurvivesAlertSettingChangesButKeepsOtherFixesAndSessions() {
        val original = point(1L)
        val otherTime = point(2L)
        val otherCoordinate = original.copy(latitude = 2.0)
        val otherSession = original.copy(userName = "another-test-user")
        val otherEndpoint = original.copy(trackingUrl = "https://other.example.invalid")
        val positions = listOf(original, original, otherTime, otherCoordinate, otherSession, otherEndpoint)
        val acknowledged =
            original.copy(
                start = true,
                notificationEmails = "test@example.invalid",
                gsmSignalPercent = 0,
            )
        assertEquals(
            listOf(otherTime, otherCoordinate, otherSession, otherEndpoint),
            acknowledgedLiveTrackingPositions(positions, acknowledged),
        )
    }

    @Test
    fun slowHttpFailureRetainsTheAttemptedPointForHistoricalReplay() {
        val candidate = candidate()
        val attempted = selectLiveTrackingCandidate(listOf(candidate))!!
        val retry =
            selectLiveTrackingRetryCandidate(
                attempted,
                listOf(candidate),
                nowElapsedRealtimeNanos = 201_000_000_000L,
                nowEpochMilliseconds = 1_200_000L,
            )
        assertSame(attempted, retry)
    }

    @Test
    fun stalePointThatWasNeverAttemptedCannotBecomeANormalRetry() {
        assertNull(
            selectLiveTrackingRetryCandidate(
                attempted = null,
                candidates = listOf(candidate()),
                nowElapsedRealtimeNanos = 201_000_000_000L,
                nowEpochMilliseconds = 1_200_000L,
            ),
        )
    }

    private fun candidate(): LiveTrackingCandidate<String> =
        LiveTrackingCandidate(
            value = "selected",
            fix =
                LiveTrackingLocationFix(
                    latitude = 0.0,
                    longitude = 0.0,
                    epochMilliseconds = 1_000_000L,
                    elapsedRealtimeNanos = 1_000_000_000L,
                    accuracyMeters = 10f,
                    speedMetersPerSecond = null,
                ),
            decision =
                LiveTrackingLocationQualityDecision(
                    result = LiveTrackingLocationQualityResult.ACCEPT,
                    reason = "test",
                    accuracyMeters = 10f,
                    fixAgeMillis = 0L,
                    distanceFromPreviousMeters = null,
                    impliedSpeedMetersPerSecond = null,
                ),
            isScheduledFallback = true,
        )

    private fun point(time: Long): ArkluzLocationUpdate =
        ArkluzLocationUpdate(
            trackingUrl = "https://example.invalid",
            latitude = 1.0,
            longitude = 1.0,
            altitudeMeters = null,
            speedMetersPerSecond = null,
            accuracyMeters = 10f,
            epochMilliseconds = time,
            batteryPercent = 80,
            gsmSignalPercent = 100,
            group = "test-group",
            participantPassword = "placeholder",
            userName = "test-user",
            notificationEmails = "",
            alertEmails = "",
            stuckAlarmMinutes = "10",
            start = false,
            stop = false,
        )
}
