package com.glancemap.glancemapwearos.domain.sensors

import android.hardware.SensorManager

data class CompassRenderState(
    val providerType: CompassProviderType,
    val headingDeg: Float,
    val accuracy: Int,
    val headingErrorDeg: Float? = null,
    val conservativeHeadingErrorDeg: Float? = null,
    val headingSampleElapsedRealtimeMs: Long? = null,
    val headingSampleStale: Boolean = false,
    val headingSource: HeadingSource,
    val headingSourceStatus: HeadingSourceStatus,
    val northReferenceStatus: NorthReferenceStatus,
    val magneticInterference: Boolean,
    val trackingState: CompassTrackingState = CompassTrackingState.ACQUIRING,
    val trackingReason: CompassTrackingReason = CompassTrackingReason.STARTUP,
    val headingRenderable: Boolean = false,
    val headingTrusted: Boolean = false,
    val northBasis: CompassNorthBasis = CompassNorthBasis.UNKNOWN,
    val magneticQuality: CompassMagneticQuality = CompassMagneticQuality.UNKNOWN,
    /**
     * Relative game-rotation heading used only to validate a compass turn. It has no north
     * reference and must never be rendered as the map heading.
     */
    val relativeHeadingDeg: Float? = null,
)

internal fun initialCompassRenderState(
    providerType: CompassProviderType,
    headingSensorAvailable: Boolean = false,
    rotationVectorAvailable: Boolean = false,
    magAccelFallbackAvailable: Boolean = false,
): CompassRenderState =
    CompassRenderState(
        providerType = providerType,
        headingDeg = 0f,
        accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE,
        headingErrorDeg = null,
        conservativeHeadingErrorDeg = null,
        headingSampleElapsedRealtimeMs = null,
        headingSampleStale = false,
        headingSource = HeadingSource.NONE,
        headingSourceStatus =
            HeadingSourceStatus(
                requestedMode = CompassHeadingSourceMode.AUTO,
                activeSource = HeadingSource.NONE,
                headingSensorAvailable = headingSensorAvailable,
                rotationVectorAvailable = rotationVectorAvailable,
                magAccelFallbackAvailable = magAccelFallbackAvailable,
            ),
        northReferenceStatus =
            NorthReferenceStatus(
                requestedMode = NorthReferenceMode.TRUE,
                effectiveMode = NorthReferenceMode.MAGNETIC,
                declinationAvailable = false,
                waitingForDeclination = true,
                pipeline = HeadingPipeline.NONE,
            ),
        magneticInterference = false,
        trackingState = CompassTrackingState.ACQUIRING,
        trackingReason = CompassTrackingReason.STARTUP,
        headingRenderable = false,
        headingTrusted = false,
        northBasis = CompassNorthBasis.UNKNOWN,
        magneticQuality = CompassMagneticQuality.UNKNOWN,
    )

internal fun googleFusedCachedHeadingAgeMs(
    renderState: CompassRenderState,
    nowElapsedMs: Long,
): Long? {
    val sampleAtElapsedMs = renderState.headingSampleElapsedRealtimeMs
    val canUseCachedHeading =
        renderState.providerType == CompassProviderType.GOOGLE_FUSED &&
            renderState.headingDeg.isFinite() &&
            sampleAtElapsedMs != null
    return if (canUseCachedHeading) {
        (nowElapsedMs - sampleAtElapsedMs).coerceAtLeast(0L)
    } else {
        null
    }
}

internal fun hasRecentGoogleFusedCachedHeading(
    renderState: CompassRenderState,
    nowElapsedMs: Long,
    maxAgeMs: Long,
): Boolean {
    val ageMs =
        googleFusedCachedHeadingAgeMs(
            renderState = renderState,
            nowElapsedMs = nowElapsedMs,
        ) ?: return false
    return ageMs <= maxAgeMs
}
