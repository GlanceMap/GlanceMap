package com.glancemap.glancemapwearos.presentation.features.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.graphics.TileBitmap
import java.io.OutputStream

class FirstVisibleTileRendererLayerTest {
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
