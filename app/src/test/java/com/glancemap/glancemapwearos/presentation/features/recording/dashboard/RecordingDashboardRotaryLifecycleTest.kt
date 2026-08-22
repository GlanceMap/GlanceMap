package com.glancemap.glancemapwearos.presentation.features.recording.dashboard

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingDashboardRotaryLifecycleTest {
    @Test
    fun `screen pause resets the partial rotary gesture without requesting focus`() {
        assertEquals(
            DashboardRotaryLifecycleAction.RESET,
            dashboardRotaryLifecycleAction(Lifecycle.Event.ON_PAUSE),
        )
    }

    @Test
    fun `screen wake resets the rotary gesture and requests popup focus again`() {
        assertEquals(
            DashboardRotaryLifecycleAction.RESET_AND_REFOCUS,
            dashboardRotaryLifecycleAction(Lifecycle.Event.ON_RESUME),
        )
    }
}
