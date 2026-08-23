package com.glancemap.glancemapwearos.presentation.features.navigate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.glancemap.glancemapwearos.core.routing.HikeRouteProfileParams
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.features.settings.SettingsViewModel

internal data class NavigateSettingsState(
    val zoomDefaultScaleMeters: Int,
    val zoomMinScaleMeters: Int,
    val zoomMaxScaleMeters: Int,
    val northIndicatorMode: String,
    val northReferenceMode: String,
    val compassProviderMode: String,
    val headingSourceMode: String,
    val gpxTrackColor: Int,
    val gpxTrackColorMode: String,
    val gpxTrackWidth: Float,
    val gpxTrackOpacityPercent: Int,
    val gpxTrackDirectionArrowsEnabled: Boolean,
    val autoRecenterEnabled: Boolean,
    val autoRecenterDelay: Int,
    val promptForCalibration: Boolean,
    val keepGpsInAmbient: Boolean,
    val turnByTurnHapticsEnabled: Boolean,
    val turnByTurnVoiceGuidanceEnabled: Boolean,
    val turnByTurnTurnAlertsMode: String,
    val turnByTurnOffRouteAlertsEnabled: Boolean,
    val turnByTurnCompactPopupEnabled: Boolean,
    val turnByTurnOffRouteThresholdMeters: Int,
    val turnByTurnOffRouteRepeatSeconds: Int,
    val turnByTurnGpsInAmbient: Boolean,
    val turnByTurnBrouterGuideBackEnabled: Boolean,
    val turnByTurnRouteStartBehavior: String,
    val turnByTurnReverseSuggestionMode: String,
    val crownZoomEnabled: Boolean,
    val crownZoomInverted: Boolean,
    val navigateTimeFormat: String,
    val mapZoomButtonsMode: String,
    val navigationMarkerStyleSetting: String,
    val navigationMarkerAnchorMode: String,
    val gpsAccuracyCircleEnabled: Boolean,
    val liveElevationEnabled: Boolean,
    val liveDistanceEnabled: Boolean,
    val offlineMode: Boolean,
    val gpsDebugTelemetry: Boolean,
    val gpsDebugTelemetryPopupEnabled: Boolean,
    val isGpxInspectionEnabled: Boolean,
    val isMetric: Boolean,
    val backButtonExitsNavigation: Boolean,
    val poiIconSizePx: Int,
    val poiMarkerStyle: String,
    val poiPopupTimeoutSeconds: Int,
    val poiPopupManualCloseOnly: Boolean,
    val recordingDashboardMetricSlots: List<String>,
    val turnByTurnDashboardMetricSlots: List<String>,
    val activityProfile: String,
    val gpxToolRouteStyle: String,
    val gpxToolUseElevation: Boolean,
    val gpxToolAllowFerries: Boolean,
    val gpxToolCustomHikeParams: HikeRouteProfileParams,
    val gpxFlatSpeedMps: Float,
    val gpxAdvancedEtaEnabled: Boolean,
    val gpxStaminaAdjustmentEnabled: Boolean,
    val gpxUphillVerticalMetersPerHour: Float,
    val gpxDownhillVerticalMetersPerHour: Float,
    val compassConeAccuracyColorsEnabled: Boolean,
    val userWeightKg: Float,
    val backpackWeightKg: Float,
    val bikeWeightKg: Float,
)

