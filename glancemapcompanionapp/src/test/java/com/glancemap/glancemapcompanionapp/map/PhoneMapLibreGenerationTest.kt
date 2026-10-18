package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMapLibreGenerationTest {
    @Test
    fun oldRendererStyleCallbackIsIgnoredAfterReplacement() {
        val firstRenderer = PhoneMapLibreGeneration()
        val replacement = firstRenderer.nextRenderer()

        assertEquals(replacement, replacement.onStyleReady(firstRenderer.renderer))
    }

    @Test
    fun currentRendererStyleCallbackAdvancesTheStyleRevision() {
        val renderer = PhoneMapLibreGeneration().nextRenderer()

        val styleReady = renderer.onStyleReady(renderer.renderer)

        assertEquals(renderer.styleRevision + 1, styleReady.styleRevision)
        assertTrue(styleReady.accepts(renderer.renderer))
    }

    @Test
    fun rendererReplacementInvalidatesThePreviousStyleRevision() {
        val firstRenderer = PhoneMapLibreGeneration().nextRenderer()
        val styleReady = firstRenderer.onStyleReady(firstRenderer.renderer)
        val replacement = styleReady.nextRenderer()

        assertEquals(0L, replacement.styleRevision)
        assertFalse(replacement.accepts(styleReady.renderer))
    }

    @Test
    fun styleReplayKeyChangesAcrossProviderGenerationsEvenWhenRevisionRepeats() {
        val openTopo = PhoneMapLibreGeneration().nextRenderer().let { it.onStyleReady(it.renderer) }
        val ign = openTopo.nextRenderer().let { it.onStyleReady(it.renderer) }
        val openTopoAgain = ign.nextRenderer().let { it.onStyleReady(it.renderer) }

        assertEquals(openTopo.styleRevision, ign.styleRevision)
        assertEquals(openTopo.styleRevision, openTopoAgain.styleRevision)
        assertTrue(openTopo.styleReplayKey != ign.styleReplayKey)
        assertTrue(ign.styleReplayKey != openTopoAgain.styleReplayKey)
    }

    @Test
    fun rendererLifetimeCallbacksRemainCurrentAfterStyleReady() {
        val renderer = MapRuntime(generation = PhoneMapLibreGeneration().nextRenderer())
        val styleReady =
            renderer.copy(
                generation = renderer.generation.onStyleReady(renderer.generation.renderer),
            )

        assertFalse(renderer.isCurrentIn(styleReady))
        assertTrue(renderer.isRendererCurrentIn(styleReady))
    }

    @Test
    fun rapidOpenTopoIgnOpenTopoSwitchesAcceptOnlyTheLatestStyleCallback() {
        val openTopo = PhoneMapLibreGeneration().nextRenderer()
        val ign = openTopo.nextRenderer()
        val openTopoAgain = ign.nextRenderer()

        assertFalse(openTopoAgain.accepts(openTopo.renderer))
        assertFalse(openTopoAgain.accepts(ign.renderer))
        assertEquals(openTopoAgain, openTopoAgain.onStyleReady(openTopo.renderer))
        assertEquals(openTopoAgain, openTopoAgain.onStyleReady(ign.renderer))
        assertEquals(openTopoAgain.styleRevision + 1L, openTopoAgain.onStyleReady(openTopoAgain.renderer).styleRevision)
    }

    @Test
    fun onlineOfflineOnlineRecreationRejectsTheFirstOnlineViewCallback() {
        val firstOnlineView = Any()
        val offlineView = Any()
        val returnedOnlineView = Any()
        val firstOnline = PhoneMapLibreGeneration().nextRenderer()
        val offline = firstOnline.nextRenderer()
        val returnedOnline = offline.nextRenderer()

        assertFalse(
            phoneMapLibreRendererCallbackIsCurrent(
                callbackGeneration = firstOnline.renderer,
                currentGeneration = returnedOnline.renderer,
                callbackRenderer = firstOnlineView,
                currentRenderer = returnedOnlineView,
            ),
        )
        assertFalse(
            phoneMapLibreRendererCallbackIsCurrent(
                callbackGeneration = offline.renderer,
                currentGeneration = returnedOnline.renderer,
                callbackRenderer = offlineView,
                currentRenderer = returnedOnlineView,
            ),
        )
    }

    @Test
    fun comparisonCreationAndRemovalDoNotInvalidateTheBaseRenderer() {
        val baseView = Any()
        val comparisonView = Any()
        val base = PhoneMapLibreGeneration().nextRenderer()
        val comparison = PhoneMapLibreGeneration().nextRenderer()
        val removedComparisonGeneration = comparison.nextRenderer()

        assertTrue(
            phoneMapLibreRendererCallbackIsCurrent(
                callbackGeneration = base.renderer,
                currentGeneration = base.renderer,
                callbackRenderer = baseView,
                currentRenderer = baseView,
            ),
        )
        assertFalse(
            phoneMapLibreRendererCallbackIsCurrent(
                callbackGeneration = comparison.renderer,
                currentGeneration = removedComparisonGeneration.renderer,
                callbackRenderer = comparisonView,
                currentRenderer = null,
            ),
        )
    }

    @Test
    fun repeatedProviderChangesRejectEverySupersededStyleCallback() {
        var current = PhoneMapLibreGeneration().nextRenderer()
        val superseded = mutableListOf<Long>()
        repeat(20) {
            superseded += current.renderer
            current = current.nextRenderer()
        }

        assertTrue(superseded.none(current::accepts))
        assertEquals(current.styleRevision, current.onStyleReady(superseded.last()).styleRevision)
    }

    @Test
    fun staleCallbackFromDisposedRendererCannotPassGenerationAndIdentityGate() {
        val disposedView = Any()
        val currentView = Any()
        val disposed = PhoneMapLibreGeneration().nextRenderer()
        val current = disposed.nextRenderer()

        assertFalse(
            phoneMapLibreRendererCallbackIsCurrent(
                callbackGeneration = disposed.renderer,
                currentGeneration = current.renderer,
                callbackRenderer = disposedView,
                currentRenderer = currentView,
            ),
        )
    }
}
