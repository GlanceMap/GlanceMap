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
}
