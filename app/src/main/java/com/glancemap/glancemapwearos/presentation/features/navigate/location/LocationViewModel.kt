package com.glancemap.glancemapwearos.presentation.features.navigate

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.core.service.diagnostics.FieldMarkerDiagnostics
import com.glancemap.glancemapwearos.core.service.location.model.GpsSignalSnapshot
import com.glancemap.glancemapwearos.core.service.location.model.LocationScreenState
import com.glancemap.glancemapwearos.core.service.location.model.resolveLocationTimingProfile
import com.glancemap.glancemapwearos.core.service.location.service.LocationService
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class LocationViewModel(
    application: Application,
    settingsRepository: SettingsRepository,
) : AndroidViewModel(application) {
    private val _currentLocation = MutableStateFlow<android.location.Location?>(null)
    val currentLocation = _currentLocation.asStateFlow()
    private val _gpsSignalSnapshot = MutableStateFlow(GpsSignalSnapshot())
    val gpsSignalSnapshot = _gpsSignalSnapshot.asStateFlow()
    private val _effectiveGpsIntervalMs = MutableStateFlow(UNKNOWN_EFFECTIVE_GPS_INTERVAL_MS)
    val effectiveGpsIntervalMs = _effectiveGpsIntervalMs.asStateFlow()

    private var locationService: LocationService? = null
    private var isBound = false
    private var isTrackingEnabled = false

    private var locationJob: Job? = null
    private var gpsSignalJob: Job? = null
    private var intervalJob: Job? = null
    private var desiredKeepAppOpen: Boolean = false
    private var desiredScreenState: LocationScreenState = LocationScreenState.INTERACTIVE
    private var desiredBackgroundGpsEnabled: Boolean = false
    private var desiredRuntimeReason: String = "idle"
    private var desiredTurnByTurnScreenOffIntervalOverrideMs: Long? = null
    private var pendingImmediateLocationRequestSource: String? = null
    private var lastImmediateRequestAtMs: Long = Long.MIN_VALUE
    private var lastWakeImmediateRequestAtMs: Long = Long.MIN_VALUE
    private var reconnectJob: Job? = null
    private var connectionWatchdogJob: Job? = null
    private var isBindingInProgress: Boolean = false
    private var lastBindAttemptAtMs: Long = 0L
    private var reconnectAttempt: Int = 0
    private var keepAppOpenSettingsInitialized = false

    init {
        settingsRepository.keepAppOpen
            .distinctUntilChanged()
            .onEach(::applyKeepAppOpen)
            .launchIn(viewModelScope)
    }

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                className: ComponentName,
                service: IBinder,
            ) {
                val binder = service as LocationService.LocalBinder
                locationService = binder.getService()
                isBound = true
                isBindingInProgress = false
                lastBindAttemptAtMs = 0L
                reconnectAttempt = 0
                reconnectJob?.cancel()
                reconnectJob = null
                logConnection("service connected")

                locationJob?.cancel()
                locationJob =
                    locationService
                        ?.currentLocation
                        ?.onEach { _currentLocation.value = it }
                        ?.launchIn(viewModelScope)
                gpsSignalJob?.cancel()
                gpsSignalJob =
                    locationService
                        ?.gpsSignalSnapshot
                        ?.onEach { _gpsSignalSnapshot.value = it }
                        ?.launchIn(viewModelScope)
                intervalJob?.cancel()
                intervalJob =
                    locationService
                        ?.effectiveUpdateIntervalMs
                        ?.onEach { _effectiveGpsIntervalMs.value = it }
                        ?.launchIn(viewModelScope)

                locationService?.setKeepAppOpenState(desiredKeepAppOpen)
                locationService?.setRuntimeState(
                    screenState = desiredScreenState,
                    trackingEnabled = isTrackingEnabled,
                    backgroundGpsEnabled = desiredBackgroundGpsEnabled,
                    runtimeReason = desiredRuntimeReason,
                )
                locationService?.setTurnByTurnScreenOffIntervalOverride(
                    intervalMs = desiredTurnByTurnScreenOffIntervalOverrideMs,
                )
                pendingImmediateLocationRequestSource?.let { pendingSource ->
                    if (isTrackingEnabled) {
                        locationService?.requestImmediateLocation(source = "${pendingSource}_after_bind")
                    }
                    pendingImmediateLocationRequestSource = null
                }

                if (isTrackingEnabled) {
                    ensureConnectionWatchdog()
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                locationJob?.cancel()
                locationJob = null
                gpsSignalJob?.cancel()
                gpsSignalJob = null
                intervalJob?.cancel()
                intervalJob = null
                _effectiveGpsIntervalMs.value = UNKNOWN_EFFECTIVE_GPS_INTERVAL_MS

                isBound = false
                isBindingInProgress = false
                lastBindAttemptAtMs = 0L
                locationService = null
                logConnection("service disconnected")
                // Keep last location to avoid flicker; UI can optionally show "disconnected" state
                if (shouldMaintainConnection()) {
                    scheduleReconnect(reason = "disconnected")
                }
            }
        }

    fun syncRuntimeState(
        screenState: LocationScreenState,
        trackingEnabled: Boolean,
        backgroundGpsEnabled: Boolean = desiredBackgroundGpsEnabled,
        runtimeReason: String = desiredRuntimeReason,
    ) {
        val screenStateChanged = desiredScreenState != screenState
        val trackingChanged = isTrackingEnabled != trackingEnabled
        val backgroundGpsChanged = desiredBackgroundGpsEnabled != backgroundGpsEnabled
        val runtimeReasonChanged = desiredRuntimeReason != runtimeReason
        val needsServiceRecovery =
            (trackingEnabled || desiredKeepAppOpen) && !isBound && !isBindingInProgress
        if (
            !screenStateChanged &&
            !trackingChanged &&
            !backgroundGpsChanged &&
            !runtimeReasonChanged &&
            !needsServiceRecovery
        ) {
            return
        }

        desiredScreenState = screenState
        desiredBackgroundGpsEnabled = backgroundGpsEnabled
        desiredRuntimeReason = runtimeReason.ifBlank { "idle" }

        if (!trackingChanged) {
            locationService?.setRuntimeState(
                screenState = screenState,
                trackingEnabled = trackingEnabled,
                backgroundGpsEnabled = backgroundGpsEnabled,
                runtimeReason = desiredRuntimeReason,
            )
            if (
                needsServiceRecovery &&
                startService(
                    keepAppOpen = desiredKeepAppOpen,
                    trackingEnabled = trackingEnabled,
                )
            ) {
                if (trackingEnabled) {
                    bindService()
                    ensureConnectionWatchdog()
                }
            }
            return
        }

        isTrackingEnabled = trackingEnabled

        if (trackingEnabled) {
            val serviceStarted = startService(keepAppOpen = desiredKeepAppOpen, trackingEnabled = true)
            if (serviceStarted) {
                bindService()
            }
            locationService?.setRuntimeState(
                screenState = screenState,
                trackingEnabled = true,
                backgroundGpsEnabled = backgroundGpsEnabled,
                runtimeReason = desiredRuntimeReason,
            )
            dispatchPendingImmediateLocationRequestIfTrackingEnabled(suffix = "after_tracking_enable")
            if (serviceStarted) {
                ensureConnectionWatchdog()
            }
        } else {
            locationService?.setRuntimeState(
                screenState = screenState,
                trackingEnabled = false,
                backgroundGpsEnabled = backgroundGpsEnabled,
                runtimeReason = desiredRuntimeReason,
            )
            pendingImmediateLocationRequestSource = null
            if (desiredKeepAppOpen && locationService == null) {
                startService(keepAppOpen = true, trackingEnabled = false)
            }
            stopConnectionRecovery()
            unbindService()
            if (!desiredKeepAppOpen) stopService()
        }
    }

    fun setTrackingEnabled(enabled: Boolean) {
        syncRuntimeState(
            screenState = desiredScreenState,
            trackingEnabled = enabled,
        )
    }

    private fun applyKeepAppOpen(enabled: Boolean) {
        if (keepAppOpenSettingsInitialized && desiredKeepAppOpen == enabled) return
        val wasInitialized = keepAppOpenSettingsInitialized
        val wasEnabled = desiredKeepAppOpen
        keepAppOpenSettingsInitialized = true
        desiredKeepAppOpen = enabled
        logConnection("keep open synced from settings: enabled=$enabled")

        if (enabled) {
            // Start the service shell so it can keep the app pinned if needed.
            val serviceStarted = startService(keepAppOpen = true, trackingEnabled = isTrackingEnabled)
            if (isTrackingEnabled && serviceStarted) {
                bindService()
            } else {
                stopConnectionRecovery()
                unbindService()
            }
        } else if (wasInitialized && wasEnabled) {
            if (isTrackingEnabled) {
                val serviceStarted = startService(keepAppOpen = false, trackingEnabled = true)
                if (serviceStarted) {
                    bindService()
                }
            } else {
                // A stopped tracking session does not need a new service start merely to remove
                // the pinned notification; stopping the existing service performs that cleanup.
                stopService()
            }
        }

        locationService?.setKeepAppOpenState(enabled)

        if (!enabled && !isTrackingEnabled) {
            stopConnectionRecovery()
            unbindService()
        }
    }

    fun setScreenState(state: LocationScreenState) {
        syncRuntimeState(
            screenState = state,
            trackingEnabled = isTrackingEnabled,
        )
    }

    fun setTurnByTurnScreenOffIntervalOverride(intervalMs: Long?) {
        val sanitizedIntervalMs =
            intervalMs?.coerceIn(
                MIN_TURN_BY_TURN_SCREEN_OFF_INTERVAL_MS,
                MAX_TURN_BY_TURN_SCREEN_OFF_INTERVAL_MS,
            )
        if (desiredTurnByTurnScreenOffIntervalOverrideMs == sanitizedIntervalMs) return
        desiredTurnByTurnScreenOffIntervalOverrideMs = sanitizedIntervalMs
        locationService?.setTurnByTurnScreenOffIntervalOverride(intervalMs = sanitizedIntervalMs)
    }

    // Guard returns make request rejection explicit and avoid mutating service state after a rejected request.
    @Suppress("ReturnCount")
    internal fun requestImmediateLocation(
        source: String = "ui_unknown",
    ): ImmediateLocationRequestResult {
        val now = SystemClock.elapsedRealtime()
        val forceImmediateRequest = shouldForceUiImmediateLocationRequest(source)
        val isWakeReacquireRequest = isWakeReacquireImmediateLocationRequest(source)
        if (!forceImmediateRequest && lastImmediateRequestAtMs != Long.MIN_VALUE) {
            val elapsedSinceLastRequestMs = (now - lastImmediateRequestAtMs).coerceAtLeast(0L)
            if (elapsedSinceLastRequestMs < UI_IMMEDIATE_REQUEST_DEBOUNCE_MS) {
                return ImmediateLocationRequestResult.SKIPPED_OTHER
            }
        }

        if (
            source.startsWith("ui_") &&
            !forceImmediateRequest &&
            shouldSkipUiImmediateRequest(nowElapsedMs = now)
        ) {
            return ImmediateLocationRequestResult.SKIPPED_OTHER
        }

        if (source.startsWith(UI_STARTUP_REQUEST_SOURCE_PREFIX)) {
            val wakeBurstDecision =
                logWakeBurstCandidateTelemetry(
                    source = source,
                    nowElapsedMs = now,
                )
            if (wakeBurstDecision.wouldSkip) {
                DebugTelemetry.log(
                    CONNECTION_TELEMETRY_TAG,
                    "wakeBurst: skipped source=$source reason=${wakeBurstDecision.reason}",
                )
                return ImmediateLocationRequestResult.SKIPPED_FRESH_WAKE_FIX
            }
        }

        if (isWakeReacquireRequest && lastWakeImmediateRequestAtMs != Long.MIN_VALUE) {
            val wakeElapsedMs = (now - lastWakeImmediateRequestAtMs).coerceAtLeast(0L)
            if (wakeElapsedMs < WAKE_IMMEDIATE_REQUEST_COOLDOWN_MS) {
                return ImmediateLocationRequestResult.SKIPPED_OTHER
            }
        }

        FieldMarkerDiagnostics.recordMarker(type = "immediate_location", note = source)

        lastImmediateRequestAtMs = now
        if (isWakeReacquireRequest) {
            lastWakeImmediateRequestAtMs = now
        }

        if (!isTrackingEnabled) {
            pendingImmediateLocationRequestSource = source
            return ImmediateLocationRequestResult.REQUESTED
        }

        val service = locationService
        if (service != null) {
            service.requestImmediateLocation(source = source)
            pendingImmediateLocationRequestSource = null
        } else {
            pendingImmediateLocationRequestSource = source
        }
        return ImmediateLocationRequestResult.REQUESTED
    }

    private fun dispatchPendingImmediateLocationRequestIfTrackingEnabled(suffix: String) {
        if (!isTrackingEnabled) return
        val service = locationService ?: return
        val pendingSource = pendingImmediateLocationRequestSource ?: return
        service.requestImmediateLocation(source = "${pendingSource}_$suffix")
        pendingImmediateLocationRequestSource = null
    }

    private fun shouldSkipUiImmediateRequest(nowElapsedMs: Long): Boolean {
        val snapshot = _gpsSignalSnapshot.value
        val effectiveIntervalMs =
            _effectiveGpsIntervalMs.value.takeIf { it > 0L }
                ?: SettingsRepository.DEFAULT_GPS_INTERVAL_MS
        val timingProfile = resolveLocationTimingProfile(effectiveIntervalMs)
        val fixAgeMs = snapshot.resolveLastFixAgeMs(nowElapsedMs = nowElapsedMs)
        if (fixAgeMs <= 0L || fixAgeMs == Long.MAX_VALUE) return false
        val serviceFreshnessMaxAgeMs =
            snapshot.lastFixFreshMaxAgeMs
                .takeIf { it > 0L }
                ?: timingProfile.strictFreshFixMaxAgeMs
        val freshnessMaxAgeMs =
            minOf(
                serviceFreshnessMaxAgeMs,
                timingProfile.uiImmediateSkipMaxAgeMs,
            )
        if (fixAgeMs > freshnessMaxAgeMs) return false
        return fixAgeMs <= timingProfile.uiImmediateSkipMaxAgeMs
    }

    private fun logWakeBurstCandidateTelemetry(
        source: String,
        nowElapsedMs: Long,
    ): WakeBurstSkipCandidate {
        val snapshot = _gpsSignalSnapshot.value
        val fixAgeMs = snapshot.resolveLastFixAgeMs(nowElapsedMs = nowElapsedMs)
        val accuracyM = snapshot.lastFixAccuracyM.takeIf { it.isFinite() }
        val effectiveIntervalMs =
            _effectiveGpsIntervalMs.value.takeIf { it > 0L }
                ?: SettingsRepository.DEFAULT_GPS_INTERVAL_MS
        val timingProfile = resolveLocationTimingProfile(effectiveIntervalMs)
        val freshnessMaxAgeMs =
            maxOf(
                timingProfile.strictFreshFixMaxAgeMs,
                snapshot.lastFixFreshMaxAgeMs.takeIf { it > 0L }
                    ?: timingProfile.strictFreshFixMaxAgeMs,
            )
        val decision =
            if (_currentLocation.value == null) {
                WakeBurstSkipCandidate(wouldSkip = false, reason = "no_current_location")
            } else {
                evaluateWakeBurstSkipCandidate(
                    fixAgeMs = fixAgeMs,
                    accuracyM = accuracyM,
                    freshnessMaxAgeMs = freshnessMaxAgeMs,
                )
            }
        DebugTelemetry.log(
            CONNECTION_TELEMETRY_TAG,
            "wakeBurstCandidate source=$source wouldSkip=${decision.wouldSkip} " +
                "reason=${decision.reason} fixAgeMs=${fixAgeMs.telemetryValue()} " +
                "accuracyM=${accuracyM.telemetryValue()} fixMaxAgeMs=$freshnessMaxAgeMs " +
                "effectiveIntervalMs=$effectiveIntervalMs " +
                "accuracyMaxM=${WAKE_BURST_SKIP_MAX_ACCURACY_M.telemetryValue()}",
        )
        return decision
    }

    private fun bindService() {
        if (isBound || isBindingInProgress) return
        Intent(getApplication(), LocationService::class.java).also { intent ->
            val bound =
                runCatching {
                    getApplication<Application>().bindService(intent, connection, Context.BIND_AUTO_CREATE)
                }.getOrDefault(false)
            if (bound) {
                isBindingInProgress = true
                lastBindAttemptAtMs = SystemClock.elapsedRealtime()
                logConnection("bind requested")
            } else {
                isBindingInProgress = false
                lastBindAttemptAtMs = 0L
                logConnection("bind request failed")
                if (shouldMaintainConnection()) {
                    scheduleReconnect(reason = "bind_failed")
                }
            }
        }
    }

    private fun unbindService() {
        if (!isBound && !isBindingInProgress) return

        locationJob?.cancel()
        locationJob = null
        gpsSignalJob?.cancel()
        gpsSignalJob = null
        intervalJob?.cancel()
        intervalJob = null
        _effectiveGpsIntervalMs.value = UNKNOWN_EFFECTIVE_GPS_INTERVAL_MS

        if (isBound) {
            runCatching { getApplication<Application>().unbindService(connection) }
        }
        isBound = false
        isBindingInProgress = false
        lastBindAttemptAtMs = 0L
        locationService = null
    }

    private fun startService(
        keepAppOpen: Boolean,
        trackingEnabled: Boolean,
    ): Boolean {
        val hasActiveConnection = isBound || isBindingInProgress
        val appResumed = isApplicationResumed()
        if (!shouldAttemptLocationServiceStart(appResumed, hasActiveConnection)) {
            DebugTelemetry.log(
                CONNECTION_TELEMETRY_TAG,
                "serviceStartSkipped appResumed=$appResumed tracking=$trackingEnabled " +
                    "keepOpen=$keepAppOpen backgroundGps=$desiredBackgroundGpsEnabled",
            )
            return false
        }
        if (hasActiveConnection) return true

        val app = getApplication<Application>()
        val intent =
            Intent(app, LocationService::class.java).apply {
                putExtra(LocationService.EXTRA_KEEP_APP_OPEN, keepAppOpen)
                putExtra(LocationService.EXTRA_TRACKING_ENABLED, trackingEnabled)
                putExtra(LocationService.EXTRA_SCREEN_STATE, desiredScreenState.name)
                putExtra(LocationService.EXTRA_BACKGROUND_GPS_ENABLED, desiredBackgroundGpsEnabled)
                putExtra(LocationService.EXTRA_RUNTIME_REASON, desiredRuntimeReason)
            }
        val startResult = runCatching { app.startService(intent) }
        if (startResult.isFailure) {
            val error = startResult.exceptionOrNull()
            DebugTelemetry.log(
                CONNECTION_TELEMETRY_TAG,
                "serviceStartFailed tracking=$trackingEnabled keepOpen=$keepAppOpen " +
                    "backgroundGps=$desiredBackgroundGpsEnabled " +
                    "error=${error?.javaClass?.simpleName ?: "unknown"} " +
                    "message=${error?.localizedMessage?.sanitizeTelemetryValue() ?: "na"}",
            )
        }
        return startResult.isSuccess
    }

    private fun isApplicationResumed(): Boolean =
        ProcessLifecycleOwner
            .get()
            .lifecycle
            .currentState
            .isAtLeast(Lifecycle.State.RESUMED)

    private fun stopService() {
        Intent(getApplication(), LocationService::class.java).also { intent ->
            getApplication<Application>().stopService(intent)
        }
    }

    override fun onCleared() {
        stopConnectionRecovery()
        unbindService()
        locationJob?.cancel()
        locationJob = null
        gpsSignalJob?.cancel()
        gpsSignalJob = null
        intervalJob?.cancel()
        intervalJob = null
        locationService = null
        super.onCleared()
    }

    private fun shouldMaintainConnection(): Boolean = isTrackingEnabled

    private fun ensureConnectionWatchdog() {
        if (!shouldMaintainConnection()) return
        if (connectionWatchdogJob?.isActive == true) return
        connectionWatchdogJob =
            viewModelScope.launch {
                while (isTrackingEnabled) {
                    delay(CONNECTION_WATCHDOG_INTERVAL_MS)
                    if (!isTrackingEnabled) break
                    if (isBound) continue
                    if (isBindingInProgress) {
                        val bindAgeMs =
                            if (lastBindAttemptAtMs > 0L) {
                                (SystemClock.elapsedRealtime() - lastBindAttemptAtMs).coerceAtLeast(0L)
                            } else {
                                0L
                            }
                        if (bindAgeMs < BIND_ATTEMPT_TIMEOUT_MS) {
                            continue
                        }
                        isBindingInProgress = false
                        lastBindAttemptAtMs = 0L
                        logConnection("bind timeout, scheduling reconnect")
                    } else {
                        logConnection("watchdog detected unbound state, scheduling reconnect")
                    }
                    scheduleReconnect(reason = "watchdog")
                }
            }
    }

    private fun scheduleReconnect(reason: String) {
        if (!shouldMaintainConnection()) return
        if (isBound) return
        if (reconnectJob?.isActive == true) return
        if (!isApplicationResumed()) {
            logConnection("reconnect deferred: app not resumed reason=$reason")
            return
        }

        reconnectAttempt += 1
        val attempt = reconnectAttempt
        val delayMs = reconnectDelayMs(attempt)
        reconnectJob =
            viewModelScope.launch {
                if (delayMs > 0L) {
                    delay(delayMs)
                }
                if (!shouldMaintainConnection() || isBound) {
                    reconnectJob = null
                    return@launch
                }
                logConnection(
                    "reconnect attempt=$attempt reason=$reason keepOpen=$desiredKeepAppOpen",
                )
                if (startService(keepAppOpen = desiredKeepAppOpen, trackingEnabled = isTrackingEnabled)) {
                    bindService()
                }
                reconnectJob = null
            }
    }

    private fun stopConnectionRecovery() {
        reconnectJob?.cancel()
        reconnectJob = null
        connectionWatchdogJob?.cancel()
        connectionWatchdogJob = null
        reconnectAttempt = 0
    }

    private fun reconnectDelayMs(attempt: Int): Long =
        when (attempt) {
            1 -> 0L
            2 -> 2_000L
            3 -> 5_000L
            4 -> 10_000L
            else -> 15_000L
        }

    private fun logConnection(message: String) {
        DebugTelemetry.log(CONNECTION_TELEMETRY_TAG, message)
    }
}

