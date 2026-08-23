@file:Suppress(
    "CyclomaticComplexMethod",
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
    "ReturnCount",
)

package com.glancemap.glancemapwearos.presentation.features.navigate

import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import com.glancemap.glancemapwearos.core.service.diagnostics.CompassDeepTraceDiagnostics
import com.glancemap.glancemapwearos.core.service.diagnostics.CompassDeepTraceRenderSample
import com.glancemap.glancemapwearos.core.service.diagnostics.CompassHeadingDiagnostics
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.core.service.diagnostics.isCompassTelemetryCaptureActive
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.domain.model.maps.theme.mapsforge.MapsforgeThemeCatalog
import com.glancemap.glancemapwearos.domain.sensors.COMPASS_TELEMETRY_TAG
import com.glancemap.glancemapwearos.domain.sensors.CompassMagneticQuality
import com.glancemap.glancemapwearos.domain.sensors.CompassProviderType
import com.glancemap.glancemapwearos.domain.sensors.CompassRenderState
import com.glancemap.glancemapwearos.domain.sensors.CompassTrackingReason
import com.glancemap.glancemapwearos.domain.sensors.CompassTrackingState
import com.glancemap.glancemapwearos.domain.sensors.HeadingSource
import com.glancemap.glancemapwearos.domain.sensors.HeadingTurnRateHysteresis
import com.glancemap.glancemapwearos.domain.sensors.hasRecentGoogleFusedCachedHeading
import com.glancemap.glancemapwearos.presentation.features.maps.MapRenderer
import com.glancemap.glancemapwearos.presentation.features.maps.RotatableMarker
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Rotation
import org.mapsforge.map.android.view.MapView
import java.io.File
import java.util.ArrayDeque
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.exp

/**
 * Synchronized Map + Marker rotation for Compass, North-Up and Panning modes.
 *
 * Works with your RotatableMarker implementation (it compensates map rotation internally).
 */
