package com.glancemap.glancemapwearos.presentation.features.routetools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateEditingTest {
    @Test
    fun longitudeNormalizationHandlesBoundariesAndVeryLargeFiniteValues() {
        assertEquals(180.0, normalizeLongitude(180.0), 0.0)
        assertEquals(-180.0, normalizeLongitude(-180.0), 0.0)
        assertEquals(180.0, normalizeLongitude(540.0), 0.0)
        assertEquals(-180.0, normalizeLongitude(-540.0), 0.0)
        assertEquals(-179.0, normalizeLongitude(181.0), 0.0)
        assertEquals(179.0, normalizeLongitude(-181.0), 0.0)

        val normalizedVeryLargeValue = normalizeLongitude(Double.MAX_VALUE)
        assertTrue(normalizedVeryLargeValue.isFinite())
        assertTrue(normalizedVeryLargeValue in -180.0..180.0)
    }

    @Test
    fun coordinateStepsChangeInNumericDirectionWithoutWrapping() {
        assertEquals(CoordinateStep.HUNDREDTH, CoordinateStep.THOUSANDTH.previous())
        assertEquals(CoordinateStep.ONE_TEN_THOUSANDTH, CoordinateStep.THOUSANDTH.next())
        assertEquals(CoordinateStep.TENTH, CoordinateStep.TENTH.previous())
        assertEquals(CoordinateStep.HUNDREDTH, CoordinateStep.TENTH.next())
        assertEquals(CoordinateStep.ONE_TEN_THOUSANDTH, CoordinateStep.ONE_TEN_THOUSANDTH.next())
        assertEquals(CoordinateStep.THOUSANDTH, CoordinateStep.ONE_TEN_THOUSANDTH.next().previous())
    }
}
