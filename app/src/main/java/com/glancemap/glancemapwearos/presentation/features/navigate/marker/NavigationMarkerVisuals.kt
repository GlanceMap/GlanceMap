package com.glancemap.glancemapwearos.presentation.features.navigate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.SensorManager
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.domain.sensors.CompassProviderType
import com.glancemap.glancemapwearos.domain.sensors.CompassRenderState
import com.glancemap.glancemapwearos.domain.sensors.HeadingSource
import com.glancemap.glancemapwearos.domain.sensors.headingAccuracyFromUncertainty
import java.util.Locale

internal enum class NavigationMarkerStyle {
    DOT,
    TRIANGLE,
}

internal enum class CompassMarkerQuality {
    UNRELIABLE,
    LOW,
    MEDIUM,
    GOOD,
}

internal data class CompassQualityReading(
    val hasQualitySample: Boolean,
    val quality: CompassMarkerQuality?,
    val headingErrorDeg: Float? = null,
    val conservativeHeadingErrorDeg: Float? = null,
    val sampleAgeMs: Long? = null,
    val isStale: Boolean = false,
)

internal fun navigationMarkerStyleFromSetting(raw: String): NavigationMarkerStyle =
    when (raw.trim().uppercase(Locale.US)) {
        SettingsRepository.MARKER_STYLE_TRIANGLE -> NavigationMarkerStyle.TRIANGLE
        else -> NavigationMarkerStyle.DOT
    }

internal fun compassMarkerQualityFromAccuracy(accuracy: Int): CompassMarkerQuality =
    when (accuracy) {
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> CompassMarkerQuality.GOOD
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> CompassMarkerQuality.MEDIUM
        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> CompassMarkerQuality.LOW
        else -> CompassMarkerQuality.UNRELIABLE
    }

internal fun compassMarkerQualityFromHeadingError(headingErrorDeg: Float): CompassMarkerQuality = compassMarkerQualityFromAccuracy(headingAccuracyFromUncertainty(headingErrorDeg))

internal fun compassQualityReadingFromRenderState(
    renderState: CompassRenderState,
    nowElapsedMs: Long,
): CompassQualityReading =
    when (renderState.providerType) {
        CompassProviderType.GOOGLE_FUSED -> {
            val headingErrorDeg = renderState.headingErrorDeg?.takeIf { it.isFinite() && it >= 0f }
            val conservativeHeadingErrorDeg =
                renderState.conservativeHeadingErrorDeg?.takeIf { it.isFinite() && it >= 0f }
            val sampleAgeMs =
                renderState.headingSampleElapsedRealtimeMs?.let { sampleAtMs ->
                    (nowElapsedMs - sampleAtMs).coerceAtLeast(0L)
                }
            val hasQualitySample =
                renderState.headingSource == HeadingSource.FUSED_ORIENTATION &&
                    headingErrorDeg != null &&
                    renderState.headingSampleElapsedRealtimeMs != null
            val quality =
                when {
                    !hasQualitySample -> null
                    renderState.headingSampleStale -> CompassMarkerQuality.UNRELIABLE
                    else -> compassMarkerQualityFromHeadingError(headingErrorDeg)
                }
            CompassQualityReading(
                hasQualitySample = hasQualitySample,
                quality = quality,
                headingErrorDeg = headingErrorDeg,
                conservativeHeadingErrorDeg = conservativeHeadingErrorDeg,
                sampleAgeMs = sampleAgeMs,
                isStale = renderState.headingSampleStale,
            )
        }

        CompassProviderType.SENSOR_MANAGER -> {
            val hasQualitySample = renderState.headingSource != HeadingSource.NONE
            CompassQualityReading(
                hasQualitySample = hasQualitySample,
                quality =
                    if (hasQualitySample) {
                        compassMarkerQualityFromAccuracy(renderState.accuracy)
                    } else {
                        null
                    },
            )
        }
    }

