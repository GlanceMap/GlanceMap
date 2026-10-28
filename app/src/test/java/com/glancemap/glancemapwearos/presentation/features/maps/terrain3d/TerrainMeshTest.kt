package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerrainMeshTest {
    @Test
    fun lightingUsesTerrainSlopeAndKeepsFlatTerrainNeutral() {
        val flat = requireNotNull(buildTerrainMesh(3, 4000f) { _, _ -> 100.0 })
        val slope = requireNotNull(buildTerrainMesh(3, 4000f) { u, _ -> u * 2000.0 })
        assertEquals(1f, flat.light[4], 0.001f)
        assertTrue(slope.light[4] < flat.light[4])
        assertTrue(slope.light[4] >= 0.75f)
    }

    @Test
    fun meshHasCenteredCoordinatesTextureAndIndexedTriangles() {
        val mesh = requireNotNull(buildTerrainMesh(3, 4000f) { _, _ -> 100.0 })
        assertEquals(9, mesh.vertices.size / 5)
        assertEquals(8 * 3, mesh.indices.size)
        assertEquals(-2000f, mesh.vertices[0], 0.001f)
        assertEquals(-2000f, mesh.vertices[2], 0.001f)
        assertEquals(0f, mesh.vertices[3], 0.001f)
        assertEquals(0f, mesh.vertices[4], 0.001f)
        assertEquals(2000f, mesh.vertices[40], 0.001f)
        assertEquals(2000f, mesh.vertices[42], 0.001f)
        assertEquals(1f, mesh.vertices[43], 0.001f)
        assertEquals(1f, mesh.vertices[44], 0.001f)
    }

    @Test
    fun fullGridKeepsReliefAndExpectedTriangleCount() {
        val mesh = requireNotNull(buildTerrainMesh(64, 4000f) { u, _ -> 100.0 + u * 80.0 })
        assertEquals(4096, mesh.vertices.size / 5)
        assertEquals(7938, mesh.indices.size / 3)
        assertEquals(100f, mesh.vertices[(63 * 5) + 1] - mesh.vertices[1], 0.001f)
    }

    @Test
    fun missingOrInvalidElevationRejectsMesh() {
        assertNull(buildTerrainMesh(3, 4000f) { _, _ -> null })
        assertNull(buildTerrainMesh(3, 4000f) { _, _ -> Double.NaN })
    }
}

class TerrainAreaTest {
    @Test
    fun extentIsCenteredAndApproximatelyFourKilometersAtEquator() {
        val area = TerrainArea(0.0, 0.0, 4000.0)
        val center = area.coordinate(0.5, 0.5)
        val northWest = area.coordinate(0.0, 0.0)
        assertEquals(0.0, center.first, 0.000001)
        assertEquals(0.0, center.second, 0.000001)
        assertEquals(0.01797, northWest.first, 0.0001)
        assertEquals(-0.01797, northWest.second, 0.0001)
    }
}
