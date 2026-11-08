package com.glancemap.glancemapwearos.presentation.features.maps

import com.glancemap.glancemapwearos.core.maps.DemCoverageSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TerrainMapSelectionTest {
    @Test
    fun missingDemTurnsOffEachEnabledTerrainFeature() =
        runBlocking {
            val events = mutableListOf<String>()
            val changed =
                disableTerrainFeatures(
                    TerrainFeatureState(terrain3d = true, hill = true, slope = true),
                    disable3d = { events += "3d" },
                    disableHill = { events += "hill" },
                    disableSlope = { events += "slope" },
                )
            assertEquals(true, changed)
            assertEquals(listOf("3d", "hill", "slope"), events)
        }

    @Test
    fun missingDemDoesNotWriteDisabledFeatures() =
        runBlocking {
            val events = mutableListOf<String>()
            val changed =
                disableTerrainFeatures(
                    TerrainFeatureState(terrain3d = false, hill = true, slope = false),
                    disable3d = { events += "3d" },
                    disableHill = { events += "hill" },
                    disableSlope = { events += "slope" },
                )
            assertEquals(true, changed)
            assertEquals(listOf("hill"), events)
        }

    @Test
    fun switchingToMapWithoutDemDisablesFeaturesBeforeSelectingAndNotifies() =
        runBlocking {
            val events = mutableListOf<String>()
            selectMapWithTerrainGuard(
                path = "new.map",
                coverage = { DemCoverageSummary(2, 0, true) },
                disableFeatures = {
                    events += "disable"
                    true
                },
                select = { events += "select:$it" },
                notifyMissing = { events += "notify:$it" },
            )
            assertEquals(listOf("disable", "select:new.map", "notify:new.map"), events)
        }

    @Test
    fun availablePartialOrUnknownCoverageDoesNotDisableFeatures() =
        runBlocking {
            val coverages =
                listOf(
                    DemCoverageSummary(2, 2, true),
                    DemCoverageSummary(2, 1, true),
                    DemCoverageSummary(0, 0, false),
                )
            for (coverage in coverages) {
                val events = mutableListOf<String>()
                selectMapWithTerrainGuard(
                    path = "map",
                    coverage = { coverage },
                    disableFeatures = {
                        events += "disable"
                        true
                    },
                    select = { events += "select:$it" },
                    notifyMissing = { events += "notify" },
                )
                assertEquals(listOf("select:map"), events)
            }
        }

    @Test
    fun deselectingMapDoesNotDisableTerrainSettings() =
        runBlocking {
            val events = mutableListOf<String>()
            selectMapWithTerrainGuard(
                path = null,
                coverage = { error("No map should be checked") },
                disableFeatures = {
                    events += "disable"
                    true
                },
                select = { events += "select:$it" },
                notifyMissing = { events += "notify" },
            )
            assertEquals(listOf("select:null"), events)
        }

    @Test
    fun switchingOffAllFeaturesDoesNotShowWarningWhenTheyWereAlreadyOff() =
        runBlocking {
            val events = mutableListOf<String>()
            selectMapWithTerrainGuard(
                path = "map",
                coverage = { DemCoverageSummary(1, 0, true) },
                disableFeatures = { false },
                select = { events += "select:$it" },
                notifyMissing = { events += "notify" },
            )
            assertEquals(listOf("select:map"), events)
        }

    @Test
    fun cancelledCoverageCheckCannotDisableOrSelectAnOlderMap() =
        runBlocking {
            val events = mutableListOf<String>()
            val started = CompletableDeferred<Unit>()
            val oldSelection =
                launch {
                    selectMapWithTerrainGuard(
                        path = "old.map",
                        coverage = {
                            started.complete(Unit)
                            CompletableDeferred<DemCoverageSummary>().await()
                        },
                        disableFeatures = {
                            events += "disable"
                            true
                        },
                        select = { events += "select:$it" },
                        notifyMissing = { events += "notify" },
                    )
                }
            started.await()
            oldSelection.cancelAndJoin()
            assertEquals(emptyList<String>(), events)
        }
}
