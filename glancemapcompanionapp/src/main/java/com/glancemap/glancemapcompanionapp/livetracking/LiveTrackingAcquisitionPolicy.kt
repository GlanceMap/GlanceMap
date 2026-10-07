@file:Suppress("TooManyFunctions")

package com.glancemap.glancemapcompanionapp.livetracking

internal const val ACCURACY_BURST_MIN_INTERVAL_MS = 60_000L
internal const val ACCURACY_BURST_TRIGGER_METERS = 100f
internal const val ACCURACY_BURST_TARGET_METERS = 25f
internal const val ACQUISITION_WINDOW_MS = 8_000L
internal const val BURST_UPDATE_INTERVAL_MS = 3_000L
internal const val BURST_MAX_UPDATE_AGE_MS = 0L
internal const val MAX_ACCURACY_EXTRA_FIXES = 2
internal const val CANDIDATE_RECENCY_BAND_MS = 3_000L

internal fun normalLiveTrackingIntervalMs(updateIntervalSeconds: Int): Long =
    updateIntervalSeconds
        .coerceIn(15, 600)
        .toLong() * 1000L

internal data class LiveTrackingCadenceTicket(
    val windowIndex: Long,
    internal val admission: LiveTrackingCadenceAdmission,
)

internal class LiveTrackingAcquisitionOwnership {
    private var registrationStarted = false
    private var completed = false

    @Synchronized
    fun <T> registerIfOwned(
        sessionActive: () -> Boolean,
        register: () -> T,
    ): T? {
        if (completed || !sessionActive()) return null
        registrationStarted = true
        return register()
    }

    @Synchronized
    fun acknowledgeRegistration(sessionActive: () -> Boolean): Boolean {
        if (!registrationStarted || completed || !sessionActive()) return false
        return true
    }

    fun acknowledgeRegistration(
        sessionActive: () -> Boolean,
        removeLateRegistration: () -> Unit,
    ): Boolean {
        val retained = acknowledgeRegistration(sessionActive)
        if (!retained) removeLateRegistration()
        return retained
    }

    @Synchronized
    fun isActive(sessionActive: () -> Boolean): Boolean = !completed && sessionActive()

    @Synchronized
    fun finish(): Boolean {
        if (completed) return false
        completed = true
        return true
    }

    @Synchronized
    fun isCompleted(): Boolean = completed
}

internal class LiveTrackingCycleConfirmationBudget {
    private var observations = 0
    private var extraProgressUsed = false

    fun allowsPendingAreaConfirmation(): Boolean = !extraProgressUsed

    fun record(decision: LiveTrackingLocationQualityDecision) {
        val repeatedAreaConfirmation =
            decision.reason == "repeated_suspect_area" || decision.reason.startsWith("confirmed_suspect_")
        if (observations > 0 && !extraProgressUsed && repeatedAreaConfirmation) {
            extraProgressUsed = true
        }
        observations += 1
    }

    fun hasUsedExtraProgress(): Boolean = extraProgressUsed
}

internal enum class LiveTrackingConfirmationHandoff {
    CONTINUE,
    COOLDOWN_BLOCKED,
    UNAVAILABLE,
    NOT_APPLICABLE,
}

internal fun handoffLiveTrackingAcquisitionToConfirmation(
    cycle: LiveTrackingAcquisitionCycle<*>,
    cooldownRemainingMillis: Long?,
    hasLocationPermission: Boolean,
): LiveTrackingConfirmationHandoff {
    if (cycle.reason != LiveTrackingAcquisitionReason.ACCURACY_REFINEMENT) {
        return LiveTrackingConfirmationHandoff.NOT_APPLICABLE
    }
    cycle.enterSuspectConfirmation()
    return when {
        !hasLocationPermission -> LiveTrackingConfirmationHandoff.UNAVAILABLE
        cooldownRemainingMillis != null -> LiveTrackingConfirmationHandoff.COOLDOWN_BLOCKED
        else -> LiveTrackingConfirmationHandoff.CONTINUE
    }
}

