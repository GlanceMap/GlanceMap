package com.glancemap.glancemapwearos.presentation.features.navigate

import com.glancemap.glancemapwearos.data.repository.MapFocusControl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigateMapFocusStateTest {
    @Test
    fun `entering focus closes shortcuts and replaces routine chrome with the exit control`() {
        val state = NavigateMapFocusState()
        var shortcutsExpanded = true
        assertTrue(mapFocusChromePolicy(state.mapFocusMode, false).showRoutineChrome)
        state.enter { shortcutsExpanded = false }
        assertFalse(shortcutsExpanded)
        assertTrue(state.mapFocusMode)
        assertFalse(mapFocusChromePolicy(state.mapFocusMode, false).showRoutineChrome)
        assertTrue(mapFocusChromePolicy(state.mapFocusMode, false).showExitControl)
    }

    @Test
    fun `exiting restores normal chrome and clears the hint`() {
        val state = NavigateMapFocusState()
        state.enter {}
        state.exit()
        assertFalse(state.mapFocusMode)
        assertFalse(state.hintVisible)
        assertTrue(mapFocusChromePolicy(state.mapFocusMode, false).showRoutineChrome)
        assertFalse(mapFocusChromePolicy(state.mapFocusMode, false).showExitControl)
    }

    @Test
    fun `hint is shown only once per Navigate state instance`() {
        val state = NavigateMapFocusState()
        state.enter {}
        assertTrue(state.hintVisible)
        state.dismissHint()
        assertTrue(state.mapFocusMode)
        assertFalse(state.hintVisible)
        state.exit()
        state.enter {}
        assertFalse(state.hintVisible)
        assertTrue(NavigateMapFocusState().apply { enter {} }.hintVisible)
    }

    @Test
    fun `dashboard takes visual precedence over focus exit`() {
        assertFalse(mapFocusChromePolicy(mapFocusMode = true, dashboardFullscreen = true).showExitControl)
        assertTrue(mapFocusChromePolicy(mapFocusMode = true, dashboardFullscreen = false).showExitControl)
    }

    @Test
    fun `all optional controls are hidden by default while focused`() {
        val policy = mapFocusChromePolicy(mapFocusMode = true, dashboardFullscreen = false)
        MapFocusControl.entries.forEach { assertFalse(policy.shows(it)) }
        assertTrue(policy.showExitControl)
    }

    @Test
    fun `each fullscreen control can be enabled independently`() {
        MapFocusControl.entries.forEach { selected ->
            val policy = mapFocusChromePolicy(true, false, setOf(selected))
            MapFocusControl.entries.forEach { control ->
                if (control == selected) assertTrue(policy.shows(control)) else assertFalse(policy.shows(control))
            }
            assertTrue(policy.showExitControl)
        }
    }

    @Test
    fun `fullscreen preferences do not hide controls in normal mode`() {
        val policy = mapFocusChromePolicy(mapFocusMode = false, dashboardFullscreen = false)
        MapFocusControl.entries.forEach { assertTrue(policy.shows(it)) }
        assertFalse(policy.showExitControl)
    }

    @Test
    fun `enabling controls never removes the focus exit or dashboard precedence`() {
        val all = MapFocusControl.entries.toSet()
        assertTrue(mapFocusChromePolicy(true, false, all).showExitControl)
        assertFalse(mapFocusChromePolicy(true, true, all).showExitControl)
    }
}
