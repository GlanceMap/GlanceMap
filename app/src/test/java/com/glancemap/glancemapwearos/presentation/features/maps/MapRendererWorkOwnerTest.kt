package com.glancemap.glancemapwearos.presentation.features.maps

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MapRendererWorkOwnerTest {
    @Test
    fun preparationUsesIoAndTransfersOwnershipBackToCaller() =
        runBlocking {
            val owner = MapRendererWorkOwner()
            val callerThread = Thread.currentThread()
            var preparationThread: Thread? = null
            val resource = Resource()
            val result =
                owner.run {
                    val prepared =
                        owner.prepare(release = Resource::close) {
                            preparationThread = Thread.currentThread()
                            resource
                        }
                    assertSame(callerThread, Thread.currentThread())
                    prepared
                }
            assertSame(resource, result)
            assertNotSame(callerThread, preparationThread)
            assertEquals(0, resource.closeCount)
        }

    @Test
    fun cancellationBeforeUiDeliveryClosesPreparedResourceOnce() =
        runTest {
            val caller = StandardTestDispatcher(testScheduler)
            val owner = MapRendererWorkOwner(StandardTestDispatcher(testScheduler))
            val resource = Resource()
            var delivered = false
            lateinit var request: Job
            request =
                launch(caller) {
                    owner.run {
                        owner.prepare(release = Resource::close) {
                            backgroundScope.launch(caller) { request.cancel() }
                            resource
                        }
                        delivered = true
                    }
                }
            request.join()
            assertTrue(request.isCancelled)
            assertEquals(false, delivered)
            assertEquals(1, resource.closeCount)
            assertEquals("next", owner.run { "next" })
        }

    @Test
    fun destructionDuringPreparationClosesResourceWithoutCancellingCollector() =
        runTest {
            val owner = MapRendererWorkOwner(StandardTestDispatcher(testScheduler))
            val resource = Resource()
            val result =
                owner.run {
                    owner.prepare(release = Resource::close) {
                        owner.destroy()
                        resource
                    }
                }
            assertNull(result)
            assertTrue(currentCoroutineContext().isActive)
            assertEquals(1, resource.closeCount)
            assertNull(owner.run { error("destroyed renderer ran again") })
        }

    @Test
    fun rendererMutationsRemainSerializedAcrossIoSuspension() =
        runTest {
            val owner = MapRendererWorkOwner(StandardTestDispatcher(testScheduler))
            val events = mutableListOf<String>()
            val first =
                launch {
                    owner.run {
                        events += "first-start"
                        owner.read { events += "io" }
                        events += "first-end"
                    }
                }
            val second = launch { owner.run { events += "second" } }
            first.join()
            second.join()
            assertEquals(listOf("first-start", "io", "first-end", "second"), events)
        }

    @Test
    fun destructionDiscardsQueuedWork() =
        runTest {
            val owner = MapRendererWorkOwner(StandardTestDispatcher(testScheduler))
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val first =
                launch {
                    owner.run {
                        entered.complete(Unit)
                        resume.await()
                    }
                }
            entered.await()
            var secondRan = false
            val second = launch { owner.run { secondRan = true } }
            owner.destroy()
            resume.complete(Unit)
            first.join()
            second.join()
            assertEquals(false, secondRan)
        }

    @Test
    fun detachedResourcesAreReleasedEvenWhenCallerIsCancelled() =
        runTest {
            val owner = MapRendererWorkOwner(StandardTestDispatcher(testScheduler))
            val resource = Resource()
            val request =
                launch {
                    currentCoroutineContext()[Job]?.cancel()
                    owner.release(resource::close)
                }
            request.join()
            assertEquals(1, resource.closeCount)
        }

    @Test
    fun cancellationWhileQueuedNeverRunsTheResourceMutation() =
        runTest {
            val owner = MapRendererWorkOwner(StandardTestDispatcher(testScheduler))
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val first =
                launch {
                    owner.run {
                        entered.complete(Unit)
                        resume.await()
                    }
                }
            entered.await()
            var mutationRan = false
            val queued = launch { owner.run { mutationRan = true } }
            testScheduler.runCurrent()
            queued.cancelAndJoin()
            resume.complete(Unit)
            first.join()
            assertEquals(false, mutationRan)
            assertEquals("next", owner.run { "next" })
        }

    private class Resource {
        var closeCount = 0

        fun close() {
            closeCount += 1
        }
    }
}
