package com.glancemap.glancemapwearos.presentation.features.navigate

import android.location.Location
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.core.service.diagnostics.MapHotPathDiagnostics
import com.glancemap.glancemapwearos.core.service.location.model.GpsSignalSnapshot
import com.glancemap.glancemapwearos.core.service.location.model.deliveredSourceModeOrNull
import com.glancemap.glancemapwearos.core.service.location.policy.LocationSourceMode
import com.glancemap.glancemapwearos.presentation.features.gpx.GpxTrackDetails
import com.glancemap.glancemapwearos.presentation.features.maps.MapViewModel
import com.glancemap.glancemapwearos.presentation.features.navigate.effects.WakeAnchorSeed
import com.glancemap.glancemapwearos.presentation.features.navigate.effects.resolveWakeAnchorSeedOrNull
import com.glancemap.glancemapwearos.presentation.features.offline.OfflineStartCenteringEffect
import com.glancemap.glancemapwearos.presentation.features.poi.PoiNavigateTarget
import kotlinx.coroutines.delay
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.view.MapView

@Composable
@Suppress("FunctionNaming", "LongParameterList")
internal fun NavigateStartupCenteringEffects(
    offlineMode: Boolean,
    shouldTrackLocation: Boolean,
    shouldFollowPosition: Boolean,
    startupLocation: Location?,
    gpsSignalSnapshot: GpsSignalSnapshot,
    locationMarkerLatLong: LatLong?,
    lastKnownLocation: LatLong?,
    retainedLocationAnchor: RetainedLocationAnchor?,
    startupMapFallbackState: StartupMapFallbackState,
    onStartupMapFallbackEvent: (StartupMapFallbackEvent) -> Unit,
    navigateTarget: PoiNavigateTarget?,
    pendingPoiFocusTarget: PoiNavigateTarget?,
    mapView: MapView,
    mapViewModel: MapViewModel,
    selectedMapPath: String?,
    activeGpxDetails: List<GpxTrackDetails>,
): LatLong? {
    val startupInput =
        OnlineStartupMapFallbackInput(
            offlineMode = offlineMode,
            shouldTrackLocation = shouldTrackLocation,
            locationMarkerLatLong = locationMarkerLatLong,
            retainedLocationAnchor = retainedLocationAnchor,
            lastKnownLocation = lastKnownLocation,
            startupMapFallbackState = startupMapFallbackState,
            navigateTarget = navigateTarget,
            pendingPoiFocusTarget = pendingPoiFocusTarget,
            shouldFollowPosition = shouldFollowPosition,
        )
    navigateStartupMapPreviewEffect(
        startupInput = startupInput,
        location = startupLocation,
        gpsSignal = gpsSignalSnapshot,
        mapView = mapView,
        onPreviewApplied = {
            onStartupMapFallbackEvent(StartupMapFallbackEvent.CANCELLED)
            mapViewModel.recordStartupMapPreview()
        },
    )
    val gpsStartupMapCenteringPending =
        shouldWaitForOnlineStartupMapFallback(startupInput)
    LaunchedEffect(gpsStartupMapCenteringPending) {
        if (!gpsStartupMapCenteringPending) return@LaunchedEffect
        delay(NORMAL_STARTUP_MAP_FALLBACK_GRACE_MS)
        onStartupMapFallbackEvent(StartupMapFallbackEvent.TIMER_EXPIRED)
    }
    val gpsStartupMapCenteringActive =
        shouldStartOnlineStartupMapCentering(startupInput)

    LaunchedEffect(navigateTarget, pendingPoiFocusTarget) {
        if (navigateTarget != null || pendingPoiFocusTarget != null) {
            onStartupMapFallbackEvent(StartupMapFallbackEvent.CANCELLED)
        }
    }

    OfflineStartCenteringEffect(
        isOfflineMode = offlineMode,
        mapView = mapView,
        mapViewModel = mapViewModel,
        selectedMapPath = selectedMapPath,
        activeGpxDetails = activeGpxDetails,
        skipInitialCentering = navigateTarget != null || pendingPoiFocusTarget != null,
        enabled = offlineMode || gpsStartupMapCenteringActive,
        onInitialCenteringApplied =
            if (!offlineMode && gpsStartupMapCenteringActive) {
                { onStartupMapFallbackEvent(StartupMapFallbackEvent.CENTERING_APPLIED) }
            } else {
                null
            },
        deferWhenNoCenter = !offlineMode && gpsStartupMapCenteringActive,
    )

    return if (offlineMode) {
        null
    } else {
        locationMarkerLatLong ?: retainedLocationAnchor?.latLong ?: lastKnownLocation
    }
}

