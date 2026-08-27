package com.glancemap.glancemapcompanionapp.layout

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionFoldGeometryTest {
    private val contentBounds =
        CompanionWindowPixelBounds(
            left = 0,
            top = 0,
            right = 200,
            bottom = 400,
        )

    @Test
    fun `recognizes a zero width vertical separator inside content`() {
        assertTrue(
            contentBounds.intersectsSeparatingFold(
                foldBounds = CompanionWindowPixelBounds(100, 0, 100, 400),
                orientation = CompanionFoldOrientation.VERTICAL,
            ),
        )
    }

    @Test
    fun `recognizes a zero height horizontal separator inside content`() {
        assertTrue(
            contentBounds.intersectsSeparatingFold(
                foldBounds = CompanionWindowPixelBounds(0, 200, 200, 200),
                orientation = CompanionFoldOrientation.HORIZONTAL,
            ),
        )
    }

    @Test
    fun `recognizes a non zero hinge inside content`() {
        assertTrue(
            contentBounds.intersectsSeparatingFold(
                foldBounds = CompanionWindowPixelBounds(90, 0, 110, 400),
                orientation = CompanionFoldOrientation.VERTICAL,
            ),
        )
    }

    @Test
    fun `ignores a vertical separator outside content`() {
        assertFalse(
            contentBounds.intersectsSeparatingFold(
                foldBounds = CompanionWindowPixelBounds(240, 0, 240, 400),
                orientation = CompanionFoldOrientation.VERTICAL,
            ),
        )
    }

    @Test
    fun `ignores a horizontal separator outside content`() {
        assertFalse(
            contentBounds.intersectsSeparatingFold(
                foldBounds = CompanionWindowPixelBounds(0, 440, 200, 440),
                orientation = CompanionFoldOrientation.HORIZONTAL,
            ),
        )
    }
}
