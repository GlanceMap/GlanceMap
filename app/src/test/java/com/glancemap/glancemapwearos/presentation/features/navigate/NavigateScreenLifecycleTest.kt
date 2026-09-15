package com.glancemap.glancemapwearos.presentation.features.navigate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigateScreenLifecycleTest {
    @Test
    fun initialInteractiveCompositionLeavesMenuGuardInactive() {
        val state = updateNavigateMenuClickGuard(NavigateMenuClickGuardState(), true, nowElapsedMs = 1_000L)

        assertEquals(0L, state.guardUntilElapsedMs)
    }

    @Test
    fun interactiveMenuReentryLeavesMenuGuardInactive() {
        val state = updateNavigateMenuClickGuard(NavigateMenuClickGuardState(), true, nowElapsedMs = 2_000L)

        assertEquals(0L, state.guardUntilElapsedMs)
    }

    @Test
    fun interactiveRecompositionDoesNotArmMenuGuard() {
        var state = NavigateMenuClickGuardState()
        state = updateNavigateMenuClickGuard(state, true, nowElapsedMs = 1_000L)
        state = updateNavigateMenuClickGuard(state, true, nowElapsedMs = 1_500L)

        assertEquals(0L, state.guardUntilElapsedMs)
    }

    @Test
    fun lifecyclePauseResumeWhileInteractiveLeavesMenuGuardInactive() {
        val state = updateNavigateMenuClickGuard(NavigateMenuClickGuardState(), true, nowElapsedMs = 2_000L)

        assertEquals(0L, state.guardUntilElapsedMs)
    }

    @Test
    fun genuineWakeArmsMenuGuardForOneSecond() {
        var state = NavigateMenuClickGuardState()
        state = updateNavigateMenuClickGuard(state, false, nowElapsedMs = 1_000L)
        state = updateNavigateMenuClickGuard(state, true, nowElapsedMs = 2_000L)

        assertEquals(2_000L, state.wakeElapsedMs)
        assertEquals(3_000L, state.guardUntilElapsedMs)
    }

    @Test
    fun menuClickWithinWakeGuardIsRejected() {
        val state = wokenMenuGuardState()

        assertTrue(2_500L < state.guardUntilElapsedMs)
    }

    @Test
    fun menuClickAfterWakeGuardIsAccepted() {
        val state = wokenMenuGuardState()

        assertFalse(3_000L < state.guardUntilElapsedMs)
    }

    @Test
    fun recompositionDuringWakeGuardDoesNotExtendDeadline() {
        var state = wokenMenuGuardState()
        state = updateNavigateMenuClickGuard(state, true, nowElapsedMs = 2_500L)

        assertEquals(3_000L, state.guardUntilElapsedMs)
    }

    @Test
    fun repeatedInteractiveEmissionsDoNotRearmMenuGuard() {
        var state = wokenMenuGuardState()
        state = updateNavigateMenuClickGuard(state, true, nowElapsedMs = 4_000L)

        assertEquals(3_000L, state.guardUntilElapsedMs)
    }

    @Test
    fun secondScreenOffWakeCycleArmsANewMenuGuard() {
        var state = wokenMenuGuardState()
        state = updateNavigateMenuClickGuard(state, false, nowElapsedMs = 5_000L)
        state = updateNavigateMenuClickGuard(state, true, nowElapsedMs = 6_000L)

        assertEquals(6_000L, state.wakeElapsedMs)
        assertEquals(7_000L, state.guardUntilElapsedMs)
    }

    @Test
    fun wakeGuardKeepsItsOwnWakeTimestamp() {
        val state = wokenMenuGuardState()

        assertEquals(2_000L, state.wakeElapsedMs)
        assertEquals(3_000L, state.guardUntilElapsedMs)
    }

    private fun wokenMenuGuardState(): NavigateMenuClickGuardState {
        var state = NavigateMenuClickGuardState()
        state = updateNavigateMenuClickGuard(state, false, nowElapsedMs = 1_000L)
        return updateNavigateMenuClickGuard(state, true, nowElapsedMs = 2_000L)
    }
}
