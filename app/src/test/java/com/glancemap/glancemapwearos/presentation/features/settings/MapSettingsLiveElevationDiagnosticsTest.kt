package com.glancemap.glancemapwearos.presentation.features.settings

import com.glancemap.glancemapwearos.core.maps.DemSource
import com.glancemap.glancemapwearos.presentation.features.maps.theme.DemMapReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapSettingsLiveElevationDiagnosticsTest {
    @Test
    fun gateDetailAllowsPartialTerrainAndReportsCompleteCoverageState() {
        val detail =
            buildLiveElevationGateDiagnosticsDetail(
                mapPath = null,
                readiness =
                    DemMapReadiness(
                        isReady = false,
                        hasAnyTerrain = true,
                        selectedSource = DemSource.MAPSFORGE_DEM3,
                        usesFallbackTerrain = false,
                        requiredTiles = 24,
                        availableTiles = 23,
                        isCoverageKnown = true,
                        selectedAvailableTiles = 20,
                        selectedCoverageKnown = true,
                        fallbackAvailableTiles = 3,
                    ),
            )

        assertEquals(
            "decision=allow map=none selectedSource=mapsforge_dem3 coverageKnown=true " +
                "requiredTiles=24 availableTiles=23 selectedCoverageKnown=true " +
                "selectedAvailableTiles=20 fallbackAvailableTiles=3 isReady=false " +
                "hasAnyTerrain=true usesFallbackTerrain=false",
            detail,
        )
    }

    @Test
    fun liveElevationRequiresAtLeastOneAvailableTerrainTile() {
        assertTrue(
            canEnableLiveElevation(
                DemMapReadiness(
                    isReady = false,
                    hasAnyTerrain = true,
                    selectedSource = DemSource.MAPSFORGE_DEM3,
                    usesFallbackTerrain = false,
                    requiredTiles = 2,
                    availableTiles = 1,
                    isCoverageKnown = true,
                    selectedAvailableTiles = 1,
                    selectedCoverageKnown = true,
                    fallbackAvailableTiles = 0,
                ),
            ),
        )
        assertFalse(
            canEnableLiveElevation(
                DemMapReadiness(
                    isReady = false,
                    hasAnyTerrain = false,
                    selectedSource = DemSource.MAPSFORGE_DEM3,
                    usesFallbackTerrain = false,
                    requiredTiles = 2,
                    availableTiles = 0,
                    isCoverageKnown = true,
                    selectedAvailableTiles = 0,
                    selectedCoverageKnown = true,
                    fallbackAvailableTiles = 0,
                ),
            ),
        )
    }
}
