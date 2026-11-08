package com.glancemap.glancemapwearos.presentation.features.settings

import com.glancemap.glancemapwearos.core.maps.DemSource
import com.glancemap.glancemapwearos.presentation.features.maps.theme.DemMapReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapSettingsLiveElevationDiagnosticsTest {
    @Test
    fun terrain3dRequiresCompleteKnownDemCoverage() {
        val readiness =
            DemMapReadiness(
                isReady = true,
                hasAnyTerrain = true,
                selectedSource = DemSource.DEFAULT,
                usesFallbackTerrain = true,
                requiredTiles = 2,
                availableTiles = 2,
                isCoverageKnown = true,
                selectedAvailableTiles = 1,
                selectedCoverageKnown = true,
                fallbackAvailableTiles = 1,
            )
        assertTrue(canEnableTerrain3d(readiness))
        assertFalse(canEnableTerrain3d(readiness.copy(isReady = false, availableTiles = 1)))
        assertFalse(canEnableTerrain3d(readiness.copy(hasAnyTerrain = false, availableTiles = 0)))
        assertFalse(canEnableTerrain3d(readiness.copy(isCoverageKnown = false, isReady = false)))
    }

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

    @Test
    fun fallbackNoticeIsShownOnlyWhenLiveElevationUsesCoarserTerrain() {
        val standardSelectedDetailedUsed =
            DemMapReadiness(
                isReady = true,
                hasAnyTerrain = true,
                selectedSource = DemSource.MAPSFORGE_DEM3,
                usesFallbackTerrain = true,
                requiredTiles = 28,
                availableTiles = 28,
                isCoverageKnown = true,
                selectedAvailableTiles = 0,
                selectedCoverageKnown = true,
                fallbackAvailableTiles = 28,
            )
        assertFalse(shouldShowFallbackTerrainNotice(standardSelectedDetailedUsed))

        val detailedSelectedStandardUsed = standardSelectedDetailedUsed.copy(selectedSource = DemSource.MAPZEN_SKADI_1S)
        assertTrue(shouldShowFallbackTerrainNotice(detailedSelectedStandardUsed))
        assertFalse(shouldShowFallbackTerrainNotice(detailedSelectedStandardUsed.copy(usesFallbackTerrain = false)))
    }
}