private const val UNKNOWN_EFFECTIVE_GPS_INTERVAL_MS = 0L

internal fun shouldForceUiImmediateLocationRequest(source: String): Boolean =
    source.startsWith(UI_STARTUP_REQUEST_SOURCE_PREFIX) ||
        source == UI_WAKE_REACQUIRE_TIMEOUT_SOURCE

internal fun isWakeReacquireImmediateLocationRequest(source: String): Boolean =
    source.startsWith(UI_STARTUP_REQUEST_SOURCE_PREFIX) ||
        source == UI_WAKE_REACQUIRE_TIMEOUT_SOURCE

internal fun shouldAttemptLocationServiceStart(
    appResumed: Boolean,
    hasActiveConnection: Boolean,
): Boolean = appResumed || hasActiveConnection

internal enum class ImmediateLocationRequestResult {
    REQUESTED,
    SKIPPED_FRESH_WAKE_FIX,
    SKIPPED_OTHER,
}

internal data class WakeBurstSkipCandidate(
    val wouldSkip: Boolean,
    val reason: String,
)

internal fun evaluateWakeBurstSkipCandidate(
    fixAgeMs: Long,
    accuracyM: Float?,
    freshnessMaxAgeMs: Long,
): WakeBurstSkipCandidate =
    when {
        fixAgeMs <= 0L || fixAgeMs == Long.MAX_VALUE -> {
            WakeBurstSkipCandidate(wouldSkip = false, reason = "no_recent_fix")
        }
        fixAgeMs > freshnessMaxAgeMs -> {
            WakeBurstSkipCandidate(wouldSkip = false, reason = "fix_too_old")
        }
        accuracyM == null -> {
            WakeBurstSkipCandidate(wouldSkip = false, reason = "accuracy_unknown")
        }
        accuracyM > WAKE_BURST_SKIP_MAX_ACCURACY_M -> {
            WakeBurstSkipCandidate(wouldSkip = false, reason = "accuracy_too_low")
        }
        else -> {
            WakeBurstSkipCandidate(wouldSkip = true, reason = "fresh_valid_fix")
        }
    }

