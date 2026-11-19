package com.glancemap.glancemapwearos.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseDefaultsTest {
    @Test
    fun autoPauseDefaultsToOff() {
        assertEquals(SettingsRepository.RECORDING_AUTO_PAUSE_OFF, SettingsRepository.DEFAULT_RECORDING_AUTO_PAUSE_MODE)
    }

    @Test
    fun stayDefaultsToOn() {
        assertTrue(SettingsRepository.DEFAULT_KEEP_APP_OPEN)
    }
}
