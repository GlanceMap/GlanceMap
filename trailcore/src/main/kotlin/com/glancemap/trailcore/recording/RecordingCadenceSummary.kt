package com.glancemap.trailcore.recording

import kotlin.math.roundToInt

fun averageWalkingCadence(
    stepCount: Int?,
    activeDurationMillis: Long,
): Int? =
    stepCount
        ?.takeIf { it >= 0 && activeDurationMillis > 0L }
        ?.let { (it * 60_000.0 / activeDurationMillis).roundToInt() }
