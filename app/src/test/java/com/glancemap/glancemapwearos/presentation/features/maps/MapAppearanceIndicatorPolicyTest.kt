package com.glancemap.glancemapwearos.presentation.features.maps

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapAppearanceIndicatorPolicyTest {
    @Test
    fun `fast initial map load never reaches delayed show`() {
        val policy = mapAppearanceIndicatorPolicy(MapAppearanceIndicatorRequest.INITIAL_MAP_LOAD)

        assertEquals(400L, policy.showDelayMs)
        assertTrue(listOf(145L, 216L, 257L).all { it < policy.showDelayMs })
        assertFalse(shouldShowInitialMapLoadIndicator(firstVisibleMapReceived = true))
    }

    @Test
    fun `non initial readiness starts after the current first visible version`() {
        val emittedVersion = 1L

        val baseline = firstVisibleMapBaselineVersion(currentVersion = emittedVersion)

        assertEquals(emittedVersion, baseline)
    }

    @Test
    fun `first visible just before delayed show resumes keeps the popup hidden`() {
        assertFalse(shouldShowInitialMapLoadIndicator(firstVisibleMapReceived = true))
    }

    @Test
    fun `slow initial map load shows after delayed threshold`() {
        val policy = mapAppearanceIndicatorPolicy(MapAppearanceIndicatorRequest.INITIAL_MAP_LOAD)

        assertEquals(400L, policy.showDelayMs)
        assertTrue(shouldShowInitialMapLoadIndicator(firstVisibleMapReceived = false))
    }

    @Test
    fun `ready initial map load hides without a minimum duration`() {
        val policy = mapAppearanceIndicatorPolicy(MapAppearanceIndicatorRequest.INITIAL_MAP_LOAD)

        assertEquals(0L, policy.minimumVisibleMs)
        assertFalse(shouldRetainInitialMapLoadIndicator(policy, mapReady = true))
    }

    @Test
    fun `explicit map changes still show immediately`() {
        val policy = mapAppearanceIndicatorPolicy(MapAppearanceIndicatorRequest.MAP_CHANGE)

        assertEquals(0L, policy.showDelayMs)
        assertEquals(900L, policy.minimumVisibleMs)
    }

    @Test
    fun `explicit theme changes still show immediately`() {
        val policy = mapAppearanceIndicatorPolicy(MapAppearanceIndicatorRequest.THEME_CHANGE)

        assertEquals(0L, policy.showDelayMs)
        assertEquals(900L, policy.minimumVisibleMs)
    }

    @Test
    fun `explicit cache reloads still show immediately`() {
        val policy = mapAppearanceIndicatorPolicy(MapAppearanceIndicatorRequest.EXTERNAL_CACHE_RELOAD)

        assertEquals(0L, policy.showDelayMs)
        assertEquals(900L, policy.minimumVisibleMs)
    }

    @Test
    fun `generation replacement before delayed show prevents stale popup`() {
        assertFalse(
            canMapAppearanceIndicatorOwnGeneration(
                candidateGeneration = 2L,
                currentGeneration = 3L,
            ),
        )
    }

    @Test
    fun `generation replacement after popup shown prevents stale hide`() {
        assertFalse(
            canMapAppearanceIndicatorOwnGeneration(
                candidateGeneration = 2L,
                currentGeneration = 3L,
            ),
        )
        assertTrue(canMapAppearanceIndicatorOwnGeneration(candidateGeneration = 3L, currentGeneration = 3L))
    }

    @Test
    fun `replacement initial load clears a visible older generation`() {
        assertTrue(
            shouldClearVisibleIndicatorForInitialLoadReplacement(
                visibleGeneration = 2L,
                nextGeneration = 3L,
            ),
        )
        assertFalse(
            shouldClearVisibleIndicatorForInitialLoadReplacement(
                visibleGeneration = null,
                nextGeneration = 3L,
            ),
        )
    }

    @Test
    fun `initial timeout retains feedback until a later first visible signal`() =
        runBlocking {
            val policy = mapAppearanceIndicatorPolicy(MapAppearanceIndicatorRequest.INITIAL_MAP_LOAD)
            var calls = 0
            var timeoutObserved = false

            val firstVisible =
                awaitInitialFirstVisibleAfterTimeout(
                    timeoutMs = 4_500L,
                    awaitFirstVisible = {
                        calls += 1
                        if (calls == 1) null else "visible"
                    },
                    onTimeout = { timeoutObserved = true },
                )

            assertTrue(shouldRetainInitialMapLoadIndicator(policy, mapReady = false))
            assertFalse(shouldRetainInitialMapLoadIndicator(policy, mapReady = true))
            assertEquals("visible", firstVisible)
            assertEquals(2, calls)
            assertTrue(timeoutObserved)
        }

    @Test
    fun `clean process state starts without an inherited first visible token`() {
        assertEquals(
            0L,
            firstVisibleMapBaselineVersion(currentVersion = 0L),
        )
    }

    @Test
    fun `viewport readiness accepts an already arrived tile for the same request`() {
        val viewport = VisibleTileViewportKey(zoomLevel = 16, tiles = emptySet())
        val request =
            VisibleTileViewportReadinessRequest(
                layerId = 101,
                requestId = 7L,
                viewportKey = viewport,
            )

        assertTrue(
            visibleTileViewportReadinessMatches(
                request,
                VisibleTileViewportReadinessEvent(
                    layerId = 101,
                    requestId = 7L,
                    viewportKey = viewport,
                ),
            ),
        )
    }

    @Test
    fun `viewport readiness rejects old viewport layer or request events`() {
        val request =
            VisibleTileViewportReadinessRequest(
                layerId = 101,
                requestId = 7L,
                viewportKey = VisibleTileViewportKey(zoomLevel = 16, tiles = emptySet()),
            )

        assertFalse(
            visibleTileViewportReadinessMatches(
                request,
                VisibleTileViewportReadinessEvent(
                    layerId = 101,
                    requestId = 7L,
                    viewportKey = VisibleTileViewportKey(zoomLevel = 15, tiles = emptySet()),
                ),
            ),
        )
        assertFalse(
            visibleTileViewportReadinessMatches(
                request,
                VisibleTileViewportReadinessEvent(
                    layerId = 102,
                    requestId = 7L,
                    viewportKey = request.viewportKey,
                ),
            ),
        )
        assertFalse(
            visibleTileViewportReadinessMatches(
                request,
                VisibleTileViewportReadinessEvent(
                    layerId = 101,
                    requestId = 6L,
                    viewportKey = request.viewportKey,
                ),
            ),
        )
    }

    @Test
    fun `first visible warm cold classification remains unchanged`() {
        assertEquals("warm", FirstVisibleBaseTileSource.WARM_CACHE.telemetryToken)
        assertEquals("cold", FirstVisibleBaseTileSource.COLD_RENDER.telemetryToken)
    }

    @Test
    fun `visible tile diagnostic states distinguish availability from redraw evidence`() {
        assertEquals("pending_no_tiles", visibleTileDiagnosticState(null, drawable = false, pendingJobCount = 2))
        assertEquals("no_tiles_no_jobs", visibleTileDiagnosticState(null, drawable = false, pendingJobCount = 0))
        assertEquals(
            "tile_arrived",
            visibleTileDiagnosticState("pending_no_tiles", drawable = true, pendingJobCount = 0),
        )
        assertEquals("drawable", visibleTileDiagnosticState(null, drawable = true, pendingJobCount = 0))
    }

    @Test
    fun `first visible tile diagnostic sample bypasses rate limiting`() {
        assertTrue(
            isVisibleTileDiagnosticSampleDue(
                lastSampleAtElapsedMs = null,
                nowElapsedMs = 12_000L,
                sampleIntervalMs = 250L,
            ),
        )
        assertFalse(
            isVisibleTileDiagnosticSampleDue(
                lastSampleAtElapsedMs = 12_000L,
                nowElapsedMs = 12_249L,
                sampleIntervalMs = 250L,
            ),
        )
        assertTrue(
            isVisibleTileDiagnosticSampleDue(
                lastSampleAtElapsedMs = 12_000L,
                nowElapsedMs = 12_250L,
                sampleIntervalMs = 250L,
            ),
        )
    }

    @Test
    fun `same generation readiness does not cancel active initial map work`() {
        assertFalse(shouldCancelActiveRendererWork(activeGeneration = 4L, requestedGeneration = 4L))
        assertTrue(shouldCancelActiveRendererWork(activeGeneration = 4L, requestedGeneration = 5L))
    }
}
