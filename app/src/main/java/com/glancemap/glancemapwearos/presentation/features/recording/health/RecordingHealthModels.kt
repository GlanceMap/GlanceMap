package com.glancemap.glancemapwearos.presentation.features.recording.health

internal enum class RecordingHealthActivity { HIKE, BIKE }

internal enum class RecordingHealthExercise { HIKING, WALKING, BIKING }

internal enum class RecordingHealthMetric { HEART_RATE, STEPS_TOTAL, CADENCE, STEP_DELTA }

internal data class RecordingHealthRequest(
    val activity: RecordingHealthActivity,
    val heartRate: Boolean,
    val steps: Boolean,
    val cadence: Boolean,
    val preferFrequentHeartRate: Boolean = false,
)

internal enum class RecordingHealthPhase {
    IDLE,
    STARTING,
    ACTIVE,
    PAUSED,
    ENDING,
    EXTERNAL_CONFLICT,
    OWNED_CONFLICT,
    UNSUPPORTED,
    ENDED,
    ERROR,
}

internal data class RecordingHealthStatus(
    val phase: RecordingHealthPhase,
    val activeKinds: Set<RecordingHealthMetric> = emptySet(),
    val message: String? = null,
)

internal data class RecordingHealthSample(
    val kind: RecordingHealthMetric,
    val value: Double,
    val measuredElapsedRealtimeMillis: Long,
    val measuredWallTimeMillis: Long,
    val intervalStartElapsedRealtimeMillis: Long? = null,
    val accuracy: String? = null,
)

internal fun isUsableRecordingHealthHeartRate(
    value: Double,
    accuracy: String?,
): Boolean = value.isFinite() && value > 0.0 && accuracy != "NO_CONTACT" && accuracy != "UNRELIABLE"

internal interface RecordingHealthController {
    suspend fun start(request: RecordingHealthRequest): RecordingHealthStatus

    suspend fun pause()

    suspend fun resume()

    suspend fun flush()

    suspend fun end(): Boolean
}

internal data class RecordingHealthDeviceCapabilities(
    val exercises: List<RecordingHealthExerciseCapabilities>,
    val supportsFrequentHeartRate: Boolean = false,
)

internal data class RecordingHealthExerciseCapabilities(
    val exercise: RecordingHealthExercise,
    val heartRate: Boolean,
    val stepTotals: Boolean,
    val stepIntervals: Boolean,
    val cadence: Boolean,
)

internal data class RecordingHealthConfiguration(
    val exercise: RecordingHealthExercise,
    val activeKinds: Set<RecordingHealthMetric>,
    val stepIntervals: Boolean,
    val frequentHeartRate: Boolean,
)

internal fun selectRecordingHealthConfiguration(
    request: RecordingHealthRequest,
    capabilities: RecordingHealthDeviceCapabilities,
): RecordingHealthConfiguration? {
    val exerciseOrder =
        when (request.activity) {
            RecordingHealthActivity.HIKE -> listOf(RecordingHealthExercise.HIKING, RecordingHealthExercise.WALKING)
            RecordingHealthActivity.BIKE -> listOf(RecordingHealthExercise.BIKING)
        }
    for (exercise in exerciseOrder) {
        val supported = capabilities.exercises.firstOrNull { it.exercise == exercise } ?: continue
        val kinds = supported.requestedMetrics(request)
        if (kinds.isNotEmpty()) {
            return RecordingHealthConfiguration(
                exercise = exercise,
                activeKinds = kinds,
                stepIntervals = request.steps && supported.stepIntervals,
                frequentHeartRate =
                    request.preferFrequentHeartRate &&
                        capabilities.supportsFrequentHeartRate &&
                        RecordingHealthMetric.HEART_RATE in kinds,
            )
        }
    }
    return null
}

private fun RecordingHealthExerciseCapabilities.requestedMetrics(request: RecordingHealthRequest) =
    buildSet {
        if (request.heartRate && heartRate) add(RecordingHealthMetric.HEART_RATE)
        if (request.steps && stepTotals) add(RecordingHealthMetric.STEPS_TOTAL)
        if (request.cadence && cadence) add(RecordingHealthMetric.CADENCE)
    }
