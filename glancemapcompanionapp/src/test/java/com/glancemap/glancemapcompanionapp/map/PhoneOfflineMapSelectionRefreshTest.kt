package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File

class PhoneOfflineMapSelectionRefreshTest {
    @Test
    fun updatedBaseRevisionPreservesFollowPresentationAndPendingCommands() {
        val previous = map("alps.map", "old")
        val updated = map("alps.map", "new")
        val comparison = PhoneMapComparisonLayer.Online(PhoneOnlineMapSource.OPEN_STREET_MAP)
        val selected =
            PhoneMapUiState(
                source = PhoneMapSource.Offline(previous),
                mapMode = PhoneMapMode().detachAfterManualRotation(123f),
                toolLauncherExpanded = true,
            ).selectComparisonLayer(comparison)
                .setComparisonTransparency(75f)
                .requestZoom(1)

        val refreshed = selected.refreshOfflineMapSelections(listOf(updated))

        assertEquals(PhoneMapSource.Offline(updated), refreshed.source)
        assertEquals(selected.comparison.layer, refreshed.comparison.layer)
        assertEquals(selected.comparison.transparencyPercent, refreshed.comparison.transparencyPercent)
        assertEquals(selected.mapMode, refreshed.mapMode)
        assertEquals(selected.cameraCommand, refreshed.cameraCommand)
        assertEquals(selected.nextCameraCommandId, refreshed.nextCameraCommandId)
        assertEquals(selected.toolPanel, refreshed.toolPanel)
        assertEquals(selected.toolLauncherExpanded, refreshed.toolLauncherExpanded)
        assertEquals(selected.contentVisibility, refreshed.contentVisibility)
    }

    @Test
    fun updatedComparisonRevisionRejectsTheOldRendererFailure() {
        val previous = map("alps.map", "old")
        val updated = map("alps.map", "new")
        val oldLayer = PhoneMapComparisonLayer.Offline(previous)
        val selected = PhoneMapUiState().selectComparisonLayer(oldLayer).setComparisonTransparency(25f)

        val refreshed = selected.refreshOfflineMapSelections(listOf(updated))

        assertEquals(PhoneMapSource.Online, refreshed.source)
        assertEquals(PhoneMapComparisonLayer.Offline(updated), refreshed.comparison.layer)
        assertEquals(25f, refreshed.comparison.transparencyPercent, 0f)
        assertSame(
            refreshed,
            refreshed.clearFailedOfflineComparison(oldLayer, selected.comparison.selectionGeneration),
        )
    }

    @Test
    fun unchangedDiscoveryDoesNotRecreateTheComparison() {
        val selected =
            PhoneMapUiState()
                .selectComparisonLayer(PhoneMapComparisonLayer.Offline(map("alps.map", "same")))

        assertSame(selected, selected.refreshOfflineMapSelections(listOf(map("alps.map", "same"))))
    }

    @Test
    fun matchingNamesInDifferentDirectoriesDoNotRebindTheSelection() {
        val oldLayer = PhoneMapComparisonLayer.Offline(map("/old/alps.map", "old"))
        val selected = PhoneMapUiState().selectComparisonLayer(oldLayer)

        val refreshed = selected.refreshOfflineMapSelections(listOf(map("/other/alps.map", "new")))

        assertNull(refreshed.comparison.layer)
        assertEquals(PhoneMapSource.Online, refreshed.source)
    }

    @Test
    fun pendingMapSelectionReceivesTheLatestRevisionAtItsOriginalPath() {
        val previous = map("/maps/alps.map", "old")
        val updated = map("/maps/alps.map", "new")

        assertSame(updated, previous.refreshedFrom(listOf(map("/other/alps.map", "other"), updated)))
        assertSame(previous, previous.refreshedFrom(emptyList()))
    }

    @Test
    fun validationOfAnObsoleteRevisionCannotSelectItAfterRediscovery() {
        val previous = map("alps.map", "old")
        val updated = map("alps.map", "new")
        val requested = PhoneMapUiState().nextComparisonSelectionRequest()
        val refreshed = requested.refreshOfflineMapSelections(listOf(updated))

        val completion =
            refreshed.completeOfflineComparisonSelection(
                requestGeneration = requested.comparison.validationRequestGeneration,
                layer = PhoneMapComparisonLayer.Offline(previous),
                validationError = null,
                offlineMaps = listOf(updated),
            )

        assertSame(refreshed, completion.state)
        assertNull(completion.validationError)
    }

    private fun map(
        path: String,
        revision: String,
    ) = PhoneOfflineMap(File(path), contentRevision = revision)
}
