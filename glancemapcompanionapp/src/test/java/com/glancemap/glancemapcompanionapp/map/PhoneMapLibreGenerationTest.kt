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
}
