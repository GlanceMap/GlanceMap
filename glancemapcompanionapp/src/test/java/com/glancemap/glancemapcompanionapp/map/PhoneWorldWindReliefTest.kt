package com.glancemap.glancemapcompanionapp.map

import earth.worldwind.geom.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class PhoneWorldWindReliefTest {
    @Test
    fun reliefStrengthIsOffAtZeroAndHalfwayMatchesTheEarlierFixedShading() {
        assertEquals(0f, phoneWorldWindReliefStrength(0, offline = true), 0f)
        assertEquals(0.28f, phoneWorldWindReliefStrength(50, offline = true), 1e-6f)
        assertEquals(0.12f, phoneWorldWindReliefStrength(50, offline = false), 1e-6f)
        assertEquals(0.56f, phoneWorldWindReliefStrength(150, offline = true), 1e-6f)
        assertEquals(0f, phoneWorldWindReliefStrength(-10, offline = false), 0f)
    }

    @Test
    fun lightIsNormalizedAndAboveTheTerrainAtDifferentLatitudes() {
        for (latitude in listOf(-80.0, 0.0, 46.0, 80.0)) {
            val lat = Math.toRadians(latitude)
            val lon = Math.toRadians(35.0)
            val light = phoneWorldWindReliefDirection(lat, lon, Vec3())
            assertEquals(1.0, light.x * light.x + light.y * light.y + light.z * light.z, 0.000001)
            val upDot = light.x * cos(lat) * sin(lon) + light.y * sin(lat) + light.z * cos(lat) * cos(lon)
            assertTrue(upDot > 0.7)
        }
    }
}
