package com.glancemap.glancemapwearos.presentation.features.download

/** A system-enabled callback is distinct from the application's acquired/isHeld state. */
internal class OamWakeLockDiagnostics(
    private val emit: (String) -> Unit,
) {
    private var generation: Int? = null
    private var identity = 0
    private var enabled: Boolean? = null
    private var emittedTransitions = 0

    val systemEnabled: Boolean?
        @Synchronized get() = enabled

    @Synchronized
    fun begin(
        generation: Int,
        identity: Int,
    ) {
        this.generation = generation
        this.identity = identity
        enabled = null
        emittedTransitions = 0
    }

    @Synchronized
    fun onSystemState(
        generation: Int,
        enabled: Boolean,
    ) {
        if (this.generation != generation || this.enabled == enabled) return
        this.enabled = enabled
        if (emittedTransitions < MAX_TRANSITION_LOGS) {
            emittedTransitions++
            emit(
                "event=wake_lock_system_state wakeLockGeneration=$generation " +
                    "wakeLockIdentity=$identity wakeLockSystemEnabled=$enabled",
            )
        }
    }

    @Synchronized
    fun clear() {
        generation = null
        enabled = null
    }

    private companion object {
        const val MAX_TRANSITION_LOGS = 16
    }
}
