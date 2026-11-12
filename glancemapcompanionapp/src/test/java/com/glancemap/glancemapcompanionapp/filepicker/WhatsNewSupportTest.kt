package com.glancemap.glancemapcompanionapp.filepicker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewSupportTest {
    @Test
    fun `automatically shows whats new only for a newer version code`() {
        assertTrue(
            shouldAutoShowWhatsNew(
                lastShownVersionCode = 2038L,
                currentVersionCode = 2039L,
            ),
        )
        assertFalse(
            shouldAutoShowWhatsNew(
                lastShownVersionCode = 2039L,
                currentVersionCode = 2039L,
            ),
        )
        assertFalse(
            shouldAutoShowWhatsNew(
                lastShownVersionCode = 2040L,
                currentVersionCode = 2039L,
            ),
        )
    }

    @Test
    fun `play store fallback targets the companion app listing`() {
        assertEquals(
            "https://play.google.com/store/apps/details?id=com.glancemap.glancemapwearos",
            companionPlayStoreWebUrl("com.glancemap.glancemapwearos"),
        )
    }
}
