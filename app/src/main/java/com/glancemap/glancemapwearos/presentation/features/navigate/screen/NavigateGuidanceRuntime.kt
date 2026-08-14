package com.glancemap.glancemapwearos.presentation.features.navigate

import android.content.Context
import android.location.Location
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.features.gpx.GpxEtaModelConfig
import com.glancemap.glancemapwearos.presentation.features.gpx.GpxViewModel
import com.glancemap.glancemapwearos.presentation.features.gpx.buildRouteEtaProjection
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GpxGuidanceSession
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GpxGuidanceTuning
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GuidanceMode
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.GuidanceOffRouteConfirmationState
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.TurnByTurnGuidanceState
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.computeTurnByTurnGuidanceState
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.haversineMeters
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.isGuidanceStartReached
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.projectLocationToRoute
import com.glancemap.glancemapwearos.presentation.features.navigate.guidance.updateGuidanceOffRouteConfirmation
import org.mapsforge.core.model.LatLong

internal data class NavigateGuidanceRuntime(
    val state: TurnByTurnGuidanceState,
    val guideBackToRouteActive: Boolean,
    val showGuideBackPrompt: Boolean,
    val startDecisionPrompt: GuidanceDecisionPrompt?,
    val onGuideBackToRoute: () -> Unit,
    val onDismissGuideBackPrompt: () -> Unit,
    val onAcceptStartDecisionPrompt: () -> Unit,
    val onDismissStartDecisionPrompt: () -> Unit,
)

private const val OFF_ROUTE_WAKE_GRACE_MS = 5_000L