@Composable
private fun navigateStartupMapPreviewEffect(
    startupInput: OnlineStartupMapFallbackInput,
    location: Location?,
    gpsSignal: GpsSignalSnapshot,
    mapView: MapView,
    onPreviewApplied: () -> Unit,
) {
    LaunchedEffect(startupInput, location, gpsSignal, mapView) {
        if (!startupInput.shouldFollowPosition || !shouldWaitForOnlineStartupMapFallback(startupInput)) {
            return@LaunchedEffect
        }
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val fix =
            resolveWakeAnchorSeedOrNull(
                location = location,
                receivedAtElapsedMs = nowElapsedMs,
                nowWallClockMs = System.currentTimeMillis(),
                maxAgeMs = gpsSignal.lastFixFreshMaxAgeMs,
                maxAccuracyM = Float.MAX_VALUE,
            ) ?: return@LaunchedEffect
        val sourceMode = location?.deliveredSourceModeOrNull()
        val center =
            resolveOnlineStartupMapPreview(
                startupInput = startupInput,
                fix = fix,
                sourceMode = sourceMode,
                gpsSignal = gpsSignal,
                nowElapsedMs = nowElapsedMs,
            ) ?: return@LaunchedEffect
        // Preview only the camera; this fix must not become a trusted marker or guidance anchor.
        mapView.setCenter(center)
        onPreviewApplied()
        if (DebugTelemetry.isFullDiagnosticsCaptureEnabled()) {
            MapHotPathDiagnostics.recordEvent(
                stage = "navigate.startupMapPreview",
                status = "centered",
                detail =
                    "fixAgeMs=${nowElapsedMs - fix.fixElapsedMs} " +
                        "accuracyM=${fix.accuracyM} source=${sourceMode?.telemetryValue} " +
                        "zoom=${mapView.model.mapViewPosition.zoomLevel}",
            )
        }
    }
}

internal fun resolveOnlineStartupMapPreview(
    startupInput: OnlineStartupMapFallbackInput,
    fix: WakeAnchorSeed?,
    sourceMode: LocationSourceMode?,
    gpsSignal: GpsSignalSnapshot,
    nowElapsedMs: Long,
): LatLong? {
    val sourceReady =
        !gpsSignal.requiresFreshLiveFixAfterSourceChange &&
            !hasHardLocationEnvironmentRestriction(gpsSignal.environmentWarning) &&
            sourceMode != null &&
            sourceMode.telemetryValue == gpsSignal.activeSourceModeValue
    val canPreview =
        startupInput.shouldFollowPosition &&
            shouldWaitForOnlineStartupMapFallback(startupInput) &&
            gpsSignal.lastFixFresh &&
            sourceReady
    if (!canPreview || fix == null) return null
    val validTimestamp = fix.fixElapsedMs > 0L && fix.fixElapsedMs <= nowElapsedMs
    val ageMs = nowElapsedMs - fix.fixElapsedMs
    val fresh = gpsSignal.lastFixFreshMaxAgeMs > 0L && ageMs in 0L..gpsSignal.lastFixFreshMaxAgeMs
    val validAccuracy = fix.accuracyM.isFinite() && fix.accuracyM >= 0f
    return fix.latLong.takeIf { validTimestamp && fresh && validAccuracy }
}

private const val NORMAL_STARTUP_MAP_FALLBACK_GRACE_MS = 15_000L

internal data class OnlineStartupMapFallbackInput(
    val offlineMode: Boolean,
    val shouldTrackLocation: Boolean,
    val locationMarkerLatLong: LatLong?,
    val retainedLocationAnchor: RetainedLocationAnchor?,
    val lastKnownLocation: LatLong?,
    val startupMapFallbackState: StartupMapFallbackState,
    val navigateTarget: PoiNavigateTarget?,
    val pendingPoiFocusTarget: PoiNavigateTarget?,
    val shouldFollowPosition: Boolean = true,
)

internal fun shouldWaitForOnlineStartupMapFallback(
    input: OnlineStartupMapFallbackInput,
): Boolean =
    !input.offlineMode &&
        input.shouldTrackLocation &&
        input.startupMapFallbackState == StartupMapFallbackState.WAITING &&
        input.locationMarkerLatLong == null &&
        input.retainedLocationAnchor == null &&
        input.lastKnownLocation == null &&
        input.navigateTarget == null &&
        input.pendingPoiFocusTarget == null

internal fun shouldStartOnlineStartupMapCentering(
    input: OnlineStartupMapFallbackInput,
): Boolean =
    !input.offlineMode &&
        input.shouldTrackLocation &&
        input.startupMapFallbackState == StartupMapFallbackState.READY &&
        input.locationMarkerLatLong == null &&
        input.retainedLocationAnchor == null &&
        input.lastKnownLocation == null &&
        input.navigateTarget == null &&
        input.pendingPoiFocusTarget == null
