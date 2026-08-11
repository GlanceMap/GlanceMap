package com.glancemap.glancemapwearos.core.service.location.telemetry

import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.core.service.diagnostics.TelemetryFormatters
import com.glancemap.glancemapwearos.core.service.location.activity.LocationActivityState

internal class LocationServiceTelemetry(
    private val tag: String,
    private val summaryIntervalMs: Long,
    private val logSink: (String) -> Unit = { message -> DebugTelemetry.log(tag, message) },
) {
    private var summaryWindowStartedAtMs: Long = 0L
    private var locationCallbacks: Int = 0
    private var acceptedFixes: Int = 0
    private var filteredByAccuracy: Int = 0
    private var filteredByInvalidCoordinates: Int = 0
    private var filteredByJitter: Int = 0
    private var filteredByStale: Int = 0
    private var immediateRequests: Int = 0
    private var immediateSkippedCooldown: Int = 0
    private var immediateSkippedBurst: Int = 0
    private var callbackAcceptedFixes: Int = 0
    private var immediateAcceptedFixes: Int = 0
    private var filteredBySourceMismatch: Int = 0
    private var burstInteractiveDoubleApplyCount: Int = 0
    private var fixGapCount: Int = 0
    private var fixGapSumMs: Long = 0L
    private var fixGapMinMs: Long = Long.MAX_VALUE
    private var fixGapMaxMs: Long = 0L
    private var lastAcceptedFixAtMs: Long = 0L
    private var lastAcceptedFixAccuracyM: Float? = null
    private var activeBurstTelemetry: ActiveBurstTelemetry? = null
    private var interactiveFixGapStats = FixGapStats()
    private var nonInteractiveFixGapStats = FixGapStats()
    private var unknownScreenFixGapStats = FixGapStats()

    @Volatile private var latestScreenState: String = "UNKNOWN"

    @Volatile private var latestExpectedIntervalMs: Long = 0L

    @Volatile private var latestTrackingEnabled: Boolean = false

    @Volatile private var latestBackgroundGpsEnabled: Boolean = false

    fun updateFixContext(
        screenState: String,
        expectedIntervalMs: Long,
        trackingEnabled: Boolean,
        backgroundGpsEnabled: Boolean,
    ) {
        latestScreenState = screenState
        latestExpectedIntervalMs = expectedIntervalMs
        latestTrackingEnabled = trackingEnabled
        latestBackgroundGpsEnabled = backgroundGpsEnabled
    }

    fun onLocationCallback() {
        locationCallbacks += 1
    }

    fun onFilteredByAccuracy(
        nowElapsedMs: Long,
        activityState: LocationActivityState,
        burst: Boolean,
    ) {
        filteredByAccuracy += 1
        maybeLogSummary(nowElapsedMs, activityState, burst)
    }

    fun onFilteredByJitter(
        nowElapsedMs: Long,
        activityState: LocationActivityState,
        burst: Boolean,
    ) {
        filteredByJitter += 1
        maybeLogSummary(nowElapsedMs, activityState, burst)
    }

    fun onFilteredByStale(
        nowElapsedMs: Long,
        activityState: LocationActivityState,
        burst: Boolean,
    ) {
        filteredByStale += 1
        maybeLogSummary(nowElapsedMs, activityState, burst)
    }

    fun logStaleFixDropped(
        nowElapsedMs: Long,
        activityState: LocationActivityState,
        burst: Boolean,
        source: String,
        ageMs: Long,
        maxAgeMs: Long,
    ) {
        filteredByStale += 1
        log("staleFix: dropped source=$source ageMs=$ageMs maxAgeMs=$maxAgeMs")
        maybeLogSummary(nowElapsedMs, activityState, burst)
    }

    fun logAccuracyFixDropped(
        nowElapsedMs: Long,
        activityState: LocationActivityState,
        burst: Boolean,
        source: String,
        accuracyM: Float,
        maxAccuracyM: Float,
        ageMs: Long,
        maxAgeMs: Long,
    ) {
        filteredByAccuracy += 1
        log(
            "accuracyFix: dropped source=$source accuracyM=${accuracyM.format(1)} " +
                "maxAccuracyM=${maxAccuracyM.format(1)} ageMs=$ageMs maxAgeMs=$maxAgeMs",
        )
        maybeLogSummary(nowElapsedMs, activityState, burst)
    }

    fun logInvalidCoordinatesDropped(
        nowElapsedMs: Long,
        activityState: LocationActivityState,
        burst: Boolean,
        source: String,
        latitude: Double,
        longitude: Double,
        provider: String?,
    ) {
        filteredByInvalidCoordinates += 1
        log(
            "coordFix: dropped source=$source lat=$latitude lon=$longitude " +
                "provider=${provider ?: "unknown"}",
        )
        maybeLogSummary(nowElapsedMs, activityState, burst)
    }

    fun logJumpFixDeferred(
        nowElapsedMs: Long,
        activityState: LocationActivityState,
        burst: Boolean,
        source: String,
        jumpM: Float,
        maxAllowedM: Float,
        gapMs: Long,
        previousSpeedMps: Float,
        candidateSpeedMps: Float,
    ) {
        filteredByJitter += 1
        log(
            "jumpFix: deferred source=$source jumpM=${jumpM.format(1)} " +
                "maxAllowedM=${maxAllowedM.format(1)} gapMs=$gapMs " +
                "prevSpeedMps=${previousSpeedMps.format(2)} candSpeedMps=${candidateSpeedMps.format(2)}",
        )
        maybeLogSummary(nowElapsedMs, activityState, burst)
    }

    fun logJumpFixConfirmed(
        source: String,
        jumpM: Float,
        confirmRadiusM: Float,
        gapMs: Long,
    ) {
        log(
            "jumpFix: confirmed source=$source jumpM=${jumpM.format(1)} " +
                "confirmRadiusM=${confirmRadiusM.format(1)} gapMs=$gapMs",
        )
    }

    fun onCallbackFixAccepted(
        nowElapsedMs: Long,
        activityState: LocationActivityState,
        burst: Boolean,
        source: String,
        ageMs: Long,
        accuracyM: Float,
        provider: String?,
        origin: String,
    ) {
        callbackAcceptedFixes += 1
        onAcceptedFix(
            nowElapsedMs = nowElapsedMs,
            activityState = activityState,
            burst = burst,
            source = "callback",
            sourceDetail = source,
            ageMs = ageMs,
            accuracyM = accuracyM,
            provider = provider,
            origin = origin,
        )
    }

    fun onImmediateFixAccepted(
        nowElapsedMs: Long,
        activityState: LocationActivityState,
        burst: Boolean,
        source: String,
        ageMs: Long,
        accuracyM: Float,
        provider: String?,
        origin: String,
    ) {
        immediateAcceptedFixes += 1
        onAcceptedFix(
            nowElapsedMs = nowElapsedMs,
            activityState = activityState,
            burst = burst,
            source = "immediate",
            sourceDetail = source,
            ageMs = ageMs,
            accuracyM = accuracyM,
            provider = provider,
            origin = origin,
        )
    }

    fun onImmediateRequestSkippedCooldown(
        nowElapsedMs: Long,
        activityState: LocationActivityState,
        burst: Boolean,
        source: String,
    ) {
        immediateSkippedCooldown += 1
        log("immediateRequest: skipCooldown source=$source")
        maybeLogSummary(nowElapsedMs, activityState, burst)
    }

    fun onImmediateRequestSkippedBurst(
        nowElapsedMs: Long,
        activityState: LocationActivityState,
        burst: Boolean,
        source: String,
    ) {
        immediateSkippedBurst += 1
        log("immediateRequest: skipBurst source=$source")
        maybeLogSummary(nowElapsedMs, activityState, burst)
    }

    fun onImmediateRequestStarted(
        nowElapsedMs: Long,
        durationMs: Long,
        burstId: Long,
        source: String,
    ) {
        immediateRequests += 1
        activeBurstTelemetry =
            ActiveBurstTelemetry(
                id = burstId,
                source = source,
                startedAtElapsedMs = nowElapsedMs,
                priorFixAgeMs =
                    lastAcceptedFixAtMs
                        .takeIf { it > 0L }
                        ?.let { (nowElapsedMs - it).coerceAtLeast(0L) },
                priorFixAccuracyM = lastAcceptedFixAccuracyM,
                screenState = latestScreenState,
                expectedIntervalMs = latestExpectedIntervalMs,
            )
        log("immediateRequest: burstStart id=$burstId source=$source durationMs=$durationMs")
    }

    fun onImmediateRequestEnded(
        burstId: Long,
        reason: String = "timer",
        source: String,
    ) {
        log("immediateRequest: burstEnd id=$burstId source=$source reason=$reason")
    }

    fun logBurstSummary(
        burstId: Long,
        source: String,
        reason: String,
        endedAtElapsedMs: Long,
    ) {
        val burst = activeBurstTelemetry?.takeIf { it.id == burstId } ?: return
        activeBurstTelemetry = null
        val firstFixDelayMs =
            burst.firstFixAcceptedAtElapsedMs
                ?.let { (it - burst.startedAtElapsedMs).coerceAtLeast(0L) }
        log(
            "burstSummary id=$burstId source=$source reason=$reason " +
                "durationMs=${(endedAtElapsedMs - burst.startedAtElapsedMs).coerceAtLeast(0L)} " +
                "screenState=${burst.screenState} expectedIntervalMs=${burst.expectedIntervalMs} " +
                "priorFixAgeMs=${burst.priorFixAgeMs ?: "na"} " +
                "priorFixAccuracyM=${burst.priorFixAccuracyM?.format(1) ?: "na"} " +
                "firstFixSource=${burst.firstFixSource ?: "na"} " +
                "firstFixDetail=${burst.firstFixDetail ?: "na"} " +
                "firstFixDelayMs=${firstFixDelayMs ?: "na"} " +
                "firstFixAgeMs=${burst.firstFixAgeMs ?: "na"} " +
                "firstFixAccuracyM=${burst.firstFixAccuracyM?.format(1) ?: "na"}",
        )
    }

    fun logImmediateRequestSkippedPassiveExperiment(
        source: String,
        backend: String,
    ) {
        log("immediateRequest: skipPassiveExperiment source=$source backend=$backend")
    }

    fun logImmediateRequestGuarded(
        source: String,
        reason: String,
        screenState: String,
        trackingEnabled: Boolean,
    ) {
        log(
            "immediateRequest: skipGuard source=$source reason=$reason " +
                "screenState=$screenState trackingEnabled=$trackingEnabled",
        )
    }

    fun logImmediateRequestDeferred(
        source: String,
        delayMs: Long,
        screenState: String,
        trackingEnabled: Boolean,
    ) {
        log(
            "immediateRequest: deferWakeBurst source=$source delayMs=$delayMs " +
                "screenState=$screenState trackingEnabled=$trackingEnabled",
        )
    }

    fun logGetCurrentLocationFailed(
        source: String,
        backend: String,
        errorType: String,
        errorDetail: String? = null,
    ) {
        val detailSuffix = errorDetail?.takeIf { it.isNotBlank() }?.let { " errorDetail=$it" } ?: ""
        log("getCurrentLocation: failed source=$source backend=$backend errorType=$errorType$detailSuffix")
    }

    fun logNavigateOneShotRequested(
        source: String,
        backend: String,
        maxUpdateAgeMs: Long,
        timeoutMs: Long,
    ) {
        log(
            "navigateOneShot: requested source=$source backend=$backend " +
                "maxUpdateAgeMs=$maxUpdateAgeMs timeoutMs=$timeoutMs",
        )
    }

    @Suppress("LongParameterList")
    fun logNavigateOneShotOutcome(
        source: String,
        backend: String,
        outcome: String,
        reason: String,
        durationMs: Long,
        fixAgeMs: Long?,
        accuracyM: Float?,
    ) {
        log(
            "navigateOneShot: outcome=$outcome source=$source backend=$backend " +
                "reason=$reason durationMs=$durationMs fixAgeMs=${fixAgeMs ?: "na"} " +
                "accuracyM=${accuracyM?.format(1) ?: "na"}",
        )
    }

    fun logRequestUpdatesFailed(
        priority: Int,
        intervalMs: Long,
        minDistanceMeters: Float,
        backend: String,
        errorType: String,
        errorDetail: String? = null,
    ) {
        val detailSuffix = errorDetail?.takeIf { it.isNotBlank() }?.let { " errorDetail=$it" } ?: ""
        log(
            "requestUpdates failed: priority=$priority intervalMs=$intervalMs " +
                "minDistanceM=${minDistanceMeters.format(1)} backend=$backend " +
                "errorType=$errorType$detailSuffix",
        )
    }

    fun logSelfHealTriggered(
        fixGapMs: Long,
        staleThresholdMs: Long,
        expectedIntervalMs: Long,
        activityState: LocationActivityState,
    ) {
        log(
            "selfHeal: trigger fixGapMs=$fixGapMs staleThresholdMs=$staleThresholdMs " +
                "expectedIntervalMs=$expectedIntervalMs state=${activityState.name}",
        )
    }

    fun logWatchGpsSelfHealSkipped(
        phase: String,
        searchAgeMs: Long,
        graceMs: Long,
        fixGapMs: Long,
        staleThresholdMs: Long,
        expectedIntervalMs: Long,
        activityState: LocationActivityState,
    ) {
        log(
            "watchGpsSelfHeal: skipped phase=$phase reason=await_first_callback " +
                "searchAgeMs=$searchAgeMs graceMs=$graceMs fixGapMs=$fixGapMs " +
                "staleThresholdMs=$staleThresholdMs expectedIntervalMs=$expectedIntervalMs " +
                "state=${activityState.name}",
        )
    }

    fun logWatchGpsSelfHealRestarting(
        phase: String,
        searchAgeMs: Long,
        graceMs: Long,
        fixGapMs: Long,
        staleThresholdMs: Long,
        expectedIntervalMs: Long,
        activityState: LocationActivityState,
    ) {
        log(
            "watchGpsSelfHeal: restarting phase=$phase reason=first_callback_grace_expired " +
                "searchAgeMs=$searchAgeMs graceMs=$graceMs fixGapMs=$fixGapMs " +
                "staleThresholdMs=$staleThresholdMs expectedIntervalMs=$expectedIntervalMs " +
                "state=${activityState.name}",
        )
    }

    fun logAvailabilityRecoveryTriggered(
        unavailableForMs: Long,
        staleThresholdMs: Long,
        expectedIntervalMs: Long,
        activityState: LocationActivityState,
    ) {
        log(
            "availabilityRecovery: trigger unavailableForMs=$unavailableForMs " +
                "staleThresholdMs=$staleThresholdMs expectedIntervalMs=$expectedIntervalMs " +
                "state=${activityState.name}",
        )
    }

    fun logLocationAvailabilityChanged(available: Boolean) {
        log("locationAvailability: available=$available")
    }

    fun logGpsSignalSample(
        ageMs: Long,
        fresh: Boolean,
        maxAgeMs: Long,
        accuracyM: Float,
    ) {
        log(
            "gpsSignal: sample ageMs=$ageMs fresh=$fresh maxAgeMs=$maxAgeMs " +
                "accuracyM=${accuracyM.format(1)}",
        )
    }

    fun logGpsSignalSample(
        ageMs: Long,
        fresh: Boolean,
        maxAgeMs: Long,
        accuracyM: Float,
        sourceMode: String,
        watchGpsDegraded: Boolean,
        watchGpsDegradedFixStreak: Int,
        provider: String?,
        accepted: Boolean?,
    ) {
        val acceptedToken =
            when (accepted) {
                true -> "true"
                false -> "false"
                null -> "na"
            }
        log(
            "gpsSignal: sample ageMs=$ageMs fresh=$fresh maxAgeMs=$maxAgeMs " +
                "accuracyM=${accuracyM.format(1)} sourceMode=$sourceMode " +
                "provider=${provider ?: "unknown"} accepted=$acceptedToken " +
                "watchGpsDegraded=$watchGpsDegraded " +
                "watchGpsDegradedFixStreak=$watchGpsDegradedFixStreak",
        )
    }

    fun logWatchGpsDegradedStateChanged(
        degraded: Boolean,
        accuracyM: Float,
        streak: Int,
        sourceMode: String,
    ) {
        log(
            "watchGpsDegraded: state=${if (degraded) "entered" else "cleared"} " +
                "sourceMode=$sourceMode accuracyM=${accuracyM.format(1)} streak=$streak",
        )
    }

    fun logAutoFusedFallbackTriggered(
        accuracyM: Float,
        streak: Int,
        requiredStreak: Int,
        thresholdM: Float,
        fixGapMs: Long,
    ) {
        log(
            "sourceFailover: auto_fused->watch_gps reason=accuracy_plateau " +
                "accuracyM=${accuracyM.format(1)} streak=$streak requiredStreak=$requiredStreak " +
                "thresholdM=${thresholdM.format(1)} fixGapMs=$fixGapMs",
        )
    }

    fun logAutoFusedFallbackTriggeredNoFix(
        fixGapMs: Long,
        thresholdMs: Long,
    ) {
        log(
            "sourceFailover: auto_fused->watch_gps reason=no_fix_gap " +
                "fixGapMs=$fixGapMs thresholdMs=$thresholdMs",
        )
    }

    fun logAutoFusedFallbackForced(reason: String) {
        log("sourceFailover: auto_fused->watch_gps reason=$reason")
    }

    fun logAutoFusedNoFixRecoveryProbeTriggered(
        fixGapMs: Long,
        thresholdMs: Long,
        graceMs: Long,
    ) {
        log(
            "sourceFailover: auto_fused recovery_probe reason=no_fix_gap " +
                "fixGapMs=$fixGapMs thresholdMs=$thresholdMs graceMs=$graceMs",
        )
    }

    fun logAutoFusedFallbackCleared(reason: String) {
        log("sourceFailover: cleared reason=$reason")
    }

    fun logAutoFusedRecoveryTriggered(
        reason: String,
        fallbackDurationMs: Long,
        fixGapMs: Long,
        expectedIntervalMs: Long,
    ) {
        log(
            "sourceFailover: watch_gps->auto_fused reason=$reason " +
                "fallbackDurationMs=$fallbackDurationMs fixGapMs=$fixGapMs " +
                "expectedIntervalMs=$expectedIntervalMs",
        )
    }

    fun logAutoFusedRecoveryProbeFailed(
        reason: String,
        fallbackDurationMs: Long,
        accuracyM: Float?,
    ) {
        log(
            "sourceFailover: auto_fused->watch_gps reason=recovery_probe_$reason " +
                "fallbackDurationMs=$fallbackDurationMs " +
                "accuracyM=${accuracyM?.takeIf { it.isFinite() }?.format(1) ?: "na"}",
        )
    }

    fun logLocationEnvironmentPreflight(
        sourceMode: String,
        watchGpsReason: String,
        locationSettingsSatisfied: Boolean?,
        locationSettingsStatusCode: Int?,
        phoneConnected: Boolean?,
        watchGpsAvailability: String?,
        warning: String,
        action: String,
    ) {
        log(
            "locationEnvironment: sourceMode=$sourceMode " +
                "watchGpsReason=$watchGpsReason " +
                "settingsSatisfied=${locationSettingsSatisfied ?: "na"} " +
                "settingsStatus=${locationSettingsStatusCode?.toString() ?: "na"} " +
                "phoneConnected=${phoneConnected ?: "na"} " +
                "watchGps=${watchGpsAvailability ?: "na"} warning=$warning action=$action",
        )
    }

    fun logWatchGpsFirstRawCallback(
        delayMs: Long,
        rawLocationCount: Int,
    ) {
        log(
            "watchGpsCallback: first_raw delayMs=$delayMs " +
                "rawLocations=${rawLocationCount.coerceAtLeast(0)}",
        )
    }

    fun logWatchGpsCurrentLocationResult(
        durationMs: Long,
        returnedLocation: Boolean,
    ) {
        log(
            "watchGpsCurrentLocation: result=${if (returnedLocation) "location" else "no_location"} " +
                "durationMs=$durationMs",
        )
    }

    fun logWatchGpsRawCallbackSummary(
        reason: String,
        runtimeMs: Long,
        rawCallbackCount: Int,
        rawLocationCount: Int,
        duplicatesDropped: Int,
        firstRawCallbackDelayMs: Long?,
    ) {
        log(
            "watchGpsCallback: summary reason=$reason runtimeMs=$runtimeMs " +
                "rawCallbacks=${rawCallbackCount.coerceAtLeast(0)} " +
                "rawLocations=${rawLocationCount.coerceAtLeast(0)} " +
                "duplicatesDropped=${duplicatesDropped.coerceAtLeast(0)} " +
                "firstRawDelayMs=${firstRawCallbackDelayMs ?: "na"}",
        )
    }

    fun logLocationEnvironmentWarningChanged(warning: String) {
        log("locationEnvironment: warning=$warning")
    }

    fun logCachedLocationAccepted(
        ageMs: Long,
        accuracyM: Float,
        provider: String?,
    ) {
        log(
            "cachedLocation: accepted ageMs=$ageMs accuracyM=$accuracyM " +
                "provider=${provider ?: "unknown"}",
        )
    }

    fun logCachedLocationRejected(
        ageMs: Long,
        accuracyM: Float,
        maxAgeMs: Long,
        maxAccuracyM: Float,
        provider: String?,
    ) {
        log(
            "cachedLocation: rejected ageMs=$ageMs accuracyM=$accuracyM " +
                "maxAgeMs=$maxAgeMs maxAccuracyM=$maxAccuracyM provider=${provider ?: "unknown"}",
        )
    }

    fun logSourceMismatchDropped(
        nowElapsedMs: Long,
        activityState: LocationActivityState,
        burst: Boolean,
        callbackOrigin: String,
        provider: String?,
        expectedOrigin: String,
    ) {
        filteredBySourceMismatch += 1
        log(
            "sourceMismatch: dropped callbackOrigin=$callbackOrigin " +
                "expectedOrigin=$expectedOrigin provider=${provider ?: "unknown"}",
        )
        maybeLogSummary(nowElapsedMs, activityState, burst)
    }

    fun logKeepAppOpen(enabled: Boolean) {
        log("keepAppOpen: $enabled")
    }

    fun logScreenState(screenState: String) {
        log("screenState: $screenState")
    }

    fun logTrackingEnabled(enabled: Boolean) {
        log("tracking: enabled=$enabled")
    }

    fun logRuntimeStateApplied(
        screenState: String,
        trackingEnabled: Boolean,
        screenStateChanged: Boolean,
        trackingChanged: Boolean,
        backgroundGpsEnabled: Boolean,
        runtimeReason: String,
        runtimeReasonChanged: Boolean,
    ) {
        val changedFields =
            buildList {
                if (screenStateChanged) add("screenState")
                if (trackingChanged) add("tracking")
                if (runtimeReasonChanged) add("reason")
            }.joinToString(separator = ",")
        log(
            "runtimeState: screenState=$screenState trackingEnabled=$trackingEnabled " +
                "backgroundGpsEnabled=$backgroundGpsEnabled reason=$runtimeReason changed=$changedFields",
        )
    }

    fun logImmediateLocationWorkCancelled(
        reason: String,
        cancelledBurst: Boolean,
        cancelledFetch: Boolean,
    ) {
        log(
            "immediateWork: cancelled reason=$reason burst=$cancelledBurst fetch=$cancelledFetch",
        )
    }

    fun logImmediateRequestSkippedFreshStream(
        source: String,
        runtimeReason: String,
        intervalMs: Long,
        fixAgeMs: Long,
        accuracyM: Float,
    ) {
        log(
            "immediateRequest: skippedFreshStream source=$source reason=$runtimeReason " +
                "intervalMs=$intervalMs fixAgeMs=$fixAgeMs accuracyM=${accuracyM.format(1)}",
        )
    }

    fun logActivityTransition(
        from: LocationActivityState,
        to: LocationActivityState,
    ) {
        log("activityState: ${from.name} -> ${to.name}")
    }

    fun logRequestUpdatesApplied(
        priority: Int,
        intervalMs: Long,
        minDistanceMeters: Float,
        activityState: LocationActivityState,
        bound: Boolean,
        keepOpen: Boolean,
        watchOnly: Boolean,
        burst: Boolean,
        backend: String,
        runtimeMode: String,
        trackingEnabled: Boolean,
        interactive: Boolean,
        screenState: String,
        runtimeReason: String,
        hasFinePermission: Boolean,
        hasCoarsePermission: Boolean,
        passivePriority: Boolean,
    ) {
        if (burst && runtimeMode == "INTERACTIVE") {
            burstInteractiveDoubleApplyCount += 1
        }
        log(
            "requestUpdates applied: priority=$priority intervalMs=$intervalMs " +
                "minDistanceM=$minDistanceMeters state=${activityState.name} " +
                "bound=$bound keepOpen=$keepOpen watchOnly=$watchOnly burst=$burst " +
                "backend=$backend mode=$runtimeMode trackingEnabled=$trackingEnabled " +
                "interactive=$interactive screenState=$screenState reason=$runtimeReason " +
                "finePermission=$hasFinePermission coarsePermission=$hasCoarsePermission " +
                "passivePriority=$passivePriority",
        )
    }

    fun logRequestUpdatesCleared(
        reason: String,
        bound: Boolean,
        keepOpen: Boolean,
        trackingEnabled: Boolean,
        screenState: String,
        backgroundGpsEnabled: Boolean,
    ) {
        log(
            "requestUpdates cleared: reason=$reason bound=$bound keepOpen=$keepOpen " +
                "trackingEnabled=$trackingEnabled screenState=$screenState " +
                "backgroundGpsEnabled=$backgroundGpsEnabled",
        )
    }

    fun logLocationBatchProcessed(
        rawCandidates: Int,
        normalizedCandidates: Int,
        acceptedCandidates: Int,
        fallbackUsed: Boolean,
        callbackOrigin: String,
        duplicateCandidatesDropped: Int,
    ) {
        log(
            "locationBatch: raw=$rawCandidates normalized=$normalizedCandidates " +
                "accepted=$acceptedCandidates fallback=$fallbackUsed " +
                "origin=$callbackOrigin duplicatesDropped=$duplicateCandidatesDropped",
        )
    }

    fun setDebugEnabled(enabled: Boolean) {
        DebugTelemetry.setEnabledFromLocationService(enabled)
    }

    private fun recordAcceptedFix(nowElapsedMs: Long): Long? {
        val previousAcceptedAt = lastAcceptedFixAtMs
        var gapMs: Long? = null
        if (previousAcceptedAt > 0L) {
            val gap = (nowElapsedMs - previousAcceptedAt).coerceAtLeast(0L)
            gapMs = gap
            fixGapCount += 1
            fixGapSumMs += gap
            if (gap < fixGapMinMs) fixGapMinMs = gap
            if (gap > fixGapMaxMs) fixGapMaxMs = gap
            gapStatsFor(latestScreenState).record(gap)
        }
        lastAcceptedFixAtMs = nowElapsedMs
        acceptedFixes += 1
        return gapMs
    }

    private fun onAcceptedFix(
        nowElapsedMs: Long,
        activityState: LocationActivityState,
        burst: Boolean,
        source: String,
        sourceDetail: String,
        ageMs: Long,
        accuracyM: Float,
        provider: String?,
        origin: String,
    ) {
        activeBurstTelemetry?.recordFirstAcceptedFix(
            nowElapsedMs = nowElapsedMs,
            source = source,
            sourceDetail = sourceDetail,
            ageMs = ageMs,
            accuracyM = accuracyM,
        )
        val gapMs = recordAcceptedFix(nowElapsedMs)
        lastAcceptedFixAccuracyM = accuracyM
        log(
            "fixAccepted: source=$source detail=$sourceDetail ageMs=$ageMs " +
                "accuracyM=${accuracyM.format(1)} origin=$origin provider=${provider ?: "unknown"} " +
                "gapMs=${gapMs ?: "na"} screenState=$latestScreenState " +
                "expectedIntervalMs=$latestExpectedIntervalMs trackingEnabled=$latestTrackingEnabled " +
                "backgroundGpsEnabled=$latestBackgroundGpsEnabled",
        )
        maybeLogSummary(nowElapsedMs, activityState, burst)
    }

    private fun maybeLogSummary(
        nowElapsedMs: Long,
        activityState: LocationActivityState?,
        burst: Boolean?,
    ) {
        if (!DebugTelemetry.isEnabled()) return
        if (summaryWindowStartedAtMs == 0L) {
            summaryWindowStartedAtMs = nowElapsedMs
            return
        }
        if (nowElapsedMs - summaryWindowStartedAtMs < summaryIntervalMs) return

        val windowMs = (nowElapsedMs - summaryWindowStartedAtMs).coerceAtLeast(1L)
        val callbackRatePerMin = locationCallbacks * 60_000.0 / windowMs.toDouble()
        val acceptedRatePerMin = acceptedFixes * 60_000.0 / windowMs.toDouble()
        val avgFixGapMs = if (fixGapCount > 0) (fixGapSumMs / fixGapCount).toString() else "na"
        val minFixGapMs = if (fixGapCount > 0) fixGapMinMs.toString() else "na"
        val maxFixGapMs = if (fixGapCount > 0) fixGapMaxMs.toString() else "na"
        val interactiveGapSummary = interactiveFixGapStats.summary()
        val nonInteractiveGapSummary = nonInteractiveFixGapStats.summary()
        val unknownScreenGapSummary = unknownScreenFixGapStats.summary()

        summaryWindowStartedAtMs = nowElapsedMs
        log(
            "summary windowMs=$windowMs callbacks=$locationCallbacks cbPerMin=${TelemetryFormatters.decimal(callbackRatePerMin, 1)} " +
                "fixes=$acceptedFixes fixPerMin=${TelemetryFormatters.decimal(acceptedRatePerMin, 1)} " +
                "callbackFixes=$callbackAcceptedFixes immediateFixes=$immediateAcceptedFixes " +
                "fixGapAvgMs=$avgFixGapMs fixGapMinMs=$minFixGapMs fixGapMaxMs=$maxFixGapMs " +
                "fixGapInteractive=${interactiveGapSummary.telemetryValue()} " +
                "fixGapNonInteractive=${nonInteractiveGapSummary.telemetryValue()} " +
                "fixGapUnknownScreen=${unknownScreenGapSummary.telemetryValue()} " +
                "screenState=$latestScreenState expectedIntervalMs=$latestExpectedIntervalMs " +
                "trackingEnabled=$latestTrackingEnabled backgroundGpsEnabled=$latestBackgroundGpsEnabled " +
                "filteredAcc=$filteredByAccuracy filteredCoord=$filteredByInvalidCoordinates " +
                "filteredJitter=$filteredByJitter " +
                "filteredStale=$filteredByStale filteredSourceMismatch=$filteredBySourceMismatch " +
                "immediate=$immediateRequests skipCooldown=$immediateSkippedCooldown " +
                "skipBurst=$immediateSkippedBurst " +
                "burstInteractiveDoubleApply=$burstInteractiveDoubleApplyCount " +
                "state=${activityState?.name ?: "UNKNOWN"} " +
                "burst=${burst ?: false}",
        )

        locationCallbacks = 0
        acceptedFixes = 0
        filteredByAccuracy = 0
        filteredByInvalidCoordinates = 0
        filteredByJitter = 0
        filteredByStale = 0
        filteredBySourceMismatch = 0
        immediateRequests = 0
        immediateSkippedCooldown = 0
        immediateSkippedBurst = 0
        callbackAcceptedFixes = 0
        immediateAcceptedFixes = 0
        burstInteractiveDoubleApplyCount = 0
        fixGapCount = 0
        fixGapSumMs = 0L
        fixGapMinMs = Long.MAX_VALUE
        fixGapMaxMs = 0L
        interactiveFixGapStats = FixGapStats()
        nonInteractiveFixGapStats = FixGapStats()
        unknownScreenFixGapStats = FixGapStats()
    }

    private fun log(message: String) {
        logSink(message)
    }

    private fun gapStatsFor(screenState: String): FixGapStats =
        when (screenState) {
            "INTERACTIVE" -> interactiveFixGapStats
            "SCREEN_OFF",
            "AMBIENT",
            -> nonInteractiveFixGapStats
            else -> unknownScreenFixGapStats
        }
}