@Composable
fun NavigationOrientationEffect(
    isCompassMode: Boolean,
    isAutoCentering: Boolean,
    forceNorthUpInPanning: Boolean,
    renderStateFlow: StateFlow<CompassRenderState>,
    compassInteractive: Boolean,
    mapView: MapView?,
    showRealMarkerInCompassMode: Boolean,
    locationMarker: RotatableMarker?,
    navigationMarkerAnchorMode: String,
    onRenderedHeadingChanged: (Float) -> Unit,
    onRenderedMapRotationChanged: (Float) -> Unit,
    requestMapRedraw: () -> Unit,
) {
    val mv = mapView ?: return
    val marker = locationMarker
    val latestNavigationMarkerAnchorMode = rememberUpdatedState(navigationMarkerAnchorMode)
    val latestOnRenderedHeadingChanged = rememberUpdatedState(onRenderedHeadingChanged)
    val latestOnRenderedMapRotationChanged = rememberUpdatedState(onRenderedMapRotationChanged)

    val navMode =
        remember(isCompassMode, isAutoCentering) {
            when {
                !isAutoCentering -> NavMode.PANNING
                isCompassMode -> NavMode.COMPASS_FOLLOW
                else -> NavMode.NORTH_UP_FOLLOW
            }
        }

    val displayedHeading = remember { mutableFloatStateOf(normalize360(renderStateFlow.value.headingDeg)) }
    val displayedMapRot = remember { mutableFloatStateOf(0f) }
    val frozenRotationDeg = remember { mutableFloatStateOf(0f) }
    val rotationSettleGate = remember(mv) { NavigateRotationSettleGate() }
    val wakeContinuityCapture = remember(mv) { NavigateWakeContinuityCapture() }
    val hasObservedInteractive = remember(mv) { mutableStateOf(false) }
    val latestCompassInteractive = rememberUpdatedState(compassInteractive)
    val lastMapsforgeRotationAppliedAtMs = remember(mv) { mutableLongStateOf(Long.MIN_VALUE) }
    val lastOuterUiPublishAtMs = remember(mv) { mutableLongStateOf(Long.MIN_VALUE) }

    fun publishRenderedState(
        force: Boolean = false,
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ) {
        if (
            !shouldPublishRenderedCompassUiState(
                nowElapsedMs = nowElapsedMs,
                lastPublishedAtElapsedMs = lastOuterUiPublishAtMs.longValue,
                force = force,
            )
        ) {
            return
        }
        lastOuterUiPublishAtMs.longValue = nowElapsedMs
        latestOnRenderedHeadingChanged.value(displayedHeading.floatValue)
        latestOnRenderedMapRotationChanged.value(displayedMapRot.floatValue)
    }

    fun syncDisplayedMapRotationFromMap(): Float {
        val actualRotationDeg = mv.mapRotation.degrees
        displayedMapRot.floatValue = actualRotationDeg
        return actualRotationDeg
    }

    fun recenterLowerMarkerAnchor() {
        if (navMode == NavMode.PANNING) return
        val markerLatLong = marker?.latLong ?: return
        val anchorMode = latestNavigationMarkerAnchorMode.value
        if (anchorMode != SettingsRepository.NAVIGATION_MARKER_ANCHOR_LOWER) return
        val desiredCenter = mv.resolveMapCenterForNavigationMarker(markerLatLong, anchorMode)
        if (shouldUpdateMapCenter(desiredCenter, mv.model.mapViewPosition.center)) {
            mv.setCenter(desiredCenter)
        }
    }

    fun applyMapRotation(
        targetRotationDeg: Float,
        highFrequencyRotation: Boolean = false,
        responsiveRotation: Boolean = false,
        maxVisualStepDeg: Float? = null,
    ) {
        recenterLowerMarkerAnchor()
        val currentRotationDeg = syncDisplayedMapRotationFromMap()
        val resolvedTargetRotationDeg =
            if (navMode == NavMode.COMPASS_FOLLOW) {
                resolveCompassVisualTargetAngle(
                    currentAngleDeg = currentRotationDeg,
                    targetAngleDeg = targetRotationDeg,
                    maxStepDeg =
                        minOf(
                            maxVisualStepDeg ?: Float.POSITIVE_INFINITY,
                            if (responsiveRotation) {
                                RESPONSIVE_HEADING_ANIMATION_MAX_STEP_DEG
                            } else {
                                HEADING_ANIMATION_MAX_STEP_DEG
                            },
                        ),
                )
            } else {
                targetRotationDeg
            }
        val applyEpsilonDeg =
            if (highFrequencyRotation) {
                MAP_ROTATION_ACTIVE_TURN_APPLY_EPSILON_DEG
            } else {
                MAP_ROTATION_APPLY_EPSILON_DEG
            }
        if (abs(angleDeltaDeg(resolvedTargetRotationDeg, currentRotationDeg)) < applyEpsilonDeg) {
            CompassRenderPerfTelemetry.recordRotationSkipped(navMode)
            publishRenderedState()
            return
        }
        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (
            shouldThrottleMapsforgeRotation(
                navMode = navMode,
                nowElapsedMs = nowElapsedMs,
                lastAppliedAtElapsedMs = lastMapsforgeRotationAppliedAtMs.longValue,
                highFrequencyRotation = highFrequencyRotation,
            )
        ) {
            CompassRenderPerfTelemetry.recordRotationThrottled(navMode)
            publishRenderedState(nowElapsedMs = nowElapsedMs)
            return
        }
        val anchor = mv.resolveNavigationMarkerScreenAnchor(latestNavigationMarkerAnchorMode.value)
        if (mv.trySetMapsforgeRotation(resolvedTargetRotationDeg, anchor)) {
            lastMapsforgeRotationAppliedAtMs.longValue = nowElapsedMs
            CompassRenderPerfTelemetry.recordRotationApplied(navMode)
            syncDisplayedMapRotationFromMap()
        }
        publishRenderedState(nowElapsedMs = nowElapsedMs)
    }

    fun applyMarkersForMode(targetNavMode: NavMode) {
        val markerState =
            markerRenderStateForMode(
                navMode = targetNavMode,
                displayedHeadingDeg = displayedHeading.floatValue,
                displayedMapRotationDeg = displayedMapRot.floatValue,
                frozenMapRotationDeg = frozenRotationDeg.floatValue,
                showRealMarkerInCompassMode = showRealMarkerInCompassMode,
            )
        applyMarkerRenderState(
            marker = marker,
            state = markerState,
        )
        CompassRenderPerfTelemetry.recordMarkerUpdate(targetNavMode)
    }

    LaunchedEffect(mv) {
        // Clear any legacy Android view rotation so map orientation is driven only by Mapsforge.
        mv.rotation = 0f
        syncDisplayedMapRotationFromMap()
        publishRenderedState(force = true)
    }

    LaunchedEffect(compassInteractive, mv) {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (compassInteractive) {
            if (shouldUseWakeContinuityAnchor(navMode)) {
                val fallbackHeadingDeg = normalize360(-syncDisplayedMapRotationFromMap())
                val wakeAnchor =
                    if (hasObservedInteractive.value) {
                        // Use the map heading frozen when the display went off. The renderer does
                        // not advance while non-interactive, so this is also still the map angle
                        // the user last saw.
                        wakeContinuityCapture
                            .wake(
                                fallbackHeadingDeg = fallbackHeadingDeg,
                            ).also { wakeAnchor ->
                                if (isCompassTelemetryCaptureActive()) wakeAnchor.log()
                            }
                    } else {
                        null
                    }
                // A cold Navigate entry retains the map angle already visible to the user.
                val heldHeading =
                    wakeAnchor?.wakeHeldHeadingDeg ?: fallbackHeadingDeg
                displayedHeading.floatValue = heldHeading
                rotationSettleGate.beginWakeSession(
                    nowElapsedMs = nowElapsedMs,
                    heldHeadingDeg = heldHeading,
                    coldStart = !hasObservedInteractive.value,
                    requirePostStableHeading = wakeAnchor?.preLoweringDetected == true,
                )
                publishRenderedState(force = true, nowElapsedMs = nowElapsedMs)
            }
            wakeContinuityCapture.beginInteractiveSession()
            hasObservedInteractive.value = true
        } else {
            if (shouldUseWakeContinuityAnchor(navMode)) {
                val screenOffAnchorHeading = normalize360(-syncDisplayedMapRotationFromMap())
                val screenOffAnchor =
                    wakeContinuityCapture.screenOff(
                        fallbackHeadingDeg = screenOffAnchorHeading,
                        nowElapsedMs = nowElapsedMs,
                    )
                if (isCompassTelemetryCaptureActive()) {
                    screenOffAnchor.log()
                }
            }
            rotationSettleGate.endWakeSession(nowElapsedMs)
        }
    }

    LaunchedEffect(
        navMode,
        mv,
        forceNorthUpInPanning,
        navigationMarkerAnchorMode,
    ) {
        val renderStateNow = renderStateFlow.value
        val headingNow = normalize360(renderStateNow.headingDeg)
        val shouldDriveHeadingNow = shouldDriveHeadingForNavMode(navMode, renderStateNow)
        val mapOrientationWasInitialized = hasInitializedMapOrientation(mv)
        val shouldSeedCachedHeading =
            when (navMode) {
                NavMode.COMPASS_FOLLOW ->
                    shouldSeedCompassFollowMapWithCachedHeading(
                        renderState = renderStateNow,
                        nowElapsedMs = SystemClock.elapsedRealtime(),
                    )
                NavMode.NORTH_UP_FOLLOW ->
                    shouldSeedNorthUpMarkerWithCachedHeading(
                        renderState = renderStateNow,
                        nowElapsedMs = SystemClock.elapsedRealtime(),
                    )
                NavMode.PANNING -> false
            }
        if (
            navMode == NavMode.NORTH_UP_FOLLOW &&
            (shouldDriveHeadingNow || shouldSeedCachedHeading)
        ) {
            displayedHeading.floatValue = headingNow
        }

        when (navMode) {
            NavMode.COMPASS_FOLLOW -> {
                if (
                    !mapOrientationWasInitialized &&
                    (shouldDriveHeadingNow || shouldSeedCachedHeading) &&
                    !shouldHoldCompassFollowStartupForMagneticInterference(renderStateNow)
                ) {
                    // A new MapView has no visible orientation to preserve. Seed its first
                    // bounded movement from the available heading instead of correcting from
                    // an artificial north-up start.
                    displayedHeading.floatValue = headingNow
                    recenterLowerMarkerAnchor()
                    val anchor =
                        mv.resolveNavigationMarkerScreenAnchor(
                            latestNavigationMarkerAnchorMode.value,
                        )
                    if (mv.trySetMapsforgeRotation(-headingNow, anchor)) {
                        lastMapsforgeRotationAppliedAtMs.longValue = SystemClock.elapsedRealtime()
                        CompassRenderPerfTelemetry.recordRotationApplied(navMode)
                    }
                    syncDisplayedMapRotationFromMap()
                } else {
                    // On wake or mode-effect recreation, preserve the exact visible map
                    // orientation. The frame animation then converges toward the live heading.
                    val heldMapRotation = syncDisplayedMapRotationFromMap()
                    val heldHeading = normalize360(-heldMapRotation)
                    displayedHeading.floatValue = heldHeading
                }
            }

            NavMode.NORTH_UP_FOLLOW -> {
                applyMapRotation(0f)
            }

            NavMode.PANNING -> {
                val frozen = if (forceNorthUpInPanning) 0f else mv.mapRotation.degrees
                frozenRotationDeg.floatValue = frozen
                applyMapRotation(frozen)
            }
        }
        markMapOrientationInitialized(mv)
        publishRenderedState(force = true)

        requestMapRedraw()
    }

    LaunchedEffect(
        navMode,
        marker,
        showRealMarkerInCompassMode,
        forceNorthUpInPanning,
    ) {
        applyMarkersForMode(navMode)
        requestMapRedraw()
    }

    // Heading updates — animated at display frame rate for smooth 60fps rotation.
    // A child coroutine tracks the latest sensor heading; the frame loop chases it
    // using an exponential ease so motion appears fluid between ~20Hz sensor updates.
    LaunchedEffect(
        navMode,
        mv,
        renderStateFlow,
        requestMapRedraw,
        showRealMarkerInCompassMode,
        forceNorthUpInPanning,
        navigationMarkerAnchorMode,
    ) {
        // Local var: safe because both coroutines run on Main (single-threaded).
        var liveTarget = displayedHeading.floatValue
        var latestRenderState = renderStateFlow.value
        var activeHeadingTurn = false
        var previousFrameTimeNanos = 0L
        val headingTurnTracker =
            HeadingTurnRateHysteresis(
                enterRateDegPerSec = RENDER_ACTIVE_TURN_ENTER_RATE_DEG_PER_SEC,
                exitRateDegPerSec = RENDER_ACTIVE_TURN_EXIT_RATE_DEG_PER_SEC,
                exitHoldMs = RENDER_ACTIVE_TURN_EXIT_HOLD_MS,
                minimumEntryStepDeg = RENDER_ACTIVE_TURN_MIN_ENTRY_STEP_DEG,
                maximumSampleGapMs = RENDER_ACTIVE_TURN_MAX_SAMPLE_GAP_MS,
            )

        // Keep liveTarget current without blocking the animation loop.
        launch {
            renderStateFlow.collect { state ->
                latestRenderState = state
                if (latestCompassInteractive.value && navMode == NavMode.COMPASS_FOLLOW) {
                    val providerSample = CompassHeadingDiagnostics.wakeHeadingSnapshot().provider
                    wakeContinuityCapture.recordInteractive(
                        providerHeadingDeg = providerSample?.providerHeadingDeg,
                        targetHeadingDeg = state.headingDeg,
                        renderedHeadingDeg = displayedHeading.floatValue,
                        pitchDeg = providerSample?.pitchDeg,
                        rollDeg = providerSample?.rollDeg,
                        projection = providerSample?.projection,
                        atElapsedMs = SystemClock.elapsedRealtime(),
                    )
                }
                val canDriveHeading = shouldDriveHeadingForNavMode(navMode, state)
                if (!canDriveHeading) {
                    headingTurnTracker.reset()
                    activeHeadingTurn = false
                    return@collect
                }
                val heading = normalize360(state.headingDeg)
                val nowElapsedMs = SystemClock.elapsedRealtime()
                activeHeadingTurn =
                    headingTurnTracker.update(
                        headingDeg = heading,
                        atElapsedMs = nowElapsedMs,
                    )
                liveTarget = heading
                CompassRenderPerfTelemetry.recordTargetUpdate(navMode)
            }
        }

        // Animate toward liveTarget on every display frame.
        while (true) {
            withFrameNanos { frameTimeNanos ->
                val frameDeltaMs =
                    resolveHeadingAnimationFrameDeltaMs(
                        frameTimeNanos = frameTimeNanos,
                        previousFrameTimeNanos = previousFrameTimeNanos,
                    )
                previousFrameTimeNanos = frameTimeNanos
                if (navMode == NavMode.PANNING) return@withFrameNanos
                // Fused heading and integrity state continue updating in the collector,
                // but their hidden samples must not move the map or replace the heading
                // that was visible when the display turned off.
                if (!latestCompassInteractive.value) return@withFrameNanos
                val nowElapsedMs = SystemClock.elapsedRealtime()
                val current = displayedHeading.floatValue
                val headingTarget =
                    when (navMode) {
                        NavMode.COMPASS_FOLLOW ->
                            rotationSettleGate.resolve(
                                renderState = latestRenderState,
                                compassHeadingDeg = liveTarget,
                                headingSampleElapsedRealtimeMs =
                                    latestRenderState.headingSampleElapsedRealtimeMs,
                                nowElapsedMs = nowElapsedMs,
                                currentDisplayedHeadingDeg = current,
                            )

                        NavMode.NORTH_UP_FOLLOW ->
                            if (shouldDriveHeadingForNavMode(navMode, latestRenderState)) {
                                NavigationRotationTarget(
                                    headingDeg = liveTarget,
                                )
                            } else {
                                null
                            }

                        NavMode.PANNING -> null
                    }
                if (headingTarget == null) {
                    return@withFrameNanos
                }
                CompassRenderPerfTelemetry.recordFrame(navMode)
                val diff = angleDeltaDeg(headingTarget.headingDeg, current)
                val responsiveRotation = shouldUseResponsiveCompassMapRotation(latestRenderState)
                if (abs(diff) < HEADING_ANIMATION_DONE_DEG) {
                    val mapCatchupDeltaDeg =
                        if (navMode == NavMode.COMPASS_FOLLOW) {
                            abs(angleDeltaDeg(-current, displayedMapRot.floatValue))
                        } else {
                            0f
                        }
                    if (mapCatchupDeltaDeg >= MAP_ROTATION_ACTIVE_TURN_APPLY_EPSILON_DEG) {
                        applyMapRotation(
                            targetRotationDeg = -current,
                            highFrequencyRotation = true,
                            responsiveRotation = responsiveRotation,
                            maxVisualStepDeg = headingTarget.maxVisualStepDeg,
                        )
                        if (CompassDeepTraceDiagnostics.state.value.active) {
                            CompassDeepTraceDiagnostics.recordRenderSample(
                                CompassDeepTraceRenderSample(
                                    targetHeadingDeg = headingTarget.headingDeg,
                                    renderedHeadingDeg = current,
                                    mapRotationDeg = displayedMapRot.floatValue,
                                    continuityActive = false,
                                    continuityOffsetDeg = 0f,
                                    atElapsedMs = nowElapsedMs,
                                ),
                            )
                        }
                        CompassHeadingDiagnostics.recordRenderSample(
                            targetHeadingDeg = headingTarget.headingDeg,
                            renderedHeadingDeg = current,
                            mapRotationDeg = displayedMapRot.floatValue,
                            atElapsedMs = nowElapsedMs,
                        )
                        requestMapRedraw()
                        CompassRenderPerfTelemetry.recordRedraw(navMode)
                    }
                    if (headingTarget.recordsWakeReleaseStep) {
                        rotationSettleGate.recordFirstVisibleReleaseStep(stepDeg = 0f)
                    }
                    return@withFrameNanos
                }

                val animationDelta =
                    resolveHeadingAnimationDelta(
                        diffDeg = diff,
                        activeTurn = activeHeadingTurn,
                        frameDeltaMs = frameDeltaMs,
                        responsiveRotation = responsiveRotation,
                        maxStepDeg = headingTarget.maxVisualStepDeg,
                    )
                val next = normalize360(current + animationDelta)
                displayedHeading.floatValue = next
                if (latestCompassInteractive.value && navMode == NavMode.COMPASS_FOLLOW) {
                    val providerSample = CompassHeadingDiagnostics.wakeHeadingSnapshot().provider
                    wakeContinuityCapture.recordInteractive(
                        providerHeadingDeg = providerSample?.providerHeadingDeg,
                        targetHeadingDeg = headingTarget.headingDeg,
                        renderedHeadingDeg = next,
                        pitchDeg = providerSample?.pitchDeg,
                        rollDeg = providerSample?.rollDeg,
                        projection = providerSample?.projection,
                        atElapsedMs = nowElapsedMs,
                    )
                }
                CompassRenderPerfTelemetry.recordHeadingRender(navMode)
                if (headingTarget.recordsWakeReleaseStep) {
                    rotationSettleGate.recordFirstVisibleReleaseStep(stepDeg = abs(animationDelta))
                }

                when (navMode) {
                    NavMode.COMPASS_FOLLOW -> {
                        applyMapRotation(
                            targetRotationDeg = -next,
                            highFrequencyRotation = activeHeadingTurn,
                            responsiveRotation = responsiveRotation,
                            maxVisualStepDeg = headingTarget.maxVisualStepDeg,
                        )
                    }
                    NavMode.NORTH_UP_FOLLOW -> {
                        applyMapRotation(0f)
                        applyMarkersForMode(navMode)
                    }
                    NavMode.PANNING -> Unit
                }
                if (CompassDeepTraceDiagnostics.state.value.active) {
                    CompassDeepTraceDiagnostics.recordRenderSample(
                        CompassDeepTraceRenderSample(
                            targetHeadingDeg = headingTarget.headingDeg,
                            renderedHeadingDeg = next,
                            mapRotationDeg = displayedMapRot.floatValue,
                            continuityActive = false,
                            continuityOffsetDeg = 0f,
                            atElapsedMs = nowElapsedMs,
                        ),
                    )
                }
                CompassHeadingDiagnostics.recordRenderSample(
                    targetHeadingDeg = headingTarget.headingDeg,
                    renderedHeadingDeg = next,
                    mapRotationDeg = displayedMapRot.floatValue,
                    atElapsedMs = nowElapsedMs,
                )
                requestMapRedraw()
                CompassRenderPerfTelemetry.recordRedraw(navMode)
            }
        }
    }
}

