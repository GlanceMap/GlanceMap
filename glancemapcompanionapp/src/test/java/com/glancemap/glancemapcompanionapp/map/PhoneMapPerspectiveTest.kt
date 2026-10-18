package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhoneMapPerspectiveTest {
    private val offline = PhoneMapSource.Offline(PhoneOfflineMap(File("fixture.map")))
    private val onlineComparison = PhoneMapComparisonLayer.Online(PhoneOnlineMapSource.ESRI_WORLD_IMAGERY)
    private val offlineComparison = PhoneMapComparisonLayer.Offline(offline.map)

    @Test
    fun perspectiveIsOptInAndUses45DegreesForOnlineMaps() {
        assertFalse(PhoneMapSettings().tiltedViewEnabled)
        assertTrue(phoneMapSupportsTilt(PhoneMapSource.Online, null))
        assertEquals(0.0, phoneMapTiltDegrees(false, PhoneMapSource.Online, null), 0.0)
        assertEquals(45.0, phoneMapTiltDegrees(true, PhoneMapSource.Online, null), 0.0)
    }

    @Test
    fun bothOnlineComparisonMapsUseTheSamePerspective() {
        assertTrue(phoneMapSupportsTilt(PhoneMapSource.Online, onlineComparison))
        assertEquals(45.0, phoneMapTiltDegrees(true, PhoneMapSource.Online, onlineComparison), 0.0)
        assertEquals(0.0, phoneMapTiltDegrees(false, PhoneMapSource.Online, onlineComparison), 0.0)
    }

    @Test
    fun offlineSelectionsStayFlatWithoutDiscardingThePreference() {
        val settings = PhoneMapSettings(tiltedViewEnabled = true)
        val combinations =
            listOf(
                offline to null,
                offline to onlineComparison,
                offline to offlineComparison,
                PhoneMapSource.Online to offlineComparison,
            )
        combinations.forEach { (base, comparison) ->
            assertFalse(phoneMapSupportsTilt(base, comparison))
            assertEquals(0.0, phoneMapTiltDegrees(settings.tiltedViewEnabled, base, comparison), 0.0)
        }
        assertTrue(settings.tiltedViewEnabled)
    }
}
