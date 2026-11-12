package com.glancemap.glancemapwearos.presentation.features.recording.health

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.clearUpdateCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.BatchingMode
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseState
import androidx.health.services.client.data.ExerciseTrackedStatus
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.data.HeartRateAccuracy
import androidx.health.services.client.endExercise
import androidx.health.services.client.flush
import androidx.health.services.client.getCapabilities
import androidx.health.services.client.getCurrentExerciseInfo
import androidx.health.services.client.pauseExercise
import androidx.health.services.client.resumeExercise
import androidx.health.services.client.startExercise
import kotlinx.coroutines.CompletableDeferred

/** Construct only from the API >= 30 guarded, application-owned experimental runtime. */
@RequiresApi(30)
internal class HealthServicesRecordingController(
    context: Context,
    onStatus: (RecordingHealthStatus) -> Unit,
    onSamples: (List<RecordingHealthSample>) -> Unit,
) : RecordingHealthController by RecordingHealthSessionController(
        HealthServicesRecordingClient(context.applicationContext),
        onStatus,
        onSamples,
    )

@RequiresApi(30)
private class HealthServicesRecordingClient(
    private val context: Context,
) : RecordingHealthClient {
    private val exerciseClient = HealthServices.getClient(context).exerciseClient
    private var callback: ExerciseUpdateCallback? = null

    override suspend fun capabilities(): RecordingHealthDeviceCapabilities {
        val capabilities = exerciseClient.getCapabilities()
        val exercises =
            RecordingHealthExercise.entries.mapNotNull { exercise ->
                val sdkType = exercise.sdkType()
                if (sdkType !in capabilities.supportedExerciseTypes) return@mapNotNull null
                val supported = capabilities.getExerciseTypeCapabilities(sdkType).supportedDataTypes
                RecordingHealthExerciseCapabilities(
                    exercise,
                    heartRate = DataType.HEART_RATE_BPM in supported,
                    stepTotals = DataType.STEPS_TOTAL in supported,
                    stepIntervals = DataType.STEPS in supported,
                    cadence = DataType.STEPS_PER_MINUTE in supported,
                )
            }
        return RecordingHealthDeviceCapabilities(
            exercises,
            supportsFrequentHeartRate =
                BatchingMode.HEART_RATE_5_SECONDS in capabilities.supportedBatchingModeOverrides,
        )
    }

    // The public ExerciseInfo getter uses these documented constants; 1.1.0 restricts their IntDef container.
    @SuppressLint("RestrictedApi")
    override suspend fun owner(): RecordingHealthOwner =
        when (exerciseClient.getCurrentExerciseInfo().exerciseTrackedStatus) {
            ExerciseTrackedStatus.NO_EXERCISE_IN_PROGRESS -> RecordingHealthOwner.NONE
            ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS -> RecordingHealthOwner.THIS_APP
            ExerciseTrackedStatus.OTHER_APP_IN_PROGRESS -> RecordingHealthOwner.OTHER_APP
            else -> error("Unknown Health Services exercise ownership")
        }

    override suspend fun registerListener(listener: RecordingHealthListener) {
        val registered = CompletableDeferred<Unit>()
        val next =
            object : ExerciseUpdateCallback {
                override fun onRegistered() {
                    registered.complete(Unit)
                }

                override fun onRegistrationFailed(throwable: Throwable) {
                    registered.completeExceptionally(throwable)
                    listener.onFailure(throwable)
                }

                // An asynchronous SDK decoding/callback failure must not stop the independent GPX recorder.
                @Suppress("TooGenericExceptionCaught")
                override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
                    try {
                        val state = update.exerciseStateInfo.state
                        val phase =
                            when {
                                state.isEnded -> RecordingHealthPhase.ENDED
                                state.isPaused -> RecordingHealthPhase.PAUSED
                                state == ExerciseState.ACTIVE -> RecordingHealthPhase.ACTIVE
                                else -> RecordingHealthPhase.STARTING
                            }
                        listener.onUpdate(phase, update.recordingSamples(), update.exerciseStateInfo.toString())
                    } catch (error: Exception) {
                        listener.onFailure(error)
                    }
                }

                override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) = Unit

                override fun onAvailabilityChanged(
                    dataType: DataType<*, *>,
                    availability: Availability,
                ) {
                    listener.onAvailability("$dataType=$availability")
                }
            }
        callback = next
        exerciseClient.setUpdateCallback(context.mainExecutor, next)
        registered.await()
    }

    override suspend fun clearListener() {
        val existing = callback ?: return
        exerciseClient.clearUpdateCallback(existing)
        callback = null
    }

    override suspend fun start(configuration: RecordingHealthConfiguration) {
        val dataTypes =
            buildSet<DataType<*, *>> {
                if (RecordingHealthMetric.HEART_RATE in configuration.activeKinds) add(DataType.HEART_RATE_BPM)
                if (RecordingHealthMetric.STEPS_TOTAL in configuration.activeKinds) add(DataType.STEPS_TOTAL)
                if (RecordingHealthMetric.CADENCE in configuration.activeKinds) add(DataType.STEPS_PER_MINUTE)
                if (configuration.stepIntervals) add(DataType.STEPS)
            }
        require(dataTypes.isNotEmpty())
        exerciseClient.startExercise(
            ExerciseConfig(
                exerciseType = configuration.exercise.sdkType(),
                dataTypes = dataTypes,
                isAutoPauseAndResumeEnabled = false,
                isGpsEnabled = false,
                batchingModeOverrides =
                    if (configuration.frequentHeartRate) setOf(BatchingMode.HEART_RATE_5_SECONDS) else emptySet(),
            ),
        )
    }

    override suspend fun pause() {
        exerciseClient.pauseExercise()
    }

    override suspend fun resume() {
        exerciseClient.resumeExercise()
    }

    override suspend fun flush() {
        exerciseClient.flush()
    }

    override suspend fun end() {
        exerciseClient.endExercise()
    }
}

