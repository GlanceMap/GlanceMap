package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.poi.PoiType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMapPoiSettingsTest {
    @Test
    fun defaultsMirrorWatchPoiAppearanceAndPopupDefaults() {
        val settings = PhoneMapPoiSettings()

        assertEquals(PhoneMapPoiIconSize.MEDIUM, settings.iconSize)
        assertEquals(PhoneMapPoiMarkerStyle.BADGE, settings.markerStyle)
        assertTrue(settings.linkGpxWaypointPoiFolders)
        assertTrue(settings.popupAutoCloseEnabled)
        assertEquals(5, settings.popupTimeoutSeconds)
    }

    @Test
    fun `linking gpx waypoint folders can be disabled without changing appearance`() {
        val disabled = PhoneMapPoiSettings().copy(linkGpxWaypointPoiFolders = false)

        assertTrue(!disabled.linkGpxWaypointPoiFolders)
        assertEquals(PhoneMapPoiIconSize.MEDIUM, disabled.iconSize)
        assertEquals(PhoneMapPoiMarkerStyle.BADGE, disabled.markerStyle)
    }

    @Test
    fun normalizationKeepsPopupTimeoutInsideWatchRange() {
        assertEquals(
            MIN_PHONE_MAP_POI_POPUP_TIMEOUT_SECONDS,
            PhoneMapPoiSettings(popupTimeoutSeconds = -1).normalized().popupTimeoutSeconds,
        )
        assertEquals(
            MAX_PHONE_MAP_POI_POPUP_TIMEOUT_SECONDS,
            PhoneMapPoiSettings(popupTimeoutSeconds = 100).normalized().popupTimeoutSeconds,
        )
    }

    @Test
    fun markerImageIdsChangeWithStyleAndSize() {
        val badge =
            PhoneMapPoiSettings(
                iconSize = PhoneMapPoiIconSize.SMALL,
                markerStyle = PhoneMapPoiMarkerStyle.BADGE,
            )
        val themeIcon = badge.copy(markerStyle = PhoneMapPoiMarkerStyle.THEME_ICON)

        assertEquals("companion-poi-badge-small-WATER", PoiType.WATER.phoneMapPoiMarkerImageId(badge))
        assertTrue(PoiType.WATER.phoneMapPoiMarkerImageId(badge) != PoiType.WATER.phoneMapPoiMarkerImageId(themeIcon))
    }

    @Test
    fun markerAppearanceKeyIgnoresPopupOnlyChanges() {
        val appearance = PhoneMapPoiSettings()
        val popupChanged = appearance.copy(popupAutoCloseEnabled = false, popupTimeoutSeconds = 20)

        assertEquals(appearance.markerAppearanceKey, popupChanged.markerAppearanceKey)
    }
}
