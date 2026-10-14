package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.recording.RecordingSettings
import com.glancemap.trailcore.recording.recordingGpsUsageTiming
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneRecordingSettingsTest {
    @Test
    fun changingActivityPreservesCustomGpsIntervals() {
        val custom =
            PhoneRecordingSettings(
                gpsUsageProfile = RecordingSettings.GPS_USAGE_PROFILE_CUSTOM,
                screenOnIntervalSeconds = 5,
                screenOffIntervalSeconds = 20,
            )
        assertEquals(custom, custom.withGpsPreset(PhoneActivityProfile.BIKE, custom.gpsUsageProfile))
    }

    @Test
    fun phonePresetsUseTheSharedWatchTiming() {
        for (profile in PhoneActivityProfile.entries) {
            for (preset in listOf("BEST_TRACE", "BALANCED", "LONG_BATTERY")) {
                val phone = PhoneRecordingSettings().withGpsPreset(profile, preset)
                val shared = recordingGpsUsageTiming(preset, profile.storageValue)
                assertEquals(shared.recordingScreenOnSeconds, phone.screenOnIntervalSeconds)
                assertEquals(shared.recordingScreenOffSeconds, phone.screenOffIntervalSeconds)
            }
        }
    }

    @Test
    fun sparseScreenOffFixesKeepConfiguredSamplingAndActualContinuitySeparate() {
        val settings = PhoneRecordingSettings(screenOnIntervalSeconds = 5, screenOffIntervalSeconds = 20)
        for (profile in PhoneActivityProfile.entries) {
            val options = settings.smoothingOptions(profile, 20_000L)
            assertEquals(5, options.sampleIntervalSeconds)
            assertEquals(20, options.trajectoryContinuityIntervalSeconds)
        }
    }
}
