package com.glancemap.glancemapwearos.core.service.diagnostics

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DebugTelemetryLazyTest {
    @Before
    fun setUp() {
        DebugTelemetry.setTransitionMarkersEnabledForTests(false)
        DebugTelemetry.setEnabledFromLocationService(false)
        DebugTelemetry.clear()
    }

    @After
    fun tearDown() {
        DebugTelemetry.setEnabledFromLocationService(false)
        DebugTelemetry.clear()
        DebugTelemetry.setTransitionMarkersEnabledForTests(true)
    }

    @Test
    fun lazyMessageIsNotBuiltWhenNoConsumerIsActive() {
        var evaluated = false

        DebugTelemetry.log("Test") {
            evaluated = true
            "discarded"
        }

        assertFalse(evaluated)
        assertTrue(DebugTelemetry.snapshot().isEmpty())
    }

    @Test
    fun lazyMessageIsBuiltAndFormattedForFullCapture() {
        DebugTelemetry.setEnabledFromLocationService(true)
        var evaluated = false

        // The JVM test artifact does not provide android.util.Log.d; buffering happens before
        // that logcat call, so tolerate the platform stub exception here.
        runCatching {
            DebugTelemetry.log("Test") {
                evaluated = true
                "event=full message"
            }
        }

        assertTrue(evaluated)
        assertTrue(DebugTelemetry.snapshot().single().endsWith(" [Test] event=full message"))
    }
}