private object CompassRenderPerfTelemetry {
    private var windowStartElapsedMs: Long = 0L
    private var frameCount: Int = 0
    private var targetUpdateCount: Int = 0
    private var headingRenderCount: Int = 0
    private var rotationAppliedCount: Int = 0
    private var rotationSkippedCount: Int = 0
    private var rotationThrottledCount: Int = 0
    private var markerUpdateCount: Int = 0
    private var redrawCount: Int = 0

    fun recordFrame(navMode: NavMode) = record(navMode) { frameCount += 1 }

    fun recordTargetUpdate(navMode: NavMode) = record(navMode) { targetUpdateCount += 1 }

    fun recordHeadingRender(navMode: NavMode) = record(navMode) { headingRenderCount += 1 }

    fun recordRotationApplied(navMode: NavMode) = record(navMode) { rotationAppliedCount += 1 }

    fun recordRotationSkipped(navMode: NavMode) = record(navMode) { rotationSkippedCount += 1 }

    fun recordRotationThrottled(navMode: NavMode) = record(navMode) { rotationThrottledCount += 1 }

    fun recordMarkerUpdate(navMode: NavMode) = record(navMode) { markerUpdateCount += 1 }

    fun recordRedraw(navMode: NavMode) = record(navMode) { redrawCount += 1 }