internal fun liveTrackingAcquisitionOutcome(
    acquisitionOutcome: String?,
    transmissionOutcome: String,
    hasCandidate: Boolean,
    usesScheduledFallback: Boolean,
): String {
    val outcome = acquisitionOutcome ?: return transmissionOutcome
    return when {
        outcome.startsWith("cancelled_") -> outcome
        outcome == "timeout" ->
            when {
                !hasCandidate -> "timeout_no_eligible_candidate_$transmissionOutcome"
                usesScheduledFallback -> "timeout_scheduled_fallback_$transmissionOutcome"
                else -> "timeout_selected_extra_candidate_$transmissionOutcome"
            }
        outcome == "registration_failure" ->
            if (usesScheduledFallback) {
                "registration_failure_fallback_$transmissionOutcome"
            } else {
                "registration_failure_$transmissionOutcome"
            }
        outcome == "target_reached" -> "early_target_$transmissionOutcome"
        outcome == "suspect_confirmed" -> "suspect_confirmed_$transmissionOutcome"
        outcome == "confirmation_cooldown_blocked" -> "confirmation_cooldown_blocked_$transmissionOutcome"
        outcome == "budget_exhausted" -> "budget_exhausted_$transmissionOutcome"
        else -> transmissionOutcome
    }
}

/** Coalesces startup overlap and obsolete callback work by monotonic request cadence. */
internal class LiveTrackingCadenceAdmission(
    private val intervalMillis: Long,
    private val startedAtElapsedRealtimeMillis: Long,
) {
    @Volatile
    private var newestObservedWindow = 0L
    private var newestProcessedWindow = -1L

    @Synchronized
    fun observe(elapsedRealtimeMillis: Long): LiveTrackingCadenceTicket {
        val window = ((elapsedRealtimeMillis - startedAtElapsedRealtimeMillis).coerceAtLeast(0L) / intervalMillis)
        newestObservedWindow = maxOf(newestObservedWindow, window)
        return LiveTrackingCadenceTicket(window, this)
    }

    @Synchronized
    fun initialTicket(): LiveTrackingCadenceTicket {
        newestObservedWindow = maxOf(newestObservedWindow, 0L)
        return LiveTrackingCadenceTicket(0L, this)
    }

    @Synchronized
    fun begin(ticket: LiveTrackingCadenceTicket): Boolean {
        if (
            ticket.admission !== this ||
            ticket.windowIndex < newestObservedWindow ||
            ticket.windowIndex <= newestProcessedWindow
        ) {
            return false
        }
        newestProcessedWindow = ticket.windowIndex
        return true
    }

    @Synchronized
    fun releaseRejectedStartup(ticket: LiveTrackingCadenceTicket): Boolean {
        if (ticket.admission === this && ticket.windowIndex == 0L && newestProcessedWindow == 0L) {
            newestProcessedWindow = -1L
            return true
        }
        return false
    }

    fun isSuperseded(ticket: LiveTrackingCadenceTicket): Boolean =
        ticket.admission !== this ||
            ticket.windowIndex < newestObservedWindow
}

internal enum class LiveTrackingAcquisitionReason {
    NONE,
    ACCURACY_REFINEMENT,
    SUSPECT_CONFIRMATION,
}

internal data class LiveTrackingAccuracyRefinementContext(
    val source: LiveTrackingFixSource,
    val result: LiveTrackingLocationQualityResult,
    val intervalMillis: Long,
    val accuracyMeters: Float?,
    val hasFineLocationPermission: Boolean,
    val sessionActive: Boolean,
    val acquisitionRunning: Boolean,
)

internal data class LiveTrackingCandidate<T>(
    val value: T,
    val fix: LiveTrackingLocationFix,
    val decision: LiveTrackingLocationQualityDecision,
    val isScheduledFallback: Boolean = false,
)

internal data class LiveTrackingCandidateSelection<T>(
    val candidate: LiveTrackingCandidate<T>,
    val selectionReason: String,
    val ageBehindNewestMillis: Long,
)

internal class LiveTrackingScheduledCandidatePool<T> {
    private val candidates = mutableListOf<LiveTrackingCandidate<T>>()

