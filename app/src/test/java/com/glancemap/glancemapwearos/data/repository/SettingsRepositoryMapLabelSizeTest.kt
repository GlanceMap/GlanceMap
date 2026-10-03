package com.glancemap.glancemapwearos.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsRepositoryMapLabelSizeTest {
    @Test
    fun `missing or invalid map label size uses default`() {
        assertEquals(SettingsRepository.MAP_LABEL_SIZE_DEFAULT, sanitizeMapLabelSize(null))
        assertEquals(SettingsRepository.MAP_LABEL_SIZE_DEFAULT, sanitizeMapLabelSize("unexpected"))
    }

    @Test
    fun `supported map label sizes are retained`() {
        assertEquals(
            SettingsRepository.MAP_LABEL_SIZE_SMALL,
            sanitizeMapLabelSize(SettingsRepository.MAP_LABEL_SIZE_SMALL),
        )
        assertEquals(
            SettingsRepository.MAP_LABEL_SIZE_DEFAULT,
            sanitizeMapLabelSize(SettingsRepository.MAP_LABEL_SIZE_DEFAULT),
        )
        assertEquals(
            SettingsRepository.MAP_LABEL_SIZE_LARGE,
            sanitizeMapLabelSize(SettingsRepository.MAP_LABEL_SIZE_LARGE),
        )
        assertEquals(
            SettingsRepository.MAP_LABEL_SIZE_EXTRA_LARGE,
            sanitizeMapLabelSize(SettingsRepository.MAP_LABEL_SIZE_EXTRA_LARGE),
        )
    }
}
