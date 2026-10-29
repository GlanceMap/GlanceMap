package com.glancemap.glancemapcompanionapp.livetracking

import android.content.Context

internal const val LIVE_TRACKING_POSITION_QUEUE_MAX_SIZE = 500

internal data class LiveTrackingPositionQueueAppendResult(
    val updates: List<ArkluzLocationUpdate>,
    val droppedOldestCount: Int,
) {
    val queueSize: Int get() = updates.size
}

internal fun appendLiveTrackingPosition(
    queuedUpdates: List<ArkluzLocationUpdate>,
    update: ArkluzLocationUpdate,
): LiveTrackingPositionQueueAppendResult {
    val combined = queuedUpdates + update.asStoredGpsPoint()
    val droppedOldestCount = (combined.size - LIVE_TRACKING_POSITION_QUEUE_MAX_SIZE).coerceAtLeast(0)
    return LiveTrackingPositionQueueAppendResult(
        updates = combined.takeLast(LIVE_TRACKING_POSITION_QUEUE_MAX_SIZE),
        droppedOldestCount = droppedOldestCount,
    )
}

internal object LiveTrackingPositionQueue {
    private const val PREFS_NAME = "arkluz_live_tracking_position_queue"
    private val lock = Any()

    fun enqueue(
        context: Context,
        update: ArkluzLocationUpdate,
    ): Int = synchronized(lock) { store(context).enqueue(update.asStoredGpsPoint()) }

    fun load(context: Context): List<ArkluzLocationUpdate> = synchronized(lock) { store(context).load() }

    fun acknowledge(
        context: Context,
        update: ArkluzLocationUpdate,
    ): Int = synchronized(lock) { store(context).acknowledge(update) }

    fun markTransmissionFailed(
        context: Context,
        update: ArkluzLocationUpdate,
    ) = synchronized(lock) { store(context).markTransmissionFailed(update) }

    private fun store(context: Context): LiveTrackingQueuePersistence =
        LiveTrackingQueuePersistence.fromPreferences(
            context,
            PREFS_NAME,
            "position_queue",
        )
}
