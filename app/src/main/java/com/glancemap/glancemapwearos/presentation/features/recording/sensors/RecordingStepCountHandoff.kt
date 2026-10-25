package com.glancemap.glancemapwearos.presentation.features.recording.sensors

internal class RecordingStepCountHandoff {
    private var carriedSteps: Int? = null
    private var handoffAtMillis = 0L

    fun begin(
        steps: Int?,
        atMillis: Long,
    ) {
        carriedSteps = steps
        handoffAtMillis = atMillis
    }

    fun apply(raw: RecordingSensorMetrics): RecordingSensorMetrics {
        val steps = carriedSteps ?: return raw
        return if (raw.stepCountUpdatedAtMillis > handoffAtMillis && (raw.stepCount ?: -1) >= steps) {
            carriedSteps = null
            raw
        } else {
            raw.copy(stepCount = steps, stepCountUpdatedAtMillis = 0L)
        }
    }
}

internal fun recordingStepCountForHandoff(
    sensor: Int?,
    stored: Int?,
    health: Int?,
): Int? = listOfNotNull(sensor, stored, health).maxOrNull()
