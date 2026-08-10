package com.glancemap.glancemapwearos.presentation.features.navigate.effects

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.core.service.location.config.WATCH_GPS_ACCURACY_FLOOR_M
import com.glancemap.glancemapwearos.core.service.location.config.WATCH_GPS_ACCURACY_FLOOR_TOLERANCE_M
import com.glancemap.glancemapwearos.core.service.location.model.GpsEnvironmentWarning
import com.glancemap.glancemapwearos.core.service.location.model.LocationScreenState
import com.glancemap.glancemapwearos.core.service.location.model.deliveredSourceModeOrNull
import com.glancemap.glancemapwearos.core.service.location.model.isNonInteractive
import com.glancemap.glancemapwearos.core.service.location.model.resolveLocationTimingProfile
import com.glancemap.glancemapwearos.core.service.location.policy.LocationFixPolicy
import com.glancemap.glancemapwearos.core.service.location.policy.LocationSourceMode
import com.glancemap.glancemapwearos.domain.sensors.CompassViewModel
import com.glancemap.glancemapwearos.presentation.features.maps.RotatableMarker
import com.glancemap.glancemapwearos.presentation.features.maps.mutateLayers
import com.glancemap.glancemapwearos.presentation.features.navigate.GpsFixIndicatorState
import com.glancemap.glancemapwearos.presentation.features.navigate.ImmediateLocationRequestResult
import com.glancemap.glancemapwearos.presentation.features.navigate.LocationViewModel
import com.glancemap.glancemapwearos.presentation.features.navigate.NavigateViewModel
import com.glancemap.glancemapwearos.presentation.features.navigate.UI_WAKE_REACQUIRE_TIMEOUT_SOURCE
import com.glancemap.glancemapwearos.presentation.features.navigate.motion.MarkerMotionAnchorOrigin
import com.glancemap.glancemapwearos.presentation.features.navigate.motion.MarkerMotionController
import com.glancemap.glancemapwearos.presentation.features.navigate.motion.MarkerMotionGpsFix
import com.glancemap.glancemapwearos.presentation.features.navigate.motion.MarkerMotionReading
import com.glancemap.glancemapwearos.presentation.features.navigate.motion.MarkerMotionRenderInputs
import com.glancemap.glancemapwearos.presentation.features.navigate.motion.MarkerMotionSeed
import com.glancemap.glancemapwearos.presentation.features.navigate.motion.MarkerMotionTelemetry
import com.glancemap.glancemapwearos.presentation.features.navigate.motion.markerMotionDistanceMeters
import com.glancemap.glancemapwearos.presentation.features.navigate.motion.markerMotionMetersPerPixel
import com.glancemap.glancemapwearos.presentation.features.navigate.motion.markerMotionRenderDecision
import com.glancemap.glancemapwearos.presentation.features.navigate.motion.shouldRenderMarkerMotion
import com.glancemap.glancemapwearos.presentation.features.navigate.requestLayerRedrawSafely
import com.glancemap.glancemapwearos.presentation.features.navigate.resolveMapCenterForNavigationMarker
import com.glancemap.glancemapwearos.presentation.features.navigate.setCenterForNavigationMarker
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidBitmap
import org.mapsforge.map.android.view.MapView
import kotlin.math.abs

internal data class NavigateLocationUiState(
    val locationMarker: RotatableMarker?,
    val gpsIndicatorState: GpsFixIndicatorState,
    val gpsEnvironmentWarning: GpsEnvironmentWarning,
    val showGpsIndicatorUnpinned: Boolean,
    val watchGpsDegradedWarning: Boolean,
    val lastFixSpeedMps: Float,
    val lastFixBearingDeg: Float?,
)

internal data class WakeAnchorSeed(
    val latLong: LatLong,
    val fixElapsedMs: Long,
    val accuracyM: Float,
    val speedMps: Float,
    val bearingDeg: Float?,
)

