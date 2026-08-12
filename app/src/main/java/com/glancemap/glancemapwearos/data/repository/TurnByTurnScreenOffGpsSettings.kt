package com.glancemap.glancemapwearos.data.repository

internal fun rememberedFixedGpsIntervalSeconds(
    persistedSeconds: Int?,
    activeSeconds: Int,
    defaultSeconds: Int,
    sameAsScreenOnSeconds: Int? = null,
): Int =
    persistedSeconds
        ?.takeIf(::isFixedGpsIntervalSeconds)
        ?: activeSeconds.takeIf(::isFixedGpsIntervalSeconds)
        ?: sameAsScreenOnSeconds
            ?.takeIf { activeSeconds == SettingsRepository.GPS_INTERVAL_SAME_AS_SCREEN_ON_SECONDS }
            ?.takeIf(::isFixedGpsIntervalSeconds)
        ?: defaultSeconds

internal fun rememberedTurnByTurnScreenOffFixedGpsIntervalSeconds(
    persistedSeconds: Int?,
    activeScreenOffSeconds: Int,
    screenOnSeconds: Int,
): Int =
    rememberedFixedGpsIntervalSeconds(
        persistedSeconds = persistedSeconds,
        activeSeconds = activeScreenOffSeconds,
        defaultSeconds = SettingsRepository.DEFAULT_TURN_BY_TURN_SCREEN_OFF_FIXED_GPS_INTERVAL_SECONDS,
        sameAsScreenOnSeconds = screenOnSeconds,
    )

internal fun nextGpsTimingMode(
    selectedSeconds: Int,
    fixedSeconds: Int,
    modes: List<Int>,
): Int {
    val index = modes.indexOf(selectedSeconds)
    return when {
        index < 0 -> modes.first()
        index == modes.lastIndex -> fixedSeconds
        else -> modes[index + 1]
    }
}

internal fun nextTurnByTurnScreenOffGpsMode(
    selectedSeconds: Int,
    fixedSeconds: Int,
): Int =
    nextGpsTimingMode(
        selectedSeconds = selectedSeconds,
        fixedSeconds = fixedSeconds,
        modes =
            listOf(
                SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS,
                SettingsRepository.GPS_INTERVAL_SAME_AS_SCREEN_ON_SECONDS,
                SettingsRepository.GPS_INTERVAL_ADAPTIVE_SCREEN_OFF_SECONDS,
            ),
    )

private fun isFixedGpsIntervalSeconds(seconds: Int): Boolean = seconds in 1..60 || seconds == 90 || seconds == 120
