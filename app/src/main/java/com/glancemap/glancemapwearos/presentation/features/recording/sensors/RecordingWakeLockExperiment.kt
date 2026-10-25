package com.glancemap.glancemapwearos.presentation.features.recording.sensors

internal interface RecordingExperimentLock {
    val isHeld: Boolean

    fun acquire(timeoutMillis: Long)

    fun release()
}

internal class RecordingWakeLockExperiment(
    private val enabled: Boolean,
    private val createLock: () -> RecordingExperimentLock,
    private val diagnostic: (String) -> Unit = {},
) {
    private var lock: RecordingExperimentLock? = null
    private var attempted = false
    val isHeld: Boolean get() = lock?.isHeld == true

    fun update(
        active: Boolean,
        paused: Boolean,
    ) {
        if (!enabled || !active || paused) {
            release()
            attempted = false
        } else if (!attempted) {
            attempted = true
            runCatching {
                lock = createLock()
                lock?.acquire(EXPERIMENT_TIMEOUT_MS)
                diagnostic("event=wake_lock_experiment_acquired held=$isHeld timeoutMs=$EXPERIMENT_TIMEOUT_MS")
            }.onFailure { error ->
                release()
                diagnostic("event=wake_lock_experiment_failed error=${error.javaClass.simpleName}")
            }
        }
    }

    private fun release() {
        val previous = lock ?: return
        runCatching { if (previous.isHeld) previous.release() }
            .onFailure { diagnostic("event=wake_lock_experiment_release_failed error=${it.javaClass.simpleName}") }
        lock = null
        diagnostic("event=wake_lock_experiment_released held=${previous.isHeld}")
    }
}

private const val EXPERIMENT_TIMEOUT_MS = 30L * 60L * 1_000L
