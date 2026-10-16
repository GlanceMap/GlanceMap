package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import kotlin.math.roundToInt

internal data class PhoneCompassAttitude(
    val displayRotationDegrees: Int,
    val pitchDegrees: Float? = null,
    val rollDegrees: Float? = null,
)

internal data class PhoneCompassOrientationSample(
    val sampleElapsedMs: Long?,
    val rawHeadingDegrees: Float?,
    val northAdjustedHeadingDegrees: Float?,
    val state: PhoneCompassState,
    val attitude: PhoneCompassAttitude,
    val northReference: PhoneMapNorthReferenceMode,
)

/** Source/draw observations, rather than the requested marker heading or a claim about displayed pixels. */
internal data class PhoneMapOrientationMarker(
    val screenDegrees: Float? = null,
    val observedVia: String = "unavailable",
    val animationEnabled: Boolean? = null,
    val sampleElapsedMs: Long? = null,
)

internal data class PhoneMapOrientationRenderSample(
    val renderer: String,
    val mapMode: PhoneMapMode,
    val requestedMapDegrees: Float?,
    val actualMapDegrees: Float?,
    val marker: PhoneMapOrientationMarker,
    val cameraControl: String,
)

/** Per mounted renderer: at most two lines/second while changing, one every five seconds while quiet. */
internal class PhoneMapOrientationDiagnostics {
    private var sessionId: Long? = null
    private var lastLoggedAtMs: Long? = null
    private var lastSignature: String? = null

    fun record(
        captureSessionId: Long,
        nowMs: Long,
        compass: PhoneCompassOrientationSample?,
        render: PhoneMapOrientationRenderSample,
    ) {
        if (PhoneDebugCapture.activeSessionId() != captureSessionId) return
        if (sessionId != captureSessionId) {
            sessionId = captureSessionId
            lastLoggedAtMs = null
            lastSignature = null
        }
        val elapsed = lastLoggedAtMs?.let { nowMs - it }
        if (elapsed != null && elapsed < 500L) return
        val signature = orientationFields(compass, render)
        if (signature == lastSignature && elapsed != null && elapsed < 5_000L) return
        val message =
            "event=map_orientation $signature " +
                "sensorAgeMs=${age(nowMs, compass?.sampleElapsedMs)} " +
                "markerAgeMs=${age(nowMs, render.marker.sampleElapsedMs)}"
        if (PhoneDebugCapture.log("PhoneMapOrientation", message, expectedSessionId = captureSessionId)) {
            lastLoggedAtMs = nowMs
            lastSignature = signature
        }
    }
}

private fun orientationFields(
    compass: PhoneCompassOrientationSample?,
    render: PhoneMapOrientationRenderSample,
): String {
    val heading = compass?.state?.headingDegrees
    val expectedScreen = angularDifference(heading, render.actualMapDegrees)
    return buildString {
        append("renderer=${render.renderer} control=${render.cameraControl}")
        append(" follow=${render.mapMode.follow} orientation=${render.mapMode.orientation}")
        append(" manual=${angle(render.mapMode.manualBearingDegrees)}")
        append(" pipeline=${compass?.state?.pipeline ?: "unknown"}")
        append(" accuracy=${compass?.state?.accuracy ?: "unknown"}")
        append(" renderable=${compass?.state?.isRenderable ?: false}")
        append(" calibration=${compass?.state?.calibrationRecommended ?: false}")
        append(" interference=${compass?.state?.magneticInterference ?: false}")
        append(" north=${compass?.northReference ?: "unknown"}")
        append(" raw=${angle(compass?.rawHeadingDegrees)} adjusted=${angle(compass?.northAdjustedHeadingDegrees)}")
        append(" smoothed=${angle(heading)}")
        append(" displayRotation=${compass?.attitude?.displayRotationDegrees ?: "unknown"}")
        append(" pitch=${angle(compass?.attitude?.pitchDegrees)} roll=${angle(compass?.attitude?.rollDegrees)}")
        append(" mapRequested=${angle(render.requestedMapDegrees)} mapActual=${angle(render.actualMapDegrees)}")
        append(" mapError=${angle(angularDifference(render.requestedMapDegrees, render.actualMapDegrees))}")
        append(" markerExpectedScreen=${angle(expectedScreen)} markerObservedScreen=${angle(render.marker.screenDegrees)}")
        append(" markerError=${angle(angularDifference(expectedScreen, render.marker.screenDegrees))}")
        append(" markerObservation=${render.marker.observedVia} markerAnimation=${render.marker.animationEnabled ?: "unknown"}")
    }
}

private fun angularDifference(
    target: Float?,
    actual: Float?,
): Float? =
    if (target?.isFinite() == true && actual?.isFinite() == true) {
        shortestPhoneHeadingDelta(normalizePhoneHeadingDegrees(target), normalizePhoneHeadingDegrees(actual))
    } else {
        null
    }

private fun angle(value: Float?): String = value?.takeIf(Float::isFinite)?.roundToInt()?.toString() ?: "unknown"

private fun age(
    nowMs: Long,
    sampleMs: Long?,
): String = sampleMs?.let { (nowMs - it).coerceAtLeast(0L).toString() } ?: "unknown"
