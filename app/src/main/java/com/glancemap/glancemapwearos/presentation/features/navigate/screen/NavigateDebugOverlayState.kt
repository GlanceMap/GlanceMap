package com.glancemap.glancemapwearos.presentation.features.navigate

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.core.service.location.model.LocationScreenState
import com.glancemap.glancemapwearos.domain.sensors.COMPASS_TELEMETRY_TAG
import com.glancemap.glancemapwearos.domain.sensors.CompassMagneticQuality
import com.glancemap.glancemapwearos.domain.sensors.CompassRenderState
import com.glancemap.glancemapwearos.domain.sensors.CompassTrackingState
import com.glancemap.glancemapwearos.domain.sensors.shortestAngleDiffDeg
import com.glancemap.glancemapwearos.presentation.features.navigate.motion.MarkerMotionTelemetry
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

@Composable
internal fun rememberMarkerMotionDebugOverlayLabel(
    gpsDebugTelemetry: Boolean,
    gpsDebugTelemetryPopupEnabled: Boolean,
    offlineMode: Boolean,
    renderState: CompassRenderState,
    renderedHeadingDeg: Float,
): String? {
    var markerMotionDebugOverlayLabel by remember { mutableStateOf<String?>(null) }
    val latestRenderState by rememberUpdatedState(renderState)
    val latestRenderedHeadingDeg by rememberUpdatedState(renderedHeadingDeg)
    LaunchedEffect(gpsDebugTelemetry, gpsDebugTelemetryPopupEnabled, offlineMode) {
        if (!gpsDebugTelemetry || !gpsDebugTelemetryPopupEnabled || offlineMode) {
            markerMotionDebugOverlayLabel = null
            return@LaunchedEffect
        }

        while (isActive) {
            markerMotionDebugOverlayLabel =
                MarkerMotionTelemetry.latestSnapshot().overlayLabel() +
                    "\n" +
                    compassIntegrityDebugOverlayLabel(
                        renderState = latestRenderState,
                        renderedHeadingDeg = latestRenderedHeadingDeg,
                    )
            delay(250L)
        }
    }
    return markerMotionDebugOverlayLabel
}

internal fun compassIntegrityDebugOverlayLabel(
    renderState: CompassRenderState,
    renderedHeadingDeg: Float,
): String {
    val display = compassDisplayedHeadingDebugState(renderState, renderedHeadingDeg)
    return "I:${renderState.trackingState.name}(${renderState.trackingReason.telemetryToken})\n" +
        "F:${renderState.magneticFieldUt.formatDebugOrNa(1)}µT M:${renderState.magneticQuality.telemetryToken} " +
        "Q:${if (renderState.quarantineActive) "yes" else "no"}\n" +
        "D:$display B:${renderState.northBasis.telemetryToken}"
}

internal fun compassDisplayedHeadingDebugState(
    renderState: CompassRenderState,
    renderedHeadingDeg: Float,
): String {
    val visiblyHeldAnchor =
        renderState.trackingState == CompassTrackingState.DEGRADED &&
            renderState.magneticQuality == CompassMagneticQuality.INTERFERENCE &&
            kotlin.math.abs(shortestAngleDiffDeg(renderState.headingDeg, renderedHeadingDeg)) >=
                HELD_ANCHOR_DISPLAY_DELTA_DEG
    return when {
        !renderState.headingRenderable || renderState.headingSampleStale || visiblyHeldAnchor ->
            "held/frozen anchor"
        renderState.headingTrusted -> "live trusted"
        else -> "degraded/live"
    }
}

internal fun reportCompassIssueNow(
    renderState: CompassRenderState,
    renderedHeadingDeg: Float,
    renderedMapRotationDeg: Float,
    screenState: LocationScreenState,
) {
    if (!DebugTelemetry.isEnabled()) return
    val nowElapsedMs = SystemClock.elapsedRealtime()
    val sampleAgeMs =
        renderState.headingSampleElapsedRealtimeMs
            ?.let { (nowElapsedMs - it).coerceAtLeast(0L) }
    DebugTelemetry.log(
        COMPASS_TELEMETRY_TAG,
        "user_report heading_looks_wrong " +
            "provider=${renderState.providerType.name} source=${renderState.headingSource.telemetryToken} " +
            "sourceReady=${
                renderState.headingSampleElapsedRealtimeMs != null &&
                    !renderState.headingSampleStale
            } screenState=${screenState.name} " +
            "heading=${renderState.headingDeg.formatDebug(1)} " +
            "rendered=${renderedHeadingDeg.formatDebug(1)} " +
            "mapRotation=${renderedMapRotationDeg.formatDebug(1)} " +
            "renderDelta=${compassDebugDeltaDeg(renderState.headingDeg, renderedHeadingDeg).formatDebug(1)} " +
            "headingError=${renderState.headingErrorDeg.formatDebugOrNa(1)} " +
            "conservativeError=${renderState.conservativeHeadingErrorDeg.formatDebugOrNa(1)} " +
            "sampleAgeMs=${sampleAgeMs ?: "na"} stale=${renderState.headingSampleStale} " +
            "trackingState=${renderState.trackingState.telemetryToken} " +
            "trackingReason=${renderState.trackingReason.telemetryToken} " +
            "renderable=${renderState.headingRenderable} trusted=${renderState.headingTrusted} " +
            "northBasis=${renderState.northBasis.telemetryToken} " +
            "magneticQuality=${renderState.magneticQuality.telemetryToken} " +
            "magneticInterference=${renderState.magneticInterference} " +
            "magneticFieldUt=${renderState.magneticFieldUt.formatDebugOrNa(1)} " +
            "quarantine=${renderState.quarantineActive} " +
            "display=${compassDisplayedHeadingDebugState(renderState, renderedHeadingDeg)}",
    )
}

private fun compassDebugDeltaDeg(
    firstDeg: Float,
    secondDeg: Float,
): Float {
    val normalized = ((firstDeg - secondDeg + 540f) % 360f) - 180f
    return kotlin.math.abs(normalized)
}

private fun Float.formatDebug(decimals: Int): String = "%.${decimals}f".format(Locale.US, this)

private fun Float?.formatDebugOrNa(decimals: Int): String = this?.takeIf(Float::isFinite)?.formatDebug(decimals) ?: "na"

private const val HELD_ANCHOR_DISPLAY_DELTA_DEG = 15f
