package com.glancemap.glancemapwearos.data.repository

internal fun rememberedTurnByTurnScreenOffFixedGpsIntervalSeconds(
    persistedSeconds: Int?,
    activeScreenOffSeconds: Int,
    screenOnSeconds: Int,
): Int =
    persistedSeconds
        ?.takeIf(::isFixedGpsIntervalSeconds)
        ?: activeScreenOffSeconds.takeIf(::isFixedGpsIntervalSeconds)
        ?: screenOnSeconds
            .takeIf { activeScreenOffSeconds == SettingsRepository.GPS_INTERVAL_SAME_AS_SCREEN_ON_SECONDS }
            ?.takeIf(::isFixedGpsIntervalSeconds)
        ?: SettingsRepository.DEFAULT_TURN_BY_TURN_SCREEN_OFF_FIXED_GPS_INTERVAL_SECONDS

internal fun nextTurnByTurnScreenOffGpsMode(
    selectedSeconds: Int,
    fixedSeconds: Int,
): Int =
    when (selectedSeconds) {
        SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS ->
            SettingsRepository.GPS_INTERVAL_SAME_AS_SCREEN_ON_SECONDS

        SettingsRepository.GPS_INTERVAL_SAME_AS_SCREEN_ON_SECONDS ->
            SettingsRepository.GPS_INTERVAL_ADAPTIVE_SCREEN_OFF_SECONDS

        SettingsRepository.GPS_INTERVAL_ADAPTIVE_SCREEN_OFF_SECONDS -> fixedSeconds
        else -> SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS
    }

private fun isFixedGpsIntervalSeconds(seconds: Int): Boolean = seconds in 1..60 || seconds == 90 || seconds == 120
