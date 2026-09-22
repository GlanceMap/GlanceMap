package com.glancemap.glancemapwearos.presentation.features.navigate

import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapwearos.core.service.diagnostics.CompassDeepTraceDiagnostics
import com.glancemap.glancemapwearos.core.service.location.model.LocationScreenState
import com.glancemap.glancemapwearos.core.service.location.model.isInteractive
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.domain.sensors.CompassHeadingProvenance
import com.glancemap.glancemapwearos.domain.sensors.CompassHeadingSourceMode
import com.glancemap.glancemapwearos.domain.sensors.CompassProviderType
import com.glancemap.glancemapwearos.domain.sensors.CompassRenderState
import com.glancemap.glancemapwearos.domain.sensors.CompassViewModel
import com.glancemap.glancemapwearos.domain.sensors.NorthReferenceMode
import com.glancemap.glancemapwearos.presentation.ui.WearScreenSize
import kotlinx.coroutines.delay

internal data class NavigateCompassUiState(
    val renderState: CompassRenderState,
    val accuracy: Int,
    val magneticInterference: Boolean,
    val providerType: CompassProviderType,
    val markerStyle: NavigationMarkerStyle,
    val markerSizePx: Int,
    val showCompassConeOverlay: Boolean,
    val coneBaseSizePx: Int,
    val coneQuality: CompassMarkerQuality,
    val coneHeadingErrorDeg: Float?,
    val lastCalibrationConfirmedAtMs: Long,
    val onCalibrationSucceeded: () -> Unit,
)

internal data class CompassUiConfidenceTraceKey(
    val providerType: CompassProviderType,
    val coneQuality: CompassMarkerQuality,
    val accuracyColorsEnabled: Boolean,
    val provenance: CompassHeadingProvenance?,
)

