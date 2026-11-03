package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import org.junit.Assert.assertEquals
import org.junit.Test

class WorldWindElevationTest {
    @Test
    fun `missing nonfinite and out of range samples become void`() {
        assertEquals(Short.MIN_VALUE, worldWindHeight(null))
        assertEquals(Short.MIN_VALUE, worldWindHeight(Double.NaN))
        assertEquals(Short.MIN_VALUE, worldWindHeight(Double.POSITIVE_INFINITY))
        assertEquals(Short.MIN_VALUE, worldWindHeight(40000.0))
        assertEquals(123.toShort(), worldWindHeight(123.3))
        assertEquals((-52).toShort(), worldWindHeight(-52.0))
    }
}
