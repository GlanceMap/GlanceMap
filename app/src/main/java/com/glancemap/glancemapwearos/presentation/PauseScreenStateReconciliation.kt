package com.glancemap.glancemapwearos.presentation

internal class PauseScreenStateReconciliation(
    private val scheduleDelayed: (delayMs: Long, action: () -> Unit) -> Unit,
    private val reconcile: () -> Unit,
) {
    private var generation = 0L
    private var pendingGeneration: Long? = null

    fun schedule() {
        val scheduledGeneration = ++generation
        pendingGeneration = scheduledGeneration
        scheduleDelayed(PAUSE_SCREEN_STATE_RECONCILIATION_DELAY_MS) {
            if (pendingGeneration == scheduledGeneration) {
                pendingGeneration = null
                reconcile()
            }
        }
    }

    fun invalidate() {
        generation += 1L
        pendingGeneration = null
    }
}

internal const val PAUSE_SCREEN_STATE_RECONCILIATION_DELAY_MS = 500L
