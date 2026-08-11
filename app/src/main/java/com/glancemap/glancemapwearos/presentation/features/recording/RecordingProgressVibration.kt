package com.glancemap.glancemapwearos.presentation.features.recording

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import kotlin.math.floor

internal sealed interface RecordingProgressVibrationInterval {
    data object Off : RecordingProgressVibrationInterval

    data class Distance(
        val meters: Double,
    ) : RecordingProgressVibrationInterval

    data class Time(
        val milliseconds: Long,
    ) : RecordingProgressVibrationInterval
}

internal sealed interface RecordingProgressVibrationTrigger {
    val milestone: Long

    data class Distance(
        override val milestone: Long,
    ) : RecordingProgressVibrationTrigger

    data class Time(
        override val milestone: Long,
    ) : RecordingProgressVibrationTrigger
}

internal fun recordingProgressVibrationInterval(mode: String): RecordingProgressVibrationInterval =
    when (mode) {
        SettingsRepository.RECORDING_PROGRESS_VIBRATION_DISTANCE_500_METERS ->
            RecordingProgressVibrationInterval.Distance(500.0)
        SettingsRepository.RECORDING_PROGRESS_VIBRATION_DISTANCE_1_KILOMETER ->
            RecordingProgressVibrationInterval.Distance(1_000.0)
        SettingsRepository.RECORDING_PROGRESS_VIBRATION_DISTANCE_2_KILOMETERS ->
            RecordingProgressVibrationInterval.Distance(2_000.0)
        SettingsRepository.RECORDING_PROGRESS_VIBRATION_DISTANCE_5_KILOMETERS ->
            RecordingProgressVibrationInterval.Distance(5_000.0)
        SettingsRepository.RECORDING_PROGRESS_VIBRATION_TIME_15_MINUTES ->
            RecordingProgressVibrationInterval.Time(15 * 60_000L)
        SettingsRepository.RECORDING_PROGRESS_VIBRATION_TIME_30_MINUTES ->
            RecordingProgressVibrationInterval.Time(30 * 60_000L)
        SettingsRepository.RECORDING_PROGRESS_VIBRATION_TIME_60_MINUTES ->
            RecordingProgressVibrationInterval.Time(60 * 60_000L)
        else -> RecordingProgressVibrationInterval.Off
    }

internal class RecordingProgressVibrationTracker {
    private var mode = SettingsRepository.DEFAULT_RECORDING_PROGRESS_VIBRATION_MODE
    private var distanceMilestone = 0L
    private var timeMilestone = 0L

    fun start(mode: String) {
        this.mode = mode
        distanceMilestone = 0L
        timeMilestone = 0L
    }

    fun rebase(
        mode: String,
        distanceMeters: Double,
        activeDurationMillis: Long,
    ) {
        this.mode = mode
        when (val interval = recordingProgressVibrationInterval(mode)) {
            is RecordingProgressVibrationInterval.Distance -> {
                distanceMilestone = completedMilestones(distanceMeters, interval.meters)
                timeMilestone = 0L
            }
            is RecordingProgressVibrationInterval.Time -> {
                distanceMilestone = 0L
                timeMilestone = completedMilestones(activeDurationMillis.toDouble(), interval.milliseconds.toDouble())
            }
            RecordingProgressVibrationInterval.Off -> {
                distanceMilestone = 0L
                timeMilestone = 0L
            }
        }
    }

    fun next(
        mode: String,
        distanceMeters: Double,
        activeDurationMillis: Long,
    ): RecordingProgressVibrationTrigger? {
        if (mode != this.mode) {
            rebase(mode, distanceMeters, activeDurationMillis)
            return null
        }
        return when (val interval = recordingProgressVibrationInterval(mode)) {
            is RecordingProgressVibrationInterval.Distance -> {
                val milestone = completedMilestones(distanceMeters, interval.meters)
                if (milestone > distanceMilestone) {
                    distanceMilestone = milestone
                    RecordingProgressVibrationTrigger.Distance(milestone)
                } else {
                    null
                }
            }
            is RecordingProgressVibrationInterval.Time -> {
                val milestone = completedMilestones(activeDurationMillis.toDouble(), interval.milliseconds.toDouble())
                if (milestone > timeMilestone) {
                    timeMilestone = milestone
                    RecordingProgressVibrationTrigger.Time(milestone)
                } else {
                    null
                }
            }
            RecordingProgressVibrationInterval.Off -> null
        }
    }

    fun millisecondsUntilNextTimeMilestone(activeDurationMillis: Long): Long? {
        val interval = recordingProgressVibrationInterval(mode) as? RecordingProgressVibrationInterval.Time ?: return null
        val nextMilestone = completedMilestones(activeDurationMillis.toDouble(), interval.milliseconds.toDouble()) + 1L
        return (nextMilestone * interval.milliseconds - activeDurationMillis).coerceAtLeast(1L)
    }
}

internal fun recordingDisplayDistanceMeters(state: TraceRecordingUiState): Double =
    when (state.distanceSource) {
        SettingsRepository.RECORDING_SENSOR_SOURCE_POD -> state.externalDistanceMeters ?: state.distanceMeters
        else -> state.distanceMeters
    }.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0

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
    vibrator.vibrate(
        VibrationEffect.createWaveform(longArrayOf(0L, 70L, 55L, 70L), -1),
    )
    return true
}

private fun completedMilestones(
    value: Double,
    interval: Double,
): Long =
    if (!value.isFinite() || value <= 0.0 || interval <= 0.0) {
        0L
    } else {
        floor(value / interval).toLong().coerceAtLeast(0L)
    }

private fun Context.recordingProgressVibrator(): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
