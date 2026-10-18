package com.glancemap.glancemapcompanionapp.discovery

import com.glancemap.trailcore.geo.GeoPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TrailDiscoveryRepositoryTest {
    private val query = TrailDiscoveryQuery(GeoPoint(46.0, 7.0))

    @Test
    fun disabledSourcesAreNeverCalledAndOneFailedInstanceDoesNotLoseOtherResults() =
        runTest {
            var disabledCalls = 0
            val good = fixtureProvider(TrailProvider.GEOTREK, "Park A") { listOf(trail("a")) }
            val broken = fixtureProvider(TrailProvider.GEOTREK, "Park B") { throw IOException("fixture") }
            val disabled =
                fixtureProvider(TrailProvider.OPEN_STREET_MAP, "OSM") {
                    disabledCalls++
                    listOf(trail("disabled"))
                }
            val repository = TrailDiscoveryRepository(listOf(good, broken, disabled))
            val reply = repository.discover(query, setOf(TrailProvider.GEOTREK))
            assertEquals(listOf("a"), reply.trails.map { it.id })
            assertEquals(
                listOf(TrailSourceOutcome.SUCCESS, TrailSourceOutcome.FAILED),
                reply.statuses.map { it.outcome },
            )
            assertEquals(0, disabledCalls)
            assertTrue(repository.discover(query, emptySet()).trails.isEmpty())
        }

    @Test
    fun knownFailuresReachTheUiButUnexpectedExceptionDetailsStayPrivate() =
        runTest {
            val busy =
                fixtureProvider(TrailProvider.OPEN_STREET_MAP, "OSM") {
                    throw TrailDiscoveryException(TrailDiscoveryFailure.SERVICE_UNAVAILABLE)
                }
            val unknown =
                fixtureProvider(TrailProvider.GEOTREK, "Park") {
                    throw IOException("Private transport diagnostics")
                }
            val reply = TrailDiscoveryRepository(listOf(busy, unknown)).discover(query, TrailProvider.entries.toSet())
            assertEquals(
                listOf(TrailSourceOutcome.FAILED, TrailSourceOutcome.FAILED),
                reply.statuses.map { it.outcome },
            )
            assertEquals(listOf(TrailDiscoveryFailure.SERVICE_UNAVAILABLE, null), reply.statuses.map { it.failure })
            assertTrue(reply.statuses.all { it.reason == null })
        }

    @Test
    fun missingDatatourismeCredentialsAreReportedAsUnavailable() =
        runTest {
            val repository =
                TrailDiscoveryRepository(listOf(DatatourismePublishedTrailProvider("", TrailDiscoveryHttp())))
            val reply = repository.discover(query, setOf(TrailProvider.DATATOURISME))
            assertTrue(reply.trails.isEmpty())
            assertEquals(TrailSourceOutcome.UNAVAILABLE, reply.statuses.single().outcome)
        }

    @Test
    fun cacheIsBoundedByTimeAndDoesNotReturnDisabledResults() =
        runTest {
            var calls = 0
            var now = 0L
            val source =
                fixtureProvider(TrailProvider.GEOTREK, "Park") {
                    calls++
                    listOf(trail("a"))
                }
            val repository = TrailDiscoveryRepository(listOf(source)) { now }
            repeat(2) { repository.discover(query, setOf(TrailProvider.GEOTREK)) }
            assertEquals(1, calls)
            assertTrue(repository.discover(query, emptySet()).trails.isEmpty())
            now = 300001L
            repository.discover(query, setOf(TrailProvider.GEOTREK))
            assertEquals(2, calls)
        }

    @Test
    fun obsoleteSearchCannotPublishAfterANewerSearchEvenWhenAdapterIgnoresCancellation() =
        runTest {
            val oldReply = CompletableDeferred<TrailDiscoveryReply>()
            val controller =
                TrailDiscoveryController(backgroundScope) { request, _ ->
                    if (request == query) {
                        withContext(NonCancellable) { oldReply.await() }
                    } else {
                        TrailDiscoveryReply(listOf(trail("new")))
                    }
                }
            controller.submit(query, setOf(TrailProvider.GEOTREK))
            advanceTimeBy(351)
            runCurrent()
            controller.submit(query.copy(center = GeoPoint(47.0, 8.0)), setOf(TrailProvider.GEOTREK))
            advanceTimeBy(351)
            runCurrent()
            assertEquals(
                "new",
                controller.state.value.reply.trails
                    .single()
                    .id,
            )
            oldReply.complete(TrailDiscoveryReply(listOf(trail("old"))))
            runCurrent()
            assertEquals(
                "new",
                controller.state.value.reply.trails
                    .single()
                    .id,
            )
            assertFalse(controller.state.value.loading)
        }

    @Test
    fun rapidRequestsDebounceAndDisablingAllSourcesImmediatelyClearsResults() =
        runTest {
            var calls = 0
            val controller =
                TrailDiscoveryController(backgroundScope) { _, _ ->
                    calls++
                    TrailDiscoveryReply(listOf(trail("a")))
                }
            repeat(3) {
                controller.submit(query, setOf(TrailProvider.GEOTREK))
                advanceTimeBy(100)
            }
            advanceTimeBy(351)
            runCurrent()
            assertEquals(1, calls)
            controller.submit(query, emptySet())
            assertTrue(
                controller.state.value.reply.trails
                    .isEmpty(),
            )
            assertFalse(controller.state.value.loading)
        }

    @Test
    fun movingToAnotherAreaCancelsPendingDiscoveryWithoutStartingAnotherRequest() =
        runTest {
            var calls = 0
            val pending = CompletableDeferred<TrailDiscoveryReply>()
            val controller =
                TrailDiscoveryController(backgroundScope) { _, _ ->
                    calls++
                    pending.await()
                }
            controller.submit(query, setOf(TrailProvider.GEOTREK))
            advanceTimeBy(351)
            runCurrent()
            controller.cancelIfMoved(GeoPoint(47.0, 8.0))
            assertFalse(controller.state.value.loading)
            assertFalse(controller.state.value.searched)
            assertEquals(1, calls)
        }

    private fun trail(id: String) = PublishedTrail(id, id, emptyList())

    private fun fixtureProvider(
        source: TrailProvider,
        label: String,
        action: suspend () -> List<PublishedTrail>,
    ) = object : PublishedTrailProvider {
        override val provider = source
        override val instanceLabel = label

        override suspend fun discover(query: TrailDiscoveryQuery) = action()
    }
}