@Composable
internal fun collectNavigateSettingsState(settingsViewModel: SettingsViewModel): NavigateSettingsState {
    val zoomDefaultScaleMeters by settingsViewModel.mapZoomDefaultScaleMeters.collectAsState()
    val zoomMinScaleMeters by settingsViewModel.mapZoomMinScaleMeters.collectAsState()
    val zoomMaxScaleMeters by settingsViewModel.mapZoomMaxScaleMeters.collectAsState()
    val northIndicatorMode by settingsViewModel.northIndicatorMode.collectAsState()
    val northReferenceMode by settingsViewModel.northReferenceMode.collectAsState(
        initial = SettingsRepository.NORTH_REFERENCE_TRUE,
    )
    val compassProviderMode by settingsViewModel.compassProviderMode.collectAsState(
        initial = SettingsRepository.COMPASS_PROVIDER_GOOGLE_FUSED,
    )
    val headingSourceMode by settingsViewModel.compassHeadingSourceMode.collectAsState(
        initial = SettingsRepository.COMPASS_HEADING_SOURCE_AUTO,
    )
    val gpxTrackColor by settingsViewModel.gpxTrackColor.collectAsState()
    val gpxTrackColorMode by settingsViewModel.gpxTrackColorMode.collectAsState()
    val gpxTrackWidth by settingsViewModel.gpxTrackWidth.collectAsState()
    val gpxTrackOpacityPercent by settingsViewModel.gpxTrackOpacityPercent.collectAsState()
    val gpxTrackDirectionArrowsEnabled by settingsViewModel.gpxTrackDirectionArrowsEnabled.collectAsState()
    val autoRecenterEnabled by settingsViewModel.autoRecenterEnabled.collectAsState()
    val autoRecenterDelay by settingsViewModel.autoRecenterDelay.collectAsState(initial = 5)
    val promptForCalibration by settingsViewModel.promptForCalibration.collectAsState(initial = false)
    val keepGpsInAmbient by settingsViewModel.gpsInAmbientMode.collectAsState(initial = false)
    val turnByTurnHapticsEnabled by settingsViewModel.turnByTurnHapticsEnabled.collectAsState(initial = true)
    val turnByTurnVoiceGuidanceEnabled by settingsViewModel.turnByTurnVoiceGuidanceEnabled.collectAsState(
        initial = SettingsRepository.DEFAULT_TURN_BY_TURN_VOICE_GUIDANCE_ENABLED,
    )
    val turnByTurnTurnAlertsMode by settingsViewModel.turnByTurnTurnAlertsMode.collectAsState(
        initial = SettingsRepository.DEFAULT_TURN_BY_TURN_TURN_ALERTS_MODE,
    )
    val turnByTurnOffRouteAlertsEnabled by settingsViewModel.turnByTurnOffRouteAlertsEnabled.collectAsState(
        initial = true,
    )
    val turnByTurnCompactPopupEnabled by settingsViewModel.turnByTurnCompactPopupEnabled.collectAsState(
        initial = SettingsRepository.DEFAULT_TURN_BY_TURN_COMPACT_POPUP_ENABLED,
    )
    val turnByTurnOffRouteThresholdMeters by settingsViewModel.turnByTurnOffRouteAlertThresholdMeters.collectAsState(
        initial = SettingsRepository.DEFAULT_TURN_BY_TURN_OFF_ROUTE_ALERT_THRESHOLD_METERS,
    )
    val turnByTurnOffRouteRepeatSeconds by settingsViewModel.turnByTurnOffRouteRepeatSeconds.collectAsState(
        initial = SettingsRepository.DEFAULT_TURN_BY_TURN_OFF_ROUTE_REPEAT_SECONDS,
    )
    val turnByTurnGpsInAmbient by settingsViewModel.turnByTurnGpsInAmbientMode.collectAsState(
        initial = SettingsRepository.DEFAULT_TURN_BY_TURN_GPS_IN_AMBIENT_MODE,
    )
    val turnByTurnBrouterGuideBackEnabled by settingsViewModel.turnByTurnBrouterGuideBackEnabled.collectAsState(
        initial = false,
    )
    val turnByTurnRouteStartBehavior by settingsViewModel.turnByTurnRouteStartBehavior.collectAsState(
        initial = SettingsRepository.TURN_BY_TURN_ROUTE_START_GO_TO_START,
    )
    val turnByTurnReverseSuggestionMode by settingsViewModel.turnByTurnReverseSuggestionMode.collectAsState(
        initial = SettingsRepository.TURN_BY_TURN_REVERSE_SUGGESTION_ASK,
    )
    val crownZoomEnabled by settingsViewModel.crownZoomEnabled.collectAsState(initial = true)
    val crownZoomInverted by settingsViewModel.crownZoomInverted.collectAsState(initial = true)
    val navigateTimeFormat by settingsViewModel.navigateTimeFormat.collectAsState()
    val mapZoomButtonsMode by settingsViewModel.mapZoomButtonsMode.collectAsState()
    val navigationMarkerStyleSetting by settingsViewModel.navigationMarkerStyle.collectAsState()
    val navigationMarkerAnchorMode by settingsViewModel.navigationMarkerAnchorMode.collectAsState()
    val gpsAccuracyCircleEnabled by settingsViewModel.gpsAccuracyCircleEnabled.collectAsState(initial = false)
    val liveElevationEnabled by settingsViewModel.liveElevation.collectAsState(initial = false)
    val liveDistanceEnabled by settingsViewModel.liveDistance.collectAsState(initial = false)
    val offlineMode by settingsViewModel.offlineMode.collectAsState(initial = false)
    val gpsDebugTelemetry by settingsViewModel.gpsDebugTelemetry.collectAsState()
    val gpsDebugTelemetryPopupEnabled by
        settingsViewModel.gpsDebugTelemetryPopupEnabled.collectAsState(
            initial = SettingsRepository.DEFAULT_GPS_DEBUG_TELEMETRY_POPUP_ENABLED,
        )
    val isGpxInspectionEnabled by settingsViewModel.isGpxInspectionEnabled.collectAsState()
    val isMetric by settingsViewModel.isMetric.collectAsState()
    val backButtonExitsNavigation by settingsViewModel.backButtonExitsNavigation.collectAsState()
    val poiIconSizePx by settingsViewModel.poiIconSizePx.collectAsState()
    val poiMarkerStyle by settingsViewModel.poiMarkerStyle.collectAsState()
    val poiPopupTimeoutSeconds by settingsViewModel.poiPopupTimeoutSeconds.collectAsState(
        initial = SettingsRepository.POI_POPUP_TIMEOUT_DEFAULT_SECONDS,
    )
    val poiPopupManualCloseOnly by settingsViewModel.poiPopupManualCloseOnly.collectAsState(initial = false)
    val recordingDashboardMetricSlots by settingsViewModel.recordingDashboardMetricSlots.collectAsState()
    val turnByTurnDashboardMetricSlots by settingsViewModel.turnByTurnDashboardMetricSlots.collectAsState()
    val activityProfile by settingsViewModel.activityProfile.collectAsState()
    val gpxToolRouteStyle by settingsViewModel.gpxToolRouteStyle.collectAsState()
    val gpxToolUseElevation by settingsViewModel.gpxToolUseElevation.collectAsState()
    val gpxToolAllowFerries by settingsViewModel.gpxToolAllowFerries.collectAsState()
    val gpxToolHikeHikingRoutesPreference by settingsViewModel.gpxToolHikeHikingRoutesPreference.collectAsState()
    val gpxToolHikePathPreference by settingsViewModel.gpxToolHikePathPreference.collectAsState()
    val gpxToolHikeSacScaleLimit by settingsViewModel.gpxToolHikeSacScaleLimit.collectAsState()
    val gpxToolHikeSacScalePreferred by settingsViewModel.gpxToolHikeSacScalePreferred.collectAsState()
    val gpxToolHikeConsiderForest by settingsViewModel.gpxToolHikeConsiderForest.collectAsState()
    val gpxFlatSpeedMps by settingsViewModel.gpxFlatSpeedMps.collectAsState()
    val gpxAdvancedEtaEnabled by settingsViewModel.gpxAdvancedEtaEnabled.collectAsState()
    val gpxStaminaAdjustmentEnabled by settingsViewModel.gpxStaminaAdjustmentEnabled.collectAsState()
    val gpxUphillVerticalMetersPerHour by settingsViewModel.gpxUphillVerticalMetersPerHour.collectAsState()
    val gpxDownhillVerticalMetersPerHour by settingsViewModel.gpxDownhillVerticalMetersPerHour.collectAsState()
    val compassConeAccuracyColorsEnabled by settingsViewModel.compassConeAccuracyColorsEnabled.collectAsState(
        initial = true,
    )
    val userWeightKg by settingsViewModel.userWeightKg.collectAsState()
    val backpackWeightKg by settingsViewModel.backpackWeightKg.collectAsState()
    val bikeWeightKg by settingsViewModel.bikeWeightKg.collectAsState()

    return NavigateSettingsState(
        zoomDefaultScaleMeters = zoomDefaultScaleMeters,
        zoomMinScaleMeters = zoomMinScaleMeters,
        zoomMaxScaleMeters = zoomMaxScaleMeters,
        northIndicatorMode = northIndicatorMode,
        northReferenceMode = northReferenceMode,
        compassProviderMode = compassProviderMode,
        headingSourceMode = headingSourceMode,
        gpxTrackColor = gpxTrackColor,
        gpxTrackColorMode = gpxTrackColorMode,
        gpxTrackWidth = gpxTrackWidth,
        gpxTrackOpacityPercent = gpxTrackOpacityPercent,
        gpxTrackDirectionArrowsEnabled = gpxTrackDirectionArrowsEnabled,
        autoRecenterEnabled = autoRecenterEnabled,
        autoRecenterDelay = autoRecenterDelay,
        promptForCalibration = promptForCalibration,
        keepGpsInAmbient = keepGpsInAmbient,
        turnByTurnHapticsEnabled = turnByTurnHapticsEnabled,
        turnByTurnVoiceGuidanceEnabled = turnByTurnVoiceGuidanceEnabled,
        turnByTurnTurnAlertsMode = turnByTurnTurnAlertsMode,
        turnByTurnOffRouteAlertsEnabled = turnByTurnOffRouteAlertsEnabled,
        turnByTurnCompactPopupEnabled = turnByTurnCompactPopupEnabled,
        turnByTurnOffRouteThresholdMeters = turnByTurnOffRouteThresholdMeters,
        turnByTurnOffRouteRepeatSeconds = turnByTurnOffRouteRepeatSeconds,
        turnByTurnGpsInAmbient = turnByTurnGpsInAmbient,
        turnByTurnBrouterGuideBackEnabled = turnByTurnBrouterGuideBackEnabled,
        turnByTurnRouteStartBehavior = turnByTurnRouteStartBehavior,
        turnByTurnReverseSuggestionMode = turnByTurnReverseSuggestionMode,
        crownZoomEnabled = crownZoomEnabled,
        crownZoomInverted = crownZoomInverted,
        navigateTimeFormat = navigateTimeFormat,
        mapZoomButtonsMode = mapZoomButtonsMode,
        navigationMarkerStyleSetting = navigationMarkerStyleSetting,
        navigationMarkerAnchorMode = navigationMarkerAnchorMode,
        gpsAccuracyCircleEnabled = gpsAccuracyCircleEnabled,
        liveElevationEnabled = liveElevationEnabled,
        liveDistanceEnabled = liveDistanceEnabled,
        offlineMode = offlineMode,
        gpsDebugTelemetry = gpsDebugTelemetry,
        gpsDebugTelemetryPopupEnabled = gpsDebugTelemetryPopupEnabled,
        isGpxInspectionEnabled = isGpxInspectionEnabled,
        isMetric = isMetric,
        backButtonExitsNavigation = backButtonExitsNavigation,
        poiIconSizePx = poiIconSizePx,
        poiMarkerStyle = poiMarkerStyle,
        poiPopupTimeoutSeconds = poiPopupTimeoutSeconds,
        poiPopupManualCloseOnly = poiPopupManualCloseOnly,
        recordingDashboardMetricSlots = recordingDashboardMetricSlots,
        turnByTurnDashboardMetricSlots = turnByTurnDashboardMetricSlots,
        activityProfile = activityProfile,
        gpxToolRouteStyle = gpxToolRouteStyle,
        gpxToolUseElevation = gpxToolUseElevation,
        gpxToolAllowFerries = gpxToolAllowFerries,
        gpxToolCustomHikeParams =
            HikeRouteProfileParams(
                hikingRoutesPreference = gpxToolHikeHikingRoutesPreference,
                pathPreference = gpxToolHikePathPreference,
                sacScaleLimit = gpxToolHikeSacScaleLimit,
                sacScalePreferred = gpxToolHikeSacScalePreferred,
                considerForest = gpxToolHikeConsiderForest,
            ),
        gpxFlatSpeedMps = gpxFlatSpeedMps,
        gpxAdvancedEtaEnabled = gpxAdvancedEtaEnabled,
        gpxStaminaAdjustmentEnabled = gpxStaminaAdjustmentEnabled,
        gpxUphillVerticalMetersPerHour = gpxUphillVerticalMetersPerHour,
        gpxDownhillVerticalMetersPerHour = gpxDownhillVerticalMetersPerHour,
        compassConeAccuracyColorsEnabled = compassConeAccuracyColorsEnabled,
        userWeightKg = userWeightKg,
        backpackWeightKg = backpackWeightKg,
        bikeWeightKg = bikeWeightKg,
    )
}
