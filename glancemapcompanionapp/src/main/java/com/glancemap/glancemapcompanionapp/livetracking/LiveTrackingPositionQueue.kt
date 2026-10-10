package com.glancemap.glancemapcompanionapp.livetracking

import android.content.Context

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

    private fun store(context: Context): LiveTrackingQueuePersistence =
        LiveTrackingQueuePersistence.fromPreferences(
            context,
            PREFS_NAME,
            "position_queue",
        )
}
