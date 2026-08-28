package com.glancemap.glancemapwearos.presentation.features.navigate

/** Coordinates a single TTS instance's commands with its asynchronous teardown. */
internal class TtsInstanceLifecycle {
    private val lock = Any()
    private var retired = false

    fun <T> runIfActive(command: () -> T): T? =
        synchronized(lock) {
            if (retired) null else command()
        }

    fun retire(scheduleCleanup: () -> Unit): Boolean {
        val shouldScheduleCleanup =
            synchronized(lock) {
                if (retired) {
                    false
                } else {
                    retired = true
                    true
                }
            }
        if (shouldScheduleCleanup) scheduleCleanup()
        return shouldScheduleCleanup
    }

    fun isRetired(): Boolean = synchronized(lock) { retired }
}
