package com.glancemap.glancemapwearos.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapFocusControlTest {
    @Test
    fun missingOrEmptyPreferencesHideEveryOptionalControl() {
        assertTrue(decodeMapFocusControls(null).isEmpty())
        assertTrue(decodeMapFocusControls(emptySet()).isEmpty())
    }

    @Test
    fun persistedSelectionKeepsIndependentControls() {
        assertEquals(
            setOf(MapFocusControl.ZOOM_PLUS, MapFocusControl.TIME),
            decodeMapFocusControls(setOf("ZOOM_PLUS", "TIME")),
        )
    }

    @Test
    fun unknownPreferenceValuesAreIgnored() {
        assertEquals(
            setOf(MapFocusControl.NAV_MODE_BUTTON),
            decodeMapFocusControls(setOf("NAV_MODE_BUTTON", "unknown")),
        )
    }
}