@Composable
@Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList")
internal fun rememberNavigateLocationUiState(
    mapView: MapView,
    locationViewModel: LocationViewModel,
    compassViewModel: CompassViewModel,
    navigateViewModel: NavigateViewModel,
    shouldTrackLocation: Boolean,
    shouldFollowPosition: Boolean,
    screenState: LocationScreenState,
    expectedGpsIntervalMs: Long,
    isBikeActivityProfile: Boolean,
    navigationMarkerBitmap: AndroidBitmap,
    suppressLocationMarker: Boolean,
    navigationMarkerAnchorMode: String,
): NavigateLocationUiState {
    val timingProfile =
        remember(expectedGpsIntervalMs) {
            resolveLocationTimingProfile(expectedGpsIntervalMs)
        }
    val markerMotionController =
        remember {
            MarkerMotionController(
                predictionFreshnessMaxAgeMs = timingProfile.markerPredictionFreshnessMaxAgeMs,
                maxAcceptedFixAgeMs = timingProfile.stabilizerMaxAcceptedFixAgeMs,
            )
        }
    val markerMotionSignal = remember { Channel<Unit>(capacity = Channel.CONFLATED) }
    SideEffect {
        markerMotionController.updateTiming(
            predictionFreshnessMaxAgeMs = timingProfile.markerPredictionFreshnessMaxAgeMs,
            maxAcceptedFixAgeMs = timingProfile.stabilizerMaxAcceptedFixAgeMs,
            expectedGpsIntervalMs = expectedGpsIntervalMs,
        )
        markerMotionController.updateActivityProfile(isBikeActivityProfile)
    }

    val latestShouldFollowPosition = rememberUpdatedState(shouldFollowPosition)
    val latestSuppressLocationMarker = rememberUpdatedState(suppressLocationMarker)
    val latestScreenState = rememberUpdatedState(screenState)
    val latestNavigationMarkerAnchorMode = rememberUpdatedState(navigationMarkerAnchorMode)
    val latestMarkerMotionController = rememberUpdatedState(markerMotionController)

    var locationMarker by remember { mutableStateOf<RotatableMarker?>(null) }
    var lastRenderedMarkerLatLong by remember { mutableStateOf<LatLong?>(null) }
    var indicatorFixAtElapsedMs by remember { mutableLongStateOf(0L) }
    var indicatorFixAccuracyM by remember { mutableFloatStateOf(Float.POSITIVE_INFINITY) }
    var indicatorFixFreshMaxAgeMs by remember { mutableLongStateOf(0L) }
    var latestAcceptedFixSpeedMps by remember { mutableFloatStateOf(0f) }
    var latestAcceptedFixBearingDeg by remember { mutableStateOf<Float?>(null) }
    var indicatorLocationAvailable by remember { mutableStateOf(true) }
    var indicatorUnavailableSinceElapsedMs by remember { mutableLongStateOf(0L) }
    var indicatorWatchGpsOnlyActive by remember { mutableStateOf(false) }
    var indicatorWatchGpsDegraded by remember { mutableStateOf(false) }
    var indicatorEnvironmentWarning by remember { mutableStateOf(GpsEnvironmentWarning.NONE) }
    var indicatorSourceAcquisitionStartedAtElapsedMs by remember { mutableLongStateOf(0L) }
    var indicatorSourceEpoch by remember { mutableLongStateOf(0L) }
    var indicatorRequiresFreshLiveFixAfterSourceChange by remember { mutableStateOf(false) }
    var gpsIndicatorAbnormalSinceElapsedMs by remember { mutableLongStateOf(0L) }
    var holdMarkerUntilFreshFix by
        remember(shouldTrackLocation, screenState) {
            mutableStateOf(shouldTrackLocation && !screenState.isNonInteractive)
        }
    var holdMarkerStartedAtElapsedMs by
        remember(shouldTrackLocation, screenState) { mutableLongStateOf(0L) }
    var trackingActivatedAtElapsedMs by
        remember(shouldTrackLocation, screenState) { mutableLongStateOf(0L) }
    var postWakePredictionHoldUntilElapsedMs by
        remember(shouldTrackLocation, screenState) { mutableLongStateOf(0L) }
    var lastAcceptedLocationFixElapsedMs by remember { mutableLongStateOf(0L) }
    var lastMarkerVisualUpdateAtElapsedMs by remember { mutableLongStateOf(0L) }
    var lastMarkerMotionAdvanceAtElapsedMs by remember { mutableLongStateOf(0L) }
    var lastInteractiveStaleRefreshAtElapsedMs by remember { mutableLongStateOf(Long.MIN_VALUE) }
    var lastInteractiveStaleRefreshStateLabel by remember { mutableStateOf<String?>(null) }
    var lastWakeReacquireStartedAtElapsedMs by remember { mutableLongStateOf(Long.MIN_VALUE) }
    var activeWakeSessionId by remember { mutableLongStateOf(0L) }
    var nextWakeSessionId by remember { mutableLongStateOf(0L) }
    var wakeAnchorSeeded by
        remember(shouldTrackLocation, screenState) { mutableStateOf(false) }
    var wasInteractiveTrackingActive by remember { mutableStateOf(false) }

    var gpsIndicatorClockMs by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }

    LaunchedEffect(shouldTrackLocation, screenState) {
        if (shouldTrackLocation && !screenState.isNonInteractive) return@LaunchedEffect
        // Screen-off keeps only the last visual anchor. A real tracking stop clears all motion memory.
        holdMarkerUntilFreshFix = false
        holdMarkerStartedAtElapsedMs = 0L
        trackingActivatedAtElapsedMs = 0L
        postWakePredictionHoldUntilElapsedMs = 0L
        lastAcceptedLocationFixElapsedMs = 0L
        latestAcceptedFixSpeedMps = 0f
        latestAcceptedFixBearingDeg = null
        lastMarkerVisualUpdateAtElapsedMs = 0L
        lastMarkerMotionAdvanceAtElapsedMs = 0L
        lastInteractiveStaleRefreshAtElapsedMs = Long.MIN_VALUE
        lastInteractiveStaleRefreshStateLabel = null
        activeWakeSessionId = 0L
        wakeAnchorSeeded = false
        wasInteractiveTrackingActive = false
        if (screenState.isNonInteractive) {
            markerMotionController.requireFreshFixForPrediction(reason = "screen_non_interactive")
        } else {
            markerMotionController.reset(
                reason = "tracking_stopped",
                nowElapsedMs = android.os.SystemClock.elapsedRealtime(),
            )
        }
    }

    val interactiveTrackingActive = shouldTrackLocation && !screenState.isNonInteractive

    // Only re-enter wake handling when interactive tracking actually starts again.
    LaunchedEffect(interactiveTrackingActive) {
        val shouldStartWakeSession =
            shouldStartInteractiveWakeSession(
                wasInteractiveTrackingActive = wasInteractiveTrackingActive,
                shouldTrackLocation = shouldTrackLocation,
                screenState = screenState,
            )
        wasInteractiveTrackingActive = interactiveTrackingActive
        if (!shouldStartWakeSession) return@LaunchedEffect

        val nowElapsedMs = android.os.SystemClock.elapsedRealtime()
        trackingActivatedAtElapsedMs = nowElapsedMs
        postWakePredictionHoldUntilElapsedMs = 0L
        wakeAnchorSeeded = false
        val retainedAnchor =
            markerMotionController.retainedAnchorSeed
                ?.takeIf { anchor ->
                    anchor.reading.fixElapsedMs > 0L &&
                        (nowElapsedMs - anchor.reading.fixElapsedMs).coerceAtLeast(0L) <=
                        RETAINED_VISUAL_ANCHOR_MAX_AGE_MS
                }
        markerMotionController.reset(
            reason = "interactive_start",
            nowElapsedMs = nowElapsedMs,
        )
        var resumePredictionFromWakeAnchor = false
        val cachedLocation = locationViewModel.currentLocation.value
        val cachedAnchor =
            resolveWakeAnchorSeedOrNull(
                location = cachedLocation,
                receivedAtElapsedMs = nowElapsedMs,
                nowWallClockMs = System.currentTimeMillis(),
                maxAgeMs =
                    computeWarmReturnAnchorMaxAgeMs(
                        expectedGpsIntervalMs = expectedGpsIntervalMs,
                        speedMps =
                            cachedLocation
                                ?.takeIf { it.hasSpeed() && it.speed.isFinite() }
                                ?.speed
                                ?: 0f,
                    ),
                maxAccuracyM = WARM_RETURN_ANCHOR_MAX_ACCURACY_M,
            )?.let { anchor ->
                MarkerMotionSeed(
                    latLong = anchor.latLong,
                    reading =
                        MarkerMotionReading(
                            fixElapsedMs = anchor.fixElapsedMs,
                            accuracyM = anchor.accuracyM,
                            speedMps = anchor.speedMps,
                            bearingDeg = anchor.bearingDeg,
                        ),
                    sourceMode =
                        cachedLocation?.deliveredSourceModeOrNull()
                            ?: retainedAnchor?.sourceMode
                            ?: if (indicatorWatchGpsOnlyActive) {
                                LocationSourceMode.WATCH_GPS
                            } else {
                                LocationSourceMode.AUTO_FUSED
                            },
                    origin = MarkerMotionAnchorOrigin.CACHED_LOCATION,
                )
            }
        listOfNotNull(cachedAnchor, retainedAnchor)
            .maxByOrNull { it.reading.fixElapsedMs }
            ?.let { anchor ->
                resumePredictionFromWakeAnchor =
                    shouldResumePredictionFromWakeAnchor(
                        anchor = anchor,
                        receivedAtElapsedMs = nowElapsedMs,
                        expectedGpsIntervalMs = expectedGpsIntervalMs,
                    )
                markerMotionController.seedAnchor(
                    seed = anchor,
                    nowElapsedMs = nowElapsedMs,
                    allowPredictionUntilFreshFix = resumePredictionFromWakeAnchor,
                )
                lastRenderedMarkerLatLong = anchor.latLong
                wakeAnchorSeeded = true
                if (!latestSuppressLocationMarker.value) {
                    val existingMarker = locationMarker
                    if (existingMarker == null) {
                        removeAllRotatableMarkers(mapView)
                        locationMarker =
                            RotatableMarker(
                                anchor.latLong,
                                navigationMarkerBitmap,
                                -navigationMarkerBitmap.width / 2,
                                -navigationMarkerBitmap.height / 2,
                            ).also { marker ->
                                mapView.mutateLayers { layers -> layers.add(marker) }
                            }
                    } else {
                        existingMarker.latLong = anchor.latLong
                    }
                    lastMarkerVisualUpdateAtElapsedMs = nowElapsedMs
                    lastMarkerMotionAdvanceAtElapsedMs = nowElapsedMs
                    if (
                        shouldCenterOnNavigationMarker(
                            mapView = mapView,
                            shouldFollowPosition = latestShouldFollowPosition.value,
                            target = anchor.latLong,
                            markerAnchorMode = latestNavigationMarkerAnchorMode.value,
                            currentCenter = mapView.model.mapViewPosition.center,
                        )
                    ) {
                        mapView.setCenterForNavigationMarker(
                            anchor.latLong,
                            latestNavigationMarkerAnchorMode.value,
                        )
                    }
                    mapView.requestLayerRedrawSafely()
                }
                DebugTelemetry.log(
                    NAV_MARKER_TELEMETRY_TAG,
                    "warmReturn restored=true source=${anchor.origin.telemetryLabel} " +
                        "ageMs=${(nowElapsedMs - anchor.reading.fixElapsedMs).coerceAtLeast(0L)} " +
                        "accuracyM=${anchor.reading.accuracyM} speedMps=${anchor.reading.speedMps} " +
                        "predictionContinues=$resumePredictionFromWakeAnchor",
                )
            }
        val wakeReacquireInCooldown =
            isWakeReacquireCooldownActive(
                nowElapsedMs = nowElapsedMs,
                lastStartedAtElapsedMs = lastWakeReacquireStartedAtElapsedMs,
                cooldownMs = WAKE_REACQUIRE_COOLDOWN_MS,
            )
        if (wakeReacquireInCooldown) {
            holdMarkerUntilFreshFix = false
            holdMarkerStartedAtElapsedMs = 0L
            activeWakeSessionId = 0L
            return@LaunchedEffect
        }
        nextWakeSessionId += 1L
        activeWakeSessionId = nextWakeSessionId
        lastWakeReacquireStartedAtElapsedMs = nowElapsedMs
        holdMarkerUntilFreshFix = !resumePredictionFromWakeAnchor
        holdMarkerStartedAtElapsedMs = nowElapsedMs.takeIf { holdMarkerUntilFreshFix } ?: 0L
        logWakeSessionEvent(
            stage = "start",
            sessionId = activeWakeSessionId,
            nowElapsedMs = nowElapsedMs,
            reason =
                when {
                    resumePredictionFromWakeAnchor -> "seeded_predicting"
                    wakeAnchorSeeded -> "seeded_held"
                    else -> "no_anchor"
                },
        )
        val immediateRequestResult =
            locationViewModel.requestImmediateLocation(source = "ui_startup_fresh_fix")
        if (immediateRequestResult == ImmediateLocationRequestResult.SKIPPED_FRESH_WAKE_FIX) {
            val skippedWakeSessionId = activeWakeSessionId
            holdMarkerUntilFreshFix = false
            holdMarkerStartedAtElapsedMs = 0L
            activeWakeSessionId = 0L
            logWakeSessionEvent(
                stage = "reuse_fresh_fix",
                sessionId = skippedWakeSessionId,
                nowElapsedMs = nowElapsedMs,
                reason = "fresh_existing_fix",
            )
            return@LaunchedEffect
        }
        if (!resumePredictionFromWakeAnchor) {
            markerMotionController.requireFreshFixForPrediction()
        }
    }

    LaunchedEffect(activeWakeSessionId, shouldTrackLocation, screenState, locationViewModel) {
        val wakeSessionId = activeWakeSessionId
        if (wakeSessionId <= 0L || !shouldTrackLocation || screenState.isNonInteractive) {
            return@LaunchedEffect
        }
        delay(WAKE_REACQUIRE_TIMEOUT_MS)
        val wakeSessionStillActive =
            activeWakeSessionId == wakeSessionId && holdMarkerUntilFreshFix
        val interactiveTrackingActive = shouldTrackLocation && !screenState.isNonInteractive
        if (!wakeSessionStillActive || !interactiveTrackingActive) {
            return@LaunchedEffect
        }
        logWakeSessionEvent(
            stage = "timeout_refresh",
            sessionId = wakeSessionId,
            nowElapsedMs = android.os.SystemClock.elapsedRealtime(),
        )
        locationViewModel.requestImmediateLocation(
            source = UI_WAKE_REACQUIRE_TIMEOUT_SOURCE,
        )
    }

    LaunchedEffect(screenState) {
        if (screenState.isNonInteractive) return@LaunchedEffect
        while (isActive) {
            gpsIndicatorClockMs = android.os.SystemClock.elapsedRealtime()
            delay(1000L)
        }
    }

    LaunchedEffect(locationViewModel) {
        locationViewModel.gpsSignalSnapshot.collect { signal ->
            indicatorFixAtElapsedMs = signal.lastFixElapsedRealtimeMs
            indicatorFixAccuracyM = signal.lastFixAccuracyM
            indicatorFixFreshMaxAgeMs = signal.lastFixFreshMaxAgeMs
            indicatorLocationAvailable = signal.isLocationAvailable
            indicatorUnavailableSinceElapsedMs = signal.unavailableSinceElapsedMs
            indicatorWatchGpsOnlyActive = signal.watchGpsOnlyActive
            indicatorWatchGpsDegraded = signal.watchGpsOnlyActive && signal.watchGpsDegraded
            indicatorEnvironmentWarning = signal.environmentWarning
            indicatorSourceAcquisitionStartedAtElapsedMs = signal.sourceAcquisitionStartedElapsedMs
            indicatorSourceEpoch = signal.sourceEpoch
            indicatorRequiresFreshLiveFixAfterSourceChange = signal.requiresFreshLiveFixAfterSourceChange
            markerMotionSignal.trySend(Unit)
        }
    }

    val gpsStaleIndicatorThresholdMs = timingProfile.indicatorStaleThresholdMs
    val gpsIndicatorRawState =
        resolveGpsIndicatorState(
            isLocationAvailable = indicatorLocationAvailable,
            unavailableSinceElapsedMs = indicatorUnavailableSinceElapsedMs,
            lastFixAtElapsedMs = indicatorFixAtElapsedMs,
            accuracyM = indicatorFixAccuracyM,
            watchGpsOnlyActive = indicatorWatchGpsOnlyActive,
            requiresFreshLiveFixAfterSourceChange = indicatorRequiresFreshLiveFixAfterSourceChange,
            nowElapsedMs = gpsIndicatorClockMs,
            staleThresholdMs = gpsStaleIndicatorThresholdMs,
        )
    val gpsIndicatorWarningCondition =
        shouldTrackLocation &&
            gpsIndicatorRawState != GpsFixIndicatorState.GOOD &&
            gpsIndicatorRawState != GpsFixIndicatorState.UNAVAILABLE
    LaunchedEffect(
        gpsIndicatorWarningCondition,
        indicatorSourceEpoch,
        indicatorSourceAcquisitionStartedAtElapsedMs,
    ) {
        gpsIndicatorAbnormalSinceElapsedMs =
            when {
                !gpsIndicatorWarningCondition -> 0L
                indicatorRequiresFreshLiveFixAfterSourceChange &&
                    indicatorSourceAcquisitionStartedAtElapsedMs > 0L ->
                    indicatorSourceAcquisitionStartedAtElapsedMs
                gpsIndicatorAbnormalSinceElapsedMs <= 0L -> android.os.SystemClock.elapsedRealtime()
                else -> gpsIndicatorAbnormalSinceElapsedMs
            }
    }
    val gpsIndicatorEscalatedState =
        resolveGpsIndicatorEscalationState(
            rawState = gpsIndicatorRawState,
            abnormalSinceElapsedMs = gpsIndicatorAbnormalSinceElapsedMs,
            nowElapsedMs = gpsIndicatorClockMs,
        )
    val gpsIndicatorDisplayRawState =
        resolveGpsIndicatorDisplayState(
            rawState = gpsIndicatorEscalatedState,
        )
    val activeEnvironmentWarning =
        if (shouldTrackLocation) {
            indicatorEnvironmentWarning
        } else {
            GpsEnvironmentWarning.NONE
        }
    val gpsIndicatorState =
        resolveGpsIndicatorStateForEnvironment(
            rawState = gpsIndicatorDisplayRawState,
            environmentWarning = activeEnvironmentWarning,
        )
    val watchGpsDegradedWarning =
        shouldTrackLocation &&
            indicatorWatchGpsDegraded
    val showGpsIndicatorUnpinned =
        shouldShowGpsIndicatorUnpinned(
            gpsIndicatorState = gpsIndicatorState,
            watchGpsDegradedWarning = watchGpsDegradedWarning,
        )

    LaunchedEffect(
        indicatorSourceEpoch,
        indicatorRequiresFreshLiveFixAfterSourceChange,
    ) {
        if (
            !shouldHoldMarkerForLiveSourceHandoff(
                sourceEpoch = indicatorSourceEpoch,
                requiresFreshLiveFix = indicatorRequiresFreshLiveFixAfterSourceChange,
            )
        ) {
            return@LaunchedEffect
        }
        // Keep the last rendered marker visible during a real Phone/Watch handoff. Motion is
        // cleared so the old source cannot drive prediction; the first accepted fix from the new
        // source resumes updates on the existing marker without a visible gap.
        lastAcceptedLocationFixElapsedMs = 0L
        latestAcceptedFixSpeedMps = 0f
        latestAcceptedFixBearingDeg = null
        lastMarkerMotionAdvanceAtElapsedMs = 0L
        markerMotionController.reset(
            reason = "location_source_changed",
            nowElapsedMs = android.os.SystemClock.elapsedRealtime(),
        )
        markerMotionController.requireFreshFixForPrediction(reason = "location_source_changed")
        mapView.requestLayerRedrawSafely()
    }

    LaunchedEffect(mapView, navigationMarkerBitmap) {
        if (latestSuppressLocationMarker.value) {
            locationMarker?.let { marker ->
                mapView.mutateLayers { layers -> layers.remove(marker) }
            }
            locationMarker = null
            lastRenderedMarkerLatLong = null
            mapView.requestLayerRedrawSafely()
            return@LaunchedEffect
        }
        val currentMarker = locationMarker
        if (currentMarker == null) {
            val fallbackLatLong = lastRenderedMarkerLatLong ?: return@LaunchedEffect
            locationMarker =
                RotatableMarker(
                    fallbackLatLong,
                    navigationMarkerBitmap,
                    -navigationMarkerBitmap.width / 2,
                    -navigationMarkerBitmap.height / 2,
                ).also { marker ->
                    mapView.mutateLayers { layers -> layers.add(marker) }
                    lastMarkerVisualUpdateAtElapsedMs = android.os.SystemClock.elapsedRealtime()
                    lastMarkerMotionAdvanceAtElapsedMs = android.os.SystemClock.elapsedRealtime()
                    mapView.requestLayerRedrawSafely()
                }
            return@LaunchedEffect
        }

        val latLong = currentMarker.latLong ?: return@LaunchedEffect
        val heading = currentMarker.heading
        val isVisible = currentMarker.isVisible
        mapView.mutateLayers { layers -> layers.remove(currentMarker) }
        locationMarker =
            RotatableMarker(
                latLong,
                navigationMarkerBitmap,
                -navigationMarkerBitmap.width / 2,
                -navigationMarkerBitmap.height / 2,
            ).also { marker ->
                marker.heading = heading
                marker.isVisible = isVisible
                mapView.mutateLayers { layers -> layers.add(marker) }
                lastMarkerVisualUpdateAtElapsedMs = android.os.SystemClock.elapsedRealtime()
                mapView.requestLayerRedrawSafely()
            }
    }

    LaunchedEffect(suppressLocationMarker, mapView) {
        if (!suppressLocationMarker) return@LaunchedEffect
        locationMarker?.let { marker ->
            mapView.mutateLayers { layers -> layers.remove(marker) }
        }
        locationMarker = null
        lastRenderedMarkerLatLong = null
        holdMarkerUntilFreshFix = false
        holdMarkerStartedAtElapsedMs = 0L
        trackingActivatedAtElapsedMs = 0L
        postWakePredictionHoldUntilElapsedMs = 0L
        lastAcceptedLocationFixElapsedMs = 0L
        latestAcceptedFixSpeedMps = 0f
        latestAcceptedFixBearingDeg = null
        lastMarkerVisualUpdateAtElapsedMs = 0L
        lastMarkerMotionAdvanceAtElapsedMs = 0L
        lastInteractiveStaleRefreshAtElapsedMs = Long.MIN_VALUE
        lastInteractiveStaleRefreshStateLabel = null
        activeWakeSessionId = 0L
        wakeAnchorSeeded = false
        markerMotionController.reset(
            reason = "marker_hidden",
            nowElapsedMs = android.os.SystemClock.elapsedRealtime(),
        )
        mapView.requestLayerRedrawSafely()
    }

    // Restores old working behavior: center only when shouldFollowPosition is true.
    LaunchedEffect(
        locationViewModel,
        mapView,
        markerMotionController,
        expectedGpsIntervalMs,
        navigationMarkerBitmap,
    ) {
        locationViewModel.currentLocation
            .filterNotNull()
            .collect { loc ->
                val ll = toValidLatLongOrNull(loc.latitude, loc.longitude) ?: return@collect
                compassViewModel.updateDeclinationFromLocation(loc)
                if (latestSuppressLocationMarker.value) return@collect

                val receivedAtElapsedMs = android.os.SystemClock.elapsedRealtime()
                val fixElapsedMs =
                    resolveLocationFixElapsedRealtimeMs(
                        elapsedRealtimeNanos = loc.elapsedRealtimeNanos,
                        utcTimeMs = loc.time,
                        receivedAtElapsedMs = receivedAtElapsedMs,
                        nowWallClockMs = System.currentTimeMillis(),
                    )
                val localFixAgeMs =
                    if (fixElapsedMs > 0L) {
                        (receivedAtElapsedMs - fixElapsedMs).coerceAtLeast(0L)
                    } else {
                        Long.MAX_VALUE
                    }
                val startupFreshFixMaxAgeMs =
                    resolveStartupFreshFixMaxAgeMs(
                        expectedGpsIntervalMs = expectedGpsIntervalMs,
                        serviceFreshMaxAgeMs = indicatorFixFreshMaxAgeMs,
                    )
                val shouldIgnoreStalePreWakeFix =
                    lastAcceptedLocationFixElapsedMs <= 0L &&
                        activeWakeSessionId <= 0L &&
                        !wakeAnchorSeeded &&
                        localFixAgeMs > startupFreshFixMaxAgeMs
                if (shouldIgnoreStalePreWakeFix) {
                    return@collect
                }
                val wakeSnapEligible =
                    localFixAgeMs <= computeWakeReacquireSnapMaxAgeMs(expectedGpsIntervalMs) &&
                        loc.accuracy.isFinite() &&
                        loc.accuracy <= WAKE_REACQUIRE_SNAP_MAX_ACCURACY_M
                val wakeReleaseEligible =
                    localFixAgeMs <= computeWakeReacquireReleaseMaxAgeMs(expectedGpsIntervalMs)
                val previousAcceptedFixGapMs =
                    resolveAcceptedFixGapMs(
                        previousFixElapsedMs = lastAcceptedLocationFixElapsedMs,
                        currentFixElapsedMs = fixElapsedMs,
                    )
                val fixFromCurrentTrackingSession =
                    trackingActivatedAtElapsedMs <= 0L ||
                        (
                            fixElapsedMs > 0L &&
                                fixElapsedMs + TRACKING_SESSION_FIX_MAX_SKEW_MS >= trackingActivatedAtElapsedMs
                        )
                val holdTimedOut =
                    holdMarkerStartedAtElapsedMs > 0L &&
                        (receivedAtElapsedMs - holdMarkerStartedAtElapsedMs)
                            .coerceAtLeast(0L) >= WAKE_REACQUIRE_TIMEOUT_MS
                val releaseFromWakeHold =
                    shouldReleaseWakeReacquireHold(
                        holdMarkerUntilFreshFix = holdMarkerUntilFreshFix,
                        fixFromCurrentTrackingSession = fixFromCurrentTrackingSession,
                        wakeSnapEligible = wakeSnapEligible,
                        wakeReleaseEligible = wakeReleaseEligible,
                        holdTimedOut = holdTimedOut,
                    )
                val resolveWakeSessionFromAcceptedFix =
                    shouldResolveWakeSessionFromAcceptedFix(
                        activeWakeSessionId = activeWakeSessionId,
                        fixFromCurrentTrackingSession = fixFromCurrentTrackingSession,
                        wakeSnapEligible = wakeSnapEligible,
                    )
                if (holdMarkerUntilFreshFix && !releaseFromWakeHold) {
                    return@collect
                }
                val keepWakeAnchorForCorrection = releaseFromWakeHold && wakeAnchorSeeded
                if (releaseFromWakeHold) {
                    val releasedWakeSessionId = activeWakeSessionId
                    holdMarkerUntilFreshFix = false
                    holdMarkerStartedAtElapsedMs = 0L
                    postWakePredictionHoldUntilElapsedMs =
                        receivedAtElapsedMs + POST_WAKE_PREDICTION_GRACE_MS
                    activeWakeSessionId = 0L
                    if (releasedWakeSessionId > 0L) {
                        logWakeSessionEvent(
                            stage = "cancel",
                            sessionId = releasedWakeSessionId,
                            nowElapsedMs = receivedAtElapsedMs,
                            reason = if (wakeSnapEligible) "fresh_fix" else "timeout_release",
                            fixAgeMs = localFixAgeMs,
                        )
                    }
                    if (keepWakeAnchorForCorrection) {
                        wakeAnchorSeeded = false
                    } else {
                        markerMotionController.reset(
                            reason = "fresh_fix_release",
                            nowElapsedMs = receivedAtElapsedMs,
                        )
                    }
                } else if (resolveWakeSessionFromAcceptedFix) {
                    val resolvedWakeSessionId = activeWakeSessionId
                    holdMarkerUntilFreshFix = false
                    holdMarkerStartedAtElapsedMs = 0L
                    activeWakeSessionId = 0L
                    wakeAnchorSeeded = false
                    logWakeSessionEvent(
                        stage = "cancel",
                        sessionId = resolvedWakeSessionId,
                        nowElapsedMs = receivedAtElapsedMs,
                        reason = "accepted_fix",
                        fixAgeMs = localFixAgeMs,
                    )
                }

                navigateViewModel.onLocationUpdate(ll)
                val motionSpeedMps =
                    if (loc.hasSpeed() && loc.speed.isFinite()) {
                        loc.speed
                    } else {
                        null
                    }
                val motionBearingDeg = if (loc.hasBearing()) loc.bearing else null
                val motionSpeedAccuracyMps =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && loc.hasSpeedAccuracy()) {
                        loc.speedAccuracyMetersPerSecond
                    } else {
                        null
                    }
                val motionBearingAccuracyDeg =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && loc.hasBearingAccuracy()) {
                        loc.bearingAccuracyDegrees
                    } else {
                        null
                    }
                val markerSourceMode =
                    loc.deliveredSourceModeOrNull()
                        ?: if (indicatorWatchGpsOnlyActive) {
                            LocationSourceMode.WATCH_GPS
                        } else {
                            LocationSourceMode.AUTO_FUSED
                        }
                val markerVisible = shouldRenderLocationVisualUpdate(latestScreenState.value)

                val motionUpdate =
                    markerMotionController.onGpsFix(
                        fix =
                            MarkerMotionGpsFix(
                                latLong = ll,
                                nowElapsedMs = receivedAtElapsedMs,
                                reading =
                                    MarkerMotionReading(
                                        fixElapsedMs = fixElapsedMs,
                                        accuracyM = loc.accuracy,
                                        speedMps = motionSpeedMps,
                                        bearingDeg = motionBearingDeg,
                                        speedAccuracyMps = motionSpeedAccuracyMps,
                                        bearingAccuracyDeg = motionBearingAccuracyDeg,
                                    ),
                                allowLargeCorrection =
                                    shouldBypassCorrectionClamp(
                                        releaseFromWakeHold = releaseFromWakeHold,
                                        previousAcceptedFixGapMs = previousAcceptedFixGapMs,
                                        expectedGpsIntervalMs = expectedGpsIntervalMs,
                                    ),
                                sourceMode = markerSourceMode,
                                isMarkerVisible = markerVisible,
                            ),
                    )
                val displayLatLong = motionUpdate.displayedLatLong
                markerMotionSignal.trySend(Unit)
                if (motionUpdate.fixAccepted) {
                    latestAcceptedFixSpeedMps = motionSpeedMps ?: 0f
                    latestAcceptedFixBearingDeg = motionBearingDeg
                    lastAcceptedLocationFixElapsedMs =
                        fixElapsedMs.takeIf { it > 0L } ?: receivedAtElapsedMs
                }

                // Continue feeding the motion controller while the display is off, but avoid
                // mutating Mapsforge state or requesting an invisible redraw. The interactive
                // wake path restores the latest cached anchor before requesting a fresh fix.
                if (!markerVisible) return@collect
                if (motionUpdate.fixAccepted) {
                    MarkerMotionTelemetry.recordFixAwaitingFirstRender(receivedAtElapsedMs)
                }

                val previousRenderedMarkerLatLong = lastRenderedMarkerLatLong
                val metersPerPixel =
                    markerMotionMetersPerPixel(
                        latitude = displayLatLong.latitude,
                        zoomLevel = mapView.model.mapViewPosition.zoomLevel,
                        tileSize = mapView.model.displayModel.tileSize,
                    )
                val visualMotionStatus =
                    markerMotionController.visualMotionStatus(
                        nowElapsedMs = receivedAtElapsedMs,
                        serviceFreshnessMaxAgeMs = indicatorFixFreshMaxAgeMs,
                    )
                val renderDecision =
                    markerMotionRenderDecision(
                        MarkerMotionRenderInputs(
                            isInteractive = !latestScreenState.value.isNonInteractive,
                            isFollowingPosition = latestShouldFollowPosition.value,
                            isMoving = visualMotionStatus.isMoving,
                            isFresh = visualMotionStatus.isFresh,
                            hasPendingVisualCorrection =
                                markerMotionController.hasPendingVisualCorrection(receivedAtElapsedMs),
                            metersPerPixel = metersPerPixel,
                        ),
                    )
                val renderedMotion =
                    shouldRenderMarkerMotion(
                        previous = previousRenderedMarkerLatLong,
                        candidate = displayLatLong,
                        decision = renderDecision,
                    )
                if (!renderedMotion) return@collect
                if (locationMarker == null) {
                    removeAllRotatableMarkers(mapView)
                    locationMarker =
                        RotatableMarker(
                            displayLatLong,
                            navigationMarkerBitmap,
                            -navigationMarkerBitmap.width / 2,
                            -navigationMarkerBitmap.height / 2,
                        ).also { marker ->
                            mapView.mutateLayers { layers -> layers.add(marker) }
                        }
                } else {
                    locationMarker?.latLong = displayLatLong
                }
                lastRenderedMarkerLatLong = displayLatLong
                lastMarkerVisualUpdateAtElapsedMs = receivedAtElapsedMs
                val displacementM =
                    previousRenderedMarkerLatLong?.let { previous ->
                        markerMotionDistanceMeters(previous, displayLatLong)
                    }
                MarkerMotionTelemetry.recordMotionRendered(
                    nowElapsedMs = receivedAtElapsedMs,
                    displacementM = displacementM,
                    displacementPx =
                        displacementM?.let { distanceM ->
                            metersPerPixel
                                ?.takeIf { it > 0.0 }
                                ?.let { (distanceM / it).toFloat() }
                        },
                )
                lastMarkerMotionAdvanceAtElapsedMs = receivedAtElapsedMs

                // The marker already passed the scale-aware displacement gate. Move the map on
                // that same frame so follow mode cannot accumulate a separate centering threshold.
                if (latestShouldFollowPosition.value) {
                    mapView.setCenterForNavigationMarker(displayLatLong, latestNavigationMarkerAnchorMode.value)
                } else {
                    mapView.requestLayerRedrawSafely()
                }
            }
    }

    // Cleanup marker when leaving screen.
    DisposableEffect(mapView) {
        onDispose {
            locationMarker?.let { marker ->
                mapView.mutateLayers { layers -> layers.remove(marker) }
            }
            locationMarker = null
            lastRenderedMarkerLatLong = null
            holdMarkerUntilFreshFix = false
            holdMarkerStartedAtElapsedMs = 0L
            trackingActivatedAtElapsedMs = 0L
            postWakePredictionHoldUntilElapsedMs = 0L
            lastAcceptedLocationFixElapsedMs = 0L
            latestAcceptedFixSpeedMps = 0f
            latestAcceptedFixBearingDeg = null
            lastMarkerVisualUpdateAtElapsedMs = 0L
            lastMarkerMotionAdvanceAtElapsedMs = 0L
            lastInteractiveStaleRefreshAtElapsedMs = Long.MIN_VALUE
            lastInteractiveStaleRefreshStateLabel = null
            activeWakeSessionId = 0L
            wakeAnchorSeeded = false
            latestMarkerMotionController.value.reset(
                reason = "dispose",
                nowElapsedMs = android.os.SystemClock.elapsedRealtime(),
            )
        }
    }

    // Motion prediction loop.
    LaunchedEffect(
        mapView,
        markerMotionController,
        shouldTrackLocation,
        shouldFollowPosition,
        screenState,
        suppressLocationMarker,
    ) {
        if (
            !shouldRunMarkerPredictionLoop(
                shouldTrackLocation = shouldTrackLocation,
                screenState = screenState,
                suppressLocationMarker = suppressLocationMarker,
            )
        ) {
            return@LaunchedEffect
        }
        while (isActive) {
            val waitStartedAtElapsedMs = android.os.SystemClock.elapsedRealtime()
            val scaleAnchor = lastRenderedMarkerLatLong ?: mapView.model.mapViewPosition.center
            val postWakeHoldRemainingMs =
                (postWakePredictionHoldUntilElapsedMs - waitStartedAtElapsedMs).coerceAtLeast(0L)
            val canProduceVisualSample =
                !holdMarkerUntilFreshFix &&
                    postWakeHoldRemainingMs <= 0L &&
                    indicatorLocationAvailable &&
                    markerMotionController.canProduceVisualSample(
                        nowElapsedMs = waitStartedAtElapsedMs,
                        serviceFreshnessMaxAgeMs = indicatorFixFreshMaxAgeMs,
                        watchGpsDegraded = indicatorWatchGpsDegraded,
                    )
            val waitMotionStatus =
                markerMotionController.visualMotionStatus(
                    nowElapsedMs = waitStartedAtElapsedMs,
                    serviceFreshnessMaxAgeMs = indicatorFixFreshMaxAgeMs,
                )
            val waitDecision =
                markerMotionRenderDecision(
                    MarkerMotionRenderInputs(
                        isInteractive = !latestScreenState.value.isNonInteractive,
                        isFollowingPosition = latestShouldFollowPosition.value,
                        isMoving = canProduceVisualSample && waitMotionStatus.isMoving,
                        isFresh = canProduceVisualSample && waitMotionStatus.isFresh,
                        hasPendingVisualCorrection =
                            canProduceVisualSample &&
                                markerMotionController.hasPendingVisualCorrection(waitStartedAtElapsedMs),
                        metersPerPixel =
                            markerMotionMetersPerPixel(
                                latitude = scaleAnchor.latitude,
                                zoomLevel = mapView.model.mapViewPosition.zoomLevel,
                                tileSize = mapView.model.displayModel.tileSize,
                            ),
                    ),
                )
            val nextTickMs =
                postWakeHoldRemainingMs
                    .takeIf { it > 0L }
                    ?: waitDecision.tickIntervalMs
            if (nextTickMs == null) {
                markerMotionSignal.receive()
            } else {
                withTimeoutOrNull(nextTickMs) {
                    markerMotionSignal.receive()
                }
            }
            val nowElapsedMs = android.os.SystemClock.elapsedRealtime()
            if (
                holdMarkerUntilFreshFix ||
                isPostWakePredictionHoldActive(
                    nowElapsedMs = nowElapsedMs,
                    holdUntilElapsedMs = postWakePredictionHoldUntilElapsedMs,
                )
            ) {
                continue
            }
            if (!indicatorLocationAvailable) continue
            val predicted =
                markerMotionController.predict(
                    nowElapsedMs = nowElapsedMs,
                    serviceFreshnessMaxAgeMs = indicatorFixFreshMaxAgeMs,
                    watchGpsDegraded = indicatorWatchGpsDegraded,
                ) ?: continue
            val marker = locationMarker ?: continue

            val metersPerPixel =
                markerMotionMetersPerPixel(
                    latitude = predicted.latitude,
                    zoomLevel = mapView.model.mapViewPosition.zoomLevel,
                    tileSize = mapView.model.displayModel.tileSize,
                )
            val renderMotionStatus =
                markerMotionController.visualMotionStatus(
                    nowElapsedMs = nowElapsedMs,
                    serviceFreshnessMaxAgeMs = indicatorFixFreshMaxAgeMs,
                )
            val renderDecision =
                markerMotionRenderDecision(
                    MarkerMotionRenderInputs(
                        isInteractive = !latestScreenState.value.isNonInteractive,
                        isFollowingPosition = latestShouldFollowPosition.value,
                        isMoving = renderMotionStatus.isMoving,
                        isFresh = renderMotionStatus.isFresh,
                        hasPendingVisualCorrection =
                            markerMotionController.hasPendingVisualCorrection(nowElapsedMs),
                        metersPerPixel = metersPerPixel,
                    ),
                )
            val previousRendered = lastRenderedMarkerLatLong
            if (!shouldRenderMarkerMotion(previousRendered, predicted, renderDecision)) continue
            lastRenderedMarkerLatLong = predicted
            val displacementM =
                previousRendered?.let { previous ->
                    markerMotionDistanceMeters(previous, predicted)
                }
            MarkerMotionTelemetry.recordMotionRendered(
                nowElapsedMs = nowElapsedMs,
                displacementM = displacementM,
                displacementPx =
                    displacementM?.let { distanceM ->
                        metersPerPixel
                            ?.takeIf { it > 0.0 }
                            ?.let { (distanceM / it).toFloat() }
                    },
            )
            lastMarkerVisualUpdateAtElapsedMs = nowElapsedMs
            lastMarkerMotionAdvanceAtElapsedMs = nowElapsedMs

            marker.latLong = predicted
            if (latestShouldFollowPosition.value) {
                mapView.setCenterForNavigationMarker(predicted, latestNavigationMarkerAnchorMode.value)
            } else {
                mapView.requestLayerRedrawSafely()
            }
        }
    }

    LaunchedEffect(
        shouldTrackLocation,
        screenState,
        locationViewModel,
    ) {
        if (!shouldTrackLocation) return@LaunchedEffect
        if (screenState.isNonInteractive) return@LaunchedEffect

        while (isActive) {
            delay(INTERACTIVE_STALE_REFRESH_CHECK_MS)
            val nowElapsedMs = android.os.SystemClock.elapsedRealtime()
            val refreshDecision =
                resolveInteractiveStaleRefreshDecision(
                    input =
                        InteractiveStaleRefreshInput(
                            shouldTrackLocation = shouldTrackLocation,
                            screenState = screenState,
                            holdMarkerUntilFreshFix = holdMarkerUntilFreshFix,
                            postWakePredictionHoldActive =
                                isPostWakePredictionHoldActive(
                                    nowElapsedMs = nowElapsedMs,
                                    holdUntilElapsedMs = postWakePredictionHoldUntilElapsedMs,
                                ),
                            activeWakeSessionId = activeWakeSessionId,
                            lastFixAtElapsedMs = indicatorFixAtElapsedMs,
                            lastFixFreshMaxAgeMs = indicatorFixFreshMaxAgeMs,
                            lastVisualUpdateAtElapsedMs = lastMarkerVisualUpdateAtElapsedMs,
                            lastMotionAdvanceAtElapsedMs = lastMarkerMotionAdvanceAtElapsedMs,
                            lastRefreshRequestAtElapsedMs = lastInteractiveStaleRefreshAtElapsedMs,
                            fixLatenessThresholdMs = timingProfile.indicatorStaleThresholdMs,
                            nowElapsedMs = nowElapsedMs,
                        ),
                )
            if (refreshDecision.stateLabel != lastInteractiveStaleRefreshStateLabel) {
                lastInteractiveStaleRefreshStateLabel = refreshDecision.stateLabel
                logInteractiveStaleRefresh(
                    nowElapsedMs = nowElapsedMs,
                    reason = refreshDecision.reason,
                    fixAgeMs = refreshDecision.fixAgeMs ?: Long.MAX_VALUE,
                    visualAgeMs = refreshDecision.visualAgeMs ?: Long.MAX_VALUE,
                    motionIdleMs = refreshDecision.motionIdleMs ?: Long.MAX_VALUE,
                )
            }
            if (!refreshDecision.shouldRequest) continue

            lastInteractiveStaleRefreshAtElapsedMs = nowElapsedMs
            logInteractiveStaleRefresh(
                nowElapsedMs = nowElapsedMs,
                reason = refreshDecision.reason,
                fixAgeMs = refreshDecision.fixAgeMs ?: Long.MAX_VALUE,
                visualAgeMs = refreshDecision.visualAgeMs ?: Long.MAX_VALUE,
                motionIdleMs = refreshDecision.motionIdleMs ?: Long.MAX_VALUE,
            )
            locationViewModel.requestImmediateLocation(source = UI_INTERACTIVE_STALE_REFRESH_SOURCE)
        }
    }

    return NavigateLocationUiState(
        locationMarker = if (suppressLocationMarker) null else locationMarker,
        gpsIndicatorState = gpsIndicatorState,
        gpsEnvironmentWarning = activeEnvironmentWarning,
        showGpsIndicatorUnpinned = showGpsIndicatorUnpinned,
        watchGpsDegradedWarning = watchGpsDegradedWarning,
        lastFixSpeedMps = latestAcceptedFixSpeedMps,
        lastFixBearingDeg = latestAcceptedFixBearingDeg,
    )
}

