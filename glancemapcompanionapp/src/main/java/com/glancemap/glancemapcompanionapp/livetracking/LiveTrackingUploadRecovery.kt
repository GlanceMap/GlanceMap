package com.glancemap.glancemapcompanionapp.livetracking

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
