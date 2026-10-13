package com.glancemap.glancemapwearos.presentation.features.recording

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.trailcore.recording.RecordingProgressVibrationTracker as SharedRecordingProgressVibrationTracker
import com.glancemap.trailcore.recording.RecordingProgressVibrationTrigger as SharedRecordingProgressVibrationTrigger

internal typealias RecordingProgressVibrationTracker = SharedRecordingProgressVibrationTracker
internal typealias RecordingProgressVibrationTrigger = SharedRecordingProgressVibrationTrigger

internal fun recordingDisplayDistanceMeters(
    state: TraceRecordingUiState,
    nowMillis: Long = System.currentTimeMillis(),
): Double =
    when (state.distanceSource) {
        SettingsRepository.RECORDING_SENSOR_SOURCE_POD ->
            state.externalDistanceMeters
                ?.takeIf { state.externalDistanceUpdatedAtMillis.isFreshExternalDistanceTime(nowMillis) }
                ?: state.externalDistanceFallbackBaseMeters
                    ?.let { baseMeters ->
                        baseMeters +
                            (state.distanceMeters - (state.externalDistanceFallbackGpsMeters ?: state.distanceMeters))
                                .coerceAtLeast(0.0)
                    }
                ?: state.distanceMeters
        else -> state.distanceMeters
    }.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0

private fun Long.isFreshExternalDistanceTime(nowMillis: Long): Boolean = this > 0L && (nowMillis - this).coerceAtLeast(0L) <= EXTERNAL_DISTANCE_FALLBACK_STALE_MS

private const val EXTERNAL_DISTANCE_FALLBACK_STALE_MS = 15_000L

internal fun recordingActiveDurationMillis(
    state: TraceRecordingUiState,
    nowMillis: Long,
): Long {
    val startedAtMillis = state.startedAtMillis ?: return 0L
    val currentPausedMillis =
        if (state.paused) {
            state.pausedAtMillis?.let { nowMillis - it }?.coerceAtLeast(0L) ?: 0L
        } else {
            0L
        }
    return (nowMillis - startedAtMillis - state.accumulatedPausedMillis - currentPausedMillis).coerceAtLeast(0L)
}

internal fun vibrateRecordingProgress(context: Context?): Boolean {
    val vibrator = context?.recordingProgressVibrator() ?: return false
    if (!vibrator.hasVibrator()) return false
    vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0L, 70L, 55L, 70L), -1))
    return true
}

private fun Context.recordingProgressVibrator(): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