private fun GpsSignalSnapshot.resolveLastFixAgeMs(nowElapsedMs: Long): Long =
    if (lastFixElapsedRealtimeMs > 0L) {
        (nowElapsedMs - lastFixElapsedRealtimeMs).coerceAtLeast(0L)
    } else {
        lastFixAgeMs
    }

private fun Long.telemetryValue(): String =
    if (this == Long.MAX_VALUE) {
        "na"
    } else {
        toString()
    }

private fun Long?.telemetryValue(): String = this?.telemetryValue() ?: "na"

private fun Float?.telemetryValue(): String = this?.let { "%.1f".format(it) } ?: "na"

private fun String.sanitizeTelemetryValue(): String =
    replace(Regex("\\s+"), "_")
        .take(80)

private const val UI_IMMEDIATE_REQUEST_DEBOUNCE_MS = 1_500L

// One shared guard for the initial wake request and its only timeout fallback.
private const val WAKE_IMMEDIATE_REQUEST_COOLDOWN_MS = 6_000L
private const val UI_STARTUP_REQUEST_SOURCE_PREFIX = "ui_startup_fresh_fix"
internal const val UI_WAKE_REACQUIRE_TIMEOUT_SOURCE = "ui_wake_reacquire_timeout"
private const val WAKE_BURST_SKIP_MAX_ACCURACY_M = 35f
private const val MIN_TURN_BY_TURN_SCREEN_OFF_INTERVAL_MS = 1_000L
private const val MAX_TURN_BY_TURN_SCREEN_OFF_INTERVAL_MS = 10_000L
private const val CONNECTION_WATCHDOG_INTERVAL_MS = 10_000L
private const val BIND_ATTEMPT_TIMEOUT_MS = 15_000L
private const val CONNECTION_TELEMETRY_TAG = "LocationVM"
