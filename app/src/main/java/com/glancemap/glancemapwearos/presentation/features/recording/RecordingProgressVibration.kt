package com.glancemap.glancemapwearos.presentation.features.recording

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.glancemap.glancemapwearos.data.repository.RecordingProgressVibrationSettings
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import kotlin.math.floor

internal sealed interface RecordingProgressVibrationTrigger {
    val milestone: Long

    data class Distance(
        override val milestone: Long,
    ) : RecordingProgressVibrationTrigger

    data class Time(
        override val milestone: Long,
    ) : RecordingProgressVibrationTrigger
}

internal class RecordingProgressVibrationTracker {
    private var settings = RecordingProgressVibrationSettings()
    private var distanceMilestone = 0L
    private var timeMilestone = 0L

    fun start(settings: RecordingProgressVibrationSettings) {
        this.settings = settings
        distanceMilestone = 0L
        timeMilestone = 0L
    }

    fun rebase(
        settings: RecordingProgressVibrationSettings,
        distanceMeters: Double,
        activeDurationMillis: Long,
    ) {
        this.settings = settings
        distanceMilestone =
            if (settings.distanceEnabled) {
                completedMilestones(distanceMeters, settings.distanceMeters.toDouble())
            } else {
                0L
            }
        timeMilestone =
            if (settings.timeEnabled) {
                completedMilestones(activeDurationMillis.toDouble(), settings.timeIntervalMillis().toDouble())
            } else {
                0L
            }
    }

    fun next(
        settings: RecordingProgressVibrationSettings,
        distanceMeters: Double,
        activeDurationMillis: Long,
    ): List<RecordingProgressVibrationTrigger> {
        if (settings != this.settings) {
            rebase(settings, distanceMeters, activeDurationMillis)
            return emptyList()
        }
        val triggers = mutableListOf<RecordingProgressVibrationTrigger>()
        if (settings.distanceEnabled) {
            val milestone = completedMilestones(distanceMeters, settings.distanceMeters.toDouble())
            if (milestone > distanceMilestone) {
                distanceMilestone = milestone
                triggers += RecordingProgressVibrationTrigger.Distance(milestone)
            }
        }
        if (settings.timeEnabled) {
            val milestone =
                completedMilestones(
                    activeDurationMillis.toDouble(),
                    settings.timeIntervalMillis().toDouble(),
                )
            if (milestone > timeMilestone) {
                timeMilestone = milestone
                triggers += RecordingProgressVibrationTrigger.Time(milestone)
            }
        }
        return triggers
    }

    fun millisecondsUntilNextTimeMilestone(activeDurationMillis: Long): Long? {
        if (!settings.timeEnabled) return null
        val intervalMillis = settings.timeIntervalMillis()
        val nextMilestone = completedMilestones(activeDurationMillis.toDouble(), intervalMillis.toDouble()) + 1L
        return (nextMilestone * intervalMillis - activeDurationMillis).coerceAtLeast(1L)
    }
}

private fun RecordingProgressVibrationSettings.timeIntervalMillis(): Long = timeMinutes * 60_000L

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