    @Synchronized
    private fun record(
        navMode: NavMode,
        mutate: () -> Unit,
    ) {
        if (!isCompassTelemetryCaptureActive()) return
        val now = SystemClock.elapsedRealtime()
        if (windowStartElapsedMs == 0L) {
            windowStartElapsedMs = now
        }
        mutate()
        val windowMs = (now - windowStartElapsedMs).coerceAtLeast(0L)
        if (windowMs < COMPASS_RENDER_PERF_LOG_WINDOW_MS) return
        val seconds = (windowMs / 1000f).coerceAtLeast(0.001f)
        DebugTelemetry.log(
            COMPASS_TELEMETRY_TAG,
            "compass_render perf windowMs=$windowMs navMode=${navMode.name} " +
                "frames=$frameCount frameHz=${(frameCount / seconds).formatTelemetry(1)} " +
                "targetUpdates=$targetUpdateCount headingRenders=$headingRenderCount " +
                "renderHz=${(headingRenderCount / seconds).formatTelemetry(1)} " +
                "rotationApplied=$rotationAppliedCount rotationSkipped=$rotationSkippedCount " +
                "rotationThrottled=$rotationThrottledCount " +
                "markerUpdates=$markerUpdateCount redraws=$redrawCount",
        )
        reset(now)
    }

    private fun reset(nextWindowStartElapsedMs: Long) {
        windowStartElapsedMs = nextWindowStartElapsedMs
        frameCount = 0
        targetUpdateCount = 0
        headingRenderCount = 0
        rotationAppliedCount = 0
        rotationSkippedCount = 0
        rotationThrottledCount = 0
        markerUpdateCount = 0
        redrawCount = 0
    }
}

/** Captures what was actually visible before the display became non-interactive. */
internal class NavigateWakeContinuityCapture {
    private val interactiveHistory = ArrayDeque<InteractiveHeadingSample>()
    private var lastInteractiveProviderHeadingDeg = Float.NaN
    private var lastInteractiveTargetHeadingDeg = Float.NaN
    private var lastInteractiveRenderedHeadingDeg = Float.NaN
    private var screenOffAnchorHeadingDeg = Float.NaN
    private var screenOffPreLoweringDetected = false

    fun beginInteractiveSession() {
        interactiveHistory.clear()
        lastInteractiveProviderHeadingDeg = Float.NaN
        lastInteractiveTargetHeadingDeg = Float.NaN
        lastInteractiveRenderedHeadingDeg = Float.NaN
    }

    fun recordInteractive(
        providerHeadingDeg: Float?,
        targetHeadingDeg: Float,
        renderedHeadingDeg: Float,
        pitchDeg: Float?,
        rollDeg: Float?,
        projection: Float?,
        atElapsedMs: Long,
    ) {
        providerHeadingDeg?.takeIf(Float::isFinite)?.let { lastInteractiveProviderHeadingDeg = it }
        if (targetHeadingDeg.isFinite()) lastInteractiveTargetHeadingDeg = targetHeadingDeg
        if (renderedHeadingDeg.isFinite()) lastInteractiveRenderedHeadingDeg = renderedHeadingDeg
        if (!renderedHeadingDeg.isFinite()) return
        interactiveHistory.addLast(
            InteractiveHeadingSample(
                providerHeadingDeg = providerHeadingDeg?.takeIf(Float::isFinite),
                targetHeadingDeg = targetHeadingDeg.takeIf(Float::isFinite),
                renderedHeadingDeg = renderedHeadingDeg,
                pitchDeg = pitchDeg?.takeIf(Float::isFinite),
                rollDeg = rollDeg?.takeIf(Float::isFinite),
                projection = projection?.takeIf(Float::isFinite),
                atElapsedMs = atElapsedMs,
            ),
        )
        while (interactiveHistory.firstOrNull()?.atElapsedMs ?: Long.MAX_VALUE < atElapsedMs - WAKE_ANCHOR_HISTORY_MS) {
            interactiveHistory.removeFirst()
        }
    }

    fun screenOff(
        fallbackHeadingDeg: Float,
        nowElapsedMs: Long,
    ): NavigateScreenOffAnchorSnapshot {
        val selection = selectScreenOffAnchor(fallbackHeadingDeg, nowElapsedMs)
        screenOffAnchorHeadingDeg = selection.headingDeg
        screenOffPreLoweringDetected = selection.preLoweringDetected
        return NavigateScreenOffAnchorSnapshot(
            lastInteractiveProviderHeadingDeg = lastInteractiveProviderHeadingDeg,
            lastInteractiveTargetHeadingDeg = lastInteractiveTargetHeadingDeg,
            lastInteractiveRenderedHeadingDeg = lastInteractiveRenderedHeadingDeg,
            screenOffAnchorHeadingDeg = this.screenOffAnchorHeadingDeg,
            currentRenderedHeadingDeg = fallbackHeadingDeg,
            anchorRewindMs = selection.rewindMs,
            preLoweringDetected = selection.preLoweringDetected,
            motionStartAgeMs = selection.motionStartAgeMs,
            selectionReason = selection.reason,
            diagnosticHistory =
                diagnosticHistory(
                    currentHeadingDeg = fallbackHeadingDeg,
                    nowElapsedMs = nowElapsedMs,
                    selection = selection,
                ),
        )
    }

    fun wake(fallbackHeadingDeg: Float): NavigateWakeAnchorSnapshot {
        val wakeHeldHeadingDeg =
            screenOffAnchorHeadingDeg.takeIf(Float::isFinite) ?: fallbackHeadingDeg
        return NavigateWakeAnchorSnapshot(
            screenOffAnchorHeadingDeg = screenOffAnchorHeadingDeg,
            wakeHeldHeadingDeg = wakeHeldHeadingDeg,
            preLoweringDetected = screenOffPreLoweringDetected,
            wakeHeadingDeltaDeg =
                if (screenOffAnchorHeadingDeg.isFinite() && wakeHeldHeadingDeg.isFinite()) {
                    abs(angleDeltaDeg(wakeHeldHeadingDeg, screenOffAnchorHeadingDeg))
                } else {
                    Float.NaN
                },
        )
    }

    private fun selectScreenOffAnchor(
        currentHeadingDeg: Float,
        nowElapsedMs: Long,
    ): WakeAnchorSelection {
        val samples = interactiveHistory.filter { nowElapsedMs - it.atElapsedMs in 0L..WAKE_ANCHOR_HISTORY_MS }
        val latestSample =
            samples.lastOrNull()
                ?: return WakeAnchorSelection(currentHeadingDeg, 0L, false, null, "current_no_history")
        if (nowElapsedMs - latestSample.atElapsedMs > WAKE_ANCHOR_RECENT_MOTION_MS) {
            return WakeAnchorSelection(currentHeadingDeg, 0L, false, null, "current_no_recent_motion")
        }
        val stableCandidate =
            samples
                .asReversed()
                .firstOrNull { candidate ->
                    isStableBefore(candidate, samples) &&
                        abs(angleDeltaDeg(currentHeadingDeg, candidate.renderedHeadingDeg)) >=
                        WAKE_ANCHOR_MIN_HEADING_CHANGE_DEG &&
                        hasLoweringTiltSignature(candidate, latestSample)
                }
        return if (stableCandidate == null) {
            WakeAnchorSelection(currentHeadingDeg, 0L, false, null, "current_no_lowering_signature")
        } else {
            WakeAnchorSelection(
                headingDeg = stableCandidate.renderedHeadingDeg,
                rewindMs = (nowElapsedMs - stableCandidate.atElapsedMs).coerceAtLeast(0L),
                preLoweringDetected = true,
                motionStartAgeMs = (nowElapsedMs - stableCandidate.atElapsedMs).coerceAtLeast(0L),
                reason = "pre_lowering_stable_visible",
                selectedAtElapsedMs = stableCandidate.atElapsedMs,
            )
        }
    }

