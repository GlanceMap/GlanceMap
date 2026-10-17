package com.glancemap.glancemapcompanionapp.livetracking

import kotlinx.coroutines.CancellationException

// At the existing 500-point queue cap, this limits full JSON checkpoints to 20 per full drain.
internal const val LIVE_TRACKING_POSITION_REPLAY_BATCH_SIZE = 25

internal enum class LiveTrackingPositionReplayOutcome {
    SUCCESS,
    FAILED,
    NETWORK_RETRY,
}

internal data class LiveTrackingPositionReplayProgress(
    val update: ArkluzLocationUpdate,
    val queueSizeBefore: Int,
    val queueSizeAfter: Int,
    val outcome: LiveTrackingPositionReplayOutcome,
)

/** Replays one bounded batch and checkpoints acknowledged progress once. */
internal suspend fun replayLiveTrackingPositionBatch(
    updates: List<ArkluzLocationUpdate>,
    send: suspend (ArkluzLocationUpdate) -> Unit,
    isRetryable: (Throwable) -> Boolean,
    persistRemaining: (List<ArkluzLocationUpdate>) -> Unit,
    onProgress: (LiveTrackingPositionReplayProgress) -> Unit = {},
): Int {
    val batchSize = minOf(updates.size, LIVE_TRACKING_POSITION_REPLAY_BATCH_SIZE)
    if (batchSize == 0) return 0

    var consumedCount = 0
    var replayedCount = 0
    try {
        while (consumedCount < batchSize) {
            val update = updates[consumedCount]
            val queueSizeBefore = updates.size - consumedCount
            val error =
                try {
                    send(update)
                    null
                } catch (error: Exception) {
                    error
                }
            if (error == null) {
                consumedCount += 1
                replayedCount += 1
                onProgress(
                    LiveTrackingPositionReplayProgress(
                        update = update,
                        queueSizeBefore = queueSizeBefore,
                        queueSizeAfter = queueSizeBefore - 1,
                        outcome = LiveTrackingPositionReplayOutcome.SUCCESS,
                    ),
                )
            } else {
                val outcome =
                    if (error is CancellationException || isRetryable(error)) {
                        LiveTrackingPositionReplayOutcome.NETWORK_RETRY
                    } else {
                        LiveTrackingPositionReplayOutcome.FAILED
                    }
                if (error !is CancellationException) {
                    onProgress(
                        LiveTrackingPositionReplayProgress(
                            update = update,
                            queueSizeBefore = queueSizeBefore,
                            queueSizeAfter = queueSizeBefore,
                            outcome = outcome,
                        ),
                    )
                }
                // Keep the failed point and all later points queued in original order.
                throw error
            }
        }
        return replayedCount
    } finally {
        if (consumedCount > 0) {
            persistRemaining(updates.drop(consumedCount))
        }
    }
}
