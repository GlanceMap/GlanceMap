package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneThemeStyleTextTest {
    @Test
    fun translatesCommonStyleLabelsButKeepsUnknownProviderNames() {
        assertEquals(R.string.map_theme_style_hiking, phoneThemeStyleResource("Hiking"))
        assertEquals(R.string.map_theme_style_cycling, phoneThemeStyleResource("Cycling"))
        assertNull(phoneThemeStyleResource("OSMARender"))
    }
}
