package com.glancemap.glancemapwearos.presentation.features.navigate

internal fun compassQualityTransitionHoldMs(
    from: CompassMarkerQuality,
    to: CompassMarkerQuality,
): Long {
    if (from == to) return 0L
    val fromRank = compassQualityHysteresisRank(from)
    val toRank = compassQualityHysteresisRank(to)
    return when {
        toRank > fromRank ->
            when (to) {
                CompassMarkerQuality.NEUTRAL -> 0L
                CompassMarkerQuality.LOW -> COMPASS_QUALITY_TO_LOW_HOLD_MS
                CompassMarkerQuality.MEDIUM -> COMPASS_QUALITY_TO_MEDIUM_HOLD_MS
                CompassMarkerQuality.GOOD -> COMPASS_QUALITY_TO_GOOD_HOLD_MS
                CompassMarkerQuality.UNRELIABLE -> 0L
            }

        else ->
            when (to) {
                CompassMarkerQuality.NEUTRAL -> 0L
                CompassMarkerQuality.MEDIUM -> COMPASS_QUALITY_DEGRADE_TO_MEDIUM_HOLD_MS
                CompassMarkerQuality.LOW -> COMPASS_QUALITY_DEGRADE_TO_LOW_HOLD_MS
                CompassMarkerQuality.UNRELIABLE -> COMPASS_QUALITY_DEGRADE_TO_UNRELIABLE_HOLD_MS
                CompassMarkerQuality.GOOD -> 0L
            }
    }
}

private fun compassQualityHysteresisRank(quality: CompassMarkerQuality): Int =
    when (quality) {
        CompassMarkerQuality.NEUTRAL -> 0
        CompassMarkerQuality.UNRELIABLE -> 0
        CompassMarkerQuality.LOW -> 1
        CompassMarkerQuality.MEDIUM -> 2
        CompassMarkerQuality.GOOD -> 3
    }

private const val COMPASS_QUALITY_TO_LOW_HOLD_MS = 1_500L
private const val COMPASS_QUALITY_TO_MEDIUM_HOLD_MS = 2_200L
private const val COMPASS_QUALITY_TO_GOOD_HOLD_MS = 3_000L
private const val COMPASS_QUALITY_DEGRADE_TO_MEDIUM_HOLD_MS = 3_000L
private const val COMPASS_QUALITY_DEGRADE_TO_LOW_HOLD_MS = 3_400L
private const val COMPASS_QUALITY_DEGRADE_TO_UNRELIABLE_HOLD_MS = 4_200L