private const val WAKE_REACQUIRE_TIMEOUT_MS = 6_000L
private const val WAKE_REACQUIRE_COOLDOWN_MS = 60_000L
private const val POST_WAKE_PREDICTION_GRACE_MS = 700L
private const val INTERACTIVE_STALE_REFRESH_CHECK_MS = 1_000L
private const val INTERACTIVE_STALE_REFRESH_MIN_FIX_AGE_MS = 2_500L
private const val INTERACTIVE_STALE_REFRESH_AFTER_PREDICTION_STALL_MS = 1_250L
private const val INTERACTIVE_STALE_REFRESH_MIN_MOTION_IDLE_MS = 1_250L
private const val INTERACTIVE_STALE_REFRESH_COOLDOWN_MS = 12_000L
private const val WAKE_REACQUIRE_SNAP_MAX_ACCURACY_M = 35f
private const val WARM_RETURN_ANCHOR_MAX_ACCURACY_M = 50f
private const val WARM_RETURN_PREDICTION_MAX_ACCURACY_M = 25f
private const val WARM_RETURN_MOVING_SPEED_THRESHOLD_MPS = 0.8f
private const val WARM_RETURN_MOVING_MAX_AGE_MS = 12_000L
private const val WARM_RETURN_STATIONARY_MAX_AGE_MS = 30_000L
private const val RETAINED_VISUAL_ANCHOR_MAX_AGE_MS = 5 * 60_000L
private const val TRACKING_SESSION_FIX_MAX_SKEW_MS = 400L
private const val CORRECTION_CLAMP_BYPASS_MULTIPLIER = 2L
private const val NAV_MARKER_TELEMETRY_TAG = "MarkerMotion"
private const val UI_INTERACTIVE_STALE_REFRESH_SOURCE = "ui_interactive_stale_refresh"

