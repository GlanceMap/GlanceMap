package com.glancemap.glancemapwearos.presentation.features.navigate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsInstanceLifecycleTest {
    @Test
    fun retireMarksTheInstanceImmediatelyAndSchedulesCleanupOnlyOnce() {
        val lifecycle = TtsInstanceLifecycle()
        var cleanupCount = 0

        assertTrue(lifecycle.retire { cleanupCount += 1 })

        assertTrue(lifecycle.isRetired())
        assertEquals(1, cleanupCount)
        assertFalse(lifecycle.retire { cleanupCount += 1 })
        assertEquals(1, cleanupCount)
    }

    @Test
    fun cleanupCanStopBeforeShutdown() {
        val lifecycle = TtsInstanceLifecycle()
        val operations = mutableListOf<String>()

        lifecycle.retire {
            operations += "stop"
            operations += "shutdown"
        }

        assertEquals(listOf("stop", "shutdown"), operations)
    }

    @Test
    fun cleanupStillShutsDownWhenStopFails() {
        val lifecycle = TtsInstanceLifecycle()
        val operations = mutableListOf<String>()

        val result =
            runCatching {
                lifecycle.retire {
                    try {
                        operations += "stop"
                        error("stop failed")
                    } finally {
                        operations += "shutdown"
                    }
                }
            }

        assertTrue(result.isFailure)
        assertEquals(listOf("stop", "shutdown"), operations)
    }

    @Test
    fun retiredInstanceRejectsLaterCommands() {
        val lifecycle = TtsInstanceLifecycle()
        var commandCount = 0

        lifecycle.retire { }
        val result = lifecycle.runIfActive { commandCount += 1 }

        assertNull(result)
        assertEquals(0, commandCount)
    }

    @Test
    fun retiringAnOldInstanceDoesNotAffectItsReplacement() {
        val oldInstance = TtsInstanceLifecycle()
        val replacement = TtsInstanceLifecycle()
        val shutdowns = mutableListOf<String>()

        oldInstance.retire { shutdowns += "old" }
        replacement.runIfActive { shutdowns += "replacement-command" }

        assertEquals(listOf("old", "replacement-command"), shutdowns)
        assertFalse(replacement.isRetired())
    }

    @Test
    fun initializationAfterRetirementCannotReactivateTheInstance() {
        val lifecycle = TtsInstanceLifecycle()
        var ready = false

        lifecycle.retire { }
        lifecycle.runIfActive { ready = true }

        assertFalse(ready)
    }
}
