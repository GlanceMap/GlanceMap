package com.glancemap.glancemapcompanionapp.map

import android.hardware.SensorManager

/** Match the watch's sensor heading freshness horizon and trust floor for the marker cone. */
internal const val PHONE_COMPASS_TRUST_FRESHNESS_MAX_AGE_MILLIS = 1_500L

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
