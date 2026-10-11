package com.glancemap.glancemapwearos.presentation.features.maps

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.graphics.TileBitmap
import org.mapsforge.core.model.Tile
import java.io.OutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class FirstVisibleTileRendererLayerTest {
    @Test
    fun `completed draw for a superseded zoom cannot signal current viewport readiness`() {
        val request = readinessRequest()
        val completedOldDraw = readinessEvent(zoom = 12)
        val currentViewport = VisibleTileViewportKey(16, emptySet())

        assertFalse(visibleTileViewportReadinessMatches(request, completedOldDraw, currentViewport))
    }

    @Test
    fun `readiness follows a changed viewport when the completed draw is still current`() {
        val request = readinessRequest()
        val completedCurrentDraw = readinessEvent(zoom = 12)
        val currentViewport = VisibleTileViewportKey(12, emptySet())

        assertTrue(visibleTileViewportReadinessMatches(request, completedCurrentDraw, currentViewport))
    }

    @Test
    fun `padded drawing buffer can satisfy a smaller current viewport`() {
        val request = readinessRequest()
        val visible = Tile(1, 2, 12, 256)
        val padding = Tile(2, 2, 12, 256)
        val drawn = VisibleTileViewportReadinessEvent(101, 7L, VisibleTileViewportKey(12, setOf(visible, padding)))
        val currentViewport = VisibleTileViewportKey(12, setOf(visible))

        assertTrue(visibleTileViewportReadinessMatches(request, drawn, currentViewport))
    }

    @Test
    fun `draw for an old pan cannot satisfy a viewport outside its coverage`() {
        val request = readinessRequest()
        val oldTile = Tile(1, 2, 12, 256)
        val newTile = Tile(5, 2, 12, 256)
        val drawn = VisibleTileViewportReadinessEvent(101, 7L, VisibleTileViewportKey(12, setOf(oldTile)))
        val currentViewport = VisibleTileViewportKey(12, setOf(newTile))

        assertFalse(visibleTileViewportReadinessMatches(request, drawn, currentViewport))
    }

    @Test
    fun `appearance wait accepts an already drawable current viewport immediately`() =
        runTest {
            val request = readinessRequest()
            val ready = readinessEvent()

            assertEquals(
                ready,
                awaitVisibleTileViewportReadiness(request, MutableStateFlow(ready), 4_500L),
            )
            assertEquals(0L, testScheduler.currentTime)
        }

    @Test
    fun `appearance wait rejects previous layer and request events then follows the current viewport`() =
        runTest {
            val events = MutableStateFlow<VisibleTileViewportReadinessEvent?>(null)
            val waiting = async { awaitVisibleTileViewportReadiness(readinessRequest(), events, 4_500L) }
            runCurrent()

            events.value = readinessEvent(layerId = 99)
            runCurrent()
            assertTrue(waiting.isActive)
            events.value = readinessEvent(requestId = 6L)
            runCurrent()
            assertTrue(waiting.isActive)
            val currentViewport = readinessEvent(zoom = 12)
            events.value = currentViewport
            runCurrent()

            assertEquals(currentViewport, waiting.await())
        }

    @Test
    fun `appearance wait times out without a drawable viewport event`() =
        runTest {
            val events = MutableStateFlow<VisibleTileViewportReadinessEvent?>(null)
            val waiting = async { awaitVisibleTileViewportReadiness(readinessRequest(), events, 4_500L) }
            runCurrent()
            advanceTimeBy(4_499L)
            runCurrent()
            assertTrue(waiting.isActive)
            advanceTimeBy(1L)
            runCurrent()

            assertNull(waiting.await())
        }

    @Test
    fun `superseded appearance wait is cancellable`() =
        runTest {
            val events = MutableStateFlow<VisibleTileViewportReadinessEvent?>(null)
            val waiting = async { awaitVisibleTileViewportReadiness(readinessRequest(), events, 4_500L) }
            runCurrent()

            waiting.cancelAndJoin()
            events.value = readinessEvent()
            runCurrent()

            assertTrue(waiting.isCancelled)
        }

    @Test
    fun exactTileHitKeepsCoverageResultAndBalancesAcquiredReference() {
        val bitmap = ReferenceCountedTileBitmap()

        assertTrue(hasAcquiredTileBitmap { bitmap.acquire() })

        assertEquals(1, bitmap.referenceCount)
        assertEquals(1, bitmap.decrementCount)
    }

    @Test
    fun parentTileHitKeepsDrawableResultAndBalancesAcquiredReference() {
        val bitmap = ReferenceCountedTileBitmap()

        assertTrue(hasAcquiredTileBitmap { bitmap.acquire() })

        assertEquals(1, bitmap.referenceCount)
        assertEquals(1, bitmap.decrementCount)
    }

    @Test
    fun tileMissIsNotDrawableAndDoesNotDecrement() {
        val bitmap = ReferenceCountedTileBitmap()

        assertFalse(hasAcquiredTileBitmap { null })

        assertEquals(1, bitmap.referenceCount)
        assertEquals(0, bitmap.decrementCount)
    }

    @Test
    fun repeatedDiagnosticCapturesBalanceEachLookupWithoutChangingCoverage() {
        val bitmap = ReferenceCountedTileBitmap()
        var drawableSamples = 0

        repeat(3) {
            if (hasAcquiredTileBitmap { bitmap.acquire() }) drawableSamples += 1
        }

        assertEquals(3, drawableSamples)
        assertEquals(1, bitmap.referenceCount)
        assertEquals(3, bitmap.decrementCount)
    }

    private fun readinessRequest(): VisibleTileViewportReadinessRequest =
        VisibleTileViewportReadinessRequest(
            101,
            7L,
            VisibleTileViewportKey(16, emptySet()),
        )

    private fun readinessEvent(
        layerId: Int = 101,
        requestId: Long = 7L,
        zoom: Byte = 16,
    ): VisibleTileViewportReadinessEvent =
        VisibleTileViewportReadinessEvent(
            layerId,
            requestId,
            VisibleTileViewportKey(zoom, emptySet()),
        )

    private class ReferenceCountedTileBitmap : TileBitmap {
        var referenceCount = 1
            private set
        var decrementCount = 0
            private set

        fun acquire(): TileBitmap {
            incrementRefCount()
            return this
        }

        override fun getMutex(): Any = this

        override fun compress(outputStream: OutputStream) = Unit

        override fun decrementRefCount() {
            check(referenceCount > 0) { "Tile bitmap reference decremented more than once" }
            referenceCount -= 1
            decrementCount += 1
        }

        override fun getHeight(): Int = 24

        override fun getWidth(): Int = 24

        override fun incrementRefCount() {
            referenceCount += 1
        }

        override fun isDestroyed(): Boolean = referenceCount == 0

        override fun scaleTo(
            width: Int,
            height: Int,
        ) = Unit

        override fun setBackgroundColor(color: Int) = Unit

        override fun getTimestamp(): Long = 0L

        override fun isExpired(): Boolean = false

        override fun setExpiration(expiration: Long) = Unit

        override fun setTimestamp(timestamp: Long) = Unit
    }
}