internal fun shouldRenderLocationVisualUpdate(
    screenState: LocationScreenState,
): Boolean = screenState != LocationScreenState.SCREEN_OFF

internal fun shouldRunMarkerPredictionLoop(
    shouldTrackLocation: Boolean,
    screenState: LocationScreenState,
    suppressLocationMarker: Boolean,
): Boolean =
    shouldTrackLocation &&
        !screenState.isNonInteractive &&
        !suppressLocationMarker

private fun removeAllRotatableMarkers(mapView: MapView) {
    mapView.mutateLayers { layers ->
        for (i in layers.size() - 1 downTo 0) {
            val layer = layers[i]
            if (layer is RotatableMarker) {
                layers.remove(layer)
            }
        }
    }
}

internal fun isWakeReacquireCooldownActive(
    nowElapsedMs: Long,
    lastStartedAtElapsedMs: Long,
    cooldownMs: Long,
): Boolean {
    if (lastStartedAtElapsedMs == Long.MIN_VALUE) return false
    val elapsedMs = (nowElapsedMs - lastStartedAtElapsedMs).coerceAtLeast(0L)
    return elapsedMs < cooldownMs.coerceAtLeast(0L)
}

internal fun toValidLatLongOrNull(
    latitude: Double,
    longitude: Double,
): LatLong? {
    if (!LocationFixPolicy.hasValidCoordinates(latitude = latitude, longitude = longitude)) {
        return null
    }
    return LatLong(latitude, longitude)
}

