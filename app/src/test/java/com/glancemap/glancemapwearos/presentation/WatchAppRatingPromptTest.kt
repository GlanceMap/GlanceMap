package com.glancemap.glancemapwearos.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchAppRatingPromptTest {
    @Test
    fun promptOnlyShowsBeforeItHasBeenShown() {
        assertTrue(shouldShowWatchAppRatingPrompt(promptShown = false))
        assertFalse(shouldShowWatchAppRatingPrompt(promptShown = true))
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
