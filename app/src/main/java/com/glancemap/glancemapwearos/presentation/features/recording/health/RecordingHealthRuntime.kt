package com.glancemap.glancemapwearos.presentation.features.recording.health

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.glancemap.glancemapwearos.BuildConfig
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.features.recording.TraceRecordingUiState
import com.glancemap.glancemapwearos.presentation.features.recording.TraceRecordingViewModel
import com.glancemap.glancemapwearos.presentation.features.recording.sensors.recordingHeartRatePermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal class RecordingHealthRuntime(
    private val context: Context,
) {
    private val requestState = RecordingHealthRequestState()
    val requests = requestState.requests
    val saving = requestState.saving
    private val _reconcileRevision = MutableStateFlow(0L)
    val reconcileRevision = _reconcileRevision.asStateFlow()
    private val _activeKinds = MutableStateFlow(emptySet<RecordingHealthMetric>())
    val activeKinds = _activeKinds.asStateFlow()
    private var target: TraceRecordingViewModel? = null
    private var sessionId: Long? = null
    private var serviceRequested = false
    private var finishHandler: (suspend () -> Boolean)? = null
    private var controllerGeneration = 0L

    fun bind(
        viewModel: TraceRecordingViewModel,
        settings: SettingsRepository,
        scope: CoroutineScope,
    ) {
        if (!BuildConfig.RECORDING_HEALTH_SERVICES_EXPERIMENT || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        target = viewModel
        viewModel.prepareHealthDataForSave = { finishBeforeSave() }
        scope.launch(Dispatchers.Main.immediate) {
            combine(
                viewModel.recordingPresentationState,
                settings.recordingHeartRateSource,
                settings.recordingDashboardMetricSlots,
                settings.recordingCadenceSource,
                settings.recordingStepsSource,
            ) { state, heartRateSource, metrics, cadenceSource, stepsSource ->
                state to
                    selectWatchHealthRequest(
                        state.copy(cadenceSource = cadenceSource, stepsSource = stepsSource),
                        heartRateSource,
                        metrics,
                        heartRateGranted =
                            ContextCompat.checkSelfPermission(context, recordingHeartRatePermission()) ==
                                PackageManager.PERMISSION_GRANTED,
                        activityGranted =
                            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
                                PackageManager.PERMISSION_GRANTED,
                    )
            }.collect { (state, request) -> updateDesired(state, request) }
        }
    }

    private fun updateDesired(
        state: TraceRecordingUiState,
        request: RecordingHealthRequest,
    ) {
        val now = System.currentTimeMillis()
        if (state.active && state.startedAtMillis != sessionId) {
            sessionId = state.startedAtMillis
            state.startedAtMillis?.let { target?.beginRecordingHealthSession(it) }
            DebugTelemetry.log("TraceRecordingHealth", "event=experiment_start enabled=true gpsOwned=false")
        }
        target?.setRecordingHealthPaused(state.paused || !state.active, state.pausedAtMillis ?: now)
        if (state.saving) return
        val next =
            state.startedAtMillis
                ?.takeIf { state.active && (request.heartRate || request.steps || request.cadence) }
                ?.let {
                    RecordingHealthDesiredSession(it, request, state.paused)
                }
        requestState.update(next)
        if (next != null && !serviceRequested) {
            serviceRequested = true
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RecordingHealthForegroundService::class.java),
                )
            }.onFailure { onServiceFailure("startForegroundService: ${it.javaClass.simpleName}") }
        }
    }

    @RequiresApi(30)
    fun createController(startedAtMillis: Long): RecordingHealthController {
        val generation = ++controllerGeneration
        target?.beginRecordingHealthBackend(System.currentTimeMillis())
        return HealthServicesRecordingController(
            context,
            onStatus = { status ->
                if (sessionId == startedAtMillis && generation == controllerGeneration) {
                    _activeKinds.value = status.activeKinds
                    target?.setRecordingHealthOwnership(status.activeKinds, System.currentTimeMillis())
                    DebugTelemetry.log(
                        "TraceRecordingHealth",
                        "event=status phase=${status.phase} kinds=${status.activeKinds.joinToString(
                            "|",
                        )} detail=${status.message}",
                    )
                    if (status.phase == RecordingHealthPhase.ENDED) _reconcileRevision.value += 1L
                }
            },
            onSamples = { samples ->
                if (generation == controllerGeneration) target?.onRecordingHealthSamples(startedAtMillis, samples)
            },
        )
    }

    fun attachFinishHandler(handler: suspend () -> Boolean) {
        finishHandler = handler
    }

    fun detachFinishHandler(handler: suspend () -> Boolean) {
        if (finishHandler !== handler) return
        finishHandler = null
        serviceRequested = false
        _activeKinds.value = emptySet()
        target?.setRecordingHealthOwnership(emptySet(), System.currentTimeMillis())
    }

    fun onServiceFailure(reason: String) {
        serviceRequested = false
        _activeKinds.value = emptySet()
        target?.setRecordingHealthOwnership(emptySet(), System.currentTimeMillis())
        DebugTelemetry.log("TraceRecordingHealth", "event=service_failure detail=$reason")
    }

    // The SDK cleanup boundary must preserve saving known data; coroutine cancellation still propagates.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun finishBeforeSave() {
        requestState.beginSave()
        try {
            val finished = withTimeout(30_000L) { finishHandler?.invoke() }
            if (finished == false) DebugTelemetry.log("TraceRecordingHealth", "event=save_cleanup_pending")
        } catch (error: TimeoutCancellationException) {
            DebugTelemetry.log("TraceRecordingHealth", "event=save_flush_timeout error=${error.javaClass.simpleName}")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DebugTelemetry.log("TraceRecordingHealth", "event=save_flush_failed error=${error.javaClass.simpleName}")
        }
    }
}