internal fun shouldCenterOnRenderedMarker(
    shouldFollowPosition: Boolean,
    target: LatLong,
    currentCenter: LatLong?,
): Boolean {
    if (!shouldFollowPosition) return false
    return shouldRenderMarkerMotion(
        previous = currentCenter,
        candidate = target,
    )
}

private fun shouldCenterOnNavigationMarker(
    mapView: MapView,
    shouldFollowPosition: Boolean,
    target: LatLong,
    markerAnchorMode: String,
    currentCenter: LatLong?,
): Boolean =
    shouldCenterOnRenderedMarker(
        shouldFollowPosition = shouldFollowPosition,
        target = mapView.resolveMapCenterForNavigationMarker(target, markerAnchorMode),
        currentCenter = currentCenter,
    )

internal fun resolveWakeAnchorSeedOrNull(
    location: android.location.Location?,
    receivedAtElapsedMs: Long,
    nowWallClockMs: Long,
    maxAgeMs: Long,
    maxAccuracyM: Float,
): WakeAnchorSeed? {
    if (location == null) return null
    if (!location.accuracy.isFinite() || location.accuracy > maxAccuracyM) return null
    val latLong = toValidLatLongOrNull(location.latitude, location.longitude) ?: return null
    val fixElapsedMs =
        resolveLocationFixElapsedRealtimeMs(
            elapsedRealtimeNanos = location.elapsedRealtimeNanos,
            utcTimeMs = location.time,
            receivedAtElapsedMs = receivedAtElapsedMs,
            nowWallClockMs = nowWallClockMs,
        )
    return resolveWakeAnchorSeedFromFixOrNull(
        latLong = latLong,
        fixElapsedMs = fixElapsedMs,
        receivedAtElapsedMs = receivedAtElapsedMs,
        accuracyM = location.accuracy,
        maxAgeMs = maxAgeMs,
        maxAccuracyM = maxAccuracyM,
        speedMps = if (location.hasSpeed() && location.speed.isFinite()) location.speed else 0f,
        bearingDeg = if (location.hasBearing()) location.bearing else null,
    )
}

