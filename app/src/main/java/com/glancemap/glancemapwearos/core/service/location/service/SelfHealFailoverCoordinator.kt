package com.glancemap.glancemapwearos.core.service.location.service

import android.location.Location
import android.os.SystemClock
import com.glancemap.glancemapwearos.core.service.location.engine.LocationEngine
import com.glancemap.glancemapwearos.core.service.location.model.resolveLocationTimingProfile
import com.glancemap.glancemapwearos.core.service.location.policy.LocationFixPolicy
import com.glancemap.glancemapwearos.core.service.location.policy.LocationSourceMode
import com.glancemap.glancemapwearos.core.service.location.telemetry.LocationServiceTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Suppress("LongParameterList")
internal class SelfHealFailoverCoordinator(
    private val serviceScope: CoroutineScope,
    private val isServiceActive: () -> Boolean,
    private val engine: LocationEngine,
    private val telemetry: LocationServiceTelemetry,
    private val requestLocationUpdateIfNeeded: () -> Unit,
    private val requestImmediateLocation: (String) -> Unit,
    private val trackingEnabled: () -> Boolean,
    private val ambientModeActive: () -> Boolean,
    private val backgroundGpsEnabled: () -> Boolean = { false },
    private val hasFinePermission: () -> Boolean,
    private val hasCoarsePermission: () -> Boolean,
    private val watchGpsOnly: () -> Boolean,
    private val passiveLocationExperiment: () -> Boolean,
    private val phoneConnected: () -> Boolean?,
    private val lastAnyAcceptedFixAtElapsedMs: () -> Long,
    private val lastCallbackAcceptedFixAtElapsedMs: () -> Long,
    private val lastRequestAppliedAtElapsedMs: () -> Long,
    private val expectedIntervalMs: () -> Long,
    private val strictFreshMaxAgeMs: () -> Long,
    private val requestWatchGpsRecovery: (Long, Long, Long) -> Boolean = { _, _, _ -> false },
) {
    private var autoFusedPoorAccuracyStreak: Int = 0
    private var autoFusedFallbackToWatchGps: Boolean = false
    private var autoFusedFallbackSinceElapsedMs: Long = 0L
    private var lastAutoFusedRecoveryProbeAtElapsedMs: Long = 0L
    private var autoFusedRecoveryProbeStartedAtElapsedMs: Long = 0L
    private var autoFusedRecoveryGraceUntilElapsedMs: Long = 0L
    private var pendingNoFixRecoveryProbeUntilElapsedMs: Long = 0L
    private var lastSelfHealAtElapsedMs: Long = 0L
    private var selfHealJob: Job? = null

    fun isAutoFusedFallbackToWatchGps(): Boolean = autoFusedFallbackToWatchGps

    fun onPhoneConnectionStateChecked(phoneConnected: Boolean) {
        if (phoneConnected && autoFusedFallbackToWatchGps) {
            // A Bluetooth connection says nothing about the quality of the phone's GPS fix.
            // Keep the working watch-GPS fallback until a recovery probe receives a fresh,
            // accepted Auto-Fused fix. This prevents source flapping on every reconnect.
            return
        }
    }

    fun currentLocationSourceMode(): LocationSourceMode =
        when {
            watchGpsOnly() ||
                (autoFusedFallbackToWatchGps && !isAutoFusedRecoveryProbeActive()) ->
                LocationSourceMode.WATCH_GPS
            passiveLocationExperiment() -> LocationSourceMode.PASSIVE_EXTERNAL
            else -> LocationSourceMode.AUTO_FUSED
        }

    fun clearAutoFusedFailoverState(reason: String) {
        clearAutoFusedFailoverStateInternal(reason = reason)
        lastAutoFusedRecoveryProbeAtElapsedMs = 0L
        autoFusedRecoveryProbeStartedAtElapsedMs = 0L
        autoFusedRecoveryGraceUntilElapsedMs = 0L
        pendingNoFixRecoveryProbeUntilElapsedMs = 0L
    }

    fun forceAutoFusedFallbackToWatchGps(
        reason: String,
        nowElapsedMs: Long,
    ): Boolean {
        if (watchGpsOnly() || autoFusedFallbackToWatchGps) return false
        autoFusedPoorAccuracyStreak = 0
        autoFusedFallbackToWatchGps = true
        autoFusedFallbackSinceElapsedMs = nowElapsedMs
        lastAutoFusedRecoveryProbeAtElapsedMs = 0L
        autoFusedRecoveryProbeStartedAtElapsedMs = 0L
        pendingNoFixRecoveryProbeUntilElapsedMs = 0L
        telemetry.logAutoFusedFallbackForced(reason = reason)
        return true
    }

    fun maybeTriggerAutoFusedFailover(
        acceptedLocation: Location,
        callbackOrigin: LocationSourceMode,
        nowElapsedMs: Long,
    ) {
        if (watchGpsOnly()) {
            clearAutoFusedFailoverStateInternal(reason = "watch_only_enabled")
            return
        }
        if (passiveLocationExperiment() && !watchGpsOnly()) {
            autoFusedPoorAccuracyStreak = 0
            return
        }
        if (autoFusedFallbackToWatchGps) {
            return
        }
        if (callbackOrigin != LocationSourceMode.AUTO_FUSED) return
        if (nowElapsedMs < autoFusedRecoveryGraceUntilElapsedMs) return

        val ageMs = LocationFixPolicy.locationAgeMs(acceptedLocation, nowElapsedMs)
        val isFresh = ageMs != Long.MAX_VALUE && ageMs <= strictFreshMaxAgeMs()
        if (
            isFresh &&
            acceptedLocation.accuracy.isFinite() &&
            acceptedLocation.accuracy <= AUTO_FUSED_NO_FIX_RECOVERY_CLEAR_ACCURACY_M
        ) {
            pendingNoFixRecoveryProbeUntilElapsedMs = 0L
        }
        if (!isFresh) {
            autoFusedPoorAccuracyStreak = 0
            return
        }

        val accuracyM = acceptedLocation.accuracy
        val lastAcceptedFixAt = lastAnyAcceptedFixAtElapsedMs()
        val referenceFixAt =
            if (lastAcceptedFixAt > 0L) {
                lastAcceptedFixAt
            } else {
                lastRequestAppliedAtElapsedMs()
            }
        val fixGapMs =
            if (referenceFixAt > 0L) {
                (nowElapsedMs - referenceFixAt).coerceAtLeast(0L)
            } else {
                Long.MAX_VALUE
            }
        val requiredStreak =
            resolveAutoFusedAccuracyFailoverRequiredStreak(
                accuracyM = accuracyM,
                fixGapMs = fixGapMs,
                expectedIntervalMs = expectedIntervalMs(),
            )
        if (requiredStreak == null) {
            autoFusedPoorAccuracyStreak = 0
            return
        }

        autoFusedPoorAccuracyStreak += 1
        if (autoFusedPoorAccuracyStreak < requiredStreak) return

        autoFusedFallbackToWatchGps = true
        autoFusedFallbackSinceElapsedMs = nowElapsedMs
        lastAutoFusedRecoveryProbeAtElapsedMs = 0L
        autoFusedRecoveryProbeStartedAtElapsedMs = 0L
        telemetry.logAutoFusedFallbackTriggered(
            accuracyM = accuracyM,
            streak = autoFusedPoorAccuracyStreak,
            requiredStreak = requiredStreak,
            thresholdM = resolveAutoFusedFailoverThresholdM(requiredStreak = requiredStreak),
            fixGapMs = fixGapMs,
        )
        requestLocationUpdateIfNeeded()
    }

    /**
     * Completes a recovery probe only after the location engine has accepted the callback.
     * The regular failover detector runs before filtering so it can still notice bad samples;
     * a return to Auto-Fused must be stricter because it changes the active source.
     */
    fun onLocationAccepted(
        acceptedLocation: Location,
        callbackOrigin: LocationSourceMode,
        nowElapsedMs: Long,
    ) {
        onAcceptedLocationSample(
            callbackOrigin = callbackOrigin,
            ageMs = LocationFixPolicy.locationAgeMs(acceptedLocation, nowElapsedMs),
            accuracyM = acceptedLocation.accuracy,
            nowElapsedMs = nowElapsedMs,
        )
    }

    internal fun onAcceptedLocationSample(
        callbackOrigin: LocationSourceMode,
        ageMs: Long,
        accuracyM: Float,
        nowElapsedMs: Long,
    ) {
        if (!autoFusedFallbackToWatchGps || !isAutoFusedRecoveryProbeActive()) return
        if (callbackOrigin != LocationSourceMode.AUTO_FUSED) return

        val isFresh = ageMs != Long.MAX_VALUE && ageMs <= strictFreshMaxAgeMs()
        val isGoodAccuracy = isWatchGpsGoodEnoughForAutoFusedRecovery(accuracyM)
        if (!isFresh || !isGoodAccuracy) {
            abandonAutoFusedRecoveryProbe(
                reason = if (isFresh) "poor_accuracy" else "stale_fix",
                nowElapsedMs = nowElapsedMs,
                accuracyM = accuracyM,
            )
            return
        }

        val fallbackDurationMs = fallbackDurationMs(nowElapsedMs)
        clearAutoFusedFailoverStateInternal(reason = "auto_fused_probe_accepted")
        autoFusedRecoveryGraceUntilElapsedMs = nowElapsedMs + AUTO_FUSED_RECOVERY_GRACE_MS
        telemetry.logAutoFusedRecoveryTriggered(
            reason = "accepted_fused_fix",
            fallbackDurationMs = fallbackDurationMs,
            fixGapMs = ageMs,
            expectedIntervalMs = expectedIntervalMs(),
        )
    }

    fun updateSelfHealMonitor() {
        if (!shouldRunSelfHealMonitor()) {
            selfHealJob?.cancel()
            selfHealJob = null
            return
        }
        if (selfHealJob?.isActive == true) return

        selfHealJob =
            serviceScope.launch {
                while (isServiceActive() && shouldRunSelfHealMonitor()) {
                    delay(resolveSelfHealMonitorIntervalMs())
                    val nowElapsedMs = SystemClock.elapsedRealtime()
                    val interactiveTracking = trackingEnabled() && !ambientModeActive()
                    if (interactiveTracking) {
                        maybeTriggerInteractiveSelfHeal(
                            nowElapsedMs = nowElapsedMs,
                            interactiveTracking = true,
                            expectedIntervalMs = expectedIntervalMs(),
                        )
                    } else {
                        maybeTriggerBackgroundAutoFusedFailover(
                            nowElapsedMs = nowElapsedMs,
                            backgroundTracking =
                                trackingEnabled() && ambientModeActive() && backgroundGpsEnabled(),
                            expectedIntervalMs = expectedIntervalMs(),
                        )
                    }
                }
                selfHealJob = null
            }
    }

    fun maybeTriggerInteractiveSelfHealNow(
        nowElapsedMs: Long,
        interactiveTracking: Boolean,
        expectedIntervalMs: Long,
    ) {
        maybeTriggerInteractiveSelfHeal(
            nowElapsedMs = nowElapsedMs,
            interactiveTracking = interactiveTracking,
            expectedIntervalMs = expectedIntervalMs,
        )
    }

    internal fun maybeTriggerBackgroundAutoFusedFailoverNow(
        nowElapsedMs: Long,
        backgroundTracking: Boolean,
        expectedIntervalMs: Long,
    ) {
        maybeTriggerBackgroundAutoFusedFailover(
            nowElapsedMs = nowElapsedMs,
            backgroundTracking = backgroundTracking,
            expectedIntervalMs = expectedIntervalMs,
        )
    }

    fun stop() {
        selfHealJob?.cancel()
        selfHealJob = null
        autoFusedPoorAccuracyStreak = 0
        autoFusedFallbackToWatchGps = false
        autoFusedFallbackSinceElapsedMs = 0L
        lastAutoFusedRecoveryProbeAtElapsedMs = 0L
        autoFusedRecoveryProbeStartedAtElapsedMs = 0L
        autoFusedRecoveryGraceUntilElapsedMs = 0L
        pendingNoFixRecoveryProbeUntilElapsedMs = 0L
        lastSelfHealAtElapsedMs = 0L
    }

    private fun shouldRunSelfHealMonitor(): Boolean {
        val hasAnyPermission = hasFinePermission() || hasCoarsePermission()
        return trackingEnabled() &&
            (!ambientModeActive() || backgroundGpsEnabled()) &&
            hasAnyPermission
    }

    private fun resolveSelfHealMonitorIntervalMs(): Long =
        if (ambientModeActive()) {
            BACKGROUND_SELF_HEAL_CHECK_INTERVAL_MS
        } else {
            SELF_HEAL_CHECK_INTERVAL_MS
        }

    private fun maybeTriggerBackgroundAutoFusedFailover(
        nowElapsedMs: Long,
        backgroundTracking: Boolean,
        expectedIntervalMs: Long,
    ) {
        if (!backgroundTracking || expectedIntervalMs <= 0L) return
        if (watchGpsOnly() || passiveLocationExperiment() || autoFusedFallbackToWatchGps) return
        if (engine.currentSourceModeOrNull() != LocationSourceMode.AUTO_FUSED) return

        val lastFixAt =
            resolveLatestAcceptedFixAtElapsedMs(
                lastAnyAcceptedFixAtElapsedMs = lastAnyAcceptedFixAtElapsedMs(),
                lastCallbackAcceptedFixAtElapsedMs = lastCallbackAcceptedFixAtElapsedMs(),
            ).takeIf { it > 0L } ?: lastRequestAppliedAtElapsedMs()
        if (lastFixAt <= 0L) return

        val fixGapMs = (nowElapsedMs - lastFixAt).coerceAtLeast(0L)
        if (fixGapMs < resolveBackgroundAutoFusedFailoverThresholdMs(expectedIntervalMs)) return

        if (
            forceAutoFusedFallbackToWatchGps(
                reason = "background_no_fix",
                nowElapsedMs = nowElapsedMs,
            )
        ) {
            requestLocationUpdateIfNeeded()
        }
    }

    private fun maybeTriggerInteractiveSelfHeal(
        nowElapsedMs: Long,
        interactiveTracking: Boolean,
        expectedIntervalMs: Long,
    ) {
        if (!interactiveTracking) return
        if (expectedIntervalMs <= 0L) return

        val lastAnyFixAt = lastAnyAcceptedFixAtElapsedMs()
        val lastCallbackFixAt = lastCallbackAcceptedFixAtElapsedMs()
        if (lastAnyFixAt > lastCallbackFixAt) pendingNoFixRecoveryProbeUntilElapsedMs = 0L
        val lastFixAt = resolveLatestAcceptedFixAtElapsedMs(lastAnyFixAt, lastCallbackFixAt)
        val referenceFixAt =
            if (lastFixAt > 0L) {
                lastFixAt
            } else {
                lastRequestAppliedAtElapsedMs()
            }
        if (referenceFixAt <= 0L) return

        val fixGapMs = (nowElapsedMs - referenceFixAt).coerceAtLeast(0L)
        val timingProfile = resolveLocationTimingProfile(expectedIntervalMs)
        if (engine.isBurstActive()) {
            maybeTriggerGpsSearchRefresh(
                nowElapsedMs = nowElapsedMs,
                fixGapMs = fixGapMs,
                thresholdMs =
                    resolveGpsSearchRefreshThresholdMs(
                        defaultThresholdMs = timingProfile.autoFusedNoFixFailoverGapMs,
                    ),
                expectedIntervalMs = expectedIntervalMs,
            )
            return
        }
        if (maybeTriggerPassiveExperimentNoFixFailover()) {
            return
        }
        if (
            maybeTriggerAutoFusedRecoveryProbe(
                nowElapsedMs = nowElapsedMs,
                fixGapMs = fixGapMs,
                expectedIntervalMs = expectedIntervalMs,
            )
        ) {
            return
        }

        val noFixFailoverThresholdMs = timingProfile.autoFusedNoFixFailoverGapMs
        if (
            maybeTriggerAutoFusedNoFixFailover(
                nowElapsedMs = nowElapsedMs,
                fixGapMs = fixGapMs,
                thresholdMs = noFixFailoverThresholdMs,
            )
        ) {
            return
        }

        val currentSourceMode = engine.currentSourceModeOrNull()
        if (currentSourceMode == LocationSourceMode.WATCH_GPS && hasFinePermission()) {
            val recoveryThresholdMs = resolveWatchGpsRecoveryStaleThresholdMs(expectedIntervalMs)
            if (
                fixGapMs >= recoveryThresholdMs &&
                requestWatchGpsRecovery(fixGapMs, recoveryThresholdMs, expectedIntervalMs)
            ) {
                return
            }
        }

        val staleThresholdMs = timingProfile.selfHealFixGapMs
        if (fixGapMs < staleThresholdMs) return

        val sinceLastAppliedMs =
            if (lastRequestAppliedAtElapsedMs() > 0L) {
                (nowElapsedMs - lastRequestAppliedAtElapsedMs()).coerceAtLeast(0L)
            } else {
                Long.MAX_VALUE
            }
        if (sinceLastAppliedMs < staleThresholdMs) return

        val sinceLastHealMs =
            if (lastSelfHealAtElapsedMs > 0L) {
                (nowElapsedMs - lastSelfHealAtElapsedMs).coerceAtLeast(0L)
            } else {
                Long.MAX_VALUE
            }
        if (sinceLastHealMs < SELF_HEAL_COOLDOWN_MS) return

        if (
            maybeSkipWatchGpsFirstCallbackSelfHeal(
                sourceMode = currentSourceMode,
                nowElapsedMs = nowElapsedMs,
                fixGapMs = fixGapMs,
                staleThresholdMs = staleThresholdMs,
                expectedIntervalMs = expectedIntervalMs,
                phase = "interactive",
            )
        ) {
            return
        }

        logWatchGpsSelfHealRestartIfNeeded(
            sourceMode = currentSourceMode,
            nowElapsedMs = nowElapsedMs,
            fixGapMs = fixGapMs,
            staleThresholdMs = staleThresholdMs,
            expectedIntervalMs = expectedIntervalMs,
            phase = "interactive",
        )
        lastSelfHealAtElapsedMs = nowElapsedMs
        telemetry.logSelfHealTriggered(
            fixGapMs = fixGapMs,
            staleThresholdMs = staleThresholdMs,
            expectedIntervalMs = expectedIntervalMs,
            activityState = engine.activityState(),
        )
        engine.forceRequestRefresh()
        serviceScope.launch { requestLocationUpdateIfNeeded() }
    }

    private fun maybeTriggerGpsSearchRefresh(
        nowElapsedMs: Long,
        fixGapMs: Long,
        thresholdMs: Long,
        expectedIntervalMs: Long,
    ): Boolean {
        val lastRequestAppliedAt = lastRequestAppliedAtElapsedMs()
        val sinceLastAppliedMs = (nowElapsedMs - lastRequestAppliedAt).coerceAtLeast(0L)
        val currentSourceMode = engine.currentSourceModeOrNull()
        val sinceLastHealMs =
            if (lastSelfHealAtElapsedMs > 0L) {
                (nowElapsedMs - lastSelfHealAtElapsedMs).coerceAtLeast(0L)
            } else {
                Long.MAX_VALUE
            }
        val shouldRefresh =
            fixGapMs >= thresholdMs &&
                lastRequestAppliedAt > 0L &&
                sinceLastAppliedMs >= thresholdMs &&
                currentSourceMode.isGpsSearchRefreshSourceMode() &&
                sinceLastHealMs >= SELF_HEAL_COOLDOWN_MS
        if (!shouldRefresh) return false

        if (
            maybeSkipWatchGpsFirstCallbackSelfHeal(
                sourceMode = currentSourceMode,
                nowElapsedMs = nowElapsedMs,
                fixGapMs = fixGapMs,
                staleThresholdMs = thresholdMs,
                expectedIntervalMs = expectedIntervalMs,
                phase = "burst",
            )
        ) {
            return true
        }

        logWatchGpsSelfHealRestartIfNeeded(
            sourceMode = currentSourceMode,
            nowElapsedMs = nowElapsedMs,
            fixGapMs = fixGapMs,
            staleThresholdMs = thresholdMs,
            expectedIntervalMs = expectedIntervalMs,
            phase = "burst",
        )
        lastSelfHealAtElapsedMs = nowElapsedMs
        telemetry.logSelfHealTriggered(
            fixGapMs = fixGapMs,
            staleThresholdMs = thresholdMs,
            expectedIntervalMs = expectedIntervalMs,
            activityState = engine.activityState(),
        )
        engine.forceRequestRefresh()
        serviceScope.launch { requestLocationUpdateIfNeeded() }
        return true
    }

    private fun LocationSourceMode?.isGpsSearchRefreshSourceMode(): Boolean =
        when (this) {
            LocationSourceMode.AUTO_FUSED,
            LocationSourceMode.WATCH_GPS,
            -> true
            else -> false
        }

    private fun maybeSkipWatchGpsFirstCallbackSelfHeal(
        sourceMode: LocationSourceMode?,
        nowElapsedMs: Long,
        fixGapMs: Long,
        staleThresholdMs: Long,
        expectedIntervalMs: Long,
        phase: String,
    ): Boolean {
        if (sourceMode != LocationSourceMode.WATCH_GPS) return false
        if (lastCallbackAcceptedFixAtElapsedMs() > 0L) return false
        val requestAppliedAt = lastRequestAppliedAtElapsedMs()
        if (requestAppliedAt <= 0L) return false
        val searchAgeMs = (nowElapsedMs - requestAppliedAt).coerceAtLeast(0L)
        if (searchAgeMs >= WATCH_GPS_FIRST_CALLBACK_SELF_HEAL_GRACE_MS) return false

        lastSelfHealAtElapsedMs = nowElapsedMs
        telemetry.logWatchGpsSelfHealSkipped(
            phase = phase,
            searchAgeMs = searchAgeMs,
            graceMs = WATCH_GPS_FIRST_CALLBACK_SELF_HEAL_GRACE_MS,
            fixGapMs = fixGapMs,
            staleThresholdMs = staleThresholdMs,
            expectedIntervalMs = expectedIntervalMs,
            activityState = engine.activityState(),
        )
        return true
    }

    private fun logWatchGpsSelfHealRestartIfNeeded(
        sourceMode: LocationSourceMode?,
        nowElapsedMs: Long,
        fixGapMs: Long,
        staleThresholdMs: Long,
        expectedIntervalMs: Long,
        phase: String,
    ) {
        if (sourceMode != LocationSourceMode.WATCH_GPS) return
        if (lastCallbackAcceptedFixAtElapsedMs() > 0L) return
        val requestAppliedAt = lastRequestAppliedAtElapsedMs()
        if (requestAppliedAt <= 0L) return
        telemetry.logWatchGpsSelfHealRestarting(
            phase = phase,
            searchAgeMs = (nowElapsedMs - requestAppliedAt).coerceAtLeast(0L),
            graceMs = WATCH_GPS_FIRST_CALLBACK_SELF_HEAL_GRACE_MS,
            fixGapMs = fixGapMs,
            staleThresholdMs = staleThresholdMs,
            expectedIntervalMs = expectedIntervalMs,
            activityState = engine.activityState(),
        )
    }

    private fun maybeTriggerPassiveExperimentNoFixFailover(): Boolean {
        if (!passiveLocationExperiment() || watchGpsOnly()) return false
        return true
    }

    private fun maybeTriggerAutoFusedRecoveryProbe(
        nowElapsedMs: Long,
        fixGapMs: Long,
        expectedIntervalMs: Long,
    ): Boolean {
        if (!autoFusedFallbackToWatchGps || watchGpsOnly()) return false
        if (expectedIntervalMs <= 0L) return false
        if (isAutoFusedRecoveryProbeActive()) {
            if (nowElapsedMs - autoFusedRecoveryProbeStartedAtElapsedMs < AUTO_FUSED_RECOVERY_PROBE_TIMEOUT_MS) {
                return true
            }
            abandonAutoFusedRecoveryProbe(
                reason = "timeout",
                nowElapsedMs = nowElapsedMs,
                accuracyM = null,
            )
            return true
        }
        val fallbackSince = autoFusedFallbackSinceElapsedMs
        if (fallbackSince <= 0L) return false

        val fallbackDurationMs = (nowElapsedMs - fallbackSince).coerceAtLeast(0L)
        val minProbeDurationMs =
            (expectedIntervalMs * AUTO_FUSED_RECOVERY_PROBE_MIN_MULTIPLIER)
                .coerceAtLeast(AUTO_FUSED_RECOVERY_PROBE_MIN_FALLBACK_MS)
        if (fallbackDurationMs < minProbeDurationMs) return false

        val knownPhoneConnected = phoneConnected()
        val sinceLastProbeMs =
            if (lastAutoFusedRecoveryProbeAtElapsedMs > 0L) {
                (nowElapsedMs - lastAutoFusedRecoveryProbeAtElapsedMs).coerceAtLeast(0L)
            } else {
                Long.MAX_VALUE
            }
        val recoveryProbeCooldownMs =
            if (knownPhoneConnected == false) {
                DISCONNECTED_PHONE_RECOVERY_CHECK_COOLDOWN_MS
            } else {
                AUTO_FUSED_RECOVERY_PROBE_COOLDOWN_MS
            }
        if (sinceLastProbeMs < recoveryProbeCooldownMs) return false

        if (knownPhoneConnected == false) {
            lastAutoFusedRecoveryProbeAtElapsedMs = nowElapsedMs
            requestLocationUpdateIfNeeded()
            return true
        }

        lastAutoFusedRecoveryProbeAtElapsedMs = nowElapsedMs
        autoFusedRecoveryProbeStartedAtElapsedMs = nowElapsedMs
        telemetry.logAutoFusedRecoveryTriggered(
            reason = "periodic_probe",
            fallbackDurationMs = fallbackDurationMs,
            fixGapMs = fixGapMs,
            expectedIntervalMs = expectedIntervalMs,
        )
        requestLocationUpdateIfNeeded()
        return true
    }

    private fun maybeTriggerAutoFusedNoFixFailover(
        nowElapsedMs: Long,
        fixGapMs: Long,
        thresholdMs: Long,
    ): Boolean {
        if (watchGpsOnly() || autoFusedFallbackToWatchGps) return false
        if (nowElapsedMs < autoFusedRecoveryGraceUntilElapsedMs) return false
        if (engine.currentSourceModeOrNull() != LocationSourceMode.AUTO_FUSED) return false
        if (
            shouldFallbackImmediatelyForInitialAutoFusedNoFix(
                fixGapMs = fixGapMs,
                thresholdMs = thresholdMs,
            )
        ) {
            activateAutoFusedNoFixFallback(
                nowElapsedMs = nowElapsedMs,
                fixGapMs = fixGapMs,
                thresholdMs = thresholdMs,
            )
            return true
        }
        when (
            resolveAutoFusedNoFixRecoveryAction(
                fixGapMs = fixGapMs,
                thresholdMs = thresholdMs,
                nowElapsedMs = nowElapsedMs,
                probeUntilElapsedMs = pendingNoFixRecoveryProbeUntilElapsedMs,
            )
        ) {
            AutoFusedNoFixRecoveryAction.NONE -> return false
            AutoFusedNoFixRecoveryAction.WAIT_FOR_PROBE -> return true
            AutoFusedNoFixRecoveryAction.START_PROBE -> {
                pendingNoFixRecoveryProbeUntilElapsedMs = nowElapsedMs + AUTO_FUSED_NO_FIX_RECOVERY_PROBE_GRACE_MS
                telemetry.logAutoFusedNoFixRecoveryProbeTriggered(
                    fixGapMs = fixGapMs,
                    thresholdMs = thresholdMs,
                    graceMs = AUTO_FUSED_NO_FIX_RECOVERY_PROBE_GRACE_MS,
                )
                requestImmediateLocation(AUTO_FUSED_NO_FIX_RECOVERY_SOURCE)
                return true
            }
            AutoFusedNoFixRecoveryAction.FAILOVER -> Unit
        }

        activateAutoFusedNoFixFallback(
            nowElapsedMs = nowElapsedMs,
            fixGapMs = fixGapMs,
            thresholdMs = thresholdMs,
        )
        return true
    }

    private fun activateAutoFusedNoFixFallback(
        nowElapsedMs: Long,
        fixGapMs: Long,
        thresholdMs: Long,
    ) {
        autoFusedPoorAccuracyStreak = 0
        autoFusedFallbackToWatchGps = true
        autoFusedFallbackSinceElapsedMs = nowElapsedMs
        lastAutoFusedRecoveryProbeAtElapsedMs = 0L
        autoFusedRecoveryProbeStartedAtElapsedMs = 0L
        pendingNoFixRecoveryProbeUntilElapsedMs = 0L
        telemetry.logAutoFusedFallbackTriggeredNoFix(
            fixGapMs = fixGapMs,
            thresholdMs = thresholdMs,
        )
        requestLocationUpdateIfNeeded()
    }

    private fun shouldFallbackImmediatelyForInitialAutoFusedNoFix(
        fixGapMs: Long,
        thresholdMs: Long,
    ): Boolean {
        if (fixGapMs < thresholdMs) return false
        return lastAnyAcceptedFixAtElapsedMs() <= 0L &&
            lastCallbackAcceptedFixAtElapsedMs() <= 0L
    }

    private fun clearAutoFusedFailoverStateInternal(reason: String) {
        val wasEnabled = autoFusedFallbackToWatchGps
        autoFusedPoorAccuracyStreak = 0
        autoFusedFallbackToWatchGps = false
        autoFusedFallbackSinceElapsedMs = 0L
        autoFusedRecoveryProbeStartedAtElapsedMs = 0L
        pendingNoFixRecoveryProbeUntilElapsedMs = 0L
        if (wasEnabled) {
            telemetry.logAutoFusedFallbackCleared(reason = reason)
        }
    }

    private fun isAutoFusedRecoveryProbeActive(): Boolean = autoFusedRecoveryProbeStartedAtElapsedMs > 0L

    private fun abandonAutoFusedRecoveryProbe(
        reason: String,
        nowElapsedMs: Long,
        accuracyM: Float?,
    ) {
        val fallbackDurationMs = fallbackDurationMs(nowElapsedMs)
        autoFusedRecoveryProbeStartedAtElapsedMs = 0L
        telemetry.logAutoFusedRecoveryProbeFailed(
            reason = reason,
            fallbackDurationMs = fallbackDurationMs,
            accuracyM = accuracyM,
        )
        requestLocationUpdateIfNeeded()
    }

    private fun fallbackDurationMs(nowElapsedMs: Long): Long =
        if (autoFusedFallbackSinceElapsedMs > 0L && nowElapsedMs > 0L) {
            (nowElapsedMs - autoFusedFallbackSinceElapsedMs).coerceAtLeast(0L)
        } else {
            0L
        }
}

