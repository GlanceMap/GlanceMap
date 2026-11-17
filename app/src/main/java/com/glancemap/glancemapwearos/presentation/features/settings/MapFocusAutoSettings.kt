package com.glancemap.glancemapwearos.presentation.features.settings

import com.glancemap.glancemapwearos.data.repository.SettingsRepository

internal data class MapFocusAutoSettings(
    val enabled: Boolean = false,
    val delaySeconds: Int = SettingsRepository.MAP_FOCUS_AUTO_DELAY_DEFAULT_SECONDS,
    val onEnabledChange: (Boolean) -> Unit = {},
    val onDelayChange: (Int) -> Unit = {},
)
