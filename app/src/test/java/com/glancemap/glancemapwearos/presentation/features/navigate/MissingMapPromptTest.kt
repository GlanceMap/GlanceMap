package com.glancemap.glancemapwearos.presentation.features.navigate

import com.glancemap.glancemapwearos.core.maps.GeoBounds
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class MissingMapPromptTest {
    private val location = LatLong(48.0, 7.0)
    private val localMap = GeoBounds(47.0, 49.0, 6.0, 8.0)
    private val distantMap = GeoBounds(59.0, 61.0, 23.0, 25.0)

    @Test
    fun emptyInstalledLibraryOffersDownloads() {
        assertTrue(showPrompt(emptyList()))
    }

    @Test
    fun mapsForOtherRegionsStillOfferDownloads() {
        assertTrue(showPrompt(listOf(distantMap)))
    }

    @Test
    fun coveringMapSuppressesPrompt() {
        assertFalse(showPrompt(listOf(localMap)))
    }

    @Test
    fun inactiveCoveringMapSuppressesPromptRegardlessOfListOrder() {
        assertFalse(showPrompt(listOf(distantMap, localMap)))
        assertFalse(showPrompt(listOf(localMap, distantMap)))
    }

    @Test
    fun gapBetweenTwoInstalledMapsStillOffersDownloads() {
        val westMap = GeoBounds(47.0, 49.0, 3.0, 5.0)
        val eastMap = GeoBounds(47.0, 49.0, 9.0, 11.0)
        assertTrue(showPrompt(listOf(westMap, eastMap)))
    }

    @Test
    fun initialLoadAndRefreshWaitForTheCurrentLibraryScan() {
        assertFalse(showPrompt(null))
        assertTrue(showPrompt(emptyList()))
        assertFalse(showPrompt(null))
        assertFalse(showPrompt(listOf(localMap)))
    }

    @Test
    fun unreadableCoverageSuppressesPromptEvenWhenOtherMapsAreDistant() {
        assertFalse(showPrompt(listOf(null)))
        assertFalse(showPrompt(listOf(distantMap, null)))
    }

    @Test
    fun missingOrStaleLocationDoesNotOfferDownloads() {
        assertFalse(
            shouldShowMissingMapPrompt(null, emptyList(), disabled = false, dismissed = false, canShow = true),
        )
    }

    @Test
    fun allMapEdgesAndCornersCountAsCovered() {
        listOf(
            LatLong(47.0, 7.0),
            LatLong(49.0, 7.0),
            LatLong(48.0, 6.0),
            LatLong(48.0, 8.0),
            LatLong(47.0, 6.0),
            LatLong(49.0, 8.0),
        ).forEach { edge ->
            assertFalse(
                shouldShowMissingMapPrompt(edge, listOf(localMap), disabled = false, dismissed = false, canShow = true),
            )
        }
    }

    @Test
    fun neverAgainOrSessionDismissalSuppressesPrompt() {
        assertFalse(showPrompt(emptyList(), disabled = true))
        assertFalse(showPrompt(emptyList(), dismissed = true))
    }

    @Test
    fun backgroundOfflineOrBusyScreenDefersPromptWithoutDismissingIt() {
        assertFalse(showPrompt(emptyList(), canShow = false))
        assertTrue(showPrompt(emptyList(), canShow = true))
    }

    private fun showPrompt(
        bounds: List<GeoBounds?>?,
        disabled: Boolean = false,
        dismissed: Boolean = false,
        canShow: Boolean = true,
    ) = shouldShowMissingMapPrompt(location, bounds, disabled, dismissed, canShow)
}