internal fun coneColorArgbForQuality(quality: CompassMarkerQuality): Int =
    when (quality) {
        // Keep green reserved for the most trustworthy heading state. If the cone is not green,
        // users should read it as "heading may be off, be careful."
        CompassMarkerQuality.GOOD -> 0xFF34C759.toInt()
        CompassMarkerQuality.MEDIUM -> 0xFFFFC04D.toInt()
        CompassMarkerQuality.LOW -> 0xFFFF9F43.toInt()
        CompassMarkerQuality.UNRELIABLE -> 0xFFFF5A5F.toInt()
    }

internal fun coneSizeScaleForQuality(quality: CompassMarkerQuality): Pair<Float, Float> =
    when (quality) {
        CompassMarkerQuality.GOOD -> 1.00f to 1.00f
        CompassMarkerQuality.MEDIUM -> 1.00f to 1.20f
        CompassMarkerQuality.LOW -> 1.00f to 1.45f
        CompassMarkerQuality.UNRELIABLE -> 1.00f to 1.90f
    }

internal fun coneWidthScaleForHeadingError(
    headingErrorDeg: Float?,
    fallbackQuality: CompassMarkerQuality,
): Float {
    // Use the same uncertainty source as color, but keep width continuous so the cone
    // reflects how large the estimated heading spread really is.
    val fallbackScale = coneSizeScaleForQuality(fallbackQuality).second
    val safeErrorDeg = headingErrorDeg?.takeIf { it.isFinite() && it >= 0f } ?: return fallbackScale
    val clampedErrorDeg = safeErrorDeg.coerceIn(COMPASS_CONE_MIN_ERROR_DEG, COMPASS_CONE_MAX_ERROR_DEG)
    val normalized =
        (clampedErrorDeg - COMPASS_CONE_MIN_ERROR_DEG) /
            (COMPASS_CONE_MAX_ERROR_DEG - COMPASS_CONE_MIN_ERROR_DEG)
    return COMPASS_CONE_MIN_WIDTH_SCALE +
        (COMPASS_CONE_MAX_WIDTH_SCALE - COMPASS_CONE_MIN_WIDTH_SCALE) * normalized
}

internal fun hasMeaningfulCompassErrorImprovement(
    initialHeadingErrorDeg: Float?,
    finalHeadingErrorDeg: Float?,
): Boolean {
    val initial = initialHeadingErrorDeg?.takeIf { it.isFinite() && it >= 0f } ?: return false
    val final = finalHeadingErrorDeg?.takeIf { it.isFinite() && it >= 0f } ?: return false
    if (final >= initial) return false
    val absoluteImprovementDeg = initial - final
    val relativeImprovement = absoluteImprovementDeg / initial.coerceAtLeast(1f)
    return absoluteImprovementDeg >= COMPASS_ERROR_IMPROVEMENT_MIN_DEG ||
        relativeImprovement >= COMPASS_ERROR_IMPROVEMENT_MIN_RATIO
}

internal fun createNavigationMarkerBitmap(
    style: NavigationMarkerStyle,
    sizePx: Int = 24,
    fillColor: Int = NAVIGATION_MARKER_BLUE_ARGB,
): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    when (style) {
        NavigationMarkerStyle.DOT -> drawDotMarker(canvas, sizePx, fillColor)
        NavigationMarkerStyle.TRIANGLE -> drawArrowMarker(canvas, sizePx, fillColor)
    }
    return bitmap
}

private fun drawDotMarker(
    canvas: Canvas,
    sizePx: Int,
    fillColor: Int,
) {
    drawDotMarkerBitmap(canvas, sizePx, fillColor)
}

internal const val NAVIGATION_MARKER_BLUE_ARGB: Int = 0xFF007AFF.toInt()
internal const val NAVIGATION_MARKER_HISTORICAL_ARGB: Int = 0xFF9CA3AF.toInt()
private const val COMPASS_CONE_MIN_ERROR_DEG = 8f
private const val COMPASS_CONE_MAX_ERROR_DEG = 45f
private const val COMPASS_CONE_MIN_WIDTH_SCALE = 1.0f
private const val COMPASS_CONE_MAX_WIDTH_SCALE = 1.9f
private const val COMPASS_ERROR_IMPROVEMENT_MIN_DEG = 4f
private const val COMPASS_ERROR_IMPROVEMENT_MIN_RATIO = 0.20f
