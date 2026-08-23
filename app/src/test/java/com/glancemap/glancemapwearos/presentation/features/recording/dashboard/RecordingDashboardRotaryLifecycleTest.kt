package com.glancemap.glancemapwearos.presentation.features.recording.dashboard

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `initial popup can request rotary focus from an already focused window`() {
        assertTrue(
            dashboardRotaryFocusRequestReady(
                currentWindowFocusEpoch = 1,
                requiredWindowFocusEpoch = 0,
            ),
        )
    }

    @Test
    fun `wake waits for the next window focus before requesting rotary focus`() {
        val requiredEpoch =
            dashboardRotaryRequiredWindowFocusEpoch(
                windowHasFocus = false,
                currentWindowFocusEpoch = 3,
            )

        assertEquals(4, requiredEpoch)
        assertFalse(
            dashboardRotaryFocusRequestReady(
                currentWindowFocusEpoch = 3,
                requiredWindowFocusEpoch = requiredEpoch,
            ),
        )
        assertTrue(
            dashboardRotaryFocusRequestReady(
                currentWindowFocusEpoch = 4,
                requiredWindowFocusEpoch = requiredEpoch,
            ),
        )
    }

    @Test
    fun `repeated sleep wake cycles require each new window focus`() {
        val firstWakeEpoch =
            dashboardRotaryRequiredWindowFocusEpoch(
                windowHasFocus = false,
                currentWindowFocusEpoch = 1,
            )
        val secondWakeEpoch =
            dashboardRotaryRequiredWindowFocusEpoch(
                windowHasFocus = false,
                currentWindowFocusEpoch = firstWakeEpoch,
            )

        assertEquals(2, firstWakeEpoch)
        assertEquals(3, secondWakeEpoch)
        assertTrue(dashboardRotaryFocusRequestReady(firstWakeEpoch, firstWakeEpoch))
        assertFalse(dashboardRotaryFocusRequestReady(firstWakeEpoch, secondWakeEpoch))
        assertTrue(dashboardRotaryFocusRequestReady(secondWakeEpoch, secondWakeEpoch))
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
