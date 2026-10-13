package com.glancemap.glancemapwearos.data.repository

import com.glancemap.trailcore.recording.RecordingSettings

internal val recordingProgressVibrationDistanceMetersOptions: List<Int> =
    RecordingSettings.recordingProgressVibrationDistanceMetersOptions

internal val recordingProgressVibrationTimeMinutesOptions: List<Int> =
    RecordingSettings.recordingProgressVibrationTimeMinutesOptions

internal fun nearestRecordingProgressVibrationInterval(
    value: Int,
    options: List<Int>,
): Int = RecordingSettings.nearestProgressVibrationInterval(value, options)

internal fun normalizeProgressMode(mode: String?): String = RecordingSettings.normalizeProgressVibrationMode(mode)

internal fun normalizeProgressDistance(meters: Int?): Int = RecordingSettings.normalizeProgressDistanceMeters(meters)

internal fun normalizeProgressTime(minutes: Int?): Int = RecordingSettings.normalizeProgressTimeMinutes(minutes)
