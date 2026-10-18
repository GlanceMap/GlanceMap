package com.glancemap.glancemapcompanionapp.livetracking

import android.content.Context

internal object LiveTrackingControlQueue {
    private const val PREFS_NAME = "arkluz_live_tracking_control_queue"
    private val lock = Any()

    fun enqueue(
        context: Context,
        update: ArkluzLocationUpdate,
    ): Int = synchronized(lock) { store(context).enqueue(update) }

    fun load(context: Context): List<ArkluzLocationUpdate> = synchronized(lock) { store(context).load() }

    fun removeFirst(context: Context) = synchronized(lock) { store(context).removeFirst() }

    fun clear(context: Context) = synchronized(lock) { store(context).clear() }

    private fun store(context: Context): LiveTrackingQueuePersistence =
        LiveTrackingQueuePersistence.fromPreferences(
            context,
            PREFS_NAME,
            "control_queue",
        )
}
