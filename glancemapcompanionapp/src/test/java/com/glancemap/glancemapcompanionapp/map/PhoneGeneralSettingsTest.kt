package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneGeneralSettingsTest {
    @Test
    fun defaultsMatchWatchMetricDefault() {
        assertTrue(PhoneGeneralSettings().isMetric)
    }

    @Test
    fun unitsCanSwitchToImperial() {
        assertFalse(PhoneGeneralSettings().copy(isMetric = false).isMetric)
    }

    @Test
    fun profileAndWeightsUseWatchCompatibleDefaultsAndBounds() {
        val defaults = PhoneGeneralSettings()
        assertEquals(PhoneActivityProfile.HIKE, defaults.activityProfile)
        assertEquals(75f, defaults.userWeightKg, 0f)
        assertEquals(0f, defaults.backpackWeightKg, 0f)
        assertEquals(12f, defaults.bikeWeightKg, 0f)

        val normalized =
            defaults
                .copy(
                    userWeightKg = 1f,
                    backpackWeightKg = 100f,
                    bikeWeightKg = -1f,
                ).normalized()
        assertEquals(MIN_PHONE_USER_WEIGHT_KG, normalized.userWeightKg, 0f)
        assertEquals(MAX_PHONE_BACKPACK_WEIGHT_KG, normalized.backpackWeightKg, 0f)
        assertEquals(MIN_PHONE_BIKE_WEIGHT_KG, normalized.bikeWeightKg, 0f)
    }
}