@Composable
internal fun rememberNavigateGuidanceRuntime(
    context: Context,
    gpxViewModel: GpxViewModel,
    activeSession: GpxGuidanceSession?,
    session: GpxGuidanceSession?,
    paused: Boolean,
    rawCurrentLocation: Location?,
    recenterTarget: LatLong?,
    offlineMode: Boolean,
    routeStartBehavior: String,
    reverseSuggestionMode: String,
    offRouteThresholdMeters: Int,
    hapticsEnabled: Boolean,
    voiceGuidanceEnabled: Boolean,
    turnAlertsMode: String,
    offRouteAlertsEnabled: Boolean,
    offRouteRepeatSeconds: Int,
    guidanceGpsInAmbient: Boolean,
    brouterGuideBackEnabled: Boolean,
    lastScreenResumeElapsedMs: Long,
    isMetric: Boolean,
    activityProfile: String,
    userWeightKg: Float,
    backpackWeightKg: Float,
    bikeWeightKg: Float,
    recordingActive: Boolean,
    recordingSampleIntervalSeconds: Int,
    gpxFlatSpeedMps: Float,
    gpxAdvancedEtaEnabled: Boolean,
    gpxStaminaAdjustmentEnabled: Boolean,
    gpxUphillVerticalMetersPerHour: Float,
    gpxDownhillVerticalMetersPerHour: Float,
): NavigateGuidanceRuntime {
    val guidanceLocation: LatLong? =
        if (offlineMode) {
            null
        } else {
            rawCurrentLocation?.let { location ->
                LatLong(location.latitude, location.longitude)
            } ?: recenterTarget
        }
    val tuning =
        remember(offRouteThresholdMeters) {
            GpxGuidanceTuning(
                offRouteDistanceMeters = offRouteThresholdMeters.toDouble(),
            )
        }
    var previousGuidanceProgressMeters by
        remember(activeSession?.trackId, activeSession?.reversed) {
            mutableStateOf<Double?>(null)
        }
    val rawState =
        computeTurnByTurnGuidanceState(
            session = activeSession,
            currentLocation = guidanceLocation,
            tuning = tuning,
            previousDistanceFromStartMeters = previousGuidanceProgressMeters,
        )
    LaunchedEffect(activeSession?.trackId, activeSession?.reversed, rawState.distanceFromStartMeters) {
        rawState.distanceFromStartMeters?.let { previousGuidanceProgressMeters = it }
    }
    var offRouteConfirmation by
        remember(activeSession?.trackId, activeSession?.reversed) {
            mutableStateOf(GuidanceOffRouteConfirmationState())
        }
    val etaModelConfig =
        remember(
            gpxFlatSpeedMps,
            gpxAdvancedEtaEnabled,
            gpxStaminaAdjustmentEnabled,
            activityProfile,
            userWeightKg,
            backpackWeightKg,
            bikeWeightKg,
            gpxUphillVerticalMetersPerHour,
            gpxDownhillVerticalMetersPerHour,
        ) {
            GpxEtaModelConfig(
                flatSpeedMps = gpxFlatSpeedMps.toDouble(),
                advancedVerticalRateEnabled = gpxAdvancedEtaEnabled,
                staminaAdjustmentEnabled = gpxStaminaAdjustmentEnabled,
                activityProfile = activityProfile,
                userWeightKg = userWeightKg.toDouble(),
                backpackWeightKg = backpackWeightKg.toDouble(),
                bikeWeightKg = bikeWeightKg.toDouble(),
                uphillVerticalMetersPerHour = gpxUphillVerticalMetersPerHour.toDouble(),
                downhillVerticalMetersPerHour = gpxDownhillVerticalMetersPerHour.toDouble(),
            )
        }
    val routeEtaProjection =
        remember(
            activeSession?.trackId,
            activeSession?.reversed,
            activeSession?.trackPoints,
            etaModelConfig,
        ) {
            activeSession?.let { currentSession ->
                buildRouteEtaProjection(
                    trackPoints = currentSession.trackPoints,
                    config = etaModelConfig,
                )
            }
        }
    val estimatedRemainingSeconds =
        if (rawState.mode == GuidanceMode.FOLLOW_ROUTE) {
            routeEtaProjection?.remainingSecondsAtDistance(rawState.distanceFromStartMeters)
        } else {
            null
        }
    val currentAltitudeMeters =
        rawCurrentLocation
            ?.takeIf { it.hasAltitude() && it.altitude.isFinite() }
            ?.altitude
    val guidanceGpsDeliveryIntervalMs =
        rememberGuidanceGpsDeliveryIntervalMs(
            sessionKey = activeSession?.let { "${it.trackId}:${it.reversed}" },
            locationElapsedRealtimeNanos = rawCurrentLocation?.elapsedRealtimeNanos,
            fallbackIntervalMs =
                expectedGuidanceGpsIntervalMs(
                    recordingActive = recordingActive,
                    recordingSampleIntervalSeconds = recordingSampleIntervalSeconds,
                ),
        )
    LaunchedEffect(
        activeSession?.trackId,
        activeSession?.reversed,
        rawCurrentLocation?.elapsedRealtimeNanos,
        rawCurrentLocation?.time,
        rawState.distanceToRouteMeters,
        offRouteThresholdMeters,
        lastScreenResumeElapsedMs,
    ) {
        if (activeSession == null || rawState.mode != GuidanceMode.FOLLOW_ROUTE) {
            offRouteConfirmation = GuidanceOffRouteConfirmationState()
            return@LaunchedEffect
        }
        if (rawCurrentLocation == null) return@LaunchedEffect
        offRouteConfirmation =
            updateGuidanceOffRouteConfirmation(
                previous = offRouteConfirmation,
                distanceToRouteMeters = rawState.distanceToRouteMeters,
                thresholdMeters = offRouteThresholdMeters.toDouble(),
                allowOffRouteEntry =
                    SystemClock.elapsedRealtime() - lastScreenResumeElapsedMs >=
                        OFF_ROUTE_WAKE_GRACE_MS,
            )
    }
    val state =
        rawState.copy(
            offRoute = offRouteConfirmation.offRoute,
            estimatedRemainingSeconds = estimatedRemainingSeconds,
            currentAltitudeMeters = currentAltitudeMeters,
            alertSessionKey = activeSession?.let { "${it.trackId}:${it.reversed}" },
            alertGpsDeliveryIntervalMs = guidanceGpsDeliveryIntervalMs,
        )
    var guideBackToRouteActive by remember { mutableStateOf(false) }
    var brouterGuideBackRoute by remember { mutableStateOf<List<LatLong>>(emptyList()) }
    var dismissedGuideBackPromptTrackId by remember { mutableStateOf<String?>(null) }
    val guideBackTrackId = activeSession?.trackId
    val guideBackTargetPoint =
        nearestGuidanceRoutePoint(
            session = activeSession,
            currentLocation = guidanceLocation,
        )
    LaunchedEffect(
        state.active,
        state.offRoute,
        guideBackTrackId,
    ) {
        if (!state.active || !state.offRoute) {
            guideBackToRouteActive = false
            brouterGuideBackRoute = emptyList()
            dismissedGuideBackPromptTrackId = null
        }
    }
    val brouterGuideBackState =
        remember(guideBackToRouteActive, brouterGuideBackRoute, guidanceLocation, state) {
            buildBrouterGuideBackState(
                baseState = state,
                active = guideBackToRouteActive,
                route = brouterGuideBackRoute,
                currentLocation = guidanceLocation,
            )
        }
    var latestActiveDisplayState by
        remember(session?.trackId, session?.reversed) {
            mutableStateOf<TurnByTurnGuidanceState?>(null)
        }
    LaunchedEffect(paused, brouterGuideBackState) {
        if (!paused && brouterGuideBackState.active) {
            latestActiveDisplayState = brouterGuideBackState
        }
    }
    val displayState =
        pausedGuidanceDisplayState(
            currentState = brouterGuideBackState,
            latestActiveState = latestActiveDisplayState,
            paused = paused,
        )
    val showGuideBackPrompt =
        state.active &&
            state.offRoute &&
            !guideBackToRouteActive &&
            dismissedGuideBackPromptTrackId != guideBackTrackId
    var pendingStartDecision by remember { mutableStateOf<GuidanceStartDecision?>(null) }
    var dismissedStartDecisionKey by remember { mutableStateOf<String?>(null) }
    var startHereStableSampleCount by remember { mutableStateOf(0) }
    val startDecisionKey =
        pendingStartDecision?.let { decision ->
            "$guideBackTrackId:${activeSession?.reversed}:$decision"
        }
    val startDecisionPrompt =
        pendingStartDecision?.let { decision ->
            when (decision) {
                GuidanceStartDecision.REVERSE_ROUTE ->
                    GuidanceDecisionPrompt(
                        title = "Closer to end",
                        detail = "Follow GPX in reverse?",
                        acceptText = "Reverse",
                        dismissText = "Start",
                    )
                GuidanceStartDecision.START_HERE ->
                    GuidanceDecisionPrompt(
                        title = "On route",
                        detail = "Start from nearest point?",
                        acceptText = "Start",
                        dismissText = "GPX start",
                    )
            }
        }

    LaunchedEffect(
        activeSession,
        guidanceLocation,
        rawCurrentLocation?.accuracy,
        routeStartBehavior,
        reverseSuggestionMode,
        offRouteThresholdMeters,
    ) {
        val currentSession = activeSession
        val location = guidanceLocation
        val rawGuidanceLocation =
            rawCurrentLocation?.let { rawLocation ->
                LatLong(rawLocation.latitude, rawLocation.longitude)
            }
        if (currentSession == null || location == null || currentSession.startReached) {
            pendingStartDecision = null
            dismissedStartDecisionKey = null
            startHereStableSampleCount = 0
            return@LaunchedEffect
        }

        val points = currentSession.trackPoints.map { it.latLong }
        val start = points.firstOrNull()
        val end = points.lastOrNull()
        val projection =
            projectLocationToRoute(
                points = points,
                cumulativeDistancesMeters = currentSession.cumulativeDistancesMeters,
                location = location,
            )
        if (start == null || end == null || projection == null) {
            pendingStartDecision = null
            startHereStableSampleCount = 0
            return@LaunchedEffect
        }

        val distanceToStart = haversineMeters(location, start)
        val distanceToEnd = haversineMeters(location, end)
        val closeToRoute = projection.distanceToRouteMeters <= offRouteThresholdMeters.toDouble()
        val midRouteCandidate =
            closeToRoute &&
                distanceToStart > tuning.startReachedDistanceMeters &&
                projection.distanceFromStartMeters > START_HERE_MIN_PROGRESS_METERS &&
                currentSession.totalDistanceMeters - projection.distanceFromStartMeters > START_HERE_MIN_REMAINING_METERS
        val locationAccurateEnough =
            rawCurrentLocation?.accuracy?.let { accuracy ->
                accuracy <= START_HERE_MAX_ACCURACY_METERS
            } ?: false
        val hasFreshGpsLocation = rawGuidanceLocation != null
        if (midRouteCandidate && locationAccurateEnough) {
            startHereStableSampleCount += 1
        } else {
            startHereStableSampleCount = 0
        }
        val stableMidRouteCandidate =
            midRouteCandidate &&
                hasFreshGpsLocation &&
                startHereStableSampleCount >= START_HERE_STABLE_SAMPLE_COUNT
        val reverseCandidate =
            !currentSession.reversed &&
                reverseSuggestionMode == SettingsRepository.TURN_BY_TURN_REVERSE_SUGGESTION_ASK &&
                distanceToEnd + REVERSE_SUGGESTION_DISTANCE_MARGIN_METERS < distanceToStart &&
                distanceToEnd <= REVERSE_SUGGESTION_MAX_DISTANCE_METERS

        val nextDecision =
            when {
                reverseCandidate -> GuidanceStartDecision.REVERSE_ROUTE
                stableMidRouteCandidate &&
                    routeStartBehavior == SettingsRepository.TURN_BY_TURN_ROUTE_START_NEAREST_POINT -> {
                    gpxViewModel.markTurnByTurnStartReached()
                    null
                }
                stableMidRouteCandidate &&
                    routeStartBehavior == SettingsRepository.TURN_BY_TURN_ROUTE_START_ASK ->
                    GuidanceStartDecision.START_HERE
                else -> null
            }

        val nextKey = nextDecision?.let { "${currentSession.trackId}:${currentSession.reversed}:$it" }
        pendingStartDecision =
            if (nextKey != null && dismissedStartDecisionKey != nextKey) {
                nextDecision
            } else {
                null
            }
    }

    LaunchedEffect(activeSession, guidanceLocation, tuning) {
        if (isGuidanceStartReached(activeSession, guidanceLocation, tuning)) {
            gpxViewModel.markTurnByTurnStartReached()
        }
    }

    TurnByTurnGuidanceHapticEffect(
        context = context,
        state = state,
        currentSpeedMps = rawCurrentLocation?.speed,
        activityProfile = activityProfile,
        hapticsEnabled = hapticsEnabled,
        turnAlertsMode = turnAlertsMode,
        offRouteAlertsEnabled = offRouteAlertsEnabled,
        offRouteRepeatSeconds = offRouteRepeatSeconds,
    )

    TurnByTurnGuidanceVoiceEffect(
        context = context,
        state = state,
        currentSpeedMps = rawCurrentLocation?.speed,
        activityProfile = activityProfile,
        voiceEnabled = voiceGuidanceEnabled,
        turnAlertsMode = turnAlertsMode,
        offRouteAlertsEnabled = offRouteAlertsEnabled,
        offRouteRepeatSeconds = offRouteRepeatSeconds,
        paused = paused,
        isMetric = isMetric,
    )

    LaunchedEffect(
        state.active,
        state.mode,
        state.nextInstruction?.trackPointIndex,
        state.distanceToInstructionMeters?.roundTelemetryMeters(),
        state.distanceToStartMeters?.roundTelemetryMeters(),
        state.distanceToRouteMeters?.roundTelemetryMeters(),
        state.distanceRemainingMeters?.roundTelemetryMeters(),
        state.routeProgressFraction?.roundTelemetryPercent(),
        state.offRoute,
        paused,
        session?.trackId,
        session?.reversed,
        session?.startReached,
        guideBackToRouteActive,
        showGuideBackPrompt,
        pendingStartDecision,
        routeStartBehavior,
        reverseSuggestionMode,
        offRouteThresholdMeters,
        hapticsEnabled,
        voiceGuidanceEnabled,
        turnAlertsMode,
        offRouteAlertsEnabled,
        guidanceGpsInAmbient,
        activityProfile,
        recordingActive,
        recordingSampleIntervalSeconds,
        gpxFlatSpeedMps,
    ) {
        if (!state.active && session == null) return@LaunchedEffect
        DebugTelemetry.log(
            "TurnByTurn",
            buildTurnByTurnTelemetryMessage(
                state = state,
                paused = paused,
                trackId = session?.trackId,
                reversed = session?.reversed,
                startReached = session?.startReached,
                guideBackToRouteActive = guideBackToRouteActive,
                showGuideBackPrompt = showGuideBackPrompt,
                pendingStartDecision = pendingStartDecision,
                routeStartBehavior = routeStartBehavior,
                reverseSuggestionMode = reverseSuggestionMode,
                offRouteThresholdMeters = offRouteThresholdMeters,
                hapticsEnabled = hapticsEnabled,
                voiceGuidanceEnabled = voiceGuidanceEnabled,
                turnAlertsMode = turnAlertsMode,
                offRouteAlertsEnabled = offRouteAlertsEnabled,
                guidanceGpsInAmbient = guidanceGpsInAmbient,
                activityProfile = activityProfile,
                resolvedGpsIntervalMs = guidanceGpsDeliveryIntervalMs,
                resolvedEtaFlatSpeedMps = gpxFlatSpeedMps,
                resolvedTurnAlertMaxDistanceMeters = turnAlertMaxDistanceMeters(activityProfile),
            ),
        )
    }

    return NavigateGuidanceRuntime(
        state = displayState,
        guideBackToRouteActive = guideBackToRouteActive && state.offRoute,
        showGuideBackPrompt = showGuideBackPrompt,
        startDecisionPrompt = startDecisionPrompt,
        onGuideBackToRoute = {
            guideBackToRouteActive = true
            dismissedGuideBackPromptTrackId = guideBackTrackId
            brouterGuideBackRoute = emptyList()
            val origin = guidanceLocation
            val destination = guideBackTargetPoint
            DebugTelemetry.log(
                "TurnByTurn",
                "event=guide_back_request enabled=$brouterGuideBackEnabled " +
                    "origin=${origin != null} destination=${destination != null} " +
                    "distanceToRouteM=${state.distanceToRouteMeters?.toInt() ?: "na"}",
            )
            if (brouterGuideBackEnabled && origin != null && destination != null) {
                gpxViewModel.buildTurnByTurnGuideBackRoute(
                    origin = origin,
                    destination = destination,
                ) { result ->
                    result
                        .onSuccess { route ->
                            DebugTelemetry.log(
                                "TurnByTurn",
                                "event=guide_back_route_success points=${route.size} " +
                                    "distanceM=${route.sumRouteDistanceMeters().toInt()}",
                            )
                            if (guideBackToRouteActive) {
                                brouterGuideBackRoute = route
                            }
                        }.onFailure { error ->
                            DebugTelemetry.log(
                                "TurnByTurn",
                                "event=guide_back_route_failure error=${error.javaClass.simpleName} " +
                                    "message=${error.localizedMessage?.take(80)?.replace(' ', '_') ?: "na"}",
                            )
                        }
                }
            } else if (!brouterGuideBackEnabled) {
                DebugTelemetry.log("TurnByTurn", "event=guide_back_route_skipped reason=disabled")
            } else {
                DebugTelemetry.log("TurnByTurn", "event=guide_back_route_skipped reason=missing_location")
            }
        },
        onDismissGuideBackPrompt = {
            dismissedGuideBackPromptTrackId = guideBackTrackId
        },
        onAcceptStartDecisionPrompt = {
            when (pendingStartDecision) {
                GuidanceStartDecision.REVERSE_ROUTE -> gpxViewModel.reverseTurnByTurnGuidance()
                GuidanceStartDecision.START_HERE -> gpxViewModel.markTurnByTurnStartReached()
                null -> Unit
            }
            dismissedStartDecisionKey = startDecisionKey
            pendingStartDecision = null
        },
        onDismissStartDecisionPrompt = {
            dismissedStartDecisionKey = startDecisionKey
            pendingStartDecision = null
        },
    )
}

