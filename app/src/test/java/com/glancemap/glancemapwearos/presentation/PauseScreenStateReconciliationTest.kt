package com.glancemap.glancemapwearos.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PauseScreenStateReconciliationTest {
    private lateinit var scheduledActions: MutableList<() -> Unit>
    private var currentInteractive = true
    private var appliedInteractive: Boolean? = null
    private lateinit var reconciliation: PauseScreenStateReconciliation

    @Before
    fun setUp() {
        scheduledActions = mutableListOf()
        reconciliation =
            PauseScreenStateReconciliation(
                scheduleDelayed = { delayMs, action ->
                    assertEquals(PAUSE_SCREEN_STATE_RECONCILIATION_DELAY_MS, delayMs)
                    scheduledActions += action
                },
                reconcile = { appliedInteractive = currentInteractive },
            )
    }

    @Test
    fun pauseSchedulesExactlyOneDelayedReconciliation() {
        reconciliation.schedule()

        assertEquals(1, scheduledActions.size)
    }

    @Test
    fun delayedReconciliationQueriesCurrentStateAndAppliesFalse() {
        currentInteractive = false
        reconciliation.schedule()

        scheduledActions.single().invoke()

        assertFalse(appliedInteractive!!)
    }

    @Test
    fun delayedReconciliationPreservesInteractiveStateWhenPowerStateIsTrue() {
        currentInteractive = true
        reconciliation.schedule()

        scheduledActions.single().invoke()

        assertTrue(appliedInteractive!!)
    }

    @Test
    fun resumeInvalidatesOldCallback() {
        reconciliation.schedule()
        reconciliation.invalidate()

        scheduledActions.single().invoke()

        assertNull(appliedInteractive)
    }

    @Test
    fun screenOffBroadcastInvalidatesOldCallback() = staleCallbackDoesNothing()

    @Test
    fun screenOnBroadcastInvalidatesOldCallback() = staleCallbackDoesNothing()

    @Test
    fun ambientEnterInvalidatesOldCallback() = staleCallbackDoesNothing()

    @Test
    fun ambientExitInvalidatesOldCallback() = staleCallbackDoesNothing()

    @Test
    fun destroyInvalidatesPendingWork() = staleCallbackDoesNothing()

    @Test
    fun staleGenerationCannotOverwriteNewerScreenState() {
        reconciliation.schedule()
        val staleCallback = scheduledActions.single()
        currentInteractive = false
        reconciliation.invalidate()
        reconciliation.schedule()
        val currentCallback = scheduledActions.last()

        staleCallback.invoke()
        assertNull(appliedInteractive)

        currentCallback.invoke()
        assertFalse(appliedInteractive!!)
    }

    @Test
    fun multiplePauseEventsLeaveOnlyTheNewestCallbackEffective() {
        reconciliation.schedule()
        reconciliation.schedule()

        currentInteractive = false
        scheduledActions.forEach { it.invoke() }

        assertEquals(2, scheduledActions.size)
        assertFalse(appliedInteractive!!)
    }

    private fun staleCallbackDoesNothing() {
        reconciliation.schedule()
        reconciliation.invalidate()

        scheduledActions.single().invoke()

        assertNull(appliedInteractive)
    }
}
