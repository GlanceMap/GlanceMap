package com.glancemap.glancemapwearos.presentation.features.recording.health

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingHealthSessionControllerTest {
    @Test
    fun `existing exercises are reported without starting or replacing them`() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                for (owner in listOf(RecordingHealthOwner.OTHER_APP, RecordingHealthOwner.THIS_APP)) {
                    val client = FakeClient().apply { currentOwner = owner }
                    val controller = controller(client)

                    val status = controller.start(request)

                    assertEquals(
                        if (owner ==
                            RecordingHealthOwner.OTHER_APP
                        ) {
                            RecordingHealthPhase.EXTERNAL_CONFLICT
                        } else {
                            RecordingHealthPhase.OWNED_CONFLICT
                        },
                        status.phase,
                    )
                    assertEquals(0, client.startCount)
                    assertNull(client.listener)
                }
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `start future alone does not advertise active sensor ownership`() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                val client = FakeClient().apply { activeOnStart = false }
                val states = mutableListOf<RecordingHealthStatus>()
                val controller = controller(client, onStatus = states::add)

                assertEquals(RecordingHealthPhase.STARTING, controller.start(request).phase)
                assertTrue(states.last().activeKinds.isEmpty())
                client.listener!!.onUpdate(RecordingHealthPhase.ACTIVE, emptyList(), null)
                assertEquals(setOf(RecordingHealthMetric.HEART_RATE), states.last().activeKinds)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `failed start without an owned exercise releases listener and permits retry`() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                val client = FakeClient().apply { failStart = true }
                val controller = controller(client)

                assertEquals(RecordingHealthPhase.ERROR, controller.start(request).phase)
                assertNull(client.listener)
                client.failStart = false
                assertEquals(RecordingHealthPhase.ACTIVE, controller.start(request).phase)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `cached callback before new start cannot inject another session measurements`() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                val client = FakeClient().apply { updateOnRegister = true }
                val received = mutableListOf<RecordingHealthSample>()
                val controller = controller(client, onSamples = { received += it })

                controller.start(request)

                assertTrue(received.isEmpty())
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `cancelled end waits for final callback without issuing another completed end request`() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                val client = FakeClient()
                val controller = controller(client)
                controller.start(request)
                val ending = launch { controller.end() }
                runCurrent()

                ending.cancel()
                runCurrent()
                assertEquals(1, client.endCount)
                assertFalse(ending.isCompleted)
                client.listener!!.onUpdate(RecordingHealthPhase.ENDED, listOf(sample), "USER_END")
                runCurrent()

                assertTrue(ending.isCompleted)
                assertNull(client.listener)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `end delivers final measurements before clearing listener and returning`() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                val events = mutableListOf<String>()
                val client = FakeClient().apply { onClear = { events += "clear" } }
                val controller =
                    controller(client, onSamples = { events += "samples" }, onStatus = { events += it.phase.name })
                controller.start(request)
                events.clear()

                val ending =
                    launch {
                        controller.end()
                        events += "return"
                    }
                runCurrent()
                assertFalse(ending.isCompleted)
                assertNotNull(client.listener)
                client.listener!!.onUpdate(RecordingHealthPhase.ENDED, listOf(sample), "USER_END")
                runCurrent()

                assertEquals(listOf("ENDING", "samples", "ENDED", "clear", "return"), events)
                assertNull(client.listener)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `cancelled start ends the exercise and drains its final batch`() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                val client = FakeClient().apply { suspendStart = true }
                val received = mutableListOf<RecordingHealthSample>()
                val controller = controller(client, onSamples = { received += it })
                val starting = launch { controller.start(request) }
                runCurrent()

                starting.cancel()
                runCurrent()
                assertEquals(1, client.endCount)
                assertNotNull(client.listener)
                client.listener!!.onUpdate(RecordingHealthPhase.ENDED, listOf(sample), "USER_END")
                runCurrent()

                assertTrue(starting.isCompleted)
                assertEquals(listOf(sample), received)
                assertNull(client.listener)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `old callback cannot publish into a new exercise generation`() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                val client = FakeClient()
                val received = mutableListOf<RecordingHealthSample>()
                val controller = controller(client, onSamples = { received += it })
                controller.start(request)
                val old = client.listener!!
                client.listener!!.onUpdate(RecordingHealthPhase.ENDED, emptyList(), "USER_END")
                controller.end()
                controller.start(request)

                old.onUpdate(RecordingHealthPhase.ACTIVE, listOf(sample), null)

                assertTrue(received.isEmpty())
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `bounded end reports timeout and retains callback for delayed final data`() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                val client = FakeClient()
                val states = mutableListOf<RecordingHealthStatus>()
                val received = mutableListOf<RecordingHealthSample>()
                val controller = controller(client, onSamples = { received += it }, onStatus = states::add)
                controller.start(request)

                assertEquals(false, controller.end())

                assertEquals(RecordingHealthPhase.ERROR, states.last().phase)
                assertNotNull(client.listener)
                client.listener!!.onUpdate(RecordingHealthPhase.ENDED, listOf(sample), "USER_END")
                assertEquals(listOf(sample), received)
                assertEquals(RecordingHealthPhase.ENDED, states.last().phase)
                assertEquals(true, controller.end())
                assertNull(client.listener)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `late active and paused updates cannot reclaim ownership after incomplete end`() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                val client = FakeClient()
                val states = mutableListOf<RecordingHealthStatus>()
                val received = mutableListOf<RecordingHealthSample>()
                val controller = controller(client, onSamples = { received += it }, onStatus = states::add)
                controller.start(request)
                controller.end()
                val stateCount = states.size

                client.listener!!.onUpdate(RecordingHealthPhase.ACTIVE, listOf(sample), null)
                client.listener!!.onUpdate(RecordingHealthPhase.PAUSED, emptyList(), null)

                assertEquals(listOf(sample), received)
                assertEquals(stateCount, states.size)
                assertTrue(states.last().activeKinds.isEmpty())
                client.listener!!.onUpdate(RecordingHealthPhase.ENDED, emptyList(), "USER_END")
                assertEquals(true, controller.end())
                assertNull(client.listener)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `listener cleanup failure reports false and retries the same terminal session`() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                val client = FakeClient().apply { failClear = true }
                val controller = controller(client)
                controller.start(request)
                client.listener!!.onUpdate(RecordingHealthPhase.ENDED, listOf(sample), "USER_END")

                assertEquals(false, controller.end())
                assertNotNull(client.listener)
                client.failClear = false
                assertEquals(true, controller.end())

                assertNull(client.listener)
            } finally {
                Dispatchers.resetMain()
            }
        }

    private fun controller(
        client: FakeClient,
        onSamples: (List<RecordingHealthSample>) -> Unit = {},
        onStatus: (RecordingHealthStatus) -> Unit = {},
    ) = RecordingHealthSessionController(client, onStatus, onSamples, operationTimeoutMillis = 100L)

    @Test
    fun `failed pause retires uncertain exercise before fallback`() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                val client = FakeClient().apply { failPause = true }
                val states = mutableListOf<RecordingHealthStatus>()
                val controller = controller(client, onStatus = states::add)
                controller.start(request)

                controller.pause()

                assertEquals(1, client.endCount)
                val count = states.size
                client.listener!!.onUpdate(RecordingHealthPhase.ACTIVE, listOf(sample), null)
                assertEquals(count, states.size)
                assertTrue(states.last().activeKinds.isEmpty())
            } finally {
                Dispatchers.resetMain()
            }
        }

    private class FakeClient : RecordingHealthClient {
        var currentOwner = RecordingHealthOwner.NONE
        var listener: RecordingHealthListener? = null
        var startCount = 0
        var endCount = 0
        var onClear: () -> Unit = {}
        var activeOnStart = true
        var suspendStart = false
        var failStart = false
        var updateOnRegister = false
        var failClear = false
        var failPause = false

        override suspend fun capabilities() =
            RecordingHealthDeviceCapabilities(
                listOf(RecordingHealthExerciseCapabilities(RecordingHealthExercise.HIKING, true, false, false, false)),
            )

        override suspend fun owner() = currentOwner

        override suspend fun registerListener(listener: RecordingHealthListener) {
            this.listener = listener
            if (updateOnRegister) listener.onUpdate(RecordingHealthPhase.ACTIVE, listOf(sample), null)
        }

        override suspend fun clearListener() {
            check(!failClear) { "Test listener cleanup failure" }
            onClear()
            listener = null
        }

        override suspend fun start(configuration: RecordingHealthConfiguration) {
            startCount += 1
            if (failStart) throw SecurityException("Missing test permission")
            currentOwner = RecordingHealthOwner.THIS_APP
            if (activeOnStart) listener!!.onUpdate(RecordingHealthPhase.ACTIVE, emptyList(), null)
            if (suspendStart) awaitCancellation()
        }

        override suspend fun pause() {
            check(!failPause) { "Test pause failure" }
        }

        override suspend fun resume() = Unit

        override suspend fun flush() = Unit

        override suspend fun end() {
            endCount += 1
            currentOwner = RecordingHealthOwner.NONE
        }
    }

    private companion object {
        val request = RecordingHealthRequest(RecordingHealthActivity.HIKE, true, false, false)
        val sample = RecordingHealthSample(RecordingHealthMetric.HEART_RATE, 100.0, 1_000L, 1_001_000L)
    }
}