internal fun resolveLatestAcceptedFixAtElapsedMs(
    lastAnyAcceptedFixAtElapsedMs: Long,
    lastCallbackAcceptedFixAtElapsedMs: Long,
): Long = maxOf(lastAnyAcceptedFixAtElapsedMs, lastCallbackAcceptedFixAtElapsedMs)

internal enum class AutoFusedNoFixRecoveryAction {
    NONE,
    START_PROBE,
    WAIT_FOR_PROBE,
    FAILOVER,
}

internal fun resolveAutoFusedNoFixRecoveryAction(
    fixGapMs: Long,
    thresholdMs: Long,
    nowElapsedMs: Long,
    probeUntilElapsedMs: Long,
): AutoFusedNoFixRecoveryAction =
    when {
        fixGapMs < thresholdMs -> AutoFusedNoFixRecoveryAction.NONE
        probeUntilElapsedMs > nowElapsedMs -> AutoFusedNoFixRecoveryAction.WAIT_FOR_PROBE
        probeUntilElapsedMs <= 0L -> AutoFusedNoFixRecoveryAction.START_PROBE
        else -> AutoFusedNoFixRecoveryAction.FAILOVER
    }

internal fun resolvePassiveExperimentNoFixFailoverThresholdMs(
    defaultThresholdMs: Long,
): Long = minOf(defaultThresholdMs, PASSIVE_EXPERIMENT_NO_FIX_FAILOVER_MAX_GAP_MS)