internal fun computeWakeAnchorMaxAgeMs(expectedGpsIntervalMs: Long): Long = resolveLocationTimingProfile(expectedGpsIntervalMs).wakeAnchorMaxAgeMs

internal fun computeWarmReturnAnchorMaxAgeMs(
    expectedGpsIntervalMs: Long,
    speedMps: Float,
): Long {
    val baseline = computeWakeAnchorMaxAgeMs(expectedGpsIntervalMs)
    val warmReturnLimit =
        if (speedMps.isFinite() && speedMps >= WARM_RETURN_MOVING_SPEED_THRESHOLD_MPS) {
            WARM_RETURN_MOVING_MAX_AGE_MS
        } else {
            WARM_RETURN_STATIONARY_MAX_AGE_MS
        }
    return maxOf(baseline, warmReturnLimit)
}

internal fun shouldResumePredictionFromWakeAnchor(
    anchor: MarkerMotionSeed,
    receivedAtElapsedMs: Long,
    expectedGpsIntervalMs: Long,
): Boolean {
    val reading = anchor.reading
    val speedMps = reading.speedMps
    val bearingDeg = reading.bearingDeg
    val validElapsedTime =
        reading.fixElapsedMs > 0L &&
            receivedAtElapsedMs >= reading.fixElapsedMs
    val fixAgeMs =
        if (validElapsedTime) {
            receivedAtElapsedMs - reading.fixElapsedMs
        } else {
            Long.MAX_VALUE
        }
    return validElapsedTime &&
        reading.accuracyM.isFinite() &&
        reading.accuracyM <= WARM_RETURN_PREDICTION_MAX_ACCURACY_M &&
        speedMps != null &&
        speedMps.isFinite() &&
        speedMps >= WARM_RETURN_MOVING_SPEED_THRESHOLD_MPS &&
        bearingDeg != null &&
        bearingDeg.isFinite() &&
        fixAgeMs <= resolveLocationTimingProfile(expectedGpsIntervalMs).markerPredictionFreshnessMaxAgeMs
}

