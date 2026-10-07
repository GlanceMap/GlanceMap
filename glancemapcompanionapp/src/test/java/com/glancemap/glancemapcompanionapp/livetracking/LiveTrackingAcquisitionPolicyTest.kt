package com.glancemap.glancemapcompanionapp.livetracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTrackingAcquisitionPolicyTest {
    @Test
    fun refinesOnlyPoorAcceptedLongIntervalPeriodicFixesWithFinePermission() {
        assertFalse(eligible(intervalMillis = 60_000L, accuracyMeters = 10f))
        assertFalse(eligible(intervalMillis = 15_000L, accuracyMeters = 180f))
        assertFalse(eligible(intervalMillis = 30_000L, accuracyMeters = 180f))
        assertFalse(eligible(intervalMillis = 60_000L, accuracyMeters = 99f))
        assertFalse(eligible(intervalMillis = 60_000L, accuracyMeters = null))
        assertTrue(eligible(intervalMillis = 60_000L, accuracyMeters = 100f))
        assertTrue(eligible(intervalMillis = 120_000L, accuracyMeters = 180f))
        assertFalse(shouldRefineLiveTrackingAccuracy(context(60_000L, 180f).copy(hasFineLocationPermission = false)))
        assertFalse(shouldRefineLiveTrackingAccuracy(context(60_000L, 180f).copy(sessionActive = false)))
        assertFalse(shouldRefineLiveTrackingAccuracy(context(60_000L, 180f).copy(acquisitionRunning = true)))
        assertFalse(
            shouldRefineLiveTrackingAccuracy(
                context(120_000L, 180f).copy(source = LiveTrackingFixSource.CACHED_STARTUP),
            ),
        )
        assertFalse(
            shouldRefineLiveTrackingAccuracy(
                context(120_000L, 180f).copy(result = LiveTrackingLocationQualityResult.SUSPECT),
            ),
        )
    }

    @Test
    fun keepsTheUserSelectedNormalCadence() {
        assertEquals(30_000L, normalLiveTrackingIntervalMs(30))
        assertEquals(60_000L, normalLiveTrackingIntervalMs(60))
        assertEquals(120_000L, normalLiveTrackingIntervalMs(120))
        assertEquals(15_000L, normalLiveTrackingIntervalMs(1))
    }

    @Test
    fun selectsTheNewestTargetQualityCandidateAndOneTransmissionCandidate() {
        val cycle =
            LiveTrackingAcquisitionCycle(
                id = 1L,
                reason = LiveTrackingAcquisitionReason.ACCURACY_REFINEMENT,
                intervalMillis = 60_000L,
                initialCandidate = candidate("initial-180m", seconds = 60, accuracy = 180f, fallback = true),
            )
        val fortyFiveMeters = candidate("extra-45m", seconds = 63, accuracy = 45f)
        val twelveMeters = candidate("extra-12m", seconds = 66, accuracy = 12f)

        assertTrue(cycle.consumeDeliveredFix())
        cycle.recordDecision(fortyFiveMeters.decision, fortyFiveMeters)
        assertTrue(cycle.consumeDeliveredFix())
        cycle.recordDecision(twelveMeters.decision, twelveMeters)
        val selected = cycle.selectCandidate()

        assertEquals("extra-12m", selected?.candidate?.value)
        assertEquals("newest_target_accuracy", selected?.selectionReason)
        assertTrue(cycle.hasReachedTarget())
    }

    @Test
    fun earlyTargetStopsTheCycleAfterTheFirstExtraFix() {
        val cycle =
            LiveTrackingAcquisitionCycle(
                id = 2L,
                reason = LiveTrackingAcquisitionReason.ACCURACY_REFINEMENT,
                intervalMillis = 120_000L,
                initialCandidate = candidate("initial", 120, 180f, fallback = true),
            )
        val target = candidate("target", 123, 25f)

        assertTrue(cycle.consumeDeliveredFix())
        cycle.recordDecision(target.decision, target)
        assertTrue(cycle.hasReachedTarget())
        cycle.complete()
        assertFalse(cycle.consumeDeliveredFix())
        assertEquals("target", cycle.selectCandidate()?.candidate?.value)
    }

    @Test
    fun keepsTheAcceptedScheduledFixAsFallbackWhenExtrasDoNotImproveIt() {
        val initial = candidate("initial-350m", 60, 350f, fallback = true)
        val worse = candidate("extra-400m", 63, 400f)
        val cycle =
            LiveTrackingAcquisitionCycle(
                id = 3L,
                reason = LiveTrackingAcquisitionReason.ACCURACY_REFINEMENT,
                intervalMillis = 60_000L,
                initialCandidate = initial,
            )
        cycle.consumeDeliveredFix()
        cycle.recordDecision(worse.decision, worse)

        val selected = cycle.selectCandidate()

        assertEquals("initial-350m", selected?.candidate?.value)
        assertTrue(selected?.candidate?.isScheduledFallback == true)
    }

    @Test
    fun recencyBandAvoidsChoosingAnOldAbsoluteAccuracyWinner() {
        val oldThreeMeters = candidate("old-3m", 60, 3f)
        val recentTwelveMeters = candidate("recent-12m", 65, 12f)

        val selected = selectLiveTrackingCandidate(listOf(oldThreeMeters, recentTwelveMeters))

        assertEquals("recent-12m", selected?.candidate?.value)
        assertEquals("newest_target_accuracy", selected?.selectionReason)
    }

    @Test
    fun selectsTheNewestAcceptedFixWhenAccuracyIsUnavailable() {
        val older = candidate("older", 60, null)
        val newer = candidate("newer", 63, null)

        val selected = selectLiveTrackingCandidate(listOf(older, newer))

        assertEquals("newer", selected?.candidate?.value)
        assertEquals("newest_accept_accuracy_unavailable", selected?.selectionReason)
        assertFalse(eligible(intervalMillis = 120_000L, accuracyMeters = null))
    }

    @Test
    fun duplicateAndStaleDeliveriesConsumeTheSameTwoFixBudget() {
        val cycle =
            LiveTrackingAcquisitionCycle(
                id = 4L,
                reason = LiveTrackingAcquisitionReason.ACCURACY_REFINEMENT,
                intervalMillis = 60_000L,
                initialCandidate = candidate("fallback", 60, 180f, fallback = true),
            )

        assertTrue(cycle.consumeDeliveredFix())
        cycle.recordIgnoredFix(duplicate = true)
        assertTrue(cycle.consumeDeliveredFix())
        cycle.recordIgnoredFix(duplicate = false)
        assertFalse(cycle.consumeDeliveredFix())

        assertEquals(2, cycle.extraFixesDelivered)
        assertEquals(1, cycle.duplicateFixesIgnored)
        assertEquals(1, cycle.staleFixesIgnored)
        assertEquals("fallback", cycle.selectCandidate()?.candidate?.value)
    }

    @Test
    fun suspectDuringRefinementUsesTheSameRemainingCycleAndBudget() {
        val cycle =
            LiveTrackingAcquisitionCycle(
                id = 5L,
                reason = LiveTrackingAcquisitionReason.ACCURACY_REFINEMENT,
                intervalMillis = 60_000L,
                initialCandidate = candidate("fallback", 60, 180f, fallback = true),
            )
        val suspect = decision(LiveTrackingLocationQualityResult.SUSPECT, accuracy = 20f)
        val confirmed = candidate("confirmed", 66, 20f)

        cycle.consumeDeliveredFix()
        cycle.recordDecision(suspect, null)
        cycle.consumeDeliveredFix()
        cycle.recordDecision(confirmed.decision, confirmed)

        assertEquals(2, cycle.extraFixesDelivered)
        assertEquals(1, cycle.extraFixesSuspect)
        assertEquals(1, cycle.extraFixesAccepted)
        assertEquals("confirmed", cycle.selectCandidate()?.candidate?.value)
    }

    @Test
    fun processesMultipleLocationsInChronologicalOrder() {
        val locations =
            listOf(
                "late" to fix(seconds = 66, accuracy = 12f),
                "early" to fix(seconds = 60, accuracy = 180f),
                "middle" to fix(seconds = 63, accuracy = 45f),
            )

        assertEquals(
            listOf("early", "middle", "late"),
            sortLiveTrackingFixesChronologically(locations).map { it.first },
        )
    }

    @Test
    fun startupCacheAndFirstCallbackShareOneAdmissionWindow() {
        val cadence = LiveTrackingCadenceAdmission(intervalMillis = 60_000L, startedAtElapsedRealtimeMillis = 10_000L)
        val cached = cadence.initialTicket()
        val freshCallbackWithDifferentFixAge = cadence.observe(10_250L)

        assertTrue(cadence.begin(cached))
        assertFalse(cadence.begin(freshCallbackWithDifferentFixAge))
    }

    @Test
    fun startupReleaseReportsOnlyAnActualOwnedAdmissionRelease() {
        val cadence = LiveTrackingCadenceAdmission(60_000L, 10_000L)
        val initial = cadence.initialTicket()
        val foreign = LiveTrackingCadenceAdmission(60_000L, 10_000L).initialTicket()

        assertFalse(cadence.releaseRejectedStartup(initial))
        assertTrue(cadence.begin(initial))
        assertFalse(cadence.releaseRejectedStartup(foreign))
        assertTrue(cadence.releaseRejectedStartup(initial))
        assertFalse(cadence.releaseRejectedStartup(initial))
        assertTrue(cadence.begin(initial))

        val later = cadence.observe(70_000L)
        assertTrue(cadence.begin(later))
        assertFalse(cadence.releaseRejectedStartup(later))
        assertFalse(cadence.releaseRejectedStartup(initial))
    }

    @Test
    fun dropsCallbacksQueuedBehindSlowWorkWhenANewerCadenceWindowArrives() {
        val cadence = LiveTrackingCadenceAdmission(intervalMillis = 60_000L, startedAtElapsedRealtimeMillis = 10_000L)
        val delayed = cadence.observe(70_000L)
        val current = cadence.observe(130_000L)

        assertFalse(cadence.begin(delayed))
        assertTrue(cadence.begin(current))
    }

    @Test
    fun requestAgeZeroAndFreshnessSelectionKeepHistoricalAndExpiredFixesOut() {
        assertEquals(0L, BURST_MAX_UPDATE_AGE_MS)
        val fallback = candidate("fallback", 1, 180f, fallback = true)
        val freshExtra = candidate("fresh-extra", 10, 12f)
        val staleTime = fix(seconds = 130, accuracy = 12f)
        val nowElapsedRealtimeNanos = checkNotNull(staleTime.elapsedRealtimeNanos)

        assertEquals(
            "fresh-extra",
            selectFreshLiveTrackingCandidate(
                listOf(fallback, freshExtra),
                nowElapsedRealtimeNanos,
                staleTime.epochMilliseconds,
            )?.candidate?.value,
        )
        assertEquals(
            null,
            selectFreshLiveTrackingCandidate(
                listOf(fallback),
                nowElapsedRealtimeNanos,
                staleTime.epochMilliseconds,
            ),
        )
        val staleExtra = candidate("stale-extra", 1, 12f)
        val alternateFresh = candidate("alternate-fresh", 20, 40f)
        assertEquals(
            "alternate-fresh",
            selectFreshLiveTrackingCandidate(
                listOf(staleExtra, alternateFresh),
                nowElapsedRealtimeNanos,
                staleTime.epochMilliseconds,
            )?.candidate?.value,
        )
    }

    @Test
    fun normalMultiLocationCandidatesSurviveLaterRejectsAndUseSharedSelection() {
        val pool = LiveTrackingScheduledCandidatePool<String>()
        val thirtyMeters = candidate("accept-30m", 60, 30f)
        val twelveMeters = candidate("accept-12m", 63, 12f)

        pool.record(thirtyMeters.decision, thirtyMeters)
        pool.record(decision(LiveTrackingLocationQualityResult.REJECT, 2f), null)

        assertEquals(listOf("accept-30m"), pool.acceptedCandidates().map { it.value })
        pool.record(twelveMeters.decision, twelveMeters)
        assertEquals("accept-12m", pool.select()?.candidate?.value)
        assertEquals(2, pool.acceptedCandidates().size)
    }

    @Test
    fun oneNormalCallbackCannotAdvanceRepeatedAreaConfirmationMoreThanOnce() {
        val gate = LiveTrackingLocationQualityGate()
        val budget = LiveTrackingCycleConfirmationBudget()
        val start = fix(seconds = 0, accuracy = 5f)
        val firstSuspect = fix(seconds = 60, accuracy = 5f).copy(latitude = 1.0)
        val secondSuspect =
            firstSuspect
                .copy(epochMilliseconds = firstSuspect.epochMilliseconds + 3_000L)
                .copy(elapsedRealtimeNanos = firstSuspect.elapsedRealtimeNanos!! + 3_000_000_000L)
        val thirdSuspect =
            secondSuspect
                .copy(epochMilliseconds = secondSuspect.epochMilliseconds + 3_000L)
                .copy(elapsedRealtimeNanos = secondSuspect.elapsedRealtimeNanos!! + 3_000_000_000L)
        val inputs = listOf(start, firstSuspect, secondSuspect, thirdSuspect)
        val decisions =
            inputs.map { fix ->
                val decision =
                    gate.evaluate(
                        fix,
                        fix.elapsedRealtimeNanos!!,
                        fix.epochMilliseconds,
                        allowPendingAreaConfirmation = budget.allowsPendingAreaConfirmation(),
                    )
                budget.record(decision)
                decision
            }

        assertEquals(LiveTrackingLocationQualityResult.SUSPECT, decisions[1].result)
        assertEquals(LiveTrackingLocationQualityResult.SUSPECT, decisions[2].result)
        assertEquals("repeated_suspect_area", decisions[3].reason)
        assertEquals(LiveTrackingLocationQualityResult.SUSPECT, decisions[3].result)
        assertTrue(budget.hasUsedExtraProgress())
    }

    @Test
    fun acquisitionRegistrationOwnershipRejectsCancellationAndLateAcknowledgement() {
        val ownership = LiveTrackingAcquisitionOwnership()
        var registrations = 0
        var removals = 0
        val registered =
            ownership.registerIfOwned(
                sessionActive = { true },
                register = { registrations += 1 },
            )
        assertEquals(1, registrations)
        assertEquals(Unit, registered)

        ownership.finish()
        assertFalse(ownership.acknowledgeRegistration({ true }) { removals += 1 })
        assertEquals(1, removals)
        assertEquals(null, ownership.registerIfOwned({ true }) { registrations += 1 })
        assertEquals(1, registrations)
    }

    @Test
    fun pauseStopAndDestroyBeforeRegistrationCannotStartTheCallback() {
        for (lifecycle in listOf("pause", "stop", "destroy")) {
            val ownership = LiveTrackingAcquisitionOwnership()
            ownership.finish()
            var registrations = 0
            assertEquals(null, ownership.registerIfOwned({ lifecycle.isNotEmpty() }) { registrations += 1 })
            assertEquals(0, registrations)
        }
    }

    @Test
    fun refinementSuspectHandoffUsesTheSameCycleAndHonorsCooldown() {
        val allowed =
            LiveTrackingAcquisitionCycle(
                id = 7L,
                reason = LiveTrackingAcquisitionReason.ACCURACY_REFINEMENT,
                intervalMillis = 120_000L,
                initialCandidate = candidate("fallback", 120, 180f, fallback = true),
            )
        allowed.consumeDeliveredFix()
        assertEquals(
            LiveTrackingConfirmationHandoff.CONTINUE,
            handoffLiveTrackingAcquisitionToConfirmation(
                allowed,
                cooldownRemainingMillis = null,
                hasLocationPermission = true,
            ),
        )
        assertEquals(LiveTrackingAcquisitionReason.SUSPECT_CONFIRMATION, allowed.reason)
        assertEquals(1, allowed.extraFixesDelivered)
        assertEquals(
            LiveTrackingConfirmationHandoff.NOT_APPLICABLE,
            handoffLiveTrackingAcquisitionToConfirmation(
                allowed,
                cooldownRemainingMillis = null,
                hasLocationPermission = true,
            ),
        )

        val blocked =
            LiveTrackingAcquisitionCycle(
                id = 8L,
                reason = LiveTrackingAcquisitionReason.ACCURACY_REFINEMENT,
                intervalMillis = 120_000L,
                initialCandidate = candidate("fallback", 120, 180f, fallback = true),
            )
        assertEquals(
            LiveTrackingConfirmationHandoff.COOLDOWN_BLOCKED,
            handoffLiveTrackingAcquisitionToConfirmation(
                blocked,
                cooldownRemainingMillis = 60_000L,
                hasLocationPermission = true,
            ),
        )
        assertEquals(LiveTrackingAcquisitionReason.SUSPECT_CONFIRMATION, blocked.reason)
        assertEquals(0, blocked.extraFixesDelivered)
        assertTrue(blocked.selectCandidate()?.candidate?.isScheduledFallback == true)
    }

    @Test
    fun acquisitionTimeoutOutcomesDistinguishFallbackExtraAndNoCandidate() {
        assertEquals(
            "timeout_no_eligible_candidate_none",
            liveTrackingAcquisitionOutcome("timeout", "none", false, false),
        )
        assertEquals("timeout_scheduled_fallback_sent", liveTrackingAcquisitionOutcome("timeout", "sent", true, true))
        assertEquals(
            "timeout_selected_extra_candidate_queued",
            liveTrackingAcquisitionOutcome("timeout", "queued", true, false),
        )
    }

    @Test
    fun completedCycleRejectsCallbacksAfterPauseStopOrDestroy() {
        val cycle =
            LiveTrackingAcquisitionCycle(
                id = 6L,
                reason = LiveTrackingAcquisitionReason.ACCURACY_REFINEMENT,
                intervalMillis = 60_000L,
                initialCandidate = candidate("fallback", 60, 180f, fallback = true),
            )
        cycle.complete()

        assertFalse(cycle.consumeDeliveredFix())
        assertFalse(
            canContinueLiveTrackingSend(
                isPaused = false,
                isStopping = false,
                stop = false,
                isPauseRequested = true,
            ),
        )
        assertFalse(canContinueLiveTrackingSend(isPaused = false, isStopping = true, stop = false))
    }

    private fun eligible(
        intervalMillis: Long,
        accuracyMeters: Float?,
    ): Boolean = shouldRefineLiveTrackingAccuracy(context(intervalMillis, accuracyMeters))

    private fun context(
        intervalMillis: Long,
        accuracyMeters: Float?,
    ): LiveTrackingAccuracyRefinementContext =
        LiveTrackingAccuracyRefinementContext(
            source = LiveTrackingFixSource.CALLBACK,
            result = LiveTrackingLocationQualityResult.ACCEPT,
            intervalMillis = intervalMillis,
            accuracyMeters = accuracyMeters,
            hasFineLocationPermission = true,
            sessionActive = true,
            acquisitionRunning = false,
        )

    private fun candidate(
        label: String,
        seconds: Long,
        accuracy: Float?,
        fallback: Boolean = false,
    ): LiveTrackingCandidate<String> {
        val fix = fix(seconds, accuracy)
        return LiveTrackingCandidate(
            value = label,
            fix = fix,
            decision = decision(LiveTrackingLocationQualityResult.ACCEPT, accuracy),
            isScheduledFallback = fallback,
        )
    }

    private fun decision(
        result: LiveTrackingLocationQualityResult,
        accuracy: Float?,
    ): LiveTrackingLocationQualityDecision =
        LiveTrackingLocationQualityDecision(
            result = result,
            reason = "test",
            accuracyMeters = accuracy,
            fixAgeMillis = 0L,
            distanceFromPreviousMeters = null,
            impliedSpeedMetersPerSecond = null,
        )

    private fun fix(
        seconds: Long,
        accuracy: Float?,
    ): LiveTrackingLocationFix =
        LiveTrackingLocationFix(
            latitude = 0.0,
            longitude = 0.0,
            epochMilliseconds = BASE_EPOCH_MILLISECONDS + seconds * 1_000L,
            elapsedRealtimeNanos = BASE_ELAPSED_REALTIME_NANOS + seconds * NANOS_PER_SECOND,
            accuracyMeters = accuracy,
            speedMetersPerSecond = null,
        )

    private companion object {
        const val BASE_EPOCH_MILLISECONDS = 1_750_000_000_000L
        const val BASE_ELAPSED_REALTIME_NANOS = 1_000_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
