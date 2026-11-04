package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMapGpxEnabledStateTest {
    @Test
    fun aNewSessionRestoresTheTracksTurnedOnAndOffInTheLastOne() {
        val loaded = listOf(item("a"), item("b"), item("c"))

        val restored = mergePhoneMapGpxItems(emptyList(), loaded, null, savedEnabledIds = setOf("b"))

        assertEquals(listOf(false, true, false), restored.map(PhoneMapGpxItem::enabled))
    }

    @Test
    fun theLibrarySelectionTurnsOnOnceSoTurningItOffSticks() {
        val firstLaunch = PhoneMapGpxEnabledState()
        assertEquals("a", firstLaunch.newSelection("a"))

        // The user turned "a" off; the next launch still has "a" selected in the library.
        val afterTurningOff = firstLaunch.withItems(listOf(item("a", enabled = false)), appliedSelectionId = "a")
        assertNull(afterTurningOff.newSelection("a"))
        val nextLaunch =
            mergePhoneMapGpxItems(
                emptyList(),
                listOf(item("a")),
                afterTurningOff.newSelection("a"),
                afterTurningOff.enabledIds,
            )
        assertFalse(nextLaunch.single().enabled)

        // Selecting another route in the library is a new selection again.
        assertEquals("b", afterTurningOff.newSelection("b"))
    }

    @Test
    fun savingKeepsChoicesOfTracksThatDidNotLoadThisTime() {
        val saved = PhoneMapGpxEnabledState(enabledIds = setOf("folder-track", "a"))

        val next = saved.withItems(listOf(item("a", enabled = false), item("b", enabled = true)))

        assertEquals(setOf("folder-track", "b"), next.enabledIds)
    }

    @Test
    fun beforeTheFirstLoadATrackFollowsItsSavedChoiceInsteadOfFlashingOn() {
        val loading = PhoneMapGpxUiState(isLoading = true, savedEnabledIds = setOf("on"))

        assertTrue(loading.isTrackEnabled("on"))
        assertFalse(loading.isTrackEnabled("off"))
        assertFalse(PhoneMapGpxUiState().isTrackEnabled("never-saved"))
        // Once loaded, a hike route that is not in the GPX list is still shown.
        assertTrue(PhoneMapGpxUiState(hasLoaded = true).isTrackEnabled("not-a-gpx-item"))
    }

    private fun item(
        id: String,
        enabled: Boolean = false,
    ) = PhoneMapGpxItem(id = id, displayName = id, track = PhoneMapGpxTrack(id, emptyList()), enabled = enabled)
}
