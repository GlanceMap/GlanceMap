package com.glancemap.glancemapwearos.presentation.features.navigate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationViewModelSupportTest {
    @Test
    fun startupFreshFixRequestAlwaysBypassesFreshnessSkip() {
        assertTrue(shouldForceUiImmediateLocationRequest("ui_startup_fresh_fix"))
    }

    @Test
    fun wakeTimeoutRequestAlwaysBypassesFreshnessSkip() {
        assertTrue(shouldForceUiImmediateLocationRequest(UI_WAKE_REACQUIRE_TIMEOUT_SOURCE))
    }

    @Test
    fun initialWakeAndTimeoutFallbackUseTheSameCooldownGroup() {
        assertTrue(isWakeReacquireImmediateLocationRequest("ui_startup_fresh_fix"))
        assertTrue(isWakeReacquireImmediateLocationRequest(UI_WAKE_REACQUIRE_TIMEOUT_SOURCE))
        assertFalse(isWakeReacquireImmediateLocationRequest("ui_unknown"))
    }

    @Test
    fun ordinaryUiRequestStillUsesFreshnessSkip() {
        assertFalse(shouldForceUiImmediateLocationRequest("ui_unknown"))
    }

    @Test
    fun locationServiceStartRequiresAnActiveUiOrExistingConnection() {
        assertTrue(shouldAttemptLocationServiceStart(appResumed = true, hasActiveConnection = false))
        assertTrue(shouldAttemptLocationServiceStart(appResumed = false, hasActiveConnection = true))
        assertFalse(shouldAttemptLocationServiceStart(appResumed = false, hasActiveConnection = false))
    }

    @Test
    fun wakeBurstReusesARecentAccurateFixForTheActiveCadence() {
        val decision =
            evaluateWakeBurstSkipCandidate(
                fixAgeMs = 6_500L,
                accuracyM = 12f,
                freshnessMaxAgeMs = 7_000L,
            )

        assertTrue(decision.wouldSkip)
    }

    @Test
    fun wakeBurstDoesNotSkipForAnOldOrWeakFix() {
        assertFalse(
            evaluateWakeBurstSkipCandidate(
                fixAgeMs = 7_001L,
                accuracyM = 12f,
                freshnessMaxAgeMs = 7_000L,
            ).wouldSkip,
        )
        assertFalse(
            evaluateWakeBurstSkipCandidate(
                fixAgeMs = 6_500L,
                accuracyM = 36f,
                freshnessMaxAgeMs = 7_000L,
            ).wouldSkip,
        )
    }
}