private fun Float.format(digits: Int): String = TelemetryFormatters.decimal(this, digits)

private data class ActiveBurstTelemetry(
    val id: Long,
    val source: String,
    val startedAtElapsedMs: Long,
    val priorFixAgeMs: Long?,
    val priorFixAccuracyM: Float?,
    val screenState: String,
    val expectedIntervalMs: Long,
    var firstFixAcceptedAtElapsedMs: Long? = null,
    var firstFixSource: String? = null,
    var firstFixDetail: String? = null,
    var firstFixAgeMs: Long? = null,
    var firstFixAccuracyM: Float? = null,
) {
    fun recordFirstAcceptedFix(
        nowElapsedMs: Long,
        source: String,
        sourceDetail: String,
        ageMs: Long,
        accuracyM: Float,
    ) {
        if (firstFixAcceptedAtElapsedMs != null) return
        firstFixAcceptedAtElapsedMs = nowElapsedMs
        firstFixSource = source
        firstFixDetail = sourceDetail
        firstFixAgeMs = ageMs
        firstFixAccuracyM = accuracyM
    }
}

private class FixGapStats {
    var count: Int = 0
        private set
    private var sumMs: Long = 0L
    private var maxMs: Long = 0L

    fun record(gapMs: Long) {
        count += 1
        sumMs += gapMs
        if (gapMs > maxMs) maxMs = gapMs
    }

    fun summary(): FixGapSummary =
        FixGapSummary(
            count = count,
            avgMs = if (count > 0) sumMs / count else null,
            maxMs = if (count > 0) maxMs else null,
        )
}

private data class FixGapSummary(
    val count: Int,
    val avgMs: Long?,
    val maxMs: Long?,
) {
    fun telemetryValue(): String = "count:$count,avgMs:${avgMs ?: "na"},maxMs:${maxMs ?: "na"}"
}