private fun ExerciseUpdate.recordingSamples(): List<RecordingHealthSample> {
    val bootWallMillis = System.currentTimeMillis() - SystemClock.elapsedRealtime()
    return buildList {
        latestMetrics.getData(DataType.HEART_RATE_BPM).forEach { point ->
            val accuracy = (point.accuracy as? HeartRateAccuracy)?.sensorStatus?.name
            if (isUsableRecordingHealthHeartRate(point.value, accuracy)) {
                val measured = point.timeDurationFromBoot.toMillis()
                add(
                    RecordingHealthSample(
                        RecordingHealthMetric.HEART_RATE,
                        point.value,
                        measured,
                        bootWallMillis + measured,
                        accuracy = accuracy,
                    ),
                )
            }
        }
        latestMetrics.getData(DataType.STEPS_PER_MINUTE).forEach { point ->
            val measured = point.timeDurationFromBoot.toMillis()
            if (point.value >= 0L) {
                add(
                    RecordingHealthSample(
                        RecordingHealthMetric.CADENCE,
                        point.value.toDouble(),
                        measured,
                        bootWallMillis + measured,
                    ),
                )
            }
        }
        latestMetrics.getData(DataType.STEPS_TOTAL)?.let { point ->
            if (point.total >= 0L) {
                val wall = point.end.toEpochMilli()
                add(
                    RecordingHealthSample(
                        RecordingHealthMetric.STEPS_TOTAL,
                        point.total.toDouble(),
                        wall - bootWallMillis,
                        wall,
                    ),
                )
            }
        }
        addAll(stepIntervalSamples(bootWallMillis))
    }.sortedBy { it.measuredElapsedRealtimeMillis }
}

private fun RecordingHealthExercise.sdkType(): ExerciseType =
    when (this) {
        RecordingHealthExercise.HIKING -> ExerciseType.HIKING
        RecordingHealthExercise.WALKING -> ExerciseType.WALKING
        RecordingHealthExercise.BIKING -> ExerciseType.BIKING
    }

// Interval deltas stay separate; totals already exclude pauses and must not be incremented again.
private fun ExerciseUpdate.stepIntervalSamples(bootWallMillis: Long): List<RecordingHealthSample> =
    buildList {
        latestMetrics.getData(DataType.STEPS).forEach { point ->
            val measured = point.endDurationFromBoot.toMillis()
            if (point.value >= 0L) {
                add(
                    RecordingHealthSample(
                        RecordingHealthMetric.STEP_DELTA,
                        point.value.toDouble(),
                        measured,
                        bootWallMillis + measured,
                        intervalStartElapsedRealtimeMillis = point.startDurationFromBoot.toMillis(),
                    ),
                )
            }
        }
    }
