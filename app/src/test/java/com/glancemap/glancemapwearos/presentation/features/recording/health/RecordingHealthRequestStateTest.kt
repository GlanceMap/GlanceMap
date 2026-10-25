package com.glancemap.glancemapwearos.presentation.features.recording.health

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingHealthRequestStateTest {
    @Test
    fun failedSaveEmitsSameRecordingAsANewDesiredSession() =
        runTest {
            val events = mutableListOf<String>()
            val lifecycle = RecordingHealthSessionLifecycle { TestController(events) }
            val state = RecordingHealthRequestState()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                state.requests.collect { lifecycle.update(it) }
            }
            val session =
                RecordingHealthDesiredSession(
                    1_000L,
                    RecordingHealthRequest(RecordingHealthActivity.HIKE, true, false, false),
                    false,
                )
            state.update(session)
            state.beginSave()
            state.update(session)

            assertEquals(listOf("start", "end", "start"), events)
        }

    private class TestController(
        private val events: MutableList<String>,
    ) : RecordingHealthController {
        override suspend fun start(request: RecordingHealthRequest): RecordingHealthStatus {
            events += "start"
            return RecordingHealthStatus(RecordingHealthPhase.ACTIVE)
        }

        override suspend fun pause() = Unit

        override suspend fun resume() = Unit

        override suspend fun flush() = Unit

        override suspend fun end(): Boolean {
            events += "end"
            return true
        }
    }
}
