package com.glancemap.glancemapwearos.presentation.features.recording.health

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingHealthSessionLifecycleTest {
    @Test
    fun repeatedPresentationUpdatesDoNotRestartExercise() =
        runTest {
            val events = mutableListOf<String>()
            val lifecycle = RecordingHealthSessionLifecycle { FakeController(events) }
            val request = RecordingHealthDesiredSession(1_000L, request(), paused = false)
            lifecycle.update(request)
            lifecycle.update(request)
            lifecycle.update(request.copy(paused = true))
            lifecycle.update(request.copy(paused = true))
            lifecycle.update(request)
            lifecycle.finish()
            lifecycle.finish()

            assertEquals(listOf("start", "pause", "resume", "end"), events)
        }

    @Test
    fun sourceSwitchEndsPreviousExerciseBeforeStartingReplacement() =
        runTest {
            val events = mutableListOf<String>()
            val lifecycle = RecordingHealthSessionLifecycle { FakeController(events) }
            lifecycle.update(RecordingHealthDesiredSession(1_000L, request(), paused = false))
            lifecycle.update(RecordingHealthDesiredSession(1_000L, request().copy(steps = true), paused = true))
            lifecycle.update(null)

            assertEquals(listOf("start", "end", "start", "pause", "end"), events)
        }

    @Test
    fun incompleteCleanupRetainsControllerAndRetriesBeforeReplacement() =
        runTest {
            val events = mutableListOf<String>()
            val first = FakeController(events).apply { endSucceeded = false }
            val replacement = FakeController(events)
            var creations = 0
            val lifecycle = RecordingHealthSessionLifecycle { if (creations++ == 0) first else replacement }
            val original = RecordingHealthDesiredSession(1_000L, request(), paused = false)
            val next = original.copy(request = request().copy(steps = true))
            lifecycle.update(original)

            val incomplete = lifecycle.update(next)

            assertEquals(listOf("start", "end"), events)
            assertEquals(1, creations)
            assertEquals(false, incomplete)
            first.endSucceeded = true
            assertEquals(true, lifecycle.update(next))
            assertEquals(listOf("start", "end", "end", "start"), events)
            assertEquals(2, creations)
        }

    @Test
    fun incompleteFinishRetainsControllerUntilSuccessfulRetry() =
        runTest {
            val events = mutableListOf<String>()
            val first = FakeController(events).apply { endSucceeded = false }
            val lifecycle = RecordingHealthSessionLifecycle { first }
            lifecycle.update(RecordingHealthDesiredSession(1_000L, request(), paused = false))

            assertEquals(false, lifecycle.finish())
            first.endSucceeded = true
            assertEquals(true, lifecycle.finish())

            assertEquals(listOf("start", "end", "end"), events)
        }

    @Test
    fun cleanupRetryDoesNotSkipWhenDesiredSessionReturnsToPreviousValue() =
        runTest {
            val events = mutableListOf<String>()
            val first = FakeController(events).apply { endSucceeded = false }
            var creations = 0
            val lifecycle =
                RecordingHealthSessionLifecycle {
                    creations += 1
                    first
                }
            val original = RecordingHealthDesiredSession(1_000L, request(), paused = false)
            lifecycle.update(original)
            lifecycle.update(original.copy(request = request().copy(steps = true)))
            first.endSucceeded = true

            assertEquals(true, lifecycle.update(original))

            assertEquals(listOf("start", "end", "end", "start"), events)
            assertEquals(2, creations)
            assertTrue(first.endSucceeded)
        }

    private fun request() =
        RecordingHealthRequest(
            activity = RecordingHealthActivity.HIKE,
            heartRate = true,
            steps = false,
            cadence = false,
        )

    private class FakeController(
        private val events: MutableList<String>,
    ) : RecordingHealthController {
        var endSucceeded = true

        override suspend fun start(request: RecordingHealthRequest): RecordingHealthStatus {
            events += "start"
            return RecordingHealthStatus(RecordingHealthPhase.ACTIVE)
        }

        override suspend fun pause() {
            events += "pause"
        }

        override suspend fun resume() {
            events += "resume"
        }

        override suspend fun flush() {
            events += "flush"
        }

        override suspend fun end(): Boolean {
            events += "end"
            return endSucceeded
        }
    }
}