@Composable
internal fun rememberNavigateCompassUiState(
    compassViewModel: CompassViewModel,
    screenState: LocationScreenState,
    isScreenResumed: Boolean,
    offlineMode: Boolean,
    northReferenceMode: String,
    compassProviderMode: String,
    headingSourceMode: String,
    navigationMarkerStyleSetting: String,
    compassConeAccuracyColorsEnabled: Boolean,
    density: Density,
    screenSize: WearScreenSize,
): NavigateCompassUiState {
    val selectedCompassProviderType =
        when (compassProviderMode) {
            SettingsRepository.COMPASS_PROVIDER_SENSOR_MANAGER ->
                CompassProviderType.SENSOR_MANAGER
            else -> CompassProviderType.GOOGLE_FUSED
        }
    val effectiveHeadingSourceMode =
        if (compassProviderMode == SettingsRepository.COMPASS_PROVIDER_SENSOR_MANAGER) {
            headingSourceMode
        } else {
            SettingsRepository.COMPASS_HEADING_SOURCE_AUTO
        }

    val compassRenderState by compassViewModel.renderState.collectAsState()
    val deepTraceState by CompassDeepTraceDiagnostics.state.collectAsState()
    val compassAccuracy = compassRenderState.accuracy
    val magneticInterference = compassRenderState.magneticInterference
    val liveCompassQualityReading =
        compassQualityReadingFromRenderState(
            renderState = compassRenderState,
            nowElapsedMs = SystemClock.elapsedRealtime(),
        )
    val navigationMarkerStyle =
        remember(navigationMarkerStyleSetting) {
            navigationMarkerStyleFromSetting(navigationMarkerStyleSetting)
        }
    var displayedCompassQualityName by rememberSaveable {
        mutableStateOf(CompassMarkerQuality.MEDIUM.name)
    }
    val displayedCompassQuality =
        remember(displayedCompassQualityName) {
            runCatching { CompassMarkerQuality.valueOf(displayedCompassQualityName) }
                .getOrDefault(CompassMarkerQuality.MEDIUM)
        }
    var compassQualityWarmupUntilMs by rememberSaveable { mutableLongStateOf(0L) }
    LaunchedEffect(isScreenResumed, screenState, offlineMode) {
        if (isScreenResumed && screenState.isInteractive && !offlineMode) {
            compassQualityWarmupUntilMs =
                SystemClock.elapsedRealtime() + COMPASS_QUALITY_STARTUP_GRACE_MS
        }
    }
    LaunchedEffect(compassQualityWarmupUntilMs, compassAccuracy) {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (
            compassAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE &&
            compassQualityWarmupUntilMs > nowElapsedMs
        ) {
            delay(compassQualityWarmupUntilMs - nowElapsedMs)
            compassQualityWarmupUntilMs = 0L
        }
    }
    var lastCalibrationConfirmedAtMs by rememberSaveable { mutableLongStateOf(0L) }
    LaunchedEffect(
        liveCompassQualityReading.quality,
        liveCompassQualityReading.hasQualitySample,
        liveCompassQualityReading.isStale,
        lastCalibrationConfirmedAtMs,
        compassQualityWarmupUntilMs,
    ) {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val rawQuality = liveCompassQualityReading.quality ?: CompassMarkerQuality.UNRELIABLE
        val effectiveRawQuality =
            applyCompassStartupWarmupGuard(
                rawQuality = rawQuality,
                displayedQuality = displayedCompassQuality,
                nowElapsedMs = nowElapsedMs,
                warmupUntilElapsedMs = compassQualityWarmupUntilMs,
            )
        val targetQuality =
            displayTargetCompassQuality(
                rawQuality = effectiveRawQuality,
                nowElapsedMs = nowElapsedMs,
                lastCalibrationConfirmedAtElapsedMs = lastCalibrationConfirmedAtMs,
            )
        if (targetQuality == displayedCompassQuality) return@LaunchedEffect
        val holdMs =
            compassQualityTransitionHoldMs(
                from = displayedCompassQuality,
                to = targetQuality,
            )
        if (holdMs > 0L) {
            delay(holdMs)
        }
        val refreshedNowElapsedMs = SystemClock.elapsedRealtime()
        val refreshedReading =
            compassQualityReadingFromRenderState(
                renderState = compassRenderState,
                nowElapsedMs = refreshedNowElapsedMs,
            )
        val refreshedRawQuality = refreshedReading.quality ?: CompassMarkerQuality.UNRELIABLE
        val refreshedEffectiveRawQuality =
            applyCompassStartupWarmupGuard(
                rawQuality = refreshedRawQuality,
                displayedQuality = displayedCompassQuality,
                nowElapsedMs = refreshedNowElapsedMs,
                warmupUntilElapsedMs = compassQualityWarmupUntilMs,
            )
        val refreshedTargetQuality =
            displayTargetCompassQuality(
                rawQuality = refreshedEffectiveRawQuality,
                nowElapsedMs = refreshedNowElapsedMs,
                lastCalibrationConfirmedAtElapsedMs = lastCalibrationConfirmedAtMs,
            )
        if (refreshedTargetQuality == targetQuality) {
            displayedCompassQualityName = targetQuality.name
        }
    }
    val markerSizePx =
        when (navigationMarkerStyle) {
            NavigationMarkerStyle.DOT -> 50
            NavigationMarkerStyle.TRIANGLE -> 50
        }
    val showCompassConeOverlay = navigationMarkerStyle == NavigationMarkerStyle.DOT
    val effectiveCompassConeAccuracyColorsEnabled =
        compassConeAccuracyColorsEnabled &&
            selectedCompassProviderType == CompassProviderType.SENSOR_MANAGER
    val compassConeQuality =
        if (effectiveCompassConeAccuracyColorsEnabled) {
            displayedCompassQuality
        } else {
            CompassMarkerQuality.NEUTRAL
        }
    val uiConfidenceTraceKey =
        CompassUiConfidenceTraceKey(
            providerType = compassRenderState.providerType,
            coneQuality = compassConeQuality,
            accuracyColorsEnabled = effectiveCompassConeAccuracyColorsEnabled,
            provenance = compassRenderState.headingProvenance,
        )
    LaunchedEffect(deepTraceState.active, uiConfidenceTraceKey) {
        if (deepTraceState.active) {
            CompassDeepTraceDiagnostics.recordUiConfidence(
                provider = uiConfidenceTraceKey.providerType.name.lowercase(),
                quality = uiConfidenceTraceKey.coneQuality.name.lowercase(),
                accuracyColorsEnabled = uiConfidenceTraceKey.accuracyColorsEnabled,
                provenance = uiConfidenceTraceKey.provenance,
            )
        }
    }
    val compassConeHeadingErrorDeg =
        if (
            effectiveCompassConeAccuracyColorsEnabled &&
            liveCompassQualityReading.hasQualitySample &&
            !liveCompassQualityReading.isStale
        ) {
            liveCompassQualityReading.headingErrorDeg
        } else {
            null
        }
    val compassConeBaseSizePx =
        with(density) {
            when (screenSize) {
                WearScreenSize.LARGE -> 64.dp.roundToPx()
                WearScreenSize.MEDIUM -> 58.dp.roundToPx()
                WearScreenSize.SMALL -> 52.dp.roundToPx()
            }
        }

    LaunchedEffect(northReferenceMode, isScreenResumed) {
        if (!isScreenResumed) return@LaunchedEffect
        val mode =
            if (northReferenceMode == SettingsRepository.NORTH_REFERENCE_MAGNETIC) {
                NorthReferenceMode.MAGNETIC
            } else {
                NorthReferenceMode.TRUE
            }
        compassViewModel.setNorthReferenceMode(mode)
    }
    LaunchedEffect(compassProviderMode) {
        compassViewModel.setProviderType(selectedCompassProviderType)
    }
    LaunchedEffect(effectiveHeadingSourceMode, isScreenResumed) {
        if (!isScreenResumed) return@LaunchedEffect
        val mode =
            when (effectiveHeadingSourceMode) {
                SettingsRepository.COMPASS_HEADING_SOURCE_TYPE_HEADING ->
                    CompassHeadingSourceMode.TYPE_HEADING
                SettingsRepository.COMPASS_HEADING_SOURCE_ROTATION_VECTOR ->
                    CompassHeadingSourceMode.ROTATION_VECTOR
                SettingsRepository.COMPASS_HEADING_SOURCE_MAGNETOMETER ->
                    CompassHeadingSourceMode.MAGNETOMETER
                else -> CompassHeadingSourceMode.AUTO
            }
        compassViewModel.setHeadingSourceMode(mode)
    }

    return NavigateCompassUiState(
        renderState = compassRenderState,
        accuracy = compassAccuracy,
        magneticInterference = magneticInterference,
        providerType = selectedCompassProviderType,
        markerStyle = navigationMarkerStyle,
        markerSizePx = markerSizePx,
        showCompassConeOverlay = showCompassConeOverlay,
        coneBaseSizePx = compassConeBaseSizePx,
        coneQuality = compassConeQuality,
        coneHeadingErrorDeg = compassConeHeadingErrorDeg,
        lastCalibrationConfirmedAtMs = lastCalibrationConfirmedAtMs,
        onCalibrationSucceeded = {
            lastCalibrationConfirmedAtMs = SystemClock.elapsedRealtime()
        },
    )
}