internal fun resolveGpsSearchRefreshThresholdMs(
    defaultThresholdMs: Long,
): Long = maxOf(defaultThresholdMs, GPS_SEARCH_REFRESH_MIN_GAP_MS)

internal fun resolveAutoFusedAccuracyFailoverRequiredStreak(
    accuracyM: Float,
    fixGapMs: Long,
    expectedIntervalMs: Long,
): Int? {
    if (!accuracyM.isFinite()) return null

    val severeFixGapThresholdMs =
        resolveLocationTimingProfile(expectedIntervalMs).autoFusedSevereFailoverGapMs
    if (
        accuracyM >= AUTO_FUSED_SEVERE_FAILOVER_ACCURACY_M &&
        fixGapMs >= severeFixGapThresholdMs
    ) {
        return AUTO_FUSED_SEVERE_FAILOVER_STREAK
    }
    if (accuracyM >= AUTO_FUSED_FAILOVER_ACCURACY_M) {
        return AUTO_FUSED_FAILOVER_STREAK
    }
    return null
}

internal fun resolveAutoFusedFailoverThresholdM(requiredStreak: Int): Float =
    if (requiredStreak <= AUTO_FUSED_SEVERE_FAILOVER_STREAK) {
        AUTO_FUSED_SEVERE_FAILOVER_ACCURACY_M
    } else {
        AUTO_FUSED_FAILOVER_ACCURACY_M
    }