internal fun resolveStartupFreshFixMaxAgeMs(
    expectedGpsIntervalMs: Long,
    serviceFreshMaxAgeMs: Long,
): Long {
    val timingProfile = resolveLocationTimingProfile(expectedGpsIntervalMs)
    val effectiveServiceFreshMaxAgeMs =
        serviceFreshMaxAgeMs
            .takeIf { it > 0L }
            ?: timingProfile.strictFreshFixMaxAgeMs
    return maxOf(timingProfile.strictFreshFixMaxAgeMs, effectiveServiceFreshMaxAgeMs)
}

internal fun computeWakeReacquireSnapMaxAgeMs(expectedGpsIntervalMs: Long): Long = resolveLocationTimingProfile(expectedGpsIntervalMs).markerPredictionFreshnessMaxAgeMs

internal fun computeWakeReacquireReleaseMaxAgeMs(expectedGpsIntervalMs: Long): Long = resolveLocationTimingProfile(expectedGpsIntervalMs).strictFreshFixMaxAgeMs

internal fun shouldReleaseWakeReacquireHold(
    holdMarkerUntilFreshFix: Boolean,
    fixFromCurrentTrackingSession: Boolean,
    wakeSnapEligible: Boolean,
    wakeReleaseEligible: Boolean,
    holdTimedOut: Boolean,
): Boolean {
    if (!holdMarkerUntilFreshFix) return false
    if (fixFromCurrentTrackingSession && wakeSnapEligible) return true
    if (holdTimedOut && wakeReleaseEligible) return true
    return false
}

internal fun shouldStartInteractiveWakeSession(
    wasInteractiveTrackingActive: Boolean,
    shouldTrackLocation: Boolean,
    screenState: LocationScreenState,
): Boolean = shouldTrackLocation && !screenState.isNonInteractive && !wasInteractiveTrackingActive

internal fun shouldResolveWakeSessionFromAcceptedFix(
    activeWakeSessionId: Long,
    fixFromCurrentTrackingSession: Boolean,
    wakeSnapEligible: Boolean,
): Boolean = activeWakeSessionId > 0L && fixFromCurrentTrackingSession && wakeSnapEligible

internal fun isPostWakePredictionHoldActive(
    nowElapsedMs: Long,
    holdUntilElapsedMs: Long,
): Boolean = holdUntilElapsedMs > 0L && nowElapsedMs < holdUntilElapsedMs

internal fun resolveAcceptedFixGapMs(
    previousFixElapsedMs: Long,
    currentFixElapsedMs: Long,
): Long {
    if (previousFixElapsedMs <= 0L || currentFixElapsedMs <= 0L) return Long.MAX_VALUE
    return (currentFixElapsedMs - previousFixElapsedMs).coerceAtLeast(0L)
}

internal fun shouldBypassCorrectionClamp(
    releaseFromWakeHold: Boolean,
    previousAcceptedFixGapMs: Long,
    expectedGpsIntervalMs: Long,
): Boolean {
    if (releaseFromWakeHold) return true
    return previousAcceptedFixGapMs >= computeCorrectionClampBypassGapMs(expectedGpsIntervalMs)
}

internal fun computeCorrectionClampBypassGapMs(expectedGpsIntervalMs: Long): Long {
    val timingProfile = resolveLocationTimingProfile(expectedGpsIntervalMs)
    return timingProfile.correctionStaleGapMs * CORRECTION_CLAMP_BYPASS_MULTIPLIER
}

internal data class InteractiveStaleRefreshDecision(
    val shouldRequest: Boolean,
    val reason: String,
    val stateLabel: String,
    val fixAgeMs: Long? = null,
    val visualAgeMs: Long? = null,
    val motionIdleMs: Long? = null,
)

internal data class InteractiveStaleRefreshInput(
    val shouldTrackLocation: Boolean,
    val screenState: LocationScreenState,
    val holdMarkerUntilFreshFix: Boolean,
    val postWakePredictionHoldActive: Boolean,
    val activeWakeSessionId: Long,
    val lastFixAtElapsedMs: Long,
    val lastFixFreshMaxAgeMs: Long,
    val lastVisualUpdateAtElapsedMs: Long,
    val lastMotionAdvanceAtElapsedMs: Long,
    val lastRefreshRequestAtElapsedMs: Long,
    val fixLatenessThresholdMs: Long,
    val nowElapsedMs: Long,
)

private data class InteractiveStaleRefreshAges(
    val fixAgeMs: Long,
    val visualAgeMs: Long,
    val motionIdleMs: Long,
)

internal fun resolveInteractiveStaleRefreshDecision(
    input: InteractiveStaleRefreshInput,
): InteractiveStaleRefreshDecision {
    val ages = resolveInteractiveStaleRefreshAges(input)
    val reason = resolveInteractiveStaleRefreshReason(input = input, ages = ages)
    return interactiveStaleRefreshDecision(
        reason = reason,
        ages = ages,
    )
}

private fun resolveInteractiveStaleRefreshAges(
    input: InteractiveStaleRefreshInput,
): InteractiveStaleRefreshAges {
    val fixAgeMs = resolveElapsedAgeMs(anchorElapsedMs = input.lastFixAtElapsedMs, nowElapsedMs = input.nowElapsedMs)
    val visualAgeMs =
        resolveElapsedAgeMs(
            anchorElapsedMs = input.lastVisualUpdateAtElapsedMs,
            nowElapsedMs = input.nowElapsedMs,
            fallbackAgeMs = fixAgeMs,
        )
    val motionIdleMs =
        resolveElapsedAgeMs(
            anchorElapsedMs = input.lastMotionAdvanceAtElapsedMs,
            nowElapsedMs = input.nowElapsedMs,
            fallbackAgeMs = visualAgeMs,
        )
    return InteractiveStaleRefreshAges(
        fixAgeMs = fixAgeMs,
        visualAgeMs = visualAgeMs,
        motionIdleMs = motionIdleMs,
    )
}

private fun resolveInteractiveStaleRefreshReason(
    input: InteractiveStaleRefreshInput,
    ages: InteractiveStaleRefreshAges,
): String {
    val interactiveTrackingInactive = !input.shouldTrackLocation || input.screenState.isNonInteractive
    val wakeRecoveryActive =
        input.holdMarkerUntilFreshFix ||
            input.postWakePredictionHoldActive ||
            input.activeWakeSessionId > 0L
    val noKnownFix = ages.fixAgeMs == Long.MAX_VALUE
    val fixRecoveryThresholdMs =
        resolveInteractiveStaleRefreshFixRecoveryThresholdMs(
            lastFixFreshMaxAgeMs = input.lastFixFreshMaxAgeMs,
            fixLatenessThresholdMs = input.fixLatenessThresholdMs,
        )
    val refreshCooldownActive =
        input.lastRefreshRequestAtElapsedMs != Long.MIN_VALUE &&
            (input.nowElapsedMs - input.lastRefreshRequestAtElapsedMs).coerceAtLeast(0L) <
            INTERACTIVE_STALE_REFRESH_COOLDOWN_MS
    return when {
        interactiveTrackingInactive -> "tracking_inactive"
        wakeRecoveryActive -> "wake_recovery_active"
        noKnownFix -> "no_fix"
        ages.fixAgeMs < fixRecoveryThresholdMs -> "prediction_active"
        ages.motionIdleMs < INTERACTIVE_STALE_REFRESH_MIN_MOTION_IDLE_MS -> "motion_recent"
        refreshCooldownActive -> "cooldown"
        else -> "motion_stalled"
    }
}

private fun resolveInteractiveStaleRefreshFixRecoveryThresholdMs(
    lastFixFreshMaxAgeMs: Long,
    fixLatenessThresholdMs: Long,
): Long {
    val effectivePredictionFreshnessMaxAgeMs =
        when {
            lastFixFreshMaxAgeMs > 0L && fixLatenessThresholdMs > 0L ->
                minOf(lastFixFreshMaxAgeMs, fixLatenessThresholdMs)
            lastFixFreshMaxAgeMs > 0L -> lastFixFreshMaxAgeMs
            else -> fixLatenessThresholdMs
        }
    return maxOf(
        INTERACTIVE_STALE_REFRESH_MIN_FIX_AGE_MS,
        effectivePredictionFreshnessMaxAgeMs + INTERACTIVE_STALE_REFRESH_AFTER_PREDICTION_STALL_MS,
    )
}

private fun interactiveStaleRefreshDecision(
    reason: String,
    ages: InteractiveStaleRefreshAges,
): InteractiveStaleRefreshDecision =
    InteractiveStaleRefreshDecision(
        shouldRequest = reason == "motion_stalled",
        reason = reason,
        stateLabel =
            if (reason == "motion_stalled") {
                "request:$reason"
            } else {
                "blocked:$reason"
            },
        fixAgeMs = ages.fixAgeMs.takeUnless { it == Long.MAX_VALUE },
        visualAgeMs = ages.visualAgeMs.takeUnless { it == Long.MAX_VALUE },
        motionIdleMs = ages.motionIdleMs.takeUnless { it == Long.MAX_VALUE },
    )

private fun resolveElapsedAgeMs(
    anchorElapsedMs: Long,
    nowElapsedMs: Long,
    fallbackAgeMs: Long = Long.MAX_VALUE,
): Long =
    if (anchorElapsedMs > 0L) {
        (nowElapsedMs - anchorElapsedMs).coerceAtLeast(0L)
    } else {
        fallbackAgeMs
    }

