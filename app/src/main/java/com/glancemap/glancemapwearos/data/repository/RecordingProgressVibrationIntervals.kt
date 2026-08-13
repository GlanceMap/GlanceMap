package com.glancemap.glancemapwearos.data.repository

import kotlin.math.abs

internal val recordingProgressVibrationDistanceMetersOptions: List<Int> =
    (50..1_000 step 50).toList() + (1_500..10_000 step 500).toList()

internal val recordingProgressVibrationTimeMinutesOptions: List<Int> =
    (1..10).toList() + (15..120 step 5).toList()

internal fun nearestRecordingProgressVibrationInterval(
    value: Int,
    options: List<Int>,
): Int = options.minWithOrNull(compareBy<Int> { abs(it - value) }.thenBy { it }) ?: value
