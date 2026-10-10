package com.glancemap.glancemapcompanionapp.livetracking

import android.content.Context
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** An admitted cycle keeps one durable identity while bounded refinement changes its candidate. */
internal class LiveTrackingRetainedCycles {
    private val ids = ConcurrentHashMap<Long, String>()
    private val active = ConcurrentHashMap.newKeySet<String>()

    fun claim(cycleId: Long): String = ids.getOrPut(cycleId) { UUID.randomUUID().toString() }.also(active::add)

    fun pointId(cycleId: Long): String? = ids[cycleId]

    fun canReplay(point: ArkluzLocationUpdate): Boolean = point.pointId?.let { it !in active } ?: true

    fun release(cycleId: Long) {
        ids.remove(cycleId)?.let(active::remove)
    }
}

internal suspend fun recoverLiveTrackingPendingUploads(
    canAttemptUpload: () -> Boolean,
    flushControls: suspend () -> Unit,
    replayPositions: suspend () -> Unit,
) {
    if (!canAttemptUpload()) throw LiveTrackingOfflineException()
    flushControls()
    if (!canAttemptUpload()) throw LiveTrackingOfflineException()
    replayPositions()
}

internal fun ArkluzLocationUpdate.isSameGpsPoint(other: ArkluzLocationUpdate): Boolean =
    trackingUrl == other.trackingUrl &&
        group == other.group &&
        participantPassword == other.participantPassword &&
        userName == other.userName &&
        epochMilliseconds == other.epochMilliseconds &&
        latitude == other.latitude &&
        longitude == other.longitude

internal fun acknowledgedLiveTrackingPositions(
    positions: List<ArkluzLocationUpdate>,
    acknowledged: ArkluzLocationUpdate,
): List<ArkluzLocationUpdate> = positions.filterNot { it.isSameGpsPoint(acknowledged) }

/** A replay pass owns one request; failures leave the stored point untouched. */
internal suspend fun replayOneLiveTrackingPosition(
    positions: List<ArkluzLocationUpdate>,
    canReplay: (ArkluzLocationUpdate) -> Boolean,
    send: suspend (ArkluzLocationUpdate) -> Unit,
    acknowledge: (ArkluzLocationUpdate) -> Unit,
): Boolean {
    val point = positions.firstOrNull(canReplay) ?: return false
    send(point)
    acknowledge(point)
    return true
}

internal enum class LiveTrackingRecoveryMode {
    ACTIVE,
    PAUSED,
    STOPPING,
}

internal fun liveTrackingRecoveryMode(
    isPaused: Boolean,
    isStopping: Boolean,
    pausePending: Boolean = false,
): LiveTrackingRecoveryMode =
    when {
        isStopping -> LiveTrackingRecoveryMode.STOPPING
        isPaused || pausePending -> LiveTrackingRecoveryMode.PAUSED
        else -> LiveTrackingRecoveryMode.ACTIVE
    }

internal fun <T> selectLiveTrackingRetryCandidate(
    attempted: LiveTrackingCandidateSelection<T>?,
    candidates: List<LiveTrackingCandidate<T>>,
    nowElapsedRealtimeNanos: Long,
    nowEpochMilliseconds: Long,
): LiveTrackingCandidateSelection<T>? =
    attempted ?: selectFreshLiveTrackingCandidate(
        candidates,
        nowElapsedRealtimeNanos,
        nowEpochMilliseconds,
    )

internal fun Throwable.toLiveTrackingFailureCode(): String =
    when (this) {
        is ArkluzHttpException -> "http_$code"
        is ArkluzUnconfirmedResponseException -> "unconfirmed_response"
        is ArkluzRetryDeferredException -> "retry_deferred"
        is LiveTrackingOfflineException -> "offline"
        is LiveTrackingQueueException -> "storage"
        else -> javaClass.simpleName
    }

internal fun liveTrackingRecoverySummary(context: Context): List<String> {
    val session = runCatching { LiveTrackingActiveSessionStore.load(context) }.getOrNull()
    val mode = session?.let { liveTrackingRecoveryMode(it.isPaused, it.isStopping, it.pausePending).name } ?: "none"
    return listOf(
        "Mode: $mode",
        "PauseIntentPending: ${session?.pausePending ?: false}",
        "ResumeIntentPending: ${session?.resumePending ?: false}",
        "SavedAtMs: ${session?.savedAtEpochMilliseconds ?: "na"}",
        "LastPointId: ${session?.lastPosition?.pointId ?: "na"}",
        "LastFixTsMs: ${session?.lastPosition?.epochMilliseconds ?: "na"}",
        "PendingPositions: ${runCatching { LiveTrackingPositionQueue.load(context).size }.getOrNull() ?: "unreadable"}",
        "PendingControls: ${runCatching { LiveTrackingControlQueue.load(context).size }.getOrNull() ?: "unreadable"}",
    )
}