internal fun resolveWakeAnchorSeedFromFixOrNull(
    latLong: LatLong?,
    fixElapsedMs: Long,
    receivedAtElapsedMs: Long,
    accuracyM: Float,
    maxAgeMs: Long,
    maxAccuracyM: Float,
    speedMps: Float = 0f,
    bearingDeg: Float? = null,
): WakeAnchorSeed? {
    if (latLong == null) return null
    if (fixElapsedMs <= 0L) return null
    if (!accuracyM.isFinite() || accuracyM > maxAccuracyM) return null
    val ageMs = (receivedAtElapsedMs - fixElapsedMs).coerceAtLeast(0L)
    if (ageMs > maxAgeMs) return null
    return WakeAnchorSeed(
        latLong = latLong,
        fixElapsedMs = fixElapsedMs,
        accuracyM = accuracyM,
        speedMps = speedMps,
        bearingDeg = bearingDeg,
    )
}

internal fun resolveLocationFixElapsedRealtimeMs(
    elapsedRealtimeNanos: Long,
    utcTimeMs: Long,
    receivedAtElapsedMs: Long,
    nowWallClockMs: Long,
): Long {
    if (elapsedRealtimeNanos > 0L) {
        return (elapsedRealtimeNanos / 1_000_000L)
            .coerceIn(0L, receivedAtElapsedMs)
    }
    if (utcTimeMs > 0L) {
        val ageMs = (nowWallClockMs - utcTimeMs).coerceAtLeast(0L)
        return (receivedAtElapsedMs - ageMs).coerceAtLeast(0L)
    }
    // Unknown timestamp: keep indicator conservative instead of treating this fix as fresh.
    return 0L
}

@Suppress("LongParameterList")
internal fun resolveGpsIndicatorState(
    isLocationAvailable: Boolean,
    unavailableSinceElapsedMs: Long,
    lastFixAtElapsedMs: Long,
    accuracyM: Float,
    watchGpsOnlyActive: Boolean = false,
    requiresFreshLiveFixAfterSourceChange: Boolean = false,
    nowElapsedMs: Long,
    staleThresholdMs: Long,
): GpsFixIndicatorState {
    val ageMs =
        if (lastFixAtElapsedMs > 0L) {
            (nowElapsedMs - lastFixAtElapsedMs).coerceAtLeast(0L)
        } else {
            Long.MAX_VALUE
        }
    val hasFreshUsableFix =
        lastFixAtElapsedMs > 0L &&
            ageMs <= staleThresholdMs &&
            accuracyM.isFinite() &&
            !requiresFreshLiveFixAfterSourceChange

    return when {
        hasFreshUsableFix -> {
            val effectiveAccuracyM = effectiveGpsIndicatorAccuracy(accuracyM, watchGpsOnlyActive)
            val goodAccuracyThresholdM = gpsIndicatorGoodAccuracyThresholdM(watchGpsOnlyActive)
            if (effectiveAccuracyM <= goodAccuracyThresholdM) {
                GpsFixIndicatorState.GOOD
            } else {
                GpsFixIndicatorState.POOR
            }
        }

        !isLocationAvailable -> {
            if (unavailableSinceElapsedMs <= 0L) {
                GpsFixIndicatorState.SEARCHING
            } else {
                val outageConfirmWindowMs = computeUnavailableConfirmWindowMs(staleThresholdMs)
                val unavailableForMs = (nowElapsedMs - unavailableSinceElapsedMs).coerceAtLeast(0L)
                if (unavailableForMs >= outageConfirmWindowMs) {
                    GpsFixIndicatorState.UNAVAILABLE
                } else {
                    GpsFixIndicatorState.SEARCHING
                }
            }
        }

        else -> GpsFixIndicatorState.SEARCHING
    }
}

private fun effectiveGpsIndicatorAccuracy(
    accuracyM: Float,
    watchGpsOnlyActive: Boolean,
): Float =
    if (watchGpsOnlyActive && accuracyM.isFinite() && isKnownWatchGpsAccuracyFloor(accuracyM)) {
        WATCH_GPS_FLOOR_INDICATOR_ACCURACY_M
    } else {
        accuracyM
    }

private fun gpsIndicatorGoodAccuracyThresholdM(
    watchGpsOnlyActive: Boolean,
): Float = if (watchGpsOnlyActive) WATCH_GPS_GOOD_FIX_ACCURACY_THRESHOLD_M else GOOD_FIX_ACCURACY_THRESHOLD_M

private fun isKnownWatchGpsAccuracyFloor(
    accuracyM: Float,
): Boolean {
    if (!accuracyM.isFinite()) return false
    return abs(accuracyM - WATCH_GPS_ACCURACY_FLOOR_M) <= WATCH_GPS_ACCURACY_FLOOR_TOLERANCE_M
}

internal fun resolveGpsIndicatorDisplayState(
    rawState: GpsFixIndicatorState,
): GpsFixIndicatorState = rawState

internal fun shouldHoldMarkerForLiveSourceHandoff(
    sourceEpoch: Long,
    requiresFreshLiveFix: Boolean,
): Boolean = sourceEpoch > 1L && requiresFreshLiveFix

internal fun resolveGpsIndicatorEscalationState(
    rawState: GpsFixIndicatorState,
    abnormalSinceElapsedMs: Long,
    nowElapsedMs: Long,
): GpsFixIndicatorState {
    if (rawState == GpsFixIndicatorState.GOOD || rawState == GpsFixIndicatorState.UNAVAILABLE) {
        return rawState
    }
    if (abnormalSinceElapsedMs <= 0L) return GpsFixIndicatorState.SEARCHING

    val abnormalDurationMs = (nowElapsedMs - abnormalSinceElapsedMs).coerceAtLeast(0L)
    return when {
        abnormalDurationMs < GPS_INDICATOR_CAUTION_AFTER_MS -> GpsFixIndicatorState.SEARCHING
        abnormalDurationMs < GPS_INDICATOR_LOST_AFTER_MS -> GpsFixIndicatorState.POOR
        else -> GpsFixIndicatorState.LOST
    }
}

internal fun resolveGpsIndicatorStateForEnvironment(
    rawState: GpsFixIndicatorState,
    environmentWarning: GpsEnvironmentWarning,
): GpsFixIndicatorState =
    when (environmentWarning) {
        GpsEnvironmentWarning.LOCATION_SETTINGS_UNSATISFIED -> GpsFixIndicatorState.UNAVAILABLE
        GpsEnvironmentWarning.NONE,
        GpsEnvironmentWarning.WATCH_GPS_UNAVAILABLE,
        GpsEnvironmentWarning.AUTO_PHONE_DISCONNECTED_NO_WATCH_GPS,
        GpsEnvironmentWarning.AUTO_PHONE_DISCONNECTED_USING_WATCH_GPS,
        -> rawState
    }

internal fun shouldShowGpsIndicatorUnpinned(
    gpsIndicatorState: GpsFixIndicatorState,
    watchGpsDegradedWarning: Boolean,
): Boolean =
    gpsIndicatorState == GpsFixIndicatorState.SEARCHING ||
        gpsIndicatorState == GpsFixIndicatorState.LOST ||
        gpsIndicatorState == GpsFixIndicatorState.UNAVAILABLE ||
        watchGpsDegradedWarning

private const val GPS_INDICATOR_CAUTION_AFTER_MS = 12_000L
private const val GPS_INDICATOR_LOST_AFTER_MS = 30_000L

private fun computeGpsFixStaleThresholdMs(expectedGpsIntervalMs: Long): Long = resolveLocationTimingProfile(expectedGpsIntervalMs).indicatorStaleThresholdMs

private fun computeUnavailableConfirmWindowMs(staleThresholdMs: Long): Long {
    val safeStaleThresholdMs = staleThresholdMs.coerceAtLeast(1_000L)
    return (safeStaleThresholdMs * 2L).coerceIn(20_000L, 60_000L)
}

internal fun computeMarkerPredictionFreshnessMaxAgeMs(expectedGpsIntervalMs: Long): Long = resolveLocationTimingProfile(expectedGpsIntervalMs).markerPredictionFreshnessMaxAgeMs

private fun logWakeSessionEvent(
    stage: String,
    sessionId: Long,
    nowElapsedMs: Long,
    reason: String? = null,
    fixAgeMs: Long? = null,
) {
    DebugTelemetry.log(
        NAV_MARKER_TELEMETRY_TAG,
        buildString {
            append("wakeSession stage=$stage")
            append(" id=$sessionId")
            append(" at=${nowElapsedMs}ms")
            reason?.let { append(" reason=$it") }
            fixAgeMs?.let { append(" fixAge=${it}ms") }
        },
    )
}

private fun logInteractiveStaleRefresh(
    nowElapsedMs: Long,
    reason: String,
    fixAgeMs: Long,
    visualAgeMs: Long,
    motionIdleMs: Long,
) {
    DebugTelemetry.log(
        NAV_MARKER_TELEMETRY_TAG,
        buildString {
            append("refresh reason=$reason")
            append(" at=${nowElapsedMs}ms")
            append(" fixAge=${fixAgeMs}ms")
            append(" visualAge=${visualAgeMs}ms")
            append(" motionIdle=${motionIdleMs}ms")
        },
    )
}

private const val GOOD_FIX_ACCURACY_THRESHOLD_M = 12f
private const val WATCH_GPS_GOOD_FIX_ACCURACY_THRESHOLD_M = 20f
private const val WATCH_GPS_FLOOR_INDICATOR_ACCURACY_M = 18f
