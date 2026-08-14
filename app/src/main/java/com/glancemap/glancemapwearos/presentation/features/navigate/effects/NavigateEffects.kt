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
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.domain.model.maps.theme.mapsforge.MapsforgeThemeCatalog
import com.glancemap.glancemapwearos.domain.sensors.COMPASS_TELEMETRY_TAG
import com.glancemap.glancemapwearos.domain.sensors.CompassProviderType
import com.glancemap.glancemapwearos.domain.sensors.CompassRenderState
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
    gpsFixFresh: Boolean,
    gpsFixSpeedMps: Float,
    gpsFixBearingDeg: Float?,
    mapView: MapView?,
    showRealMarkerInCompassMode: Boolean,
    locationMarker: RotatableMarker?,
    navigationMarkerAnchorMode: String,
    onRenderedHeadingChanged: (Float) -> Unit,
    onRenderedMapRotationChanged: (Float) -> Unit,
    onSuspectGoogleFusedHeading: () -> Unit,
    requestMapRedraw: () -> Unit,
) {
    val mv = mapView ?: return
    val marker = locationMarker
    val latestNavigationMarkerAnchorMode = rememberUpdatedState(navigationMarkerAnchorMode)
    val latestOnRenderedHeadingChanged = rememberUpdatedState(onRenderedHeadingChanged)
    val latestOnRenderedMapRotationChanged = rememberUpdatedState(onRenderedMapRotationChanged)
    val latestOnSuspectGoogleFusedHeading = rememberUpdatedState(onSuspectGoogleFusedHeading)
    val latestGpsFixFresh = rememberUpdatedState(gpsFixFresh)
    val latestGpsFixSpeedMps = rememberUpdatedState(gpsFixSpeedMps)
    val latestGpsFixBearingDeg = rememberUpdatedState(gpsFixBearingDeg)

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
    val hasObservedInteractive = remember(mv) { mutableStateOf(false) }
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
    ) {
        recenterLowerMarkerAnchor()
        val currentRotationDeg = syncDisplayedMapRotationFromMap()
        val resolvedTargetRotationDeg =
            if (navMode == NavMode.COMPASS_FOLLOW) {
                resolveCompassVisualTargetAngle(
                    currentAngleDeg = currentRotationDeg,
                    targetAngleDeg = targetRotationDeg,
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
            if (hasObservedInteractive.value) {
                val heldMapRotation = syncDisplayedMapRotationFromMap()
                val heldHeading = normalize360(-heldMapRotation)
                displayedHeading.floatValue = heldHeading
                rotationSettleGate.beginWakeSession(
                    nowElapsedMs = nowElapsedMs,
                    heldHeadingDeg = heldHeading,
                    previousRelativeHeadingDeg = renderStateFlow.value.relativeHeadingDeg,
                )
                publishRenderedState(force = true, nowElapsedMs = nowElapsedMs)
            }
            hasObservedInteractive.value = true
        } else {
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
                    (shouldDriveHeadingNow || shouldSeedCachedHeading)
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
                val nowElapsedMs = SystemClock.elapsedRealtime()
                val headingTarget =
                    when (navMode) {
                        NavMode.COMPASS_FOLLOW ->
                            rotationSettleGate.resolve(
                                nowElapsedMs = nowElapsedMs,
                                renderState = latestRenderState,
                                compassHeadingDeg = liveTarget,
                                headingSampleElapsedRealtimeMs =
                                    latestRenderState.headingSampleElapsedRealtimeMs,
                                relativeHeadingDeg = latestRenderState.relativeHeadingDeg,
                                gpsFixFresh = latestGpsFixFresh.value,
                                gpsFixSpeedMps = latestGpsFixSpeedMps.value,
                                gpsFixBearingDeg = latestGpsFixBearingDeg.value,
                                onSuspectGoogleFusedHeading = latestOnSuspectGoogleFusedHeading.value,
                            )

                        NavMode.NORTH_UP_FOLLOW ->
                            if (shouldDriveHeadingForNavMode(navMode, latestRenderState)) {
                                NavigationRotationTarget(
                                    headingDeg = liveTarget,
                                    source = NavigationRotationTargetSource.COMPASS,
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
                val current = displayedHeading.floatValue
                val diff = angleDeltaDeg(headingTarget.headingDeg, current)
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
                    return@withFrameNanos
                }

                val animationDelta =
                    resolveHeadingAnimationDelta(
                        diffDeg = diff,
                        activeTurn = activeHeadingTurn,
                        frameDeltaMs = frameDeltaMs,
                    )
                val next = normalize360(current + animationDelta)
                displayedHeading.floatValue = next
                CompassRenderPerfTelemetry.recordHeadingRender(navMode)

                when (navMode) {
                    NavMode.COMPASS_FOLLOW -> {
                        applyMapRotation(
                            targetRotationDeg = -next,
                            highFrequencyRotation = activeHeadingTurn,
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
        if (!DebugTelemetry.isEnabled()) return
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

/**
 * A Google Fused heading can restart with an inverted north reference after the display wakes.
 * Preserve the map angle for that rare large change until another source proves it is real.
 */
internal class NavigateRotationSettleGate {
    private var wakeSessionId = 0L
    private var wakeSessionActive = false
    private var wakeSessionStartedAtElapsedMs = Long.MIN_VALUE
    private var heldHeadingDeg = 0f
    private var previousRelativeHeadingDeg: Float? = null
    private var settled = false
    private var fallbackRequested = false
    private var lastHoldReason: String? = null

    fun beginWakeSession(
        nowElapsedMs: Long,
        heldHeadingDeg: Float,
        previousRelativeHeadingDeg: Float?,
    ) {
        wakeSessionId += 1L
        wakeSessionActive = true
        wakeSessionStartedAtElapsedMs = nowElapsedMs
        this.heldHeadingDeg = normalize360(heldHeadingDeg)
        this.previousRelativeHeadingDeg = previousRelativeHeadingDeg?.takeIf(Float::isFinite)
        settled = false
        fallbackRequested = false
        lastHoldReason = null
        log("rotation_settle stage=start id=$wakeSessionId heldHeading=${this.heldHeadingDeg.formatTelemetry(1)}")
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
        nowElapsedMs: Long,
        renderState: CompassRenderState,
        compassHeadingDeg: Float,
        headingSampleElapsedRealtimeMs: Long?,
        relativeHeadingDeg: Float?,
        gpsFixFresh: Boolean,
        gpsFixSpeedMps: Float,
        gpsFixBearingDeg: Float?,
        onSuspectGoogleFusedHeading: () -> Unit,
    ): NavigationRotationTarget? {
        if (!shouldDriveCompassFollowMap(renderState) || !compassHeadingDeg.isFinite()) {
            hold("await_usable_heading")
            return null
        }
        val heading = normalize360(compassHeadingDeg)
        if (!wakeSessionActive || settled) {
            return NavigationRotationTarget(heading, NavigationRotationTargetSource.COMPASS)
        }
        if (renderState.providerType != CompassProviderType.GOOGLE_FUSED) {
            settled = true
            unlock("sensor_fallback", heading)
            return NavigationRotationTarget(heading, NavigationRotationTargetSource.SENSOR_FALLBACK)
        }
        if (headingSampleElapsedRealtimeMs == null || headingSampleElapsedRealtimeMs <= wakeSessionStartedAtElapsedMs) {
            hold("await_fresh_session_sample")
            return null
        }
        val gpsBearingDeg =
            gpsFixBearingDeg?.takeIf {
                gpsFixFresh &&
                    gpsFixSpeedMps.isFinite() &&
                    gpsFixSpeedMps >= ROTATION_SETTLE_MOVING_SPEED_MPS &&
                    it.isFinite()
            }
        if (gpsBearingDeg != null) {
            val headingFromGps = normalize360(gpsBearingDeg)
            settled = true
            unlock("gps_bearing", headingFromGps)
            return NavigationRotationTarget(headingFromGps, NavigationRotationTargetSource.GPS_BEARING)
        }
        val headingDeltaDeg = abs(angleDeltaDeg(heading, heldHeadingDeg))
        if (headingDeltaDeg < ROTATION_SETTLE_LARGE_WAKE_CHANGE_DEG) {
            settled = true
            unlock("small_wake_delta", heading)
            return NavigationRotationTarget(heading, NavigationRotationTargetSource.COMPASS)
        }
        if (renderState.trackingState != CompassTrackingState.TRACKING) {
            hold("await_stable_fused", headingDeltaDeg)
            requestFallbackIfNeeded(nowElapsedMs, onSuspectGoogleFusedHeading)
            return null
        }
        if (hasMatchingRelativeTurn(heading, relativeHeadingDeg)) {
            settled = true
            unlock("relative_turn_confirmed", heading)
            return NavigationRotationTarget(heading, NavigationRotationTargetSource.COMPASS)
        }
        hold("large_unverified_wake_change", headingDeltaDeg)
        requestFallbackIfNeeded(nowElapsedMs, onSuspectGoogleFusedHeading)
        return null
    }

    private fun hasMatchingRelativeTurn(
        headingDeg: Float,
        currentRelativeHeadingDeg: Float?,
    ): Boolean {
        val previousRelative = previousRelativeHeadingDeg ?: return false
        val currentRelative = currentRelativeHeadingDeg?.takeIf(Float::isFinite) ?: return false
        val absoluteDeltaDeg = angleDeltaDeg(headingDeg, heldHeadingDeg)
        val relativeDeltaDeg = angleDeltaDeg(currentRelative, previousRelative)
        return abs(relativeDeltaDeg) >= ROTATION_SETTLE_RELATIVE_TURN_MIN_DEG &&
            abs(angleDeltaDeg(absoluteDeltaDeg, relativeDeltaDeg)) <= ROTATION_SETTLE_RELATIVE_TURN_MATCH_DEG
    }

    private fun requestFallbackIfNeeded(
        nowElapsedMs: Long,
        onSuspectGoogleFusedHeading: () -> Unit,
    ) {
        if (
            fallbackRequested ||
            nowElapsedMs - wakeSessionStartedAtElapsedMs < ROTATION_SETTLE_FALLBACK_DELAY_MS
        ) {
            return
        }
        fallbackRequested = true
        log("rotation_settle stage=fallback id=$wakeSessionId reason=large_unverified_wake_change")
        onSuspectGoogleFusedHeading()
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

    private fun unlock(
        reason: String,
        headingDeg: Float,
    ) {
        lastHoldReason = null
        log(
            "rotation_settle stage=unlock id=$wakeSessionId reason=$reason " +
                "heading=${headingDeg.formatTelemetry(1)}",
        )
    }

    private fun log(message: String) {
        if (DebugTelemetry.isEnabled()) DebugTelemetry.log(COMPASS_TELEMETRY_TAG, message)
    }
}

internal data class NavigationRotationTarget(
    val headingDeg: Float,
    val source: NavigationRotationTargetSource,
)

internal enum class NavigationRotationTargetSource {
    COMPASS,
    GPS_BEARING,
    SENSOR_FALLBACK,
}

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
): Float {
    if (!diffDeg.isFinite() || !frameDeltaMs.isFinite() || frameDeltaMs <= 0f) return 0f
    val timeConstantMs =
        when {
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
): Float {
    if (!diffDeg.isFinite()) return 0f
    val animatedDelta =
        diffDeg *
            resolveHeadingAnimationAlpha(
                diffDeg = diffDeg,
                activeTurn = activeTurn,
                frameDeltaMs = frameDeltaMs,
            )
    // A delayed frame must not turn a transient provider jump into a visible snap.
    val maximumStepDeg = HEADING_ANIMATION_MAX_STEP_DEG
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
private const val ACTIVE_TURN_LARGE_ERROR_DEG = 25f
private const val HEADING_ANIMATION_NOMINAL_FRAME_DELTA_MS = 16.666_667f
private const val HEADING_ANIMATION_MIN_FRAME_DELTA_MS = 4f
private const val HEADING_ANIMATION_MAX_FRAME_DELTA_MS = 50f
private const val HEADING_ANIMATION_MAX_STEP_DEG = 10f
private const val NANOS_PER_MILLISECOND = 1_000_000.0
private const val ROTATION_SETTLE_LARGE_WAKE_CHANGE_DEG = 45f
private const val ROTATION_SETTLE_MOVING_SPEED_MPS = 1.2f
private const val ROTATION_SETTLE_RELATIVE_TURN_MIN_DEG = 8f
private const val ROTATION_SETTLE_RELATIVE_TURN_MATCH_DEG = 18f
private const val ROTATION_SETTLE_FALLBACK_DELAY_MS = 1_200L

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
