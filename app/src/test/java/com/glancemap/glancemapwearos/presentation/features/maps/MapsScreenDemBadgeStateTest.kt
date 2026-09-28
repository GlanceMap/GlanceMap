package com.glancemap.glancemapwearos.presentation.features.maps

import org.junit.Assert.assertEquals
import org.junit.Test

class MapsScreenDemBadgeStateTest {
    @Test
    fun badgeShowsSelectedSourceCoverageState() {
        assertEquals(
            DemMapBadgeState.READY,
            demMapBadgeState(
                mapFile =
                    MapFileState(
                        name = "map",
                        path = "map",
                        demCoverageKnown = true,
                        demRequiredTiles = 2,
                        demAvailableTiles = 2,
                        demReady = true,
                    ),
                isDemDownloadingForThisMap = false,
            ),
        )
        assertEquals(
            DemMapBadgeState.PARTIAL,
            demMapBadgeState(
                mapFile =
                    MapFileState(
                        name = "map",
                        path = "map",
                        demCoverageKnown = true,
                        demRequiredTiles = 2,
                        demAvailableTiles = 1,
                    ),
                isDemDownloadingForThisMap = false,
            ),
        )
        assertEquals(
            DemMapBadgeState.UNAVAILABLE,
            demMapBadgeState(
                mapFile =
                    MapFileState(
                        name = "map",
                        path = "map",
                        demCoverageKnown = true,
                        demRequiredTiles = 2,
                    ),
                isDemDownloadingForThisMap = false,
            ),
        )
    }

    @Test
    fun downloadingOverridesCurrentCoverageState() {
        assertEquals(
            DemMapBadgeState.DOWNLOADING,
            demMapBadgeState(
                mapFile =
                    MapFileState(
                        name = "map",
                        path = "map",
                        demReady = true,
                    ),
                isDemDownloadingForThisMap = true,
            ),
        )
    }

    @Test
    fun onlyCompletedBadgeIsDisabled() {
        assertEquals(
            false,
            isDemMapBadgeEnabled(
                badgeState = DemMapBadgeState.READY,
                isDemDownloadRunning = false,
            ),
        )
        assertEquals(
            true,
            isDemMapBadgeEnabled(
                badgeState = DemMapBadgeState.PARTIAL,
                isDemDownloadRunning = false,
            ),
        )
        assertEquals(
            false,
            isDemMapBadgeEnabled(
                badgeState = DemMapBadgeState.UNAVAILABLE,
                isDemDownloadRunning = true,
            ),
        )
        assertEquals(
            true,
            isDemMapBadgeEnabled(
                badgeState = DemMapBadgeState.DOWNLOADING,
                isDemDownloadRunning = true,
            ),
        )
    }
}