    private fun diagnosticHistory(
        currentHeadingDeg: Float,
        nowElapsedMs: Long,
        selection: WakeAnchorSelection,
    ): List<WakeAnchorHistoryPoint> {
        val samples =
            interactiveHistory.filter {
                nowElapsedMs - it.atElapsedMs in 0L..WAKE_ANCHOR_HISTORY_MS
            }
        val latestSample = samples.lastOrNull() ?: return emptyList()
        val selectedSample = samples.firstOrNull { it.atElapsedMs == selection.selectedAtElapsedMs }
        return (
            samples
                .groupBy { (nowElapsedMs - it.atElapsedMs) / WAKE_ANCHOR_DIAGNOSTIC_SAMPLE_MS }
                .values
                .map { bucket -> bucket.last() } + selectedSample
        ).filterNotNull()
            .distinctBy { it.atElapsedMs }
            .sortedBy { it.atElapsedMs }
            .map { sample ->
                val pitchChangeDeg =
                    if (sample.pitchDeg != null && latestSample.pitchDeg != null) {
                        abs(latestSample.pitchDeg - sample.pitchDeg)
                    } else {
                        Float.NaN
                    }
                val rollChangeDeg =
                    if (sample.rollDeg != null && latestSample.rollDeg != null) {
                        abs(latestSample.rollDeg - sample.rollDeg)
                    } else {
                        Float.NaN
                    }
                val projectionDrop =
                    if (sample.projection != null && latestSample.projection != null) {
                        sample.projection - latestSample.projection
                    } else {
                        Float.NaN
                    }
                WakeAnchorHistoryPoint(
                    ageMs = (nowElapsedMs - sample.atElapsedMs).coerceAtLeast(0L),
                    providerHeadingDeg = sample.providerHeadingDeg,
                    targetHeadingDeg = sample.targetHeadingDeg,
                    renderedHeadingDeg = sample.renderedHeadingDeg,
                    pitchDeg = sample.pitchDeg,
                    rollDeg = sample.rollDeg,
                    projection = sample.projection,
                    headingDeltaToScreenOffDeg =
                        abs(angleDeltaDeg(currentHeadingDeg, sample.renderedHeadingDeg)),
                    tiltChangeToLatestDeg = maxOf(pitchChangeDeg, rollChangeDeg),
                    projectionDropToLatest = projectionDrop,
                    stableBefore = isStableBefore(sample, samples),
                    loweringSignatureToLatest = hasLoweringTiltSignature(sample, latestSample),
                    selected = sample.atElapsedMs == selection.selectedAtElapsedMs,
                )
            }
    }

    private fun isStableBefore(
        candidate: InteractiveHeadingSample,
        samples: List<InteractiveHeadingSample>,
    ): Boolean {
        val preceding =
            samples.filter { sample ->
                sample.atElapsedMs in (candidate.atElapsedMs - WAKE_ANCHOR_STABILITY_WINDOW_MS)..candidate.atElapsedMs
            }
        return preceding.size >= 2 &&
            preceding.all { sample ->
                abs(angleDeltaDeg(sample.renderedHeadingDeg, candidate.renderedHeadingDeg)) <=
                    WAKE_ANCHOR_STABLE_HEADING_TOLERANCE_DEG
            }
    }

    private fun hasLoweringTiltSignature(
        stable: InteractiveHeadingSample,
        current: InteractiveHeadingSample,
    ): Boolean {
        val pitchChangeDeg =
            if (stable.pitchDeg != null && current.pitchDeg != null) {
                abs(current.pitchDeg - stable.pitchDeg)
            } else {
                0f
            }
        val rollChangeDeg =
            if (stable.rollDeg != null && current.rollDeg != null) {
                abs(current.rollDeg - stable.rollDeg)
            } else {
                0f
            }
        val projectionDrop =
            if (stable.projection != null && current.projection != null) {
                stable.projection - current.projection
            } else {
                0f
            }
        return maxOf(pitchChangeDeg, rollChangeDeg) >= WAKE_ANCHOR_MIN_TILT_CHANGE_DEG ||
            projectionDrop >= WAKE_ANCHOR_MIN_PROJECTION_DROP
    }
}

private data class InteractiveHeadingSample(
    val providerHeadingDeg: Float?,
    val targetHeadingDeg: Float?,
    val renderedHeadingDeg: Float,
    val pitchDeg: Float?,
    val rollDeg: Float?,
    val projection: Float?,
    val atElapsedMs: Long,
)

private data class WakeAnchorSelection(
    val headingDeg: Float,
    val rewindMs: Long,
    val preLoweringDetected: Boolean,
    val motionStartAgeMs: Long?,
    val reason: String,
    val selectedAtElapsedMs: Long? = null,
)

internal data class WakeAnchorHistoryPoint(
    val ageMs: Long,
    val providerHeadingDeg: Float?,
    val targetHeadingDeg: Float?,
    val renderedHeadingDeg: Float,
    val pitchDeg: Float?,
    val rollDeg: Float?,
    val projection: Float?,
    val headingDeltaToScreenOffDeg: Float,
    val tiltChangeToLatestDeg: Float,
    val projectionDropToLatest: Float,
    val stableBefore: Boolean,
    val loweringSignatureToLatest: Boolean,
    val selected: Boolean,
)

internal data class NavigateScreenOffAnchorSnapshot(
    val lastInteractiveProviderHeadingDeg: Float,
    val lastInteractiveTargetHeadingDeg: Float,
    val lastInteractiveRenderedHeadingDeg: Float,
    val screenOffAnchorHeadingDeg: Float,
    val currentRenderedHeadingDeg: Float,
    val anchorRewindMs: Long,
    val preLoweringDetected: Boolean,
    val motionStartAgeMs: Long?,
    val selectionReason: String,
    val diagnosticHistory: List<WakeAnchorHistoryPoint>,
)

internal data class NavigateWakeAnchorSnapshot(
    val screenOffAnchorHeadingDeg: Float,
    val wakeHeldHeadingDeg: Float,
    val preLoweringDetected: Boolean,
    val wakeHeadingDeltaDeg: Float,
)

private fun NavigateScreenOffAnchorSnapshot.log() {
    if (!isCompassTelemetryCaptureActive()) return
    DebugTelemetry.log(
        COMPASS_TELEMETRY_TAG,
        "wake_anchor stage=screen_off " +
            "lastInteractiveProviderHeading=${lastInteractiveProviderHeadingDeg.formatWakeAnchor(1)} " +
            "lastInteractiveTargetHeading=${lastInteractiveTargetHeadingDeg.formatWakeAnchor(1)} " +
            "lastInteractiveRenderedHeading=${lastInteractiveRenderedHeadingDeg.formatWakeAnchor(1)} " +
            "screenOffAnchorHeading=${screenOffAnchorHeadingDeg.formatWakeAnchor(1)} " +
            "screenOffCurrentRenderedHeading=${currentRenderedHeadingDeg.formatWakeAnchor(1)} " +
            "screenOffSelectedAnchorHeading=${screenOffAnchorHeadingDeg.formatWakeAnchor(1)} " +
            "screenOffAnchorRewindMs=$anchorRewindMs " +
            "screenOffPreLoweringDetected=$preLoweringDetected " +
            "screenOffMotionStartAgeMs=${motionStartAgeMs ?: "na"} " +
            "screenOffAnchorSelectionReason=$selectionReason",
    )
    val chunks = diagnosticHistory.chunked(WAKE_ANCHOR_DIAGNOSTIC_POINTS_PER_LOG)
    chunks.forEachIndexed { index, points ->
        DebugTelemetry.log(
            COMPASS_TELEMETRY_TAG,
            "wake_anchor_history stage=screen_off chunk=${index + 1}/${chunks.size} " +
                "sampleIntervalMs=$WAKE_ANCHOR_DIAGNOSTIC_SAMPLE_MS " +
                "points=${points.joinToString(separator = "|") { it.formatWakeAnchorHistoryPoint() }}",
        )
    }
}

private const val WAKE_ANCHOR_HISTORY_MS = 2_000L
private const val WAKE_ANCHOR_RECENT_MOTION_MS = 350L
private const val WAKE_ANCHOR_STABILITY_WINDOW_MS = 150L
private const val WAKE_ANCHOR_STABLE_HEADING_TOLERANCE_DEG = 5f
private const val WAKE_ANCHOR_MIN_HEADING_CHANGE_DEG = 20f
private const val WAKE_ANCHOR_MIN_TILT_CHANGE_DEG = 15f
private const val WAKE_ANCHOR_MIN_PROJECTION_DROP = 0.12f
private const val WAKE_ANCHOR_DIAGNOSTIC_SAMPLE_MS = 50L
private const val WAKE_ANCHOR_DIAGNOSTIC_POINTS_PER_LOG = 6

