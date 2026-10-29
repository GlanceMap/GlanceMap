package com.glancemap.glancemapwearos.presentation.features.settings

import com.glancemap.glancemapwearos.domain.sensors.CompassProviderType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompassSettingsScreenSupportTest {
    @Test
    fun googleFusedUsesAutomaticNorthReferenceInformation() {
        assertTrue(usesAutomaticGoogleNorthReference(CompassProviderType.GOOGLE_FUSED))
    }

    @Test
    fun customSensorsKeepSelectableNorthReference() {
        assertFalse(usesAutomaticGoogleNorthReference(CompassProviderType.SENSOR_MANAGER))
    }
}
