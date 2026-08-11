package com.glancemap.glancemapwearos.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class GpsUsageProfilesTest {
    @Test
    fun balancedUsesCurrentHikeDefaults() {
        val timing =
            gpsUsageTiming(
                usageProfile = SettingsRepository.GPS_USAGE_PROFILE_BALANCED,
                activityProfile = SettingsRepository.ACTIVITY_PROFILE_HIKE,
            )

        assertEquals(3, timing.recordingScreenOnSeconds)
        assertEquals(10, timing.recordingScreenOffSeconds)
        assertEquals(3, timing.turnByTurnScreenOnSeconds)
        assertEquals(SettingsRepository.GPS_INTERVAL_ADAPTIVE_SCREEN_OFF_SECONDS, timing.turnByTurnScreenOffSeconds)
    }

    @Test
    fun balancedUsesCurrentBikeDefaults() {
        val timing =
            gpsUsageTiming(
                usageProfile = SettingsRepository.GPS_USAGE_PROFILE_BALANCED,
                activityProfile = SettingsRepository.ACTIVITY_PROFILE_BIKE,
            )

        assertEquals(1, timing.recordingScreenOnSeconds)
        assertEquals(5, timing.recordingScreenOffSeconds)
        assertEquals(1, timing.turnByTurnScreenOnSeconds)
    }

    @Test
    fun bestTraceAndLongBatteryProvideDistinctTiming() {
        val best =
            gpsUsageTiming(
                usageProfile = SettingsRepository.GPS_USAGE_PROFILE_BEST_TRACE,
                activityProfile = SettingsRepository.ACTIVITY_PROFILE_HIKE,
            )
        val endurance =
            gpsUsageTiming(
                usageProfile = SettingsRepository.GPS_USAGE_PROFILE_LONG_BATTERY,
                activityProfile = SettingsRepository.ACTIVITY_PROFILE_HIKE,
            )

        assertEquals(1, best.recordingScreenOnSeconds)
        assertEquals(3, best.recordingScreenOffSeconds)
        assertEquals(5, endurance.recordingScreenOnSeconds)
        assertEquals(20, endurance.recordingScreenOffSeconds)
    }

    @Test
    fun unmatchedAdvancedTimingIsCustom() {
        val profile =
            inferGpsUsageProfile(
                activityProfile = SettingsRepository.ACTIVITY_PROFILE_HIKE,
                timing =
                    GpsUsageTiming(
                        recordingScreenOnSeconds = 2,
                        recordingScreenOffSeconds = 7,
                        turnByTurnScreenOnSeconds = 2,
                        turnByTurnScreenOffSeconds = 8,
                    ),
            )

        assertEquals(SettingsRepository.GPS_USAGE_PROFILE_CUSTOM, profile)
    }
}
