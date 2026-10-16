package com.glancemap.glancemapcompanionapp.map

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import org.maplibre.android.location.LocationComponentConstants
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.sources.GeoJsonSource

/** Poll SDK state on the UI thread only while the map is resumed and an explicit capture is active. */
@Composable
internal fun phoneMapOrientationTelemetry(
    renderer: Any?,
    readSample: () -> PhoneMapOrientationRenderSample?,
) {
    val currentReadSample by rememberUpdatedState(readSample)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(renderer, lifecycleOwner) {
        if (renderer == null) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            PhoneDebugCapture.state
                .map { state -> state.sessionId.takeIf { state.active } }
                .distinctUntilChanged()
                .collectLatest { sessionId ->
                    if (sessionId == null) return@collectLatest
                    val diagnostics = PhoneMapOrientationDiagnostics()
                    while (isActive) {
                        currentReadSample()?.let { render ->
                            diagnostics.record(
                                sessionId,
                                SystemClock.elapsedRealtime(),
                                PhoneCompassDiagnostics.orientationSample(sessionId),
                                render,
                            )
                        }
                        delay(250L)
                    }
                }
        }
    }
}

internal fun MapLibreMap.orientationDiagnosticSample(
    renderer: String,
    mapMode: PhoneMapMode,
    requestedMapDegrees: Float,
    markerExpected: Boolean,
    cameraControl: String,
): PhoneMapOrientationRenderSample {
    val bearing = runCatching { cameraPosition.bearing.toFloat() }.getOrNull()
    val marker =
        runCatching {
            val component = locationComponent
            when {
                !markerExpected -> PhoneMapOrientationMarker(observedVia = "not_owner_or_no_permission")
                !component.isLocationComponentActivated || !component.isLocationComponentEnabled ->
                    PhoneMapOrientationMarker(observedVia = "disabled")
                component.renderMode != RenderMode.COMPASS -> PhoneMapOrientationMarker(observedVia = "no_compass")
                else -> {
                    // MapLibre 13.6.1 animates this numeric source property; never serialize feature geometry.
                    val worldBearing =
                        style
                            ?.takeIf { it.isFullyLoaded }
                            ?.getSourceAs<GeoJsonSource>(LocationComponentConstants.LOCATION_SOURCE)
                            ?.querySourceFeatures(null)
                            ?.firstOrNull()
                            ?.getNumberProperty("mapbox-property-compass-bearing")
                            ?.toFloat()
                    PhoneMapOrientationMarker(
                        screenDegrees =
                            if (worldBearing?.isFinite() == true && bearing != null) {
                                normalizePhoneHeadingDegrees(worldBearing - bearing)
                            } else {
                                null
                            },
                        observedVia = if (worldBearing == null) "source_unavailable" else "maplibre_source",
                        animationEnabled = component.getLocationComponentOptions().compassAnimationEnabled(),
                    )
                }
            }
        }.getOrElse { PhoneMapOrientationMarker(observedVia = "read_failed") }
    return PhoneMapOrientationRenderSample(renderer, mapMode, requestedMapDegrees, bearing, marker, cameraControl)
}