private fun NavigateWakeAnchorSnapshot.log() {
    if (!isCompassTelemetryCaptureActive()) return
    DebugTelemetry.log(
        COMPASS_TELEMETRY_TAG,
        "wake_anchor stage=wake " +
            "screenOffAnchorHeading=${screenOffAnchorHeadingDeg.formatWakeAnchor(1)} " +
            "wakeHeldHeading=${wakeHeldHeadingDeg.formatWakeAnchor(1)} " +
            "wakeHeadingDeltaDeg=${wakeHeadingDeltaDeg.formatWakeAnchor(1)}",
    )
}

private fun angleDeltaDeg(
    target: Float,
    current: Float,
): Float {
    var d = (target - current) % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return d
}

internal fun shouldDriveCompassFollowMap(renderState: CompassRenderState): Boolean {
    if (renderState.headingSource == HeadingSource.NONE) return false
    return if (renderState.providerType == CompassProviderType.GOOGLE_FUSED) {
        renderState.headingSource == HeadingSource.FUSED_ORIENTATION &&
            renderState.headingSampleElapsedRealtimeMs != null &&
            !renderState.headingSampleStale &&
            renderState.headingRenderable
    } else {
        renderState.accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE
    }
}

internal fun shouldHoldCompassFollowStartupForMagneticInterference(
    renderState: CompassRenderState,
): Boolean =
    renderState.providerType == CompassProviderType.GOOGLE_FUSED &&
        (
            renderState.magneticInterference ||
                renderState.magneticQuality == CompassMagneticQuality.INTERFERENCE
        )

internal fun hasStableMagneticCompassHeading(renderState: CompassRenderState): Boolean =
    renderState.providerType == CompassProviderType.GOOGLE_FUSED &&
        renderState.magneticQuality == CompassMagneticQuality.GOOD &&
        !renderState.magneticInterference &&
        renderState.trackingState == CompassTrackingState.TRACKING &&
        renderState.trackingReason == CompassTrackingReason.STABLE

internal fun shouldUseWakeContinuityAnchor(navMode: NavMode): Boolean = navMode == NavMode.COMPASS_FOLLOW

internal fun shouldUseResponsiveCompassMapRotation(renderState: CompassRenderState): Boolean =
    shouldDriveCompassFollowMap(renderState) &&
        renderState.magneticQuality == CompassMagneticQuality.GOOD &&
        !renderState.magneticInterference &&
        (
            renderState.headingTrusted ||
                hasStableMagneticCompassHeading(renderState)
        )

/** Preserves the map angle briefly while the new wake session validates its heading. */
internal class NavigateRotationSettleGate {
    private var wakeSessionId = 0L
    private var wakeSessionActive = false
    private var wakeSessionStartedAtElapsedMs = Long.MIN_VALUE
    private var heldHeadingDeg = Float.NaN
    private var firstFreshWakeHeadingDeg = Float.NaN
    private var coldStart = false
    private var requirePostStableHeading = false
    private var stableTrackingObservedAtElapsedMs = Long.MIN_VALUE
    private var magneticRecoveryRequired = false
    private var settled = false
    private var lastHoldReason: String? = null
    private var releaseVisualCapUntilElapsedMs = Long.MIN_VALUE
    private var pendingRelease: WakeRelease? = null

    fun beginWakeSession(
        nowElapsedMs: Long,
        heldHeadingDeg: Float,
        coldStart: Boolean = false,
        requirePostStableHeading: Boolean = false,
    ) {
        wakeSessionId += 1L
        wakeSessionActive = true
        wakeSessionStartedAtElapsedMs = nowElapsedMs
        this.heldHeadingDeg = normalize360(heldHeadingDeg)
        firstFreshWakeHeadingDeg = Float.NaN
        this.coldStart = coldStart
        this.requirePostStableHeading = requirePostStableHeading
        stableTrackingObservedAtElapsedMs = Long.MIN_VALUE
        magneticRecoveryRequired = false
        settled = false
        lastHoldReason = null
        releaseVisualCapUntilElapsedMs = Long.MIN_VALUE
        pendingRelease = null
        log(
            "rotation_settle stage=start id=$wakeSessionId " +
                "heldHeading=${this.heldHeadingDeg.formatTelemetry(1)} coldStart=$coldStart " +
                "requirePostStableHeading=$requirePostStableHeading",
        )
    }

    fun endWakeSession(nowElapsedMs: Long) {
        if (!wakeSessionActive) return
        log(
            "rotation_settle stage=end id=$wakeSessionId settled=$settled durationMs=" +
                "${(nowElapsedMs - wakeSessionStartedAtElapsedMs).coerceAtLeast(0L)}",
        )
        wakeSessionActive = false
    }

    fun resolve(
        renderState: CompassRenderState,
        compassHeadingDeg: Float,
        headingSampleElapsedRealtimeMs: Long?,
        nowElapsedMs: Long,
        currentDisplayedHeadingDeg: Float? = null,
    ): NavigationRotationTarget? {
        if (!shouldDriveCompassFollowMap(renderState) || !compassHeadingDeg.isFinite()) {
            hold("await_usable_heading")
            return null
        }
        val heading = normalize360(compassHeadingDeg)
        if (!wakeSessionActive || settled) {
            return NavigationRotationTarget(
                headingDeg = heading,
                maxVisualStepDeg = releaseVisualStepCap(nowElapsedMs),
                recordsWakeReleaseStep = pendingRelease != null,
            )
        }
        if (shouldHoldCompassFollowStartupForMagneticInterference(renderState)) {
            magneticRecoveryRequired = true
            currentDisplayedHeadingDeg?.takeIf(Float::isFinite)?.let {
                heldHeadingDeg = normalize360(it)
            }
            hold("await_magnetic_recovery")
            return null
        }
        if (
            headingSampleElapsedRealtimeMs == null ||
            headingSampleElapsedRealtimeMs <= wakeSessionStartedAtElapsedMs
        ) {
            hold("await_fresh_session_sample")
            return null
        }
        if (!firstFreshWakeHeadingDeg.isFinite()) {
            firstFreshWakeHeadingDeg = heading
            log(
                "rotation_settle stage=first_fresh id=$wakeSessionId " +
                    "wakeHeldHeading=${heldHeadingDeg.formatTelemetry(1)} " +
                    "firstFreshWakeHeading=${firstFreshWakeHeadingDeg.formatTelemetry(1)} " +
                    "wakeHeadingDeltaDeg=${abs(angleDeltaDeg(firstFreshWakeHeadingDeg, heldHeadingDeg)).formatTelemetry(1)}",
            )
        }
        if (coldStart && !magneticRecoveryRequired) {
            settled = true
            return NavigationRotationTarget(headingDeg = heading)
        }
        val hasStableRecoveryHeading = hasStableMagneticCompassHeading(renderState)
        val hasStableTracking =
            hasStableRecoveryHeading ||
                (
                    !magneticRecoveryRequired &&
                        renderState.trackingState == CompassTrackingState.TRACKING &&
                        renderState.trackingReason == CompassTrackingReason.STABLE
                )
        if (
            requirePostStableHeading &&
            hasStableTracking &&
            stableTrackingObservedAtElapsedMs == Long.MIN_VALUE
        ) {
            stableTrackingObservedAtElapsedMs = nowElapsedMs
        }
        val hasPostStableHeading =
            !requirePostStableHeading ||
                headingSampleElapsedRealtimeMs > stableTrackingObservedAtElapsedMs
        val releaseReason =
            when {
                hasStableTracking && hasPostStableHeading ->
                    "stable_tracking"
                !magneticRecoveryRequired &&
                    nowElapsedMs - wakeSessionStartedAtElapsedMs >= WAKE_SETTLE_TIMEOUT_MS ->
                    "settle_timeout"
                else -> null
            }
        if (releaseReason == null) {
            hold(
                reason =
                    if (hasStableTracking && !hasPostStableHeading) {
                        "await_post_stable_heading"
                    } else if (magneticRecoveryRequired) {
                        "await_stable_magnetic_heading"
                    } else {
                        "await_stable_tracking"
                    },
                headingDeltaDeg = abs(angleDeltaDeg(heading, heldHeadingDeg)),
            )
            return null
        }
        return release(
            reason = releaseReason,
            headingDeg = heading,
            nowElapsedMs = nowElapsedMs,
        )
    }

