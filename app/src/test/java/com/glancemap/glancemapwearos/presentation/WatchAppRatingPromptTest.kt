package com.glancemap.glancemapwearos.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchAppRatingPromptTest {
    @Test
    fun promptShowsOnceForEachNewerVersionWhenNotesAreAvailable() {
        assertTrue(shouldShowWatchAppRatingPrompt(0, 1041, hasReleaseNotes = true))
        assertTrue(shouldShowWatchAppRatingPrompt(1040, 1041, hasReleaseNotes = true))
        assertFalse(shouldShowWatchAppRatingPrompt(1041, 1041, hasReleaseNotes = true))
        assertFalse(shouldShowWatchAppRatingPrompt(1042, 1041, hasReleaseNotes = true))
        assertFalse(shouldShowWatchAppRatingPrompt(1040, 1041, hasReleaseNotes = false))
    }

    @Test
    fun playStoreLinkTargetsTheWearAppListing() {
        assertEquals(
            "https://play.google.com/store/apps/details?id=com.glancemap.glancemapwearos",
            WATCH_APP_PLAY_STORE_URL,
        )
        assertEquals("com.android.vending", WATCH_APP_PLAY_STORE_PACKAGE_NAME)
    }
}