    fun record(
        decision: LiveTrackingLocationQualityDecision,
        candidate: LiveTrackingCandidate<T>?,
    ) {
        if (decision.result == LiveTrackingLocationQualityResult.ACCEPT && candidate != null) {
            candidates += candidate
        }
    }

    fun acceptedCandidates(): List<LiveTrackingCandidate<T>> = candidates.toList()

    fun scheduledFallbacks(): List<LiveTrackingCandidate<T>> = candidates.map { it.copy(isScheduledFallback = true) }

    fun select(): LiveTrackingCandidateSelection<T>? = selectLiveTrackingCandidate(candidates)
}

internal fun shouldRefineLiveTrackingAccuracy(context: LiveTrackingAccuracyRefinementContext): Boolean =
    context.source == LiveTrackingFixSource.CALLBACK &&
        context.result == LiveTrackingLocationQualityResult.ACCEPT &&
        context.intervalMillis >= ACCURACY_BURST_MIN_INTERVAL_MS &&
        context.accuracyMeters != null &&
        context.accuracyMeters >= ACCURACY_BURST_TRIGGER_METERS &&
        context.hasFineLocationPermission &&
        context.sessionActive &&
        !context.acquisitionRunning

internal fun compareLiveTrackingFixTime(
    first: LiveTrackingLocationFix,
    second: LiveTrackingLocationFix,
): Int {
    val firstElapsed = first.elapsedRealtimeNanos
    val secondElapsed = second.elapsedRealtimeNanos
    return if (firstElapsed != null && secondElapsed != null) {
        firstElapsed.compareTo(secondElapsed)
    } else {
        first.epochMilliseconds.compareTo(second.epochMilliseconds)
    }
}

internal fun liveTrackingFixTimeDeltaMillis(
    newer: LiveTrackingLocationFix,
    older: LiveTrackingLocationFix,
): Long? {
    val newerElapsed = newer.elapsedRealtimeNanos
    val olderElapsed = older.elapsedRealtimeNanos
    return if (newerElapsed != null && olderElapsed != null) {
        ((newerElapsed - olderElapsed) / 1_000_000L).takeIf { it >= 0L }
    } else {
        (newer.epochMilliseconds - older.epochMilliseconds).takeIf { it >= 0L }
    }
}

internal fun <T> sortLiveTrackingFixesChronologically(
    fixes: List<Pair<T, LiveTrackingLocationFix>>,
): List<Pair<T, LiveTrackingLocationFix>> =
    fixes.sortedWith { first, second ->
        compareLiveTrackingFixTime(first.second, second.second)
    }

@Suppress("ReturnCount")
internal fun <T> selectLiveTrackingCandidate(
    candidates: List<LiveTrackingCandidate<T>>,
): LiveTrackingCandidateSelection<T>? {
    val accepted = candidates.filter { it.decision.result == LiveTrackingLocationQualityResult.ACCEPT }
    if (accepted.isEmpty()) return null

    val newest =
        accepted.maxWithOrNull { first, second -> compareLiveTrackingFixTime(first.fix, second.fix) }
            ?: return null
    val recent =
        accepted.filter { candidate ->
            liveTrackingFixTimeDeltaMillis(newest.fix, candidate.fix)
                ?.let { it <= CANDIDATE_RECENCY_BAND_MS }
                ?: false
        }
    val targetAccuracy =
        recent
            .filter { it.decision.accuracyMeters?.let { accuracy -> accuracy <= ACCURACY_BURST_TARGET_METERS } == true }
            .maxWithOrNull { first, second -> compareLiveTrackingFixTime(first.fix, second.fix) }
    if (targetAccuracy != null) {
        return LiveTrackingCandidateSelection(
            candidate = targetAccuracy,
            selectionReason = "newest_target_accuracy",
            ageBehindNewestMillis = liveTrackingFixTimeDeltaMillis(newest.fix, targetAccuracy.fix) ?: 0L,
        )
    }

    val lowestAccuracy =
        recent
            .filter { it.decision.accuracyMeters?.isFinite() == true }
            .minWithOrNull(
                compareBy<LiveTrackingCandidate<T>> { it.decision.accuracyMeters }
                    .thenComparator { first, second -> compareLiveTrackingFixTime(second.fix, first.fix) },
            )
    if (lowestAccuracy != null) {
        return LiveTrackingCandidateSelection(
            candidate = lowestAccuracy,
            selectionReason = "lowest_accuracy_in_recency_band",
            ageBehindNewestMillis = liveTrackingFixTimeDeltaMillis(newest.fix, lowestAccuracy.fix) ?: 0L,
        )
    }

    return LiveTrackingCandidateSelection(
        candidate = newest,
        selectionReason =
            if (accepted.all { it.decision.accuracyMeters == null }) {
                "newest_accept_accuracy_unavailable"
            } else {
                "newest_in_recency_band"
            },
        ageBehindNewestMillis = 0L,
    )
}

