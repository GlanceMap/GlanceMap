package com.glancemap.glancemapwearos.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class TurnByTurnScreenOffGpsSettingsTest {
    @Test
    fun persistedFixedSecondsWinWhileAdaptiveModeIsActive() {
        assertEquals(
            15,
            rememberedTurnByTurnScreenOffFixedGpsIntervalSeconds(
                persistedSeconds = 15,
                activeScreenOffSeconds = SettingsRepository.GPS_INTERVAL_ADAPTIVE_SCREEN_OFF_SECONDS,
                screenOnSeconds = 3,
            ),
        )
    }

    @Test
    fun existingFixedScreenOffSettingSeedsTheRememberedValue() {
        assertEquals(
            20,
            rememberedTurnByTurnScreenOffFixedGpsIntervalSeconds(
                persistedSeconds = null,
                activeScreenOffSeconds = 20,
                screenOnSeconds = 3,
            ),
        )
    }

    @Test
    fun sameAsScreenOnSeedsTheRememberedValueFromScreenOn() {
        assertEquals(
            5,
            rememberedTurnByTurnScreenOffFixedGpsIntervalSeconds(
                persistedSeconds = null,
                activeScreenOffSeconds = SettingsRepository.GPS_INTERVAL_SAME_AS_SCREEN_ON_SECONDS,
                screenOnSeconds = 5,
            ),
        )
    }

    @Test
    fun cyclesThroughEveryScreenOffModeBeforeReturningToOff() {
        val fixedSeconds = 10

        assertEquals(
            SettingsRepository.GPS_INTERVAL_SAME_AS_SCREEN_ON_SECONDS,
            nextTurnByTurnScreenOffGpsMode(
                selectedSeconds = SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS,
                fixedSeconds = fixedSeconds,
            ),
        )
        assertEquals(
            SettingsRepository.GPS_INTERVAL_ADAPTIVE_SCREEN_OFF_SECONDS,
            nextTurnByTurnScreenOffGpsMode(
                selectedSeconds = SettingsRepository.GPS_INTERVAL_SAME_AS_SCREEN_ON_SECONDS,
                fixedSeconds = fixedSeconds,
            ),
        )
        assertEquals(
            fixedSeconds,
            nextTurnByTurnScreenOffGpsMode(
                selectedSeconds = SettingsRepository.GPS_INTERVAL_ADAPTIVE_SCREEN_OFF_SECONDS,
                fixedSeconds = fixedSeconds,
            ),
        )
        assertEquals(
            SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS,
            nextTurnByTurnScreenOffGpsMode(
                selectedSeconds = fixedSeconds,
                fixedSeconds = fixedSeconds,
            ),
        )
    }
}