    fun recordFirstVisibleReleaseStep(stepDeg: Float) {
        val release = pendingRelease ?: return
        pendingRelease = null
        log(
            "rotation_settle stage=release id=$wakeSessionId " +
                "wakeHoldDurationMs=${release.holdDurationMs} " +
                "wakeReleaseReason=${release.reason} " +
                "wakeReleaseHeadingDeltaDeg=${release.headingDeltaDeg.formatTelemetry(1)} " +
                "firstVisibleReleaseStepDeg=${stepDeg.coerceAtLeast(0f).formatTelemetry(1)}",
        )
    }

    private fun hold(
        reason: String,
        headingDeltaDeg: Float? = null,
    ) {
        if (lastHoldReason == reason) return
        lastHoldReason = reason
        log(
            "rotation_settle stage=hold id=$wakeSessionId reason=$reason " +
                "headingDeltaDeg=${headingDeltaDeg?.formatTelemetry(1) ?: "na"}",
        )
    }

    private fun release(
        reason: String,
        headingDeg: Float,
        nowElapsedMs: Long,
    ): NavigationRotationTarget {
        val holdDurationMs = (nowElapsedMs - wakeSessionStartedAtElapsedMs).coerceAtLeast(0L)
        lastHoldReason = null
        settled = true
        releaseVisualCapUntilElapsedMs = nowElapsedMs + WAKE_RELEASE_VISUAL_CAP_DURATION_MS
        pendingRelease =
            WakeRelease(
                reason = reason,
                holdDurationMs = holdDurationMs,
                headingDeltaDeg = abs(angleDeltaDeg(headingDeg, heldHeadingDeg)),
            )
        return NavigationRotationTarget(
            headingDeg = headingDeg,
            maxVisualStepDeg = WAKE_RELEASE_MAX_VISIBLE_STEP_DEG,
            recordsWakeReleaseStep = true,
        )
    }

    private fun releaseVisualStepCap(nowElapsedMs: Long): Float? = WAKE_RELEASE_MAX_VISIBLE_STEP_DEG.takeIf { nowElapsedMs < releaseVisualCapUntilElapsedMs }

    private fun log(message: String) {
        if (isCompassTelemetryCaptureActive()) DebugTelemetry.log(COMPASS_TELEMETRY_TAG, message)
    }
}

private data class WakeRelease(
    val reason: String,
    val holdDurationMs: Long,
    val headingDeltaDeg: Float,
)

internal data class NavigationRotationTarget(
    val headingDeg: Float,
    val maxVisualStepDeg: Float? = null,
    val recordsWakeReleaseStep: Boolean = false,
)

internal fun shouldDriveMarkerHeading(renderState: CompassRenderState): Boolean {
    if (renderState.headingSource == HeadingSource.NONE) return false
    return when (renderState.providerType) {
        CompassProviderType.SENSOR_MANAGER ->
            renderState.accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE
        CompassProviderType.GOOGLE_FUSED ->
            renderState.headingSource == HeadingSource.FUSED_ORIENTATION &&
                renderState.headingSampleElapsedRealtimeMs != null &&
                !renderState.headingSampleStale &&
                renderState.headingRenderable
    }
}

internal fun shouldDriveHeadingForNavMode(
    navMode: NavMode,
    renderState: CompassRenderState,
): Boolean =
    when (navMode) {
        NavMode.COMPASS_FOLLOW -> shouldDriveCompassFollowMap(renderState)
        NavMode.NORTH_UP_FOLLOW -> shouldDriveMarkerHeading(renderState)
        NavMode.PANNING -> false
    }

internal fun shouldSeedCompassFollowMapWithCachedHeading(
    renderState: CompassRenderState,
    nowElapsedMs: Long,
): Boolean =
    hasRecentGoogleFusedCachedHeading(
        renderState = renderState,
        nowElapsedMs = nowElapsedMs,
        maxAgeMs = GOOGLE_FUSED_CACHED_HEADING_SEED_MAX_AGE_MS,
    )

internal fun shouldSeedNorthUpMarkerWithCachedHeading(
    renderState: CompassRenderState,
    nowElapsedMs: Long,
): Boolean =
    renderState.providerType == CompassProviderType.GOOGLE_FUSED &&
        hasRecentGoogleFusedCachedHeading(
            renderState = renderState,
            nowElapsedMs = nowElapsedMs,
            maxAgeMs = GOOGLE_FUSED_CACHED_HEADING_SEED_MAX_AGE_MS,
        )

internal fun resolveNavigateInitialRenderedHeadingDeg(
    renderState: CompassRenderState,
    nowElapsedMs: Long,
): Float =
    if (
        shouldDriveCompassFollowMap(renderState) ||
        shouldDriveMarkerHeading(renderState) ||
        shouldSeedCompassFollowMapWithCachedHeading(renderState, nowElapsedMs) ||
        shouldSeedNorthUpMarkerWithCachedHeading(renderState, nowElapsedMs)
    ) {
        normalize360(renderState.headingDeg)
    } else {
        0f
    }

private fun normalize360(deg: Float): Float = (deg % 360f + 360f) % 360f

internal fun shouldPublishRenderedCompassUiState(
    nowElapsedMs: Long,
    lastPublishedAtElapsedMs: Long,
    force: Boolean = false,
): Boolean =
    force ||
        lastPublishedAtElapsedMs == Long.MIN_VALUE ||
        nowElapsedMs - lastPublishedAtElapsedMs >= RENDERED_COMPASS_UI_PUBLISH_INTERVAL_MS

internal fun shouldThrottleMapsforgeRotation(
    navMode: NavMode,
    nowElapsedMs: Long,
    lastAppliedAtElapsedMs: Long,
    highFrequencyRotation: Boolean = false,
): Boolean {
    val minimumIntervalMs =
        if (highFrequencyRotation) {
            MAP_ROTATION_ACTIVE_TURN_MIN_APPLY_INTERVAL_MS
        } else {
            MAP_ROTATION_MIN_APPLY_INTERVAL_MS
        }
    return navMode == NavMode.COMPASS_FOLLOW &&
        lastAppliedAtElapsedMs != Long.MIN_VALUE &&
        nowElapsedMs - lastAppliedAtElapsedMs < minimumIntervalMs
}

internal fun resolveHeadingAnimationAlpha(
    diffDeg: Float,
    activeTurn: Boolean,
    frameDeltaMs: Float,
    responsiveRotation: Boolean = false,
): Float {
    if (!diffDeg.isFinite() || !frameDeltaMs.isFinite() || frameDeltaMs <= 0f) return 0f
    val timeConstantMs =
        when {
            responsiveRotation -> RESPONSIVE_HEADING_ANIMATION_TIME_CONSTANT_MS
            activeTurn && abs(diffDeg) >= ACTIVE_TURN_LARGE_ERROR_DEG ->
                ACTIVE_TURN_LARGE_ERROR_TIME_CONSTANT_MS
            activeTurn -> ACTIVE_TURN_ANIMATION_TIME_CONSTANT_MS
            else -> HEADING_ANIMATION_TIME_CONSTANT_MS
        }
    return (1.0 - exp(-frameDeltaMs.toDouble() / timeConstantMs.toDouble())).toFloat()
}

internal fun resolveHeadingAnimationDelta(
    diffDeg: Float,
    activeTurn: Boolean,
    frameDeltaMs: Float,
    responsiveRotation: Boolean = false,
    maxStepDeg: Float? = null,
): Float {
    if (!diffDeg.isFinite()) return 0f
    val animatedDelta =
        diffDeg *
            resolveHeadingAnimationAlpha(
                diffDeg = diffDeg,
                activeTurn = activeTurn,
                frameDeltaMs = frameDeltaMs,
                responsiveRotation = responsiveRotation,
            )
    // A delayed frame must not turn a transient provider jump into a visible snap.
    val maximumStepDeg =
        minOf(
            maxStepDeg ?: Float.POSITIVE_INFINITY,
            if (responsiveRotation) {
                RESPONSIVE_HEADING_ANIMATION_MAX_STEP_DEG
            } else {
                HEADING_ANIMATION_MAX_STEP_DEG
            },
        )
    return animatedDelta.coerceIn(
        minimumValue = -maximumStepDeg,
        maximumValue = maximumStepDeg,
    )
}

