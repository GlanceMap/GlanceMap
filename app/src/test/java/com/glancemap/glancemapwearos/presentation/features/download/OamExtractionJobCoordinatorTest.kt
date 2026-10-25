package com.glancemap.glancemapwearos.presentation.features.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OamExtractionJobCoordinatorTest {
    @Test
    fun `work runs only after matching system start and finishes once`() =
        runTest {
            val coordinator = OamExtractionJobCoordinator()
            var token = ""
            var runs = 0
            var finished = 0
            var canceled = 0
            val result =
                async {
                    coordinator.execute({
                        token = it
                        true
                    }, { canceled++ }) {
                        runs++
                        "Area.map"
                    }
                }
            runCurrent()
            assertEquals(0, runs)
            assertFalse(coordinator.start("orphan", this) { finished++ })
            assertTrue(coordinator.start(token, this) { finished++ })
            assertFalse(coordinator.start(token, this) { finished++ })
            assertEquals("Area.map", result.await())
            assertEquals(1, runs)
            assertEquals(1, finished)
            assertEquals(1, canceled)
        }

    @Test
    fun `quota rejection does not extract and releases pending slot`() =
        runTest {
            val coordinator = OamExtractionJobCoordinator()
            var ran = false
            var canceled = false
            val result =
                runCatching {
                    coordinator.execute({ false }, { canceled = true }) {
                        ran = true
                        "map"
                    }
                }
            assertTrue(result.exceptionOrNull() is OamExtractionJobUnavailableException)
            assertFalse(ran)
            assertTrue(canceled)
            assertFalse(coordinator.start("old process token", this) {})
        }

    @Test
    fun `accepted job that never starts times out without extracting`() =
        runTest {
            val coordinator = OamExtractionJobCoordinator()
            var token = ""
            var ran = false
            var canceled = false
            val result =
                runCatching {
                    coordinator.execute({
                        token = it
                        true
                    }, { canceled = true }) {
                        ran = true
                        "map"
                    }
                }
            assertTrue(result.exceptionOrNull() is OamExtractionJobUnavailableException)
            assertFalse(ran)
            assertTrue(canceled)
            assertFalse(coordinator.start(token, this) {})
        }

    @Test
    fun `pause before start cancels scheduled job and stale callback cannot start work`() =
        runTest {
            val coordinator = OamExtractionJobCoordinator()
            var token = ""
            var canceled = false
            var ran = false
            val owner =
                async {
                    coordinator.execute({
                        token = it
                        true
                    }, { canceled = true }) {
                        ran = true
                        "map"
                    }
                }
            runCurrent()
            owner.cancelAndJoin()
            assertTrue(canceled)
            assertFalse(coordinator.start(token, this) {})
            assertFalse(ran)
        }

    @Test
    fun `pause waits for real worker cleanup before returning or admitting another extraction`() =
        runTest {
            val coordinator = OamExtractionJobCoordinator()
            var token = ""
            var canceled = false
            val writing = CompletableDeferred<Unit>()
            val cleanupStarted = CompletableDeferred<Unit>()
            val cleanupAllowed = CompletableDeferred<Unit>()
            val owner =
                async {
                    coordinator.execute({
                        token = it
                        true
                    }, { canceled = true }) {
                        try {
                            writing.complete(Unit)
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                cleanupStarted.complete(Unit)
                                cleanupAllowed.await()
                            }
                        }
                    }
                }
            runCurrent()
            assertTrue(coordinator.start(token, this) {})
            writing.await()
            owner.cancel()
            cleanupStarted.await()
            assertFalse(owner.isCompleted)
            assertFalse(canceled)
            val duplicate = runCatching { coordinator.execute({ true }, {}) { "duplicate" } }
            assertTrue(duplicate.exceptionOrNull() is OamExtractionJobUnavailableException)
            cleanupAllowed.complete(Unit)
            owner.join()
            assertTrue(canceled)
        }

    @Test
    fun `system stop reports retryable failure and cancels writer without auto rescheduling`() =
        runTest {
            val coordinator = OamExtractionJobCoordinator()
            var token = ""
            var cleanup = false
            var schedules = 0
            val writing = CompletableDeferred<Unit>()
            val owner =
                async {
                    runCatching {
                        coordinator.execute({
                            token = it
                            schedules++
                            true
                        }, {}) {
                            try {
                                writing.complete(Unit)
                                awaitCancellation()
                            } finally {
                                cleanup = true
                            }
                        }
                    }
                }
            runCurrent()
            assertTrue(coordinator.start(token, this) {})
            writing.await()
            coordinator.stop(token)
            assertTrue(owner.await().exceptionOrNull() is OamExtractionJobUnavailableException)
            assertTrue(cleanup)
            assertEquals(1, schedules)
        }

    @Test
    fun `writer failure propagates and job finishes`() =
        runTest {
            val coordinator = OamExtractionJobCoordinator()
            val failure = IOException("storage failure")
            var token = ""
            var finished = 0
            val owner =
                async {
                    runCatching {
                        coordinator.execute({
                            token = it
                            true
                        }, {}) { throw failure }
                    }
                }
            runCurrent()
            assertTrue(coordinator.start(token, this) { finished++ })
            val reported = owner.await().exceptionOrNull()
            assertTrue(reported is IOException)
            assertEquals(failure.message, reported?.message)
            assertEquals(1, finished)
        }

    @Test
    fun `service scope destroyed before dispatch fails promptly without extracting`() =
        runTest {
            val coordinator = OamExtractionJobCoordinator()
            val serviceScope = CoroutineScope(coroutineContext + SupervisorJob())
            serviceScope.cancel()
            var token = ""
            var ran = false
            var finished = 0
            val owner =
                async {
                    runCatching {
                        coordinator.execute({
                            token = it
                            true
                        }, {}) {
                            ran = true
                            "map"
                        }
                    }
                }
            runCurrent()
            assertTrue(coordinator.start(token, serviceScope) { finished++ })
            assertTrue(owner.await().exceptionOrNull() is OamExtractionJobUnavailableException)
            assertFalse(ran)
            assertEquals(1, finished)
        }

    @Test
    fun `old stop and start cannot affect a resumed extraction`() =
        runTest {
            val coordinator = OamExtractionJobCoordinator()
            var old = ""
            val first =
                async {
                    coordinator.execute({
                        old = it
                        true
                    }, {}) { "first" }
                }
            runCurrent()
            first.cancelAndJoin()
            var fresh = ""
            val next =
                async {
                    coordinator.execute({
                        fresh = it
                        true
                    }, {}) { "resumed" }
                }
            runCurrent()
            coordinator.stop(old)
            assertFalse(coordinator.start(old, this) {})
            assertTrue(old != fresh)
            assertTrue(coordinator.start(fresh, this) {})
            assertEquals("resumed", next.await())
        }
}
