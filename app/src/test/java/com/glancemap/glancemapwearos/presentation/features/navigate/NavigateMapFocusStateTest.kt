package com.glancemap.glancemapwearos.presentation.features.navigate

import com.glancemap.glancemapwearos.data.repository.MapFocusControl
import org.junit.Assert.assertEquals
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
            assertEquals(selected != MapFocusControl.TOOLS_BUTTON, policy.showExitControl)
            assertEquals(selected == MapFocusControl.TOOLS_BUTTON, policy.showToolsControl)
        }
    }

    @Test
    fun `fullscreen preferences do not hide controls in normal mode`() {
        val policy = mapFocusChromePolicy(mapFocusMode = false, dashboardFullscreen = false)
        MapFocusControl.entries.forEach { assertTrue(policy.shows(it)) }
        assertFalse(policy.showExitControl)
    }

    @Test
    fun `tools replace the standalone exit while dashboards still take precedence`() {
        val all = MapFocusControl.entries.toSet()
        val focused = mapFocusChromePolicy(true, false, all)
        assertTrue(focused.showToolsControl)
        assertFalse(focused.showExitControl)
        val dashboard = mapFocusChromePolicy(true, true, all)
        assertFalse(dashboard.showToolsControl)
        assertFalse(dashboard.showExitControl)
    }

    @Test
    fun `standalone exit stays available when a workflow or missing map hides tools`() {
        val policy = mapFocusChromePolicy(true, false, setOf(MapFocusControl.TOOLS_BUTTON), toolsAvailable = false)
        assertFalse(policy.showToolsControl)
        assertTrue(policy.showExitControl)
        assertFalse(mapFocusChromePolicy(false, false, toolsAvailable = false).showExitControl)
    }

    @Test
    fun `exit highlight repeats only on entry and clears when focus ends`() {
        val state = NavigateMapFocusState()
        assertFalse(state.exitHighlightVisible)
        state.enter {}
        assertTrue(state.exitHighlightVisible)
        state.dismissExitHighlight()
        assertFalse(state.exitHighlightVisible)
        assertTrue(state.mapFocusMode)
        state.exit()
        state.enter {}
        assertTrue(state.exitHighlightVisible)
        assertFalse(state.hintVisible)
        state.exit()
        assertFalse(state.exitHighlightVisible)
    }
}
