package com.glancemap.trailcore.recording

/** Portable lifecycle shared by recording adapters; platform work stays outside TrailCore. */
enum class RecordingSessionPhase {
    IDLE,
    RECORDING,
    PAUSED,
    SAVING,
}

data class RecordingSessionLifecycleState(
    val generation: Long = 0L,
    val phase: RecordingSessionPhase = RecordingSessionPhase.IDLE,
    val startedAtMillis: Long? = null,
    val pausedAtMillis: Long? = null,
    val accumulatedPausedMillis: Long = 0L,
    val autoPaused: Boolean = false,
    val saveReturnPhase: RecordingSessionPhase? = null,
    val pendingSegmentStartReason: String? = null,
) {
    val isActive: Boolean get() = phase != RecordingSessionPhase.IDLE
    val acceptsFixes: Boolean get() = phase == RecordingSessionPhase.RECORDING

    fun isPausedForAutoResumeMonitoring(
        autoPauseEnabled: Boolean,
    ): Boolean =
        shouldMonitorRecordingAutoResume(
            active = isActive,
            paused = phase == RecordingSessionPhase.PAUSED,
            autoPaused = autoPaused,
            autoPauseEnabled = autoPauseEnabled,
        )

    fun elapsedMillis(nowMillis: Long): Long {
        val start = startedAtMillis ?: return 0L
        val pausedMillis =
            accumulatedPausedMillis +
                if (phase == RecordingSessionPhase.PAUSED ||
                    (phase == RecordingSessionPhase.SAVING && saveReturnPhase == RecordingSessionPhase.PAUSED)
                ) {
                    (nowMillis - (pausedAtMillis ?: nowMillis)).coerceAtLeast(0L)
                } else {
                    0L
                }
        return (nowMillis - start - pausedMillis).coerceAtLeast(0L)
    }
}

/**
 * Generation-aware session transitions. Adapters use the returned generation to reject delayed
 * location, save, and draft callbacks after discard or a later session has begun.
 */
class RecordingSessionLifecycle(
    initial: RecordingSessionLifecycleState = RecordingSessionLifecycleState(),
) {
    var state: RecordingSessionLifecycleState = initial
        private set

    fun start(startedAtMillis: Long): RecordingSessionLifecycleState {
        if (state.isActive) return state
        state =
            RecordingSessionLifecycleState(
                generation = state.generation + 1L,
                phase = RecordingSessionPhase.RECORDING,
                startedAtMillis = startedAtMillis,
            )
        return state
    }

    fun pause(
        atMillis: Long,
        automatic: Boolean = false,
    ): RecordingSessionLifecycleState {
        if (!state.acceptsFixes) return state
        state =
            state.copy(
                phase = RecordingSessionPhase.PAUSED,
                pausedAtMillis = atMillis,
                autoPaused = automatic,
            )
        return state
    }

    fun resume(
        atMillis: Long,
        automatic: Boolean = false,
    ): RecordingSessionLifecycleState {
        if (state.phase != RecordingSessionPhase.PAUSED) return state
        val reason =
            if (automatic && state.autoPaused) {
                RecordingSegmentStartReason.AUTO_PAUSE
            } else {
                RecordingSegmentStartReason.MANUAL_PAUSE
            }
        state =
            state.copy(
                phase = RecordingSessionPhase.RECORDING,
                accumulatedPausedMillis =
                    state.accumulatedPausedMillis +
                        (atMillis - (state.pausedAtMillis ?: atMillis)).coerceAtLeast(0L),
                pausedAtMillis = null,
                autoPaused = false,
                pendingSegmentStartReason = reason,
            )
        return state
    }

    fun setPendingSegmentStart(reason: String?): RecordingSessionLifecycleState {
        if (state.isActive) state = state.copy(pendingSegmentStartReason = reason)
        return state
    }

    fun beginSave(): RecordingSessionLifecycleState {
        if (!state.isActive || state.phase == RecordingSessionPhase.SAVING) return state
        state = state.copy(phase = RecordingSessionPhase.SAVING, saveReturnPhase = state.phase)
        return state
    }

    fun saveFailed(callbackGeneration: Long): RecordingSessionLifecycleState {
        if (callbackGeneration != state.generation || state.phase != RecordingSessionPhase.SAVING) return state
        state = state.copy(phase = state.saveReturnPhase ?: RecordingSessionPhase.RECORDING, saveReturnPhase = null)
        return state
    }

    fun saveSucceeded(callbackGeneration: Long): RecordingSessionLifecycleState {
        if (callbackGeneration != state.generation || state.phase != RecordingSessionPhase.SAVING) return state
        state = RecordingSessionLifecycleState(generation = state.generation)
        return state
    }

    fun discard(): RecordingSessionLifecycleState {
        state = RecordingSessionLifecycleState(generation = state.generation + 1L)
        return state
    }

    fun recover(
        startedAtMillis: Long,
        pausedAtMillis: Long?,
        accumulatedPausedMillis: Long,
        paused: Boolean,
        autoPaused: Boolean,
    ): RecordingSessionLifecycleState {
        state =
            RecordingSessionLifecycleState(
                generation = state.generation + 1L,
                phase = if (paused) RecordingSessionPhase.PAUSED else RecordingSessionPhase.RECORDING,
                startedAtMillis = startedAtMillis,
                pausedAtMillis = pausedAtMillis,
                accumulatedPausedMillis = accumulatedPausedMillis.coerceAtLeast(0L),
                autoPaused = paused && autoPaused,
                pendingSegmentStartReason =
                    RecordingSegmentStartReason.SESSION_RECOVERY.takeUnless { paused },
            )
        return state
    }

    fun isCurrent(callbackGeneration: Long): Boolean = callbackGeneration == state.generation
}

data class RecordingSessionPointAppend(
    val canonical: RecordingCanonicalAppendResult,
    val point: RecordingPoint,
    val nextLastElapsedRealtimeMillis: Long,
    val nextPendingSegmentStartReason: String?,
)

data class RecordingSessionAppendContext(
    val elapsedRealtimeMillis: Long,
    val previousElapsedRealtimeMillis: Long?,
    val pendingSegmentStartReason: String?,
)

/** Orders one delivered batch by its monotonic fix timestamp while preserving ties. */
fun <T> orderRecordingFixBatch(
    fixes: List<T>,
    elapsedRealtimeMillis: (T) -> Long,
): List<T> = fixes.sortedBy(elapsedRealtimeMillis)

/** Shared ordered append used after each adapter's source-specific fix and movement gates. */
fun appendRecordingSessionPoint(
    existingPoints: List<RecordingPoint>,
    point: RecordingPoint,
    options: RecordingPointSmoothingOptions,
    context: RecordingSessionAppendContext,
): RecordingSessionPointAppend? {
    if (
        context.elapsedRealtimeMillis < 0L ||
        context.previousElapsedRealtimeMillis?.let { context.elapsedRealtimeMillis <= it } == true
    ) {
        return null
    }
    val segmentReason = context.pendingSegmentStartReason?.takeIf { existingPoints.isNotEmpty() }
    val acceptedPoint =
        if (segmentReason == null) {
            point
        } else {
            point.copy(startsNewSegment = true, segmentStartReason = segmentReason)
        }
    return RecordingSessionPointAppend(
        canonical = appendCanonicalRecordingPoint(existingPoints, acceptedPoint, options),
        point = acceptedPoint,
        nextLastElapsedRealtimeMillis = context.elapsedRealtimeMillis,
        nextPendingSegmentStartReason = if (segmentReason == null) context.pendingSegmentStartReason else null,
    )
}
