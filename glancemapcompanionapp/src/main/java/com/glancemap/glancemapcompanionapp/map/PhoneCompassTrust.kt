package com.glancemap.glancemapcompanionapp.map

import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** Match the watch's sensor heading freshness horizon and trust floor for the marker cone. */
internal const val PHONE_COMPASS_TRUST_FRESHNESS_MAX_AGE_MILLIS = 1_500L
internal const val PHONE_COMPASS_CONE_TRUST_LOSS_HOLD_MILLIS = 500L

internal fun isPhoneCompassTrustworthyForCone(
    state: PhoneCompassState,
    nowElapsedRealtimeMillis: Long,
): Boolean {
    val sampleAtMillis = state.sampleElapsedRealtimeMillis ?: return false
    val ageMillis = (nowElapsedRealtimeMillis - sampleAtMillis).coerceAtLeast(0L)
    return state.isRenderable &&
        state.hasSample &&
        state.accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM &&
        !state.calibrationRecommended &&
        !state.magneticInterference &&
        sampleAtMillis > 0L &&
        ageMillis <= PHONE_COMPASS_TRUST_FRESHNESS_MAX_AGE_MILLIS
}

@Composable
internal fun rememberPhoneCompassConeVisibility(
    locationFresh: Boolean,
    compassTrustworthy: Boolean,
): Boolean {
    var hasTrustedCompass by remember { mutableStateOf(false) }
    var trustLossExpired by remember { mutableStateOf(false) }

    LaunchedEffect(compassTrustworthy) {
        if (compassTrustworthy) {
            hasTrustedCompass = true
            trustLossExpired = false
        } else if (hasTrustedCompass) {
            trustLossExpired = false
            delay(PHONE_COMPASS_CONE_TRUST_LOSS_HOLD_MILLIS)
            trustLossExpired = true
        } else {
            trustLossExpired = true
        }
    }

    return locationFresh && (compassTrustworthy || (hasTrustedCompass && !trustLossExpired))
}
