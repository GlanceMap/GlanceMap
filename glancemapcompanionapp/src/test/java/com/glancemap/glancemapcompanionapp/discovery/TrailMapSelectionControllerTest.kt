package com.glancemap.glancemapcompanionapp.discovery

import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.profile.TrailPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TrailMapSelectionControllerTest {
    @Test
    fun checkedRoutesLoadOnDemandAndRemainIndependent() =
        runTest {
            val pending = CompletableDeferred<PublishedTrail>()
            var loads = 0
            val controller =
                TrailMapSelectionController(backgroundScope) {
                    loads++
                    pending.await()
                }
            val first = trail("first", complete = false)
            val second = trail("second", complete = true)
            controller.setVisible(first, true)
            controller.setVisible(second, true)
            runCurrent()
            val initial = controller.state.value
            assertEquals(setOf("first"), initial.loading)
            assertEquals(listOf("second"), initial.visibleTrails.map { it.id })
            pending.complete(trail("first", complete = true))
            runCurrent()
            val loaded = controller.state.value
            assertEquals(setOf("first", "second"), loaded.visibleTrails.map { it.id }.toSet())
            controller.setVisible(first, true)
            assertEquals(1, loads)
            controller.setVisible(first, false)
            val remaining = controller.state.value
            assertEquals(listOf("second"), remaining.visibleTrails.map { it.id })
        }

    @Test
    fun uncheckedRouteCannotReappearAfterLateDetailResponse() =
        runTest {
            val pending = CompletableDeferred<PublishedTrail>()
            val controller =
                TrailMapSelectionController(backgroundScope) {
                    withContext(NonCancellable) { pending.await() }
                }
            val first = trail("first", complete = false)
            controller.setVisible(first, true)
            runCurrent()
            controller.setVisible(first, false)
            pending.complete(trail("first", complete = true))
            runCurrent()
            val result = controller.state.value
            assertTrue(result.visibleTrails.isEmpty())
            assertTrue(result.requested.isEmpty())
            assertTrue(result.loading.isEmpty())
        }

    @Test
    fun detailFailureDoesNotRemoveOtherRoutesAndCanBeRetried() =
        runTest {
            var fail = true
            val controller =
                TrailMapSelectionController(backgroundScope) {
                    if (fail) throw IOException("fixture")
                    trail(it.id, complete = true)
                }
            controller.setVisible(trail("shown", complete = true), true)
            val unavailable = trail("unavailable", complete = false)
            controller.setVisible(unavailable, true)
            runCurrent()
            val failed = controller.state.value
            assertEquals(setOf("unavailable"), failed.failed)
            assertFalse("unavailable" in failed.requested)
            assertEquals(listOf("shown"), failed.visibleTrails.map { it.id })
            fail = false
            controller.setVisible(unavailable, true)
            runCurrent()
            val retried = controller.state.value
            assertTrue(retried.failed.isEmpty())
            assertEquals(setOf("shown", "unavailable"), retried.visibleTrails.map { it.id }.toSet())
        }

    @Test
    fun incompleteDetailIsNotShownAsAMapRoute() =
        runTest {
            val controller = TrailMapSelectionController(backgroundScope) { it }
            controller.setVisible(trail("incomplete", complete = false), true)
            runCurrent()
            val result = controller.state.value
            assertTrue(result.visibleTrails.isEmpty())
            assertEquals(setOf("incomplete"), result.failed)
        }

    private fun trail(
        id: String,
        complete: Boolean,
    ): PublishedTrail =
        PublishedTrail(
            id = id,
            title = id,
            sources = listOf(TrailSource(TrailProvider.WAYMARKED_TRAILS, id, "https://example.com", "OSM", "ODbL")),
            geometry =
                if (complete) {
                    listOf(TrailPoint(GeoPoint(48.1, 11.5)), TrailPoint(GeoPoint(48.101, 11.501)))
                } else {
                    emptyList()
                },
            geometryComplete = complete,
        )
}
