package com.glancemap.glancemapcompanionapp.livetracking

internal class LiveTrackingGeneration(
    val id: Long,
    val admission: LiveTrackingCadenceAdmission,
)

internal interface GenerationOwnedLiveTrackingAcquisition {
    val generation: LiveTrackingGeneration
    val ownership: LiveTrackingAcquisitionOwnership
}

/** Serializes generation transitions and short state mutations, never sensor or network waiting. */
@Suppress("TooManyFunctions")
internal class LiveTrackingOrchestration<T : GenerationOwnedLiveTrackingAcquisition> {
    private var nextGenerationId = 0L
    private var currentGeneration: LiveTrackingGeneration? = null
    private var activeAcquisition: T? = null
    private var rescueCooldownOwner: T? = null
    private var rescueCooldownStartedNanos: Long? = null

    @Synchronized
    fun startGeneration(
        intervalMillis: Long,
        nowElapsedRealtimeMillis: Long,
        sessionActive: () -> Boolean,
    ): LiveTrackingGeneration? {
        if (!sessionActive()) return null
        check(currentGeneration == null && activeAcquisition == null)
        return LiveTrackingGeneration(
            ++nextGenerationId,
            LiveTrackingCadenceAdmission(intervalMillis, nowElapsedRealtimeMillis),
        ).also { currentGeneration = it }
    }

    @Synchronized
    fun invalidateGeneration(
        onCancelled: (T) -> Unit,
        stopPeriodicUpdates: () -> Unit,
    ) {
        currentGeneration = null
        activeAcquisition?.let { acquisition ->
            finishAcquisition(acquisition) { onCancelled(acquisition) }
        }
        stopPeriodicUpdates()
    }

    @Synchronized
    fun isCurrent(generation: LiveTrackingGeneration): Boolean = currentGeneration === generation

    @Synchronized
    fun <R> withGeneration(
        generation: LiveTrackingGeneration,
        action: () -> R,
    ): R? = if (isCurrent(generation)) action() else null

    @Synchronized
    fun reserveAdmission(
        generation: LiveTrackingGeneration,
        ticket: LiveTrackingCadenceTicket,
    ): Boolean = isCurrent(generation) && generation.admission.begin(ticket)

    @Synchronized
    fun releaseRejectedStartup(
        generation: LiveTrackingGeneration,
        ticket: LiveTrackingCadenceTicket,
    ) {
        if (isCurrent(generation)) generation.admission.releaseRejectedStartup(ticket)
    }

    @Synchronized
    fun publishAcquisition(acquisition: T): Boolean {
        if (!isCurrent(acquisition.generation) || activeAcquisition != null || acquisition.ownership.isCompleted()) {
            return false
        }
        activeAcquisition = acquisition
        return true
    }

    @Synchronized
    fun hasActiveAcquisition(): Boolean = activeAcquisition != null

    @Synchronized
    fun isAcquisitionActive(acquisition: T): Boolean =
        activeAcquisition === acquisition &&
            isCurrent(acquisition.generation) &&
            !acquisition.ownership.isCompleted()

    @Synchronized
    fun <R> withAcquisition(
        acquisition: T,
        action: () -> R,
    ): R? = if (isAcquisitionActive(acquisition)) action() else null

    @Synchronized
    fun <R> registerAcquisition(
        acquisition: T,
        sessionActive: () -> Boolean,
        register: () -> R,
    ): R? =
        withAcquisition(acquisition) {
            acquisition.ownership.registerIfOwned(sessionActive, register)
        }

    @Synchronized
    fun acknowledgeRegistration(
        acquisition: T,
        sessionActive: () -> Boolean,
        removeLateRegistration: () -> Unit,
    ): Boolean {
        val retained =
            isAcquisitionActive(acquisition) && acquisition.ownership.acknowledgeRegistration(sessionActive)
        if (!retained) removeLateRegistration()
        return retained
    }

    @Synchronized
    fun finishAcquisition(
        acquisition: T,
        onFinished: () -> Unit,
    ): Boolean {
        if (!acquisition.ownership.finish()) return false
        if (activeAcquisition === acquisition) activeAcquisition = null
        onFinished()
        return true
    }

    @Synchronized
    fun resetRescueCooldown() {
        rescueCooldownOwner = null
        rescueCooldownStartedNanos = null
    }

    @Synchronized
    fun rescueCooldownRemainingMillis(
        nowElapsedRealtimeNanos: Long,
        cooldownMillis: Long,
    ): Long? =
        liveTrackingRescueCooldownRemainingMillis(
            rescueCooldownStartedNanos,
            nowElapsedRealtimeNanos,
            cooldownMillis,
        )

    @Synchronized
    fun beginRescueActivity(
        acquisition: T,
        nowElapsedRealtimeNanos: Long,
        cooldownMillis: Long,
        onStarted: () -> Unit,
    ): Boolean =
        when {
            !isAcquisitionActive(acquisition) -> false
            rescueCooldownOwner === acquisition -> true
            rescueCooldownRemainingMillis(nowElapsedRealtimeNanos, cooldownMillis) != null -> false
            else -> {
                rescueCooldownOwner = acquisition
                rescueCooldownStartedNanos = nowElapsedRealtimeNanos
                onStarted()
                true
            }
        }
}
