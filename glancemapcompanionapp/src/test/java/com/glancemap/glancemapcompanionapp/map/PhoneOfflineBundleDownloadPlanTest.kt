package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.oam.OamDownloadCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneOfflineBundleDownloadPlanTest {
    private val area = OamDownloadCatalog.areas.first()

    @Test
    fun refreshForcesStayAttachedToTheSelectionWhenThePlanIsResumed() {
        val selection = PhoneOfflineBundleSelection(area = area)
        val forces =
            PhoneOfflineBundleRefreshForces(
                forceMap = true,
                forceDemTileIds = setOf("N45E006"),
            )
        val operation =
            PhoneOfflineBundleOperation(
                selections = listOf(selection),
                refreshForces = listOf(forces),
                status = PhoneOfflineBundleOperationStatus.PAUSED,
            )

        assertEquals(forces, operation.forcesFor(0))
        assertTrue(operation.canResumeSameOperation(listOf(selection), listOf(forces)))
        assertFalse(
            operation.canResumeSameOperation(
                selections = listOf(selection),
                refreshForces = listOf(PhoneOfflineBundleRefreshForces()),
            ),
        )
    }

    @Test
    fun missingForceEntriesUseSafeDefaultsForOlderPersistedPlans() {
        val operation =
            PhoneOfflineBundleOperation(
                selections = listOf(PhoneOfflineBundleSelection(area = area)),
            )

        assertEquals(PhoneOfflineBundleRefreshForces(), operation.forcesFor(0))
    }

    @Test
    fun onlyResumableTerminalDownloadsOfferCancellation() {
        val progress = PhoneOfflineBundleProgress(phase = PhoneOfflineBundlePhase.DOWNLOADING_MAP)

        assertTrue(PhoneOfflineBundleDownloadState.Paused(area.id, progress).canCancelSavedOperation())
        assertTrue(PhoneOfflineBundleDownloadState.Stopped(area.id, progress).canCancelSavedOperation())
        assertTrue(PhoneOfflineBundleDownloadState.Failed(PhoneOfflineBundleFailure.NETWORK).canCancelSavedOperation())
        assertFalse(PhoneOfflineBundleDownloadState.Downloading(area.id, progress).canCancelSavedOperation())
        assertFalse(PhoneOfflineBundleDownloadState.Cancelled.canCancelSavedOperation())
    }

    @Test
    fun changingPausedComponentsUpdatesOnlyTheCurrentAreaAndPreservesProgress() {
        val otherArea = OamDownloadCatalog.areas.first { it.id != area.id }
        val first = PhoneOfflineBundleSelection(area = otherArea)
        val current = PhoneOfflineBundleSelection(area = area)
        val forces = PhoneOfflineBundleRefreshForces(forcePoi = true)
        val operation =
            PhoneOfflineBundleOperation(
                selections = listOf(first, current),
                refreshForces = listOf(PhoneOfflineBundleRefreshForces(), forces),
                nextSelectionIndex = 1,
                status = PhoneOfflineBundleOperationStatus.PAUSED,
            )
        val changed = current.copy(includeMap = false, includePoi = false)
        val updated = operation.withUpdatedCurrentSelection(changed)
        assertEquals(first, updated.selections[0])
        assertEquals(changed, updated.selections[1])
        assertEquals(1, updated.nextSelectionIndex)
        assertEquals(forces, updated.forcesFor(1))
        assertEquals(PhoneOfflineBundleOperationStatus.PAUSED, updated.status)
        assertEquals(operation, operation.withUpdatedCurrentSelection(changed.copy(area = otherArea)))
        assertEquals(
            operation.copy(status = PhoneOfflineBundleOperationStatus.RUNNING),
            operation.copy(status = PhoneOfflineBundleOperationStatus.RUNNING).withUpdatedCurrentSelection(changed),
        )
    }

    @Test
    fun optionalMapAndPoiAreNotReportedMissingWhenNotSelected() {
        val health =
            phoneOfflineBundleHealth(
                hasMap = false,
                hasPoi = true,
                expectsMap = false,
                expectsPoi = true,
                expectsRefugesInfo = false,
                hasRefugesInfo = false,
                expectedRoutingFileNames = emptyList(),
                downloadedRoutingFileNames = emptyList(),
                expectedDemTileIds = emptyList(),
                downloadedDemTileIds = emptyList(),
            )
        assertEquals(PhoneOfflineBundleStatus.COMPLETE, health.status)
        assertEquals(listOf("poi"), health.expectedFileNames)
        assertTrue(health.missingFileNames.isEmpty())
    }
}
