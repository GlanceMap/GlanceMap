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
import com.glancemap.glancemapwearos.domain.sensors.CompassMagneticQuality
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
import java.util.Locale
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
    gpsFixFresh: Boolean,
    gpsFixSpeedMps: Float,
    gpsFixBearingDeg: Float?,
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

    val displayedHeading =
        remember(mv) { mutableFloatStateOf(normalize360(-mv.mapRotation.degrees)) }
    val displayedMapRot = remember(mv) { mutableFloatStateOf(mv.mapRotation.degrees) }
    val frozenRotationDeg = remember { mutableFloatStateOf(0f) }
    val rotationSettleGate = remember(mv) { NavigateRotationSettleGate() }
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

    LaunchedEffect(
        navMode,
        mv,
        forceNorthUpInPanning,
        navigationMarkerAnchorMode,
    ) {
        val renderStateNow = renderStateFlow.value
        val headingNow = normalize360(renderStateNow.headingDeg)
        val shouldDriveHeadingNow = shouldDriveHeadingForNavMode(navMode, renderStateNow)
        val shouldSeedCachedHeading =
            when (navMode) {
                NavMode.NORTH_UP_FOLLOW ->
                    shouldSeedNorthUpMarkerWithCachedHeading(
                        renderState = renderStateNow,
                        nowElapsedMs = SystemClock.elapsedRealtime(),
                    )
                else -> false
            }
        if (
            navMode == NavMode.NORTH_UP_FOLLOW &&
            (shouldDriveHeadingNow || shouldSeedCachedHeading)
        ) {
            displayedHeading.floatValue = headingNow
        }

        when (navMode) {
            NavMode.COMPASS_FOLLOW -> {
                // Preserve the exact opening rotation until a healthy compass heading has
                // proved stable. In particular, never seed a newly-created MapView directly
                // from a heading that may still be degraded or quarantined.
                val heldMapRotation = syncDisplayedMapRotationFromMap()
                val heldHeading = normalize360(-heldMapRotation)
                displayedHeading.floatValue = heldHeading
                rotationSettleGate.reset()
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
        var liveCompassTarget = displayedHeading.floatValue
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

        // Keep the live compass target current without blocking the animation loop.
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
                liveCompassTarget = heading
                CompassRenderPerfTelemetry.recordTargetUpdate(navMode)
            }
        }

        // Animate toward the currently permitted heading target on every display frame.
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
                                compassHeadingDeg = liveCompassTarget,
                                gpsFixFresh = latestGpsFixFresh.value,
                                gpsFixSpeedMps = latestGpsFixSpeedMps.value,
                                gpsFixBearingDeg = latestGpsFixBearingDeg.value,
                            )

                        NavMode.NORTH_UP_FOLLOW ->
                            if (shouldDriveHeadingForNavMode(navMode, latestRenderState)) {
                                NavigationRotationTarget(
                                    headingDeg = liveCompassTarget,
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
 * Holds the map's opening angle until the compass pipeline has left its short startup or
 * interference recovery period. A clearly moving, fresh GPS bearing is a safe early release
 * because its direction comes from successive positions rather than the disturbed compass.
 */
internal class NavigateRotationSettleGate {
    private var stableSinceElapsedMs: Long? = null
    private var settled = false
    private var gpsBearingReleaseHeadingDeg: Float? = null

    fun reset() {
        stableSinceElapsedMs = null
        settled = false
        gpsBearingReleaseHeadingDeg = null
    }

    fun resolve(
        nowElapsedMs: Long,
        renderState: CompassRenderState,
        compassHeadingDeg: Float,
        gpsFixFresh: Boolean,
        gpsFixSpeedMps: Float,
        gpsFixBearingDeg: Float?,
    ): NavigationRotationTarget? {
        val stableCompass = isCompassHeadingStableForNavigateOpening(renderState)
        if (settled) {
            if (stableCompass && compassHeadingDeg.isFinite()) {
                return NavigationRotationTarget(
                    headingDeg = normalize360(compassHeadingDeg),
                    source = NavigationRotationTargetSource.COMPASS,
                )
            }
            return gpsBearingReleaseHeadingDeg?.let {
                NavigationRotationTarget(
                    headingDeg = it,
                    source = NavigationRotationTargetSource.GPS_BEARING,
                )
            }
        }

        val gpsBearing =
            gpsFixBearingDeg?.takeIf {
                gpsFixFresh &&
                    gpsFixSpeedMps.isFinite() &&
                    gpsFixSpeedMps >= ROTATION_SETTLE_MOVING_SPEED_MPS &&
                    it.isFinite()
            }
        if (gpsBearing != null) {
            val normalizedGpsBearing = normalize360(gpsBearing)
            settled = true
            gpsBearingReleaseHeadingDeg = normalizedGpsBearing
            return NavigationRotationTarget(
                headingDeg = normalizedGpsBearing,
                source = NavigationRotationTargetSource.GPS_BEARING,
            )
        }

        if (!stableCompass || !compassHeadingDeg.isFinite()) {
            stableSinceElapsedMs = null
            return null
        }
        val stableSince = stableSinceElapsedMs ?: nowElapsedMs.also { stableSinceElapsedMs = it }
        if (nowElapsedMs - stableSince < ROTATION_SETTLE_STABLE_WINDOW_MS) return null

        settled = true
        return NavigationRotationTarget(
            headingDeg = normalize360(compassHeadingDeg),
            source = NavigationRotationTargetSource.COMPASS,
        )
    }
}

internal data class NavigationRotationTarget(
    val headingDeg: Float,
    val source: NavigationRotationTargetSource,
)

internal enum class NavigationRotationTargetSource {
    COMPASS,
    GPS_BEARING,
}

internal fun isCompassHeadingStableForNavigateOpening(renderState: CompassRenderState): Boolean =
    shouldDriveCompassFollowMap(renderState) &&
        renderState.trackingState == CompassTrackingState.TRACKING &&
        !renderState.magneticInterference &&
        renderState.magneticQuality != CompassMagneticQuality.INTERFERENCE &&
        renderState.magneticQuality != CompassMagneticQuality.RECOVERING &&
        renderState.magneticQuality != CompassMagneticQuality.UNAVAILABLE

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
    val maximumStepDeg =
        (
            HEADING_ANIMATION_MAX_STEP_DEG *
                frameDeltaMs.coerceIn(
                    minimumValue = HEADING_ANIMATION_MIN_FRAME_DELTA_MS,
                    maximumValue = HEADING_ANIMATION_MAX_FRAME_DELTA_MS,
                ) /
                HEADING_ANIMATION_NOMINAL_FRAME_DELTA_MS
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
private const val ACTIVE_TURN_LARGE_ERROR_DEG = 25f
private const val HEADING_ANIMATION_NOMINAL_FRAME_DELTA_MS = 16.666_667f
private const val HEADING_ANIMATION_MIN_FRAME_DELTA_MS = 4f
private const val HEADING_ANIMATION_MAX_FRAME_DELTA_MS = 50f
private const val HEADING_ANIMATION_MAX_STEP_DEG = 10f
private const val NANOS_PER_MILLISECOND = 1_000_000.0
private const val ROTATION_SETTLE_STABLE_WINDOW_MS = 600L
private const val ROTATION_SETTLE_MOVING_SPEED_MPS = 1.2f

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
