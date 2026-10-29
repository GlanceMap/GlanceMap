package com.glancemap.glancemapcompanionapp.livetracking

import com.google.android.gms.location.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class LiveTrackingServiceOrchestrationTest {
    @Test
    fun temporaryRequestIsBoundedAndDoesNotRequestHistoricalLocations() {
        val request = liveTrackingRefinementLocationRequest()
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, request.priority)
        assertEquals(BURST_UPDATE_INTERVAL_MS, request.intervalMillis)
        assertEquals(BURST_UPDATE_INTERVAL_MS, request.minUpdateIntervalMillis)
        assertTrue(request.maxUpdateDelayMillis < BURST_UPDATE_INTERVAL_MS * 2L)
        assertEquals(0L, request.maxUpdateAgeMillis)
        assertEquals(MAX_ACCURACY_EXTRA_FIXES, request.maxUpdates)
        assertEquals(ACQUISITION_WINDOW_MS, request.durationMillis)
    }

    @Test
    fun multipleDelayedOldCallbacksCannotConsumeResumedAdmission() {
        val h = Harness()
        val old = h.generation
        val tickets = (10L..12L).map { old.admission.observe(h.nowMillis + it * 60_000L) }
        h.pauseAndResume()
        tickets.forEach {
            assertFalse(h.coordinator.reserveAdmission(old, it))
            assertFalse(h.coordinator.reserveAdmission(h.generation, it))
        }
        h.normal(h.generation.admission.initialTicket(), listOf(h.fix(0)))
        assertEquals(1, h.transmitted.size)
    }

    @Test
    fun rejectedFortySecondCallbackLeavesTwentySecondStartupCacheAdmissible() {
        val h = Harness()
        h.normal(h.ticket(), listOf(h.fix(40)))
        assertTrue(h.transmitted.isEmpty())
        val cache = h.fix(20)
        h.normal(h.generation.admission.initialTicket(), listOf(cache))
        assertEquals(listOf(cache), h.transmitted)
    }

    @Test
    fun rejectedCallbacksLeaveStartupAvailableUntilAValidCallbackEstablishesIt() {
        val h = Harness()
        h.normal(h.ticket(), listOf(h.fix(40)))
        h.normal(h.ticket(), listOf(h.fix(35)))
        h.normal(h.ticket(), listOf(h.fix(0)))
        h.normal(h.ticket(), listOf(h.fix(0)))
        assertEquals(1, h.transmitted.size)
    }

    @Test
    fun rejectedInvalidInitialFixDoesNotExcludeAnOlderValidStartupCache() {
        val h = Harness()
        h.normal(h.ticket(), listOf(h.fix(0).copy(latitude = Double.NaN)))
        val cache = h.fix(20)
        h.normal(h.generation.admission.initialTicket(), listOf(cache))
        assertEquals(listOf(cache), h.transmitted)
    }

    @Test
    fun acceptedStartupCacheAndImmediateNewerCallbackTransmitOnlyOnce() {
        val h = Harness()
        h.normal(h.generation.admission.initialTicket(), listOf(h.fix(20)))
        h.normal(h.ticket(), listOf(h.fix(0)))
        assertEquals(1, h.transmitted.size)
        assertEquals(1, h.gateObservations)
    }

    @Test
    fun acceptedCandidateSurvivesTrailingRejectAndOnlyOnePositionIsQueued() {
        val h = Harness()
        h.networkFails = true
        h.normal(h.ticket(), listOf(h.fix(1), h.fix(0).copy(latitude = Double.NaN)))
        h.normal(h.ticket(), listOf(h.fix(0)))
        assertEquals(1, h.queued.size)
        assertTrue(h.transmitted.isEmpty())
    }

    @Test
    fun preparationFromInvalidatedGenerationCannotPublishOrRegister() {
        val h = Harness()
        val prepared = FakeAcquisition(h.generation)
        h.pauseAndResume()
        assertFalse(h.coordinator.publishAcquisition(prepared))
        assertNull(h.register(prepared))
        assertEquals(0, h.provider.requestCount)
    }

    @Test
    fun publicationAndPauseCancellationUseTheSameSynchronization() {
        val h = Harness()
        val prepared = FakeAcquisition(h.generation)
        val preparing = CountDownLatch(1)
        val continuePreparation = CountDownLatch(1)
        val pausing = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val preparation =
            checkedThread(failure) {
                h.coordinator.withGeneration(prepared.generation) {
                    preparing.countDown()
                    await(continuePreparation)
                    assertTrue(h.coordinator.publishAcquisition(prepared))
                }
            }
        await(preparing)
        val pause =
            checkedThread(failure) {
                pausing.countDown()
                h.pauseAndResume()
            }
        try {
            await(pausing)
        } finally {
            continuePreparation.countDown()
        }
        join(preparation, pause)
        failure.get()?.let { throw it }
        assertTrue(prepared.cancelled)
        assertNull(h.register(prepared))
        assertEquals(0, h.provider.requestCount)
    }

    @Test
    fun cancellationBeforeRegistrationPreventsTemporaryGps() {
        val h = Harness()
        val acquisition = h.publish()
        h.invalidate()
        assertTrue(acquisition.cancelled)
        assertNull(h.register(acquisition))
        assertEquals(0, h.provider.requestCount)
    }

    @Test
    fun lateAcknowledgementRemovesOldCallbackWithoutRemovingNewOwner() {
        val h = Harness()
        val old = h.publish()
        assertNotNull(h.register(old))
        h.pauseAndResume()
        val current = h.publish()
        h.register(current)
        h.provider.acknowledge(current)
        h.provider.acknowledge(old)
        assertFalse(h.provider.registered.contains(old))
        assertTrue(h.provider.registered.contains(current))
        assertEquals(2, h.provider.removals.count { it === old })
        assertEquals(false, old.registrationAccepted)
        assertTrue(h.coordinator.isAcquisitionActive(current))
    }

    @Test
    fun delayedOldTemporaryResultCannotMutateTheGateAfterResume() {
        val h = Harness()
        val old = h.publish()
        h.register(old)
        h.provider.acknowledge(old)
        val delayedResult = { h.result(old, h.fix(0)) }
        h.pauseAndResume()
        val before = h.gateObservations
        delayedResult()
        assertEquals(before, h.gateObservations)
        assertEquals(0, old.cycle.extraFixesDelivered)
        assertFalse(h.finishAndTransmit(old))
        assertTrue(h.transmitted.isEmpty())
    }

    @Test
    fun acknowledgementRacingFirstResultInitializesCooldownOnce() = runCooldownRace(acknowledgementFirst = true)

    @Test
    fun firstResultRacingAcknowledgementInitializesCooldownOnce() = runCooldownRace(acknowledgementFirst = false)

    @Test
    fun differentAcquisitionIsBlockedForTwoMinutesButSameOwnerIsAllowed() {
        val h = Harness()
        val first = h.publish(confirmation = true)
        h.register(first)
        h.provider.acknowledge(first)
        assertTrue(h.beginRescue(first))
        assertEquals(1, first.cooldownStarts)
        h.coordinator.finishAcquisition(first) { first.cycle.complete() }
        val second = h.publish(confirmation = true)
        h.register(second)
        h.provider.acknowledge(second)
        assertEquals(false, second.registrationAccepted)
        assertTrue(second.cooldownBlocked)
        h.nowMillis += 120_000L
        val third = h.publish(confirmation = true)
        h.register(third)
        h.provider.acknowledge(third)
        assertEquals(true, third.registrationAccepted)
        assertEquals(1, third.cooldownStarts)
    }

    @Test
    fun timedOutAcquisitionRetainsOnlyOneFreshFallbackAndReportsItsOutcome() {
        val h = Harness()
        val initial = h.fix(0)
        val acquisition = h.publish(fallback = LiveTrackingCandidate(initial, initial, h.evaluate(initial), true))
        h.register(acquisition)
        h.provider.acknowledge(acquisition)
        h.nowMillis += ACQUISITION_WINDOW_MS
        assertTrue(h.finishAndTransmit(acquisition))
        assertFalse(h.finishAndTransmit(acquisition))
        assertEquals(1, h.transmitted.size)
        assertEquals("timeout_scheduled_fallback_sent", h.outcome)
    }

    @Test
    fun fallbackExpiringDuringAcquisitionIsNeitherSentNorQueued() {
        val h = Harness()
        h.evaluate(h.fix(0))
        val initial = h.fix(119)
        val acquisition = h.publish(fallback = LiveTrackingCandidate(initial, initial, h.evaluate(initial), true))
        h.networkFails = true
        h.nowMillis += ACQUISITION_WINDOW_MS
        h.finishAndTransmit(acquisition)
        assertTrue(h.transmitted.isEmpty())
        assertTrue(h.queued.isEmpty())
        assertEquals("timeout_no_eligible_candidate_stale_no_candidate", h.outcome)
    }

    private fun runCooldownRace(acknowledgementFirst: Boolean) {
        val h = Harness()
        // Establish a fresh baseline before quarantining a later jump.
        h.nowMillis -= 60_000L
        h.evaluate(h.fix(0))
        h.nowMillis += 60_000L
        h.evaluate(h.fix(0).copy(latitude = 1.0))
        val acquisition = h.publish(confirmation = true)
        h.register(acquisition)
        h.nowMillis += BURST_UPDATE_INTERVAL_MS
        val insideInitialization = CountDownLatch(1)
        val releaseInitialization = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        h.onRescueStarted = {
            insideInitialization.countDown()
            await(releaseInitialization)
        }
        val acknowledgement = { h.provider.acknowledge(acquisition) }
        val result = { h.result(acquisition, h.fix(0).copy(latitude = 1.0)) }
        val first = checkedThread(failure, if (acknowledgementFirst) acknowledgement else result)
        await(insideInitialization)
        val second =
            checkedThread(failure) {
                secondStarted.countDown()
                (if (acknowledgementFirst) result else acknowledgement)()
            }
        try {
            await(secondStarted)
        } finally {
            releaseInitialization.countDown()
        }
        join(first, second)
        failure.get()?.let { throw it }
        assertEquals(1, acquisition.cooldownStarts)
        assertFalse(acquisition.cooldownBlocked)
        assertEquals(true, acquisition.registrationAccepted)
        assertTrue(h.coordinator.isAcquisitionActive(acquisition))
        assertEquals(1, acquisition.cycle.extraFixesDelivered)
    }

    private class FakeAcquisition(
        override val generation: LiveTrackingGeneration,
        val confirmation: Boolean = false,
        fallback: LiveTrackingCandidate<LiveTrackingLocationFix>? = null,
    ) : GenerationOwnedLiveTrackingAcquisition {
        override val ownership = LiveTrackingAcquisitionOwnership()
        val budget = LiveTrackingCycleConfirmationBudget()
        val cycle =
            LiveTrackingAcquisitionCycle(
                id = generation.id,
                reason =
                    if (confirmation) {
                        LiveTrackingAcquisitionReason.SUSPECT_CONFIRMATION
                    } else {
                        LiveTrackingAcquisitionReason.ACCURACY_REFINEMENT
                    },
                intervalMillis = 60_000L,
                initialCandidate = fallback,
            )
        var cancelled = false
        var registrationAccepted: Boolean? = null
        var cooldownStarts = 0
        var cooldownBlocked = false
    }

    /** All lifecycle, admission and provider transitions use the coordinator called by the service. */
    private class Harness {
        val coordinator = LiveTrackingOrchestration<FakeAcquisition>()
        val provider = FakeTemporaryLocationProvider()
        private val gate = LiveTrackingLocationQualityGate()
        private var lastProcessedFix: LiveTrackingLocationFix? = null
        var nowMillis = 200_000L
        var generation = checkNotNull(coordinator.startGeneration(60_000L, nowMillis) { true })
        var gateObservations = 0
        val transmitted = mutableListOf<LiveTrackingLocationFix>()
        val queued = mutableListOf<LiveTrackingLocationFix>()
        var networkFails = false
        var outcome: String? = null
        var onRescueStarted: () -> Unit = {}

        fun ticket(): LiveTrackingCadenceTicket = generation.admission.observe(nowMillis)

        fun invalidate() {
            coordinator.invalidateGeneration(
                onCancelled = {
                    it.cancelled = true
                    it.cycle.complete()
                    provider.remove(it)
                },
                stopPeriodicUpdates = {},
            )
        }

        fun pauseAndResume() {
            invalidate()
            generation = checkNotNull(coordinator.startGeneration(60_000L, nowMillis) { true })
        }

        fun normal(
            ticket: LiveTrackingCadenceTicket,
            fixes: List<LiveTrackingLocationFix>,
        ) {
            val owner = generation
            if (!coordinator.reserveAdmission(owner, ticket)) return
            val pool = LiveTrackingScheduledCandidatePool<LiveTrackingLocationFix>()
            val budget = LiveTrackingCycleConfirmationBudget()
            val allRejected =
                coordinator.withGeneration(owner) {
                    fixes
                        .filter { fix ->
                            val previous = lastProcessedFix
                            previous == null || compareLiveTrackingFixTime(fix, previous) > 0
                        }.map { fix ->
                            val decision = evaluate(fix, budget.allowsPendingAreaConfirmation())
                            budget.record(decision)
                            if (
                                decision.result != LiveTrackingLocationQualityResult.REJECT ||
                                gate.hasFreshInitialFix
                            ) {
                                lastProcessedFix = fix
                            }
                            pool.record(decision, LiveTrackingCandidate(fix, fix, decision))
                            decision.result
                        }.all { it == LiveTrackingLocationQualityResult.REJECT }
                } ?: return
            if (allRejected) coordinator.releaseRejectedStartup(owner, ticket)
            coordinator.withGeneration(owner) { transmit(pool.acceptedCandidates()) }
        }

        fun publish(
            confirmation: Boolean = false,
            fallback: LiveTrackingCandidate<LiveTrackingLocationFix>? = null,
        ): FakeAcquisition =
            FakeAcquisition(generation, confirmation, fallback).also {
                assertTrue(coordinator.publishAcquisition(it))
            }

        fun register(acquisition: FakeAcquisition): Unit? =
            coordinator.registerAcquisition(acquisition, { true }) {
                provider.request(acquisition) {
                    val retained =
                        coordinator.acknowledgeRegistration(acquisition, { true }) {
                            provider.remove(acquisition)
                        }
                    acquisition.registrationAccepted = retained &&
                        coordinator.withAcquisition(acquisition) {
                            !acquisition.confirmation || beginRescue(acquisition)
                        } == true
                }
            }

        fun beginRescue(acquisition: FakeAcquisition): Boolean =
            coordinator
                .beginRescueActivity(acquisition, nowMillis * 1_000_000L, 120_000L) {
                    acquisition.cooldownStarts += 1
                    onRescueStarted()
                }.also { allowed ->
                    if (!allowed) {
                        acquisition.cooldownBlocked = true
                        coordinator.finishAcquisition(acquisition) { acquisition.cycle.complete() }
                    }
                }

        fun result(
            acquisition: FakeAcquisition,
            fix: LiveTrackingLocationFix,
        ) {
            coordinator.withAcquisition(acquisition) {
                if (acquisition.confirmation && !beginRescue(acquisition)) return@withAcquisition
                if (!acquisition.cycle.consumeDeliveredFix()) return@withAcquisition
                val decision = evaluate(fix, acquisition.budget.allowsPendingAreaConfirmation())
                acquisition.budget.record(decision)
                acquisition.cycle.recordDecision(decision, LiveTrackingCandidate(fix, fix, decision))
            }
        }

        fun finishAndTransmit(acquisition: FakeAcquisition): Boolean =
            coordinator.finishAcquisition(acquisition) {
                acquisition.cycle.complete()
                provider.remove(acquisition)
                coordinator.withGeneration(acquisition.generation) {
                    val selection = transmit(acquisition.cycle.eligibleCandidates())
                    outcome =
                        liveTrackingAcquisitionOutcome(
                            "timeout",
                            if (selection == null) {
                                "stale_no_candidate"
                            } else if (networkFails) {
                                "queued"
                            } else {
                                "sent"
                            },
                            selection != null,
                            selection?.candidate?.isScheduledFallback == true,
                        )
                }
            }

        private fun transmit(
            candidates: List<LiveTrackingCandidate<LiveTrackingLocationFix>>,
        ): LiveTrackingCandidateSelection<LiveTrackingLocationFix>? {
            val selection = selectFreshLiveTrackingCandidate(candidates, nowMillis * 1_000_000L, BASE_EPOCH + nowMillis)
            selection?.candidate?.value?.let { if (networkFails) queued.add(it) else transmitted.add(it) }
            return selection
        }

        fun evaluate(
            fix: LiveTrackingLocationFix,
            allowConfirmation: Boolean = true,
        ): LiveTrackingLocationQualityDecision {
            gateObservations += 1
            return gate.evaluate(
                fix,
                nowMillis * 1_000_000L,
                BASE_EPOCH + nowMillis,
                allowPendingAreaConfirmation = allowConfirmation,
            )
        }

        fun fix(ageSeconds: Long): LiveTrackingLocationFix =
            LiveTrackingLocationFix(
                latitude = 0.0,
                longitude = 0.0,
                epochMilliseconds = BASE_EPOCH + nowMillis - ageSeconds * 1_000L,
                elapsedRealtimeNanos = (nowMillis - ageSeconds * 1_000L) * 1_000_000L,
                accuracyMeters = 20f,
                speedMetersPerSecond = null,
            )
    }

    private class FakeTemporaryLocationProvider {
        var requestCount = 0
            private set
        val registered = mutableSetOf<FakeAcquisition>()
        val removals = mutableListOf<FakeAcquisition>()
        private val acknowledgements = mutableMapOf<FakeAcquisition, () -> Unit>()

        fun request(
            acquisition: FakeAcquisition,
            onAcknowledged: () -> Unit,
        ) {
            requestCount += 1
            acknowledgements[acquisition] = onAcknowledged
        }

        fun remove(acquisition: FakeAcquisition) {
            registered.remove(acquisition)
            removals.add(acquisition)
        }

        fun acknowledge(acquisition: FakeAcquisition) {
            registered.add(acquisition)
            checkNotNull(acknowledgements.remove(acquisition))()
        }
    }

    private companion object {
        const val BASE_EPOCH = 1_750_000_000_000L

        fun await(latch: CountDownLatch) {
            check(latch.await(5, TimeUnit.SECONDS)) { "Timed out waiting for deterministic race ordering" }
        }

        fun checkedThread(
            failure: AtomicReference<Throwable?>,
            action: () -> Unit,
        ): Thread =
            thread(isDaemon = true) {
                try {
                    action()
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                }
            }

        fun join(vararg threads: Thread) {
            threads.forEach {
                it.join(5_000L)
                check(!it.isAlive) { "Orchestration thread did not finish" }
            }
        }
    }
}