private fun List<LatLong>.sumRouteDistanceMeters(): Double =
    zipWithNext().sumOf { (start, end) ->
        haversineMeters(start, end)
    }

@Composable
private fun rememberGuidanceGpsDeliveryIntervalMs(
    sessionKey: String?,
    locationElapsedRealtimeNanos: Long?,
    fallbackIntervalMs: Long,
): Long {
    var previousLocationElapsedRealtimeNanos by remember(sessionKey) { mutableStateOf<Long?>(null) }
    var observedIntervalMs by remember(sessionKey) { mutableStateOf(fallbackIntervalMs) }
    LaunchedEffect(sessionKey, locationElapsedRealtimeNanos, fallbackIntervalMs) {
        val currentNanos = locationElapsedRealtimeNanos?.takeIf { it > 0L }
        val previousNanos = previousLocationElapsedRealtimeNanos
        if (currentNanos != null && previousNanos != null && currentNanos > previousNanos) {
            val intervalMs = (currentNanos - previousNanos) / NANOS_PER_MILLISECOND
            if (intervalMs in MIN_OBSERVED_GPS_INTERVAL_MS..MAX_OBSERVED_GPS_INTERVAL_MS) {
                observedIntervalMs = intervalMs
            }
        } else if (previousNanos == null) {
            observedIntervalMs = fallbackIntervalMs
        }
        previousLocationElapsedRealtimeNanos = currentNanos
    }
    return observedIntervalMs
}

private fun expectedGuidanceGpsIntervalMs(
    recordingActive: Boolean,
    recordingSampleIntervalSeconds: Int,
): Long {
    val userIntervalMs = SettingsRepository.DEFAULT_GPS_INTERVAL_MS
    if (!recordingActive) return userIntervalMs
    val recordingIntervalMs =
        if (recordingSampleIntervalSeconds == SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS) {
            Long.MAX_VALUE
        } else {
            recordingSampleIntervalSeconds.coerceAtLeast(1) * 1_000L
        }
    return minOf(userIntervalMs, recordingIntervalMs)
}

private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val MIN_OBSERVED_GPS_INTERVAL_MS = 250L
private const val MAX_OBSERVED_GPS_INTERVAL_MS = 120_000L
