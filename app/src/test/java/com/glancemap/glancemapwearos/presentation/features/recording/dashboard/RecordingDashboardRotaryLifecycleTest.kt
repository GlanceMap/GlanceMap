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

    @Test
    fun `rotary page transition works with a dynamic page count and clears the partial gesture`() {
        var accumulator = 52f
        var nextPageRequests = 0

        val consumed =
            handleRecordingRotaryPageEvent(
                delta = 8f,
                pageCount = 5,
                accumulator = accumulator,
                onAccumulatorChange = { accumulator = it },
                onPreviousPage = {},
                onNextPage = { nextPageRequests += 1 },
            )

        assertEquals(true, consumed)
        assertEquals(1, nextPageRequests)
        assertEquals(0f, accumulator)
    }
}