internal fun <T> selectFreshLiveTrackingCandidate(
    candidates: List<LiveTrackingCandidate<T>>,
    nowElapsedRealtimeNanos: Long,
    nowEpochMilliseconds: Long,
): LiveTrackingCandidateSelection<T>? {
    val freshCandidates =
        candidates.filter { candidate ->
            liveTrackingLocationAgeMillis(candidate.fix, nowElapsedRealtimeNanos, nowEpochMilliseconds)
                ?.let { it <= MAX_LIVE_TRACKING_FIX_AGE_MILLIS }
                ?: false
        }
    return selectLiveTrackingCandidate(freshCandidates)
}

internal class LiveTrackingAcquisitionCycle<T>(
    val id: Long,
    initialReason: LiveTrackingAcquisitionReason,
    val intervalMillis: Long,
    initialCandidates: List<LiveTrackingCandidate<T>>,
) {
    constructor(
        id: Long,
        reason: LiveTrackingAcquisitionReason,
        intervalMillis: Long,
        initialCandidate: LiveTrackingCandidate<T>?,
    ) : this(id, reason, intervalMillis, listOfNotNull(initialCandidate))

    private val candidates = mutableListOf<LiveTrackingCandidate<T>>()

    var reason: LiveTrackingAcquisitionReason = initialReason
        private set

    @Volatile
    var completed: Boolean = false
        private set

    var extraFixesDelivered: Int = 0
        private set
    var extraFixesAccepted: Int = 0
        private set
    var extraFixesRejected: Int = 0
        private set
    var extraFixesSuspect: Int = 0
        private set
    var staleFixesIgnored: Int = 0
        private set
    var duplicateFixesIgnored: Int = 0
        private set
    var earlyTargetReached: Boolean = false
        private set

    init {
        candidates += initialCandidates
    }

    fun consumeDeliveredFix(): Boolean {
        if (completed || extraFixesDelivered >= MAX_ACCURACY_EXTRA_FIXES) return false
        extraFixesDelivered += 1
        return true
    }

    fun recordIgnoredFix(duplicate: Boolean) {
        if (duplicate) {
            duplicateFixesIgnored += 1
        } else {
            staleFixesIgnored += 1
        }
    }

    fun recordDecision(
        decision: LiveTrackingLocationQualityDecision,
        candidate: LiveTrackingCandidate<T>?,
    ) {
        if (completed) return
        when (decision.result) {
            LiveTrackingLocationQualityResult.ACCEPT -> {
                extraFixesAccepted += 1
                candidate?.let(candidates::add)
                if (reason == LiveTrackingAcquisitionReason.ACCURACY_REFINEMENT &&
                    decision.accuracyMeters?.let { it <= ACCURACY_BURST_TARGET_METERS } == true
                ) {
                    earlyTargetReached = true
                }
            }
            LiveTrackingLocationQualityResult.SUSPECT -> extraFixesSuspect += 1
            LiveTrackingLocationQualityResult.REJECT -> extraFixesRejected += 1
        }
    }

    fun hasReachedTarget(): Boolean = earlyTargetReached

    fun selectCandidate(): LiveTrackingCandidateSelection<T>? = selectLiveTrackingCandidate(candidates)

    fun eligibleCandidates(): List<LiveTrackingCandidate<T>> = candidates.toList()

    fun enterSuspectConfirmation() {
        reason = LiveTrackingAcquisitionReason.SUSPECT_CONFIRMATION
    }

    fun complete() {
        completed = true
    }
}