internal fun isWatchGpsGoodEnoughForAutoFusedRecovery(
    accuracyM: Float,
): Boolean = accuracyM.isFinite() && accuracyM <= AUTO_FUSED_RECOVERY_ACCURACY_M

internal fun resolveBackgroundAutoFusedFailoverThresholdMs(expectedIntervalMs: Long): Long =
    maxOf(
        BACKGROUND_AUTO_FUSED_NO_FIX_FAILOVER_MIN_GAP_MS,
        expectedIntervalMs * BACKGROUND_AUTO_FUSED_NO_FIX_FAILOVER_INTERVAL_MULTIPLIER,
    )

private const val SELF_HEAL_CHECK_INTERVAL_MS = 5_000L // was 2 s; cooldown is 15 s so 5 s is sufficient
private const val BACKGROUND_SELF_HEAL_CHECK_INTERVAL_MS = 15_000L
private const val SELF_HEAL_COOLDOWN_MS = 15_000L
private const val AUTO_FUSED_FAILOVER_ACCURACY_M = 120f
private const val AUTO_FUSED_FAILOVER_STREAK = 4
private const val AUTO_FUSED_SEVERE_FAILOVER_ACCURACY_M = 100f
private const val AUTO_FUSED_SEVERE_FAILOVER_STREAK = 3
private const val AUTO_FUSED_RECOVERY_ACCURACY_M = 65f
private const val AUTO_FUSED_RECOVERY_PROBE_MIN_MULTIPLIER = 6L
private const val AUTO_FUSED_RECOVERY_PROBE_MIN_FALLBACK_MS = 30_000L
private const val AUTO_FUSED_RECOVERY_PROBE_COOLDOWN_MS = 45_000L
private const val AUTO_FUSED_RECOVERY_PROBE_TIMEOUT_MS = 12_000L
private const val DISCONNECTED_PHONE_RECOVERY_CHECK_COOLDOWN_MS = 10_000L
private const val AUTO_FUSED_RECOVERY_GRACE_MS = 15_000L
private const val PASSIVE_EXPERIMENT_NO_FIX_FAILOVER_MAX_GAP_MS = 8_000L
private const val GPS_SEARCH_REFRESH_MIN_GAP_MS = 15_000L
private const val WATCH_GPS_FIRST_CALLBACK_SELF_HEAL_GRACE_MS = 120_000L
private const val AUTO_FUSED_NO_FIX_RECOVERY_PROBE_GRACE_MS = 4_000L
private const val AUTO_FUSED_NO_FIX_RECOVERY_CLEAR_ACCURACY_M = 65f
private const val AUTO_FUSED_NO_FIX_RECOVERY_SOURCE = "auto_fused_no_fix_recovery"
private const val BACKGROUND_AUTO_FUSED_NO_FIX_FAILOVER_MIN_GAP_MS = 20_000L
private const val BACKGROUND_AUTO_FUSED_NO_FIX_FAILOVER_INTERVAL_MULTIPLIER = 3L