internal fun resolveHeadingAnimationFrameDeltaMs(
    frameTimeNanos: Long,
    previousFrameTimeNanos: Long,
): Float {
    if (previousFrameTimeNanos <= 0L || frameTimeNanos <= previousFrameTimeNanos) {
        return HEADING_ANIMATION_NOMINAL_FRAME_DELTA_MS
    }
    return (
        (frameTimeNanos - previousFrameTimeNanos).toDouble() /
            NANOS_PER_MILLISECOND
    ).toFloat().coerceIn(
        minimumValue = HEADING_ANIMATION_MIN_FRAME_DELTA_MS,
        maximumValue = HEADING_ANIMATION_MAX_FRAME_DELTA_MS,
    )
}

internal fun resolveCompassVisualTargetAngle(
    currentAngleDeg: Float,
    targetAngleDeg: Float,
    maxStepDeg: Float = HEADING_ANIMATION_MAX_STEP_DEG,
): Float {
    if (!currentAngleDeg.isFinite() || !targetAngleDeg.isFinite()) return currentAngleDeg
    val boundedStepDeg = maxStepDeg.coerceAtLeast(0f)
    val deltaDeg =
        angleDeltaDeg(targetAngleDeg, currentAngleDeg).coerceIn(
            minimumValue = -boundedStepDeg,
            maximumValue = boundedStepDeg,
        )
    return currentAngleDeg + deltaDeg
}

// Small heading noise is visible as left/right map shimmer in compass-follow.
// Keep the compass pipeline responsive, but avoid applying sub-degree Mapsforge rotations.
private const val MAP_ROTATION_APPLY_EPSILON_DEG = 0.8f
private const val MAP_ROTATION_ACTIVE_TURN_APPLY_EPSILON_DEG = 0.35f

// Animate the Compose heading every display frame, but avoid asking Mapsforge to redraw/rotate
// more than 30 times per second while stationary. During a deliberate turn, temporarily allow
// display-rate rotation so a 360-degree sweep stays fluid, then fall back to the lower-power rate.
private const val MAP_ROTATION_MIN_APPLY_INTERVAL_MS = 33L
private const val MAP_ROTATION_ACTIVE_TURN_MIN_APPLY_INTERVAL_MS = 16L

// Keep the frame-rate interpolation local to the map, while publishing the surrounding Compose
// screen state at the same 25fps cadence as the existing map-overlay redraw flow.
private const val RENDERED_COMPASS_UI_PUBLISH_INTERVAL_MS = 40L

// Time-based interpolation keeps the same visual response when the watch renders at 30, 45 or
// 60fps. A deliberate turn uses a shorter time constant, while large corrections remain bounded.
private const val HEADING_ANIMATION_TIME_CONSTANT_MS = 80f
private const val ACTIVE_TURN_ANIMATION_TIME_CONSTANT_MS = 42f
private const val ACTIVE_TURN_LARGE_ERROR_TIME_CONSTANT_MS = 20f
private const val RESPONSIVE_HEADING_ANIMATION_TIME_CONSTANT_MS = 24f
private const val ACTIVE_TURN_LARGE_ERROR_DEG = 25f
private const val HEADING_ANIMATION_NOMINAL_FRAME_DELTA_MS = 16.666_667f
private const val HEADING_ANIMATION_MIN_FRAME_DELTA_MS = 4f
private const val HEADING_ANIMATION_MAX_FRAME_DELTA_MS = 50f
private const val HEADING_ANIMATION_MAX_STEP_DEG = 10f
private const val RESPONSIVE_HEADING_ANIMATION_MAX_STEP_DEG = 20f
private const val WAKE_SETTLE_TIMEOUT_MS = 700L
private const val WAKE_RELEASE_VISUAL_CAP_DURATION_MS = 750L
private const val WAKE_RELEASE_MAX_VISIBLE_STEP_DEG = 10f
private const val NANOS_PER_MILLISECOND = 1_000_000.0

// Enter turning mode promptly, then leave only after angular movement stays low. This prevents a
// slow 360-degree sweep from repeatedly switching between 25Hz and high-frequency rendering.
private const val RENDER_ACTIVE_TURN_ENTER_RATE_DEG_PER_SEC = 25f
private const val RENDER_ACTIVE_TURN_EXIT_RATE_DEG_PER_SEC = 15f
private const val RENDER_ACTIVE_TURN_EXIT_HOLD_MS = 300L
private const val RENDER_ACTIVE_TURN_MIN_ENTRY_STEP_DEG = 0.35f
private const val RENDER_ACTIVE_TURN_MAX_SAMPLE_GAP_MS = 300L

// Stop animating when within this threshold — below the useful visual precision of a watch map.
private const val HEADING_ANIMATION_DONE_DEG = 0.2f
private const val GOOGLE_FUSED_CACHED_HEADING_SEED_MAX_AGE_MS = 30_000L
private const val COMPASS_RENDER_PERF_LOG_WINDOW_MS = 5_000L
private const val MAP_CENTER_UPDATE_EPSILON_DEG2 = 1e-11

private val initializedOrientationMapViews =
    Collections.synchronizedMap(WeakHashMap<MapView, Boolean>())

private fun hasInitializedMapOrientation(
    mapView: MapView,
): Boolean = initializedOrientationMapViews.containsKey(mapView)

private fun markMapOrientationInitialized(mapView: MapView) {
    initializedOrientationMapViews[mapView] = true
}

private fun Float.formatTelemetry(decimals: Int): String = "%.${decimals}f".format(Locale.US, this)

private fun Float.formatWakeAnchor(decimals: Int): String = takeIf(Float::isFinite)?.let { "%.${decimals}f".format(Locale.US, it) } ?: "na"

private fun WakeAnchorHistoryPoint.formatWakeAnchorHistoryPoint(): String =
    "age=$ageMs," +
        "provider=${providerHeadingDeg?.formatWakeAnchor(1) ?: "na"}," +
        "target=${targetHeadingDeg?.formatWakeAnchor(1) ?: "na"}," +
        "rendered=${renderedHeadingDeg.formatWakeAnchor(1)}," +
        "pitch=${pitchDeg?.formatWakeAnchor(1) ?: "na"}," +
        "roll=${rollDeg?.formatWakeAnchor(1) ?: "na"}," +
        "projection=${projection?.formatWakeAnchor(2) ?: "na"}," +
        "headingDelta=${headingDeltaToScreenOffDeg.formatWakeAnchor(1)}," +
        "tiltDelta=${tiltChangeToLatestDeg.formatWakeAnchor(1)}," +
        "projectionDrop=${projectionDropToLatest.formatWakeAnchor(2)}," +
        "stable=$stableBefore," +
        "loweringSignature=$loweringSignatureToLatest," +
        "selected=$selected"

private fun shouldUpdateMapCenter(
    target: LatLong,
    current: LatLong?,
): Boolean {
    val center = current ?: return true
    val dLat = target.latitude - center.latitude
    val dLon = target.longitude - center.longitude
    return (dLat * dLat + dLon * dLon) >= MAP_CENTER_UPDATE_EPSILON_DEG2
}

private fun MapView.trySetMapsforgeRotation(
    degrees: Float,
    anchor: ScreenAnchor,
): Boolean {
    if (width <= 0 || height <= 0) return false
    rotate(Rotation(degrees, anchor.x.toFloat(), anchor.y.toFloat()))
    return true
}

/**
 * Theme application (optional).
 */
@Composable
fun MapThemeEffect(
    mapRenderer: MapRenderer?,
    themeKey: String,
    themeFile: File?,
) {
    LaunchedEffect(mapRenderer, themeKey) {
        val renderer = mapRenderer ?: return@LaunchedEffect
        renderer.setThemeConfig(
            themeFile = themeFile,
            mapsforgeThemeName = null,
            bundledThemeId = MapsforgeThemeCatalog.ELEVATE_THEME_ID,
            hillShadingEnabled = false,
            reliefOverlayEnabled = false,
        )
    }
}
