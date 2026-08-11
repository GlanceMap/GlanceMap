package com.glancemap.glancemapwearos.data.repository

internal data class GpsUsageTiming(
    val recordingScreenOnSeconds: Int,
    val recordingScreenOffSeconds: Int,
    val turnByTurnScreenOnSeconds: Int,
    val turnByTurnScreenOffSeconds: Int,
)

internal fun gpsUsageTiming(
    usageProfile: String,
    activityProfile: String,
): GpsUsageTiming {
    val bike = activityProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE
    return when (usageProfile) {
        SettingsRepository.GPS_USAGE_PROFILE_BEST_TRACE ->
            GpsUsageTiming(
                recordingScreenOnSeconds = 1,
                recordingScreenOffSeconds = 3,
                turnByTurnScreenOnSeconds = 1,
                turnByTurnScreenOffSeconds = SettingsRepository.GPS_INTERVAL_ADAPTIVE_SCREEN_OFF_SECONDS,
            )

        SettingsRepository.GPS_USAGE_PROFILE_LONG_BATTERY ->
            GpsUsageTiming(
                recordingScreenOnSeconds = if (bike) 3 else 5,
                recordingScreenOffSeconds = if (bike) 10 else 20,
                turnByTurnScreenOnSeconds = if (bike) 3 else 5,
                turnByTurnScreenOffSeconds = SettingsRepository.GPS_INTERVAL_ADAPTIVE_SCREEN_OFF_SECONDS,
            )

        else ->
            GpsUsageTiming(
                recordingScreenOnSeconds =
                    if (bike) {
                        SettingsRepository.DEFAULT_BIKE_RECORDING_SAMPLE_INTERVAL_SECONDS
                    } else {
                        SettingsRepository.DEFAULT_RECORDING_SAMPLE_INTERVAL_SECONDS
                    },
                recordingScreenOffSeconds =
                    if (bike) {
                        SettingsRepository.DEFAULT_BIKE_RECORDING_SCREEN_OFF_SAMPLE_INTERVAL_SECONDS
                    } else {
                        SettingsRepository.DEFAULT_RECORDING_SCREEN_OFF_SAMPLE_INTERVAL_SECONDS
                    },
                turnByTurnScreenOnSeconds =
                    if (bike) {
                        SettingsRepository.DEFAULT_BIKE_TURN_BY_TURN_GPS_INTERVAL_SECONDS
                    } else {
                        SettingsRepository.DEFAULT_TURN_BY_TURN_GPS_INTERVAL_SECONDS
                    },
                turnByTurnScreenOffSeconds = SettingsRepository.DEFAULT_TURN_BY_TURN_SCREEN_OFF_GPS_INTERVAL_SECONDS,
            )
    }
}

internal fun inferGpsUsageProfile(
    activityProfile: String,
    timing: GpsUsageTiming,
): String =
    listOf(
        SettingsRepository.GPS_USAGE_PROFILE_BEST_TRACE,
        SettingsRepository.GPS_USAGE_PROFILE_BALANCED,
        SettingsRepository.GPS_USAGE_PROFILE_LONG_BATTERY,
    ).firstOrNull { profile -> gpsUsageTiming(profile, activityProfile) == timing }
        ?: SettingsRepository.GPS_USAGE_PROFILE_CUSTOM
