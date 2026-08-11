package com.glancemap.glancemapwearos.presentation.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glancemap.glancemapwearos.data.repository.RecordingProgressVibrationSettings
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val gpsInterval: StateFlow<Long> =
        settingsRepository.gpsInterval
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_GPS_INTERVAL_MS,
            )

    fun setGpsInterval(interval: Long) =
        viewModelScope.launch {
            settingsRepository.setGpsInterval(interval)
        }

    val ambientGpsInterval: StateFlow<Long> =
        settingsRepository.ambientGpsInterval
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_AMBIENT_GPS_INTERVAL_MS,
            )

    fun setAmbientGpsInterval(interval: Long) =
        viewModelScope.launch {
            settingsRepository.setAmbientGpsInterval(interval)
        }

    val watchGpsOnly: StateFlow<Boolean> =
        settingsRepository.watchGpsOnly
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setWatchGpsOnly(isOnly: Boolean) =
        viewModelScope.launch {
            settingsRepository.setWatchGpsOnly(isOnly)
        }

    val gpsInAmbientMode: StateFlow<Boolean> =
        settingsRepository.gpsInAmbientMode
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setGpsInAmbientMode(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setGpsInAmbientMode(enabled)
        }

    val gpsDebugTelemetry: StateFlow<Boolean> =
        settingsRepository.gpsDebugTelemetry
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setGpsDebugTelemetry(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setGpsDebugTelemetry(enabled)
        }

    val diagnosticsCaptureMode: StateFlow<String> =
        settingsRepository.diagnosticsCaptureMode
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_DIAGNOSTICS_CAPTURE_MODE,
            )

    fun setDiagnosticsCaptureMode(mode: String) =
        viewModelScope.launch {
            settingsRepository.setDiagnosticsCaptureMode(mode)
        }

    val gpsPassiveLocationExperiment: StateFlow<Boolean> =
        settingsRepository.gpsPassiveLocationExperiment
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setGpsPassiveLocationExperiment(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setGpsPassiveLocationExperiment(enabled)
        }

    val gpsDebugTelemetryPopupEnabled: StateFlow<Boolean> =
        settingsRepository.gpsDebugTelemetryPopupEnabled
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setGpsDebugTelemetryPopupEnabled(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setGpsDebugTelemetryPopupEnabled(enabled)
        }

    val gpsUsageProfile: StateFlow<String> =
        settingsRepository.gpsUsageProfile
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_GPS_USAGE_PROFILE,
            )

    fun setGpsUsageProfile(profile: String) =
        viewModelScope.launch {
            settingsRepository.setGpsUsageProfile(profile)
        }

    val recordingSampleIntervalSeconds: StateFlow<Int> =
        settingsRepository.recordingSampleIntervalSeconds
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_RECORDING_SAMPLE_INTERVAL_SECONDS,
            )

    fun setRecordingSampleIntervalSeconds(seconds: Int) =
        viewModelScope.launch {
            settingsRepository.setRecordingSampleIntervalSeconds(seconds)
        }

    val recordingScreenOffSampleIntervalSeconds: StateFlow<Int> =
        settingsRepository.recordingScreenOffSampleIntervalSeconds
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_RECORDING_SCREEN_OFF_SAMPLE_INTERVAL_SECONDS,
            )

    fun setRecordingScreenOffSampleIntervalSeconds(seconds: Int) =
        viewModelScope.launch {
            settingsRepository.setRecordingScreenOffSampleIntervalSeconds(seconds)
        }

    val recordingAutoPauseMode: StateFlow<String> =
        settingsRepository.recordingAutoPauseMode
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_RECORDING_AUTO_PAUSE_MODE,
            )

    fun setRecordingAutoPauseMode(mode: String) =
        viewModelScope.launch {
            settingsRepository.setRecordingAutoPauseMode(mode)
        }

    val recordingTrackSmoothingMode: StateFlow<String> =
        settingsRepository.recordingTrackSmoothingMode
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_RECORDING_TRACK_SMOOTHING_MODE,
            )

    fun setRecordingTrackSmoothingMode(mode: String) =
        viewModelScope.launch {
            settingsRepository.setRecordingTrackSmoothingMode(mode)
        }

    val recordingProgressVibrationSettings: StateFlow<RecordingProgressVibrationSettings> =
        settingsRepository.recordingProgressVibrationSettings
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                RecordingProgressVibrationSettings(),
            )

    fun setRecordingProgressVibrationDistanceEnabled(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setRecordingProgressVibrationDistanceEnabled(enabled)
        }

    fun setRecordingProgressVibrationDistanceMeters(distanceMeters: Int) =
        viewModelScope.launch {
            settingsRepository.setRecordingProgressVibrationDistanceMeters(distanceMeters)
        }

    fun setRecordingProgressVibrationTimeEnabled(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setRecordingProgressVibrationTimeEnabled(enabled)
        }

    fun setRecordingProgressVibrationTimeMinutes(timeMinutes: Int) =
        viewModelScope.launch {
            settingsRepository.setRecordingProgressVibrationTimeMinutes(timeMinutes)
        }

    val recordingElevationSource: StateFlow<String> =
        settingsRepository.recordingElevationSource
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_RECORDING_ELEVATION_SOURCE,
            )

    fun setRecordingElevationSource(source: String) =
        viewModelScope.launch {
            settingsRepository.setRecordingElevationSource(source)
        }

    val recordingHeartRateSource: StateFlow<String> =
        settingsRepository.recordingHeartRateSource
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_RECORDING_HEART_RATE_SOURCE,
            )

    fun setRecordingHeartRateSource(source: String) =
        viewModelScope.launch {
            settingsRepository.setRecordingHeartRateSource(source)
        }

    val recordingCadenceSource: StateFlow<String> =
        settingsRepository.recordingCadenceSource
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_RECORDING_CADENCE_SOURCE,
            )

    fun setRecordingCadenceSource(source: String) =
        viewModelScope.launch {
            settingsRepository.setRecordingCadenceSource(source)
        }

    val recordingSpeedSource: StateFlow<String> =
        settingsRepository.recordingSpeedSource
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_RECORDING_SPEED_SOURCE,
            )

    fun setRecordingSpeedSource(source: String) =
        viewModelScope.launch {
            settingsRepository.setRecordingSpeedSource(source)
        }

    val recordingDistanceSource: StateFlow<String> =
        settingsRepository.recordingDistanceSource
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_RECORDING_DISTANCE_SOURCE,
            )

    fun setRecordingDistanceSource(source: String) =
        viewModelScope.launch {
            settingsRepository.setRecordingDistanceSource(source)
        }

    val recordingStepsSource: StateFlow<String> =
        settingsRepository.recordingStepsSource
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_RECORDING_STEPS_SOURCE,
            )

    fun setRecordingStepsSource(source: String) =
        viewModelScope.launch {
            settingsRepository.setRecordingStepsSource(source)
        }

    val recordingDashboardMetricSlots: StateFlow<List<String>> =
        settingsRepository.recordingDashboardMetricSlots
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_RECORDING_DASHBOARD_ALL_METRICS,
            )

    fun setRecordingDashboardMetricSlot(
        slotIndex: Int,
        metricId: String,
    ) = viewModelScope.launch {
        settingsRepository.setRecordingDashboardMetricSlot(slotIndex, metricId)
    }

    fun addRecordingDashboardPage() =
        viewModelScope.launch {
            settingsRepository.addRecordingDashboardPage()
        }

    fun deleteRecordingDashboardPage(pageIndex: Int) =
        viewModelScope.launch {
            settingsRepository.deleteRecordingDashboardPage(pageIndex)
        }

    val turnByTurnDashboardMetricSlots: StateFlow<List<String>> =
        settingsRepository.turnByTurnDashboardMetricSlots
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_TURN_BY_TURN_DASHBOARD_METRICS,
            )

    fun setTurnByTurnDashboardMetricSlot(
        slotIndex: Int,
        metricId: String,
    ) = viewModelScope.launch {
        settingsRepository.setTurnByTurnDashboardMetricSlot(slotIndex, metricId)
    }

    fun addTurnByTurnDashboardPage() =
        viewModelScope.launch {
            settingsRepository.addTurnByTurnDashboardPage()
        }

    fun deleteTurnByTurnDashboardPage(pageIndex: Int) =
        viewModelScope.launch {
            settingsRepository.deleteTurnByTurnDashboardPage(pageIndex)
        }

    val recordingShowSavedGpxOnMap: StateFlow<Boolean> =
        settingsRepository.recordingShowSavedGpxOnMap
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_RECORDING_SHOW_SAVED_GPX_ON_MAP,
            )

    fun setRecordingShowSavedGpxOnMap(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setRecordingShowSavedGpxOnMap(enabled)
        }

    val recordingStartWithTurnByTurn: StateFlow<Boolean> =
        settingsRepository.recordingStartWithTurnByTurn
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_RECORDING_START_WITH_TURN_BY_TURN,
            )

    fun setRecordingStartWithTurnByTurn(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setRecordingStartWithTurnByTurn(enabled)
        }

    val recordingExternalHeartRateAddress: StateFlow<String?> =
        settingsRepository.recordingExternalHeartRateAddress
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val recordingExternalHeartRateName: StateFlow<String?> =
        settingsRepository.recordingExternalHeartRateName
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setRecordingExternalHeartRateDevice(
        address: String?,
        name: String?,
    ) = viewModelScope.launch {
        settingsRepository.setRecordingExternalHeartRateDevice(address, name)
    }

    val recordingExternalRunPodAddress: StateFlow<String?> =
        settingsRepository.recordingExternalRunPodAddress
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val recordingExternalRunPodName: StateFlow<String?> =
        settingsRepository.recordingExternalRunPodName
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setRecordingExternalRunPodDevice(
        address: String?,
        name: String?,
    ) = viewModelScope.launch {
        settingsRepository.setRecordingExternalRunPodDevice(address, name)
    }

    val turnByTurnGuidanceSource: StateFlow<String> =
        settingsRepository.turnByTurnGuidanceSource
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.TURN_BY_TURN_SOURCE_AUTO,
            )

    fun setTurnByTurnGuidanceSource(source: String) =
        viewModelScope.launch {
            settingsRepository.setTurnByTurnGuidanceSource(source)
        }

    val turnByTurnHapticsEnabled: StateFlow<Boolean> =
        settingsRepository.turnByTurnHapticsEnabled
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setTurnByTurnHapticsEnabled(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setTurnByTurnHapticsEnabled(enabled)
        }

    val turnByTurnVoiceGuidanceEnabled: StateFlow<Boolean> =
        settingsRepository.turnByTurnVoiceGuidanceEnabled
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_TURN_BY_TURN_VOICE_GUIDANCE_ENABLED,
            )

    fun setTurnByTurnVoiceGuidanceEnabled(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setTurnByTurnVoiceGuidanceEnabled(enabled)
        }

    val turnByTurnTurnAlertsMode: StateFlow<String> =
        settingsRepository.turnByTurnTurnAlertsMode
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_TURN_BY_TURN_TURN_ALERTS_MODE,
            )

    fun setTurnByTurnTurnAlertsMode(mode: String) =
        viewModelScope.launch {
            settingsRepository.setTurnByTurnTurnAlertsMode(mode)
        }

    val turnByTurnOffRouteAlertsEnabled: StateFlow<Boolean> =
        settingsRepository.turnByTurnOffRouteAlertsEnabled
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setTurnByTurnOffRouteAlertsEnabled(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setTurnByTurnOffRouteAlertsEnabled(enabled)
        }

    val turnByTurnCompactPopupEnabled: StateFlow<Boolean> =
        settingsRepository.turnByTurnCompactPopupEnabled
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_TURN_BY_TURN_COMPACT_POPUP_ENABLED,
            )

    fun setTurnByTurnCompactPopupEnabled(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setTurnByTurnCompactPopupEnabled(enabled)
        }

    val turnByTurnOffRouteAlertThresholdMeters: StateFlow<Int> =
        settingsRepository.turnByTurnOffRouteAlertThresholdMeters
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_TURN_BY_TURN_OFF_ROUTE_ALERT_THRESHOLD_METERS,
            )

    fun setTurnByTurnOffRouteAlertThresholdMeters(thresholdMeters: Int) =
        viewModelScope.launch {
            settingsRepository.setTurnByTurnOffRouteAlertThresholdMeters(thresholdMeters)
        }

    val turnByTurnOffRouteRepeatSeconds: StateFlow<Int> =
        settingsRepository.turnByTurnOffRouteRepeatSeconds
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_TURN_BY_TURN_OFF_ROUTE_REPEAT_SECONDS,
            )

    fun setTurnByTurnOffRouteRepeatSeconds(seconds: Int) =
        viewModelScope.launch {
            settingsRepository.setTurnByTurnOffRouteRepeatSeconds(seconds)
        }

    val turnByTurnGpsInAmbientMode: StateFlow<Boolean> =
        settingsRepository.turnByTurnGpsInAmbientMode
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_TURN_BY_TURN_GPS_IN_AMBIENT_MODE,
            )

    fun setTurnByTurnGpsInAmbientMode(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setTurnByTurnGpsInAmbientMode(enabled)
        }

    val turnByTurnScreenOffBatchingEnabled: StateFlow<Boolean> =
        settingsRepository.turnByTurnScreenOffBatchingEnabled
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_TURN_BY_TURN_SCREEN_OFF_BATCHING_ENABLED,
            )

    fun setTurnByTurnScreenOffBatchingEnabled(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setTurnByTurnScreenOffBatchingEnabled(enabled)
        }

    val turnByTurnGpsIntervalSeconds: StateFlow<Int> =
        settingsRepository.turnByTurnGpsIntervalSeconds
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_TURN_BY_TURN_GPS_INTERVAL_SECONDS,
            )

    fun setTurnByTurnGpsIntervalSeconds(seconds: Int) =
        viewModelScope.launch {
            settingsRepository.setTurnByTurnGpsIntervalSeconds(seconds)
        }

    val turnByTurnScreenOffGpsIntervalSeconds: StateFlow<Int> =
        settingsRepository.turnByTurnScreenOffGpsIntervalSeconds
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_TURN_BY_TURN_SCREEN_OFF_GPS_INTERVAL_SECONDS,
            )

    fun setTurnByTurnScreenOffGpsIntervalSeconds(seconds: Int) =
        viewModelScope.launch {
            settingsRepository.setTurnByTurnScreenOffGpsIntervalSeconds(seconds)
        }

    val turnByTurnBrouterGuideBackEnabled: StateFlow<Boolean> =
        settingsRepository.turnByTurnBrouterGuideBackEnabled
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setTurnByTurnBrouterGuideBackEnabled(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setTurnByTurnBrouterGuideBackEnabled(enabled)
        }

    val turnByTurnRouteStartBehavior: StateFlow<String> =
        settingsRepository.turnByTurnRouteStartBehavior
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.TURN_BY_TURN_ROUTE_START_GO_TO_START,
            )

    fun setTurnByTurnRouteStartBehavior(behavior: String) =
        viewModelScope.launch {
            settingsRepository.setTurnByTurnRouteStartBehavior(behavior)
        }

    val turnByTurnReverseSuggestionMode: StateFlow<String> =
        settingsRepository.turnByTurnReverseSuggestionMode
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.TURN_BY_TURN_REVERSE_SUGGESTION_ASK,
            )

    fun setTurnByTurnReverseSuggestionMode(mode: String) =
        viewModelScope.launch {
            settingsRepository.setTurnByTurnReverseSuggestionMode(mode)
        }

    val promptForCalibration: StateFlow<Boolean> =
        settingsRepository.promptForCalibration
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setPromptForCalibration(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setPromptForCalibration(enabled)
        }

    val showTimeInNavigate: StateFlow<Boolean> =
        settingsRepository.showTimeInNavigate
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setShowTimeInNavigate(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setShowTimeInNavigate(enabled)
        }

    val navigateTimeFormat: StateFlow<String> =
        settingsRepository.navigateTimeFormat
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsRepository.TIME_FORMAT_24_HOUR)

    fun setNavigateTimeFormat(format: String) =
        viewModelScope.launch {
            settingsRepository.setNavigateTimeFormat(format)
        }

    val mapZoomButtonsMode: StateFlow<String> =
        settingsRepository.mapZoomButtonsMode
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsRepository.ZOOM_BUTTONS_BOTH)

    fun setMapZoomButtonsMode(mode: String) =
        viewModelScope.launch {
            settingsRepository.setMapZoomButtonsMode(mode)
        }

    val gpsAccuracyCircleEnabled: StateFlow<Boolean> =
        settingsRepository.gpsAccuracyCircleEnabled
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setGpsAccuracyCircleEnabled(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setGpsAccuracyCircleEnabled(enabled)
        }

    val mapZoomDefault: StateFlow<Int> =
        settingsRepository.mapZoomDefault
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 16)

    fun setMapZoomDefault(zoom: Int) =
        viewModelScope.launch {
            settingsRepository.setMapZoomDefault(zoom)
        }

    val mapZoomMin: StateFlow<Int> =
        settingsRepository.mapZoomMin
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 8)

    fun setMapZoomMin(zoom: Int) =
        viewModelScope.launch {
            settingsRepository.setMapZoomMin(zoom)
        }

    val mapZoomMax: StateFlow<Int> =
        settingsRepository.mapZoomMax
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 20)

    fun setMapZoomMax(zoom: Int) =
        viewModelScope.launch {
            settingsRepository.setMapZoomMax(zoom)
        }

    val mapZoomDefaultScaleMeters: StateFlow<Int> =
        settingsRepository.mapZoomDefaultScaleMeters
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_MAP_ZOOM_DEFAULT_SCALE_METERS,
            )

    fun setMapZoomDefaultScaleMeters(scaleMeters: Int) =
        viewModelScope.launch {
            settingsRepository.setMapZoomDefaultScaleMeters(scaleMeters)
        }

    val mapZoomMinScaleMeters: StateFlow<Int> =
        settingsRepository.mapZoomMinScaleMeters
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_MAP_ZOOM_MIN_SCALE_METERS,
            )

    fun setMapZoomMinScaleMeters(scaleMeters: Int) =
        viewModelScope.launch {
            settingsRepository.setMapZoomMinScaleMeters(scaleMeters)
        }

    val mapZoomMaxScaleMeters: StateFlow<Int> =
        settingsRepository.mapZoomMaxScaleMeters
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_MAP_ZOOM_MAX_SCALE_METERS,
            )

    fun setMapZoomMaxScaleMeters(scaleMeters: Int) =
        viewModelScope.launch {
            settingsRepository.setMapZoomMaxScaleMeters(scaleMeters)
        }

    val northIndicatorMode: StateFlow<String> =
        settingsRepository.northIndicatorMode
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "ALWAYS")

    fun setNorthIndicatorMode(mode: String) =
        viewModelScope.launch {
            settingsRepository.setNorthIndicatorMode(mode)
        }

    val northReferenceMode: StateFlow<String> =
        settingsRepository.northReferenceMode
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.NORTH_REFERENCE_TRUE,
            )

    fun setNorthReferenceMode(mode: String) =
        viewModelScope.launch {
            settingsRepository.setNorthReferenceMode(mode)
        }

    val compassSettingsMode: StateFlow<String> =
        settingsRepository.compassSettingsMode
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.COMPASS_SETTINGS_MODE_AUTOMATIC,
            )

    fun setCompassSettingsMode(mode: String) =
        viewModelScope.launch {
            settingsRepository.setCompassSettingsMode(mode)
        }

    val compassProviderMode: StateFlow<String> =
        settingsRepository.compassProviderMode
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.COMPASS_PROVIDER_GOOGLE_FUSED,
            )

    fun setCompassProviderMode(mode: String) =
        viewModelScope.launch {
            settingsRepository.setCompassProviderMode(mode)
        }

    val compassHeadingSourceMode: StateFlow<String> =
        settingsRepository.compassHeadingSourceMode
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.COMPASS_HEADING_SOURCE_AUTO,
            )

    fun setCompassHeadingSourceMode(mode: String) =
        viewModelScope.launch {
            settingsRepository.setCompassHeadingSourceMode(mode)
        }

    val compassConeAccuracyColorsEnabled: StateFlow<Boolean> =
        settingsRepository.compassConeAccuracyColorsEnabled
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setCompassConeAccuracyColorsEnabled(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setCompassConeAccuracyColorsEnabled(enabled)
        }

    val navigationMarkerStyle: StateFlow<String> =
        settingsRepository.navigationMarkerStyle
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                settingsRepository.navigationMarkerStyleInitial,
            )

    fun setNavigationMarkerStyle(style: String) =
        viewModelScope.launch {
            settingsRepository.setNavigationMarkerStyle(style)
        }

    val navigationMarkerAnchorMode: StateFlow<String> =
        settingsRepository.navigationMarkerAnchorMode
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.NAVIGATION_MARKER_ANCHOR_CENTER,
            )

    fun setNavigationMarkerAnchorMode(mode: String) =
        viewModelScope.launch {
            settingsRepository.setNavigationMarkerAnchorMode(mode)
        }

    val liveElevation: StateFlow<Boolean> =
        settingsRepository.liveElevation
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setLiveElevation(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setLiveElevation(enabled)
        }

    val liveDistance: StateFlow<Boolean> =
        settingsRepository.liveDistance
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setLiveDistance(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setLiveDistance(enabled)
        }

    val offlineMode: StateFlow<Boolean> =
        settingsRepository.offlineMode
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setOfflineMode(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setOfflineMode(enabled)
        }

    val crownZoomEnabled: StateFlow<Boolean> =
        settingsRepository.crownZoomEnabled
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setCrownZoomEnabled(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setCrownZoomEnabled(enabled)
        }

    val crownZoomInverted: StateFlow<Boolean> =
        settingsRepository.crownZoomInverted
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setCrownZoomInverted(inverted: Boolean) =
        viewModelScope.launch {
            settingsRepository.setCrownZoomInverted(inverted)
        }

    val gpxTrackColor: StateFlow<Int> =
        settingsRepository.gpxTrackColor
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun setGpxTrackColor(color: Int) =
        viewModelScope.launch {
            settingsRepository.setGpxTrackColor(color)
        }

    val gpxTrackColorMode: StateFlow<String> =
        settingsRepository.gpxTrackColorMode
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_GPX_TRACK_COLOR_MODE,
            )

    fun setGpxTrackColorMode(mode: String) =
        viewModelScope.launch {
            settingsRepository.setGpxTrackColorMode(mode)
        }

    val gpxTrackWidth: StateFlow<Float> =
        settingsRepository.gpxTrackWidth
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 8f)

    fun setGpxTrackWidth(width: Float) =
        viewModelScope.launch {
            settingsRepository.setGpxTrackWidth(width)
        }

    val gpxTrackOpacityPercent: StateFlow<Int> =
        settingsRepository.gpxTrackOpacityPercent
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_GPX_TRACK_OPACITY_PERCENT,
            )

    fun setGpxTrackOpacityPercent(opacityPercent: Int) =
        viewModelScope.launch {
            settingsRepository.setGpxTrackOpacityPercent(opacityPercent)
        }

    val gpxTrackDirectionArrowsEnabled: StateFlow<Boolean> =
        settingsRepository.gpxTrackDirectionArrowsEnabled
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_GPX_TRACK_DIRECTION_ARROWS_ENABLED,
            )

    fun setGpxTrackDirectionArrowsEnabled(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setGpxTrackDirectionArrowsEnabled(enabled)
        }

    val autoRecenterEnabled: StateFlow<Boolean> =
        settingsRepository.autoRecenterEnabled
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setAutoRecenterEnabled(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setAutoRecenterEnabled(enabled)
        }

    // ✅ FIX: this is StateFlow, not Flow
    val autoRecenterDelay: StateFlow<Int> =
        settingsRepository.autoRecenterDelay
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 5)

    fun setAutoRecenterDelay(delay: Int) =
        viewModelScope.launch {
            settingsRepository.setAutoRecenterDelay(delay)
        }

    // ✅ FIX: this is StateFlow, not Flow
    val selectedMapPath: StateFlow<String?> =
        settingsRepository.selectedMapPath
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setSelectedMapPath(path: String?) =
        viewModelScope.launch {
            settingsRepository.setSelectedMapPath(path)
        }

    val keepAppOpen: StateFlow<Boolean> =
        settingsRepository.keepAppOpen
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setKeepAppOpen(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setKeepAppOpen(enabled)
        }

    val keepAppOpenTipShown: StateFlow<Boolean> =
        settingsRepository.keepAppOpenTipShown
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setKeepAppOpenTipShown(shown: Boolean) =
        viewModelScope.launch {
            settingsRepository.setKeepAppOpenTipShown(shown)
        }

    val compassMode: StateFlow<Boolean> =
        settingsRepository.compassMode
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setCompassMode(isCompassMode: Boolean) =
        viewModelScope.launch {
            settingsRepository.setCompassMode(isCompassMode)
        }

    val isGpxInspectionEnabled: StateFlow<Boolean> =
        settingsRepository.isGpxInspectionEnabled
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setGpxInspectionEnabled(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setGpxInspectionEnabled(enabled)
        }

    val gpxFlatSpeedMps: StateFlow<Float> =
        settingsRepository.gpxFlatSpeedMps
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_GPX_FLAT_SPEED_MPS,
            )

    fun setGpxFlatSpeedMps(speedMps: Float) =
        viewModelScope.launch {
            settingsRepository.setGpxFlatSpeedMps(speedMps)
        }

    val gpxAdvancedEtaEnabled: StateFlow<Boolean> =
        settingsRepository.gpxAdvancedEtaEnabled
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_GPX_ADVANCED_ETA_ENABLED,
            )

    fun setGpxAdvancedEtaEnabled(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setGpxAdvancedEtaEnabled(enabled)
        }

    val gpxStaminaAdjustmentEnabled: StateFlow<Boolean> =
        settingsRepository.gpxStaminaAdjustmentEnabled
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_GPX_STAMINA_ADJUSTMENT_ENABLED,
            )

    fun setGpxStaminaAdjustmentEnabled(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setGpxStaminaAdjustmentEnabled(enabled)
        }

    val gpxUphillVerticalMetersPerHour: StateFlow<Float> =
        settingsRepository.gpxUphillVerticalMetersPerHour
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_GPX_UPHILL_VERTICAL_METERS_PER_HOUR,
            )

    fun setGpxUphillVerticalMetersPerHour(metersPerHour: Float) =
        viewModelScope.launch {
            settingsRepository.setGpxUphillVerticalMetersPerHour(metersPerHour)
        }

    val gpxDownhillVerticalMetersPerHour: StateFlow<Float> =
        settingsRepository.gpxDownhillVerticalMetersPerHour
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_GPX_DOWNHILL_VERTICAL_METERS_PER_HOUR,
            )

    fun setGpxDownhillVerticalMetersPerHour(metersPerHour: Float) =
        viewModelScope.launch {
            settingsRepository.setGpxDownhillVerticalMetersPerHour(metersPerHour)
        }

    val gpxElevationSmoothingDistanceMeters: StateFlow<Float> =
        settingsRepository.gpxElevationSmoothingDistanceMeters
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_GPX_ELEVATION_SMOOTHING_DISTANCE_METERS,
            )

    fun setGpxElevationSmoothingDistanceMeters(distanceMeters: Float) =
        viewModelScope.launch {
            settingsRepository.setGpxElevationSmoothingDistanceMeters(distanceMeters)
        }

    val gpxElevationNeutralDiffThresholdMeters: StateFlow<Float> =
        settingsRepository.gpxElevationNeutralDiffThresholdMeters
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_GPX_ELEVATION_NEUTRAL_DIFF_THRESHOLD_METERS,
            )

    fun setGpxElevationNeutralDiffThresholdMeters(thresholdMeters: Float) =
        viewModelScope.launch {
            settingsRepository.setGpxElevationNeutralDiffThresholdMeters(thresholdMeters)
        }

    val gpxElevationTrendActivationThresholdMeters: StateFlow<Float> =
        settingsRepository.gpxElevationTrendActivationThresholdMeters
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_GPX_ELEVATION_TREND_ACTIVATION_THRESHOLD_METERS,
            )

    fun setGpxElevationTrendActivationThresholdMeters(thresholdMeters: Float) =
        viewModelScope.launch {
            settingsRepository.setGpxElevationTrendActivationThresholdMeters(thresholdMeters)
        }

    val gpxElevationAutoAdjustPerGpx: StateFlow<Boolean> =
        settingsRepository.gpxElevationAutoAdjustPerGpx
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_GPX_ELEVATION_AUTO_ADJUST_PER_GPX,
            )

    fun setGpxElevationAutoAdjustPerGpx(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setGpxElevationAutoAdjustPerGpx(enabled)
        }

    val gpxToolRouteStyle: StateFlow<String> =
        settingsRepository.gpxToolRouteStyle
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_GPX_TOOL_ROUTE_STYLE,
            )

    fun setGpxToolRouteStyle(style: String) =
        viewModelScope.launch {
            settingsRepository.setGpxToolRouteStyle(style)
        }

    val gpxToolUseElevation: StateFlow<Boolean> =
        settingsRepository.gpxToolUseElevation
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_GPX_TOOL_USE_ELEVATION,
            )

    fun setGpxToolUseElevation(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setGpxToolUseElevation(enabled)
        }

    val gpxToolAllowFerries: StateFlow<Boolean> =
        settingsRepository.gpxToolAllowFerries
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_GPX_TOOL_ALLOW_FERRIES,
            )

    fun setGpxToolAllowFerries(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setGpxToolAllowFerries(enabled)
        }

    val gpxToolHikeHikingRoutesPreference: StateFlow<Float> =
        settingsRepository.gpxToolHikeHikingRoutesPreference
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_GPX_TOOL_HIKE_HIKING_ROUTES_PREFERENCE,
            )

    fun setGpxToolHikeHikingRoutesPreference(preference: Float) =
        viewModelScope.launch {
            settingsRepository.setGpxToolHikeHikingRoutesPreference(preference)
        }

    val gpxToolHikePathPreference: StateFlow<Float> =
        settingsRepository.gpxToolHikePathPreference
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_GPX_TOOL_HIKE_PATH_PREFERENCE,
            )

    fun setGpxToolHikePathPreference(preference: Float) =
        viewModelScope.launch {
            settingsRepository.setGpxToolHikePathPreference(preference)
        }

    val gpxToolHikeSacScaleLimit: StateFlow<Int> =
        settingsRepository.gpxToolHikeSacScaleLimit
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_GPX_TOOL_HIKE_SAC_SCALE_LIMIT,
            )

    fun setGpxToolHikeSacScaleLimit(scale: Int) =
        viewModelScope.launch {
            settingsRepository.setGpxToolHikeSacScaleLimit(scale)
        }

    val gpxToolHikeSacScalePreferred: StateFlow<Int> =
        settingsRepository.gpxToolHikeSacScalePreferred
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_GPX_TOOL_HIKE_SAC_SCALE_PREFERRED,
            )

    fun setGpxToolHikeSacScalePreferred(scale: Int) =
        viewModelScope.launch {
            settingsRepository.setGpxToolHikeSacScalePreferred(scale)
        }

    val gpxToolHikeConsiderForest: StateFlow<Boolean> =
        settingsRepository.gpxToolHikeConsiderForest
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_GPX_TOOL_HIKE_CONSIDER_FOREST,
            )

    fun setGpxToolHikeConsiderForest(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setGpxToolHikeConsiderForest(enabled)
        }

    fun setGpxToolCustomHikeProfile(
        hikingRoutesPreference: Float,
        pathPreference: Float,
        sacScaleLimit: Int,
        sacScalePreferred: Int,
        considerForest: Boolean,
    ) = viewModelScope.launch {
        settingsRepository.setGpxToolCustomHikeProfile(
            hikingRoutesPreference = hikingRoutesPreference,
            pathPreference = pathPreference,
            sacScaleLimit = sacScaleLimit,
            sacScalePreferred = sacScalePreferred,
            considerForest = considerForest,
        )
    }

    val activityProfile: StateFlow<String> =
        settingsRepository.activityProfile
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_ACTIVITY_PROFILE,
            )

    fun setActivityProfile(profile: String) =
        viewModelScope.launch {
            settingsRepository.setActivityProfile(profile)
        }

    val cyclingWheelCircumferenceMeters: StateFlow<Float> =
        settingsRepository.cyclingWheelCircumferenceMeters
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_CYCLING_WHEEL_CIRCUMFERENCE_METERS,
            )

    fun setCyclingWheelCircumferenceMeters(meters: Float) =
        viewModelScope.launch {
            settingsRepository.setCyclingWheelCircumferenceMeters(meters)
        }

    val isMetric: StateFlow<Boolean> =
        settingsRepository.isMetric
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setMetric(isMetric: Boolean) =
        viewModelScope.launch {
            settingsRepository.setMetric(isMetric)
        }

    val userWeightKg: StateFlow<Float> =
        settingsRepository.userWeightKg
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_USER_WEIGHT_KG,
            )

    fun setUserWeightKg(weightKg: Float) =
        viewModelScope.launch {
            settingsRepository.setUserWeightKg(weightKg)
        }

    val backpackWeightKg: StateFlow<Float> =
        settingsRepository.backpackWeightKg
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_BACKPACK_WEIGHT_KG,
            )

    fun setBackpackWeightKg(weightKg: Float) =
        viewModelScope.launch {
            settingsRepository.setBackpackWeightKg(weightKg)
        }

    val bikeWeightKg: StateFlow<Float> =
        settingsRepository.bikeWeightKg
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_BIKE_WEIGHT_KG,
            )

    fun setBikeWeightKg(weightKg: Float) =
        viewModelScope.launch {
            settingsRepository.setBikeWeightKg(weightKg)
        }

    val backButtonExitsNavigation: StateFlow<Boolean> =
        settingsRepository.backButtonExitsNavigation
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setBackButtonExitsNavigation(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setBackButtonExitsNavigation(enabled)
        }

    val poiIconSizePx: StateFlow<Int> =
        settingsRepository.poiIconSizePx
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.POI_ICON_SIZE_DEFAULT_PX,
            )

    fun setPoiIconSizePx(sizePx: Int) =
        viewModelScope.launch {
            settingsRepository.setPoiIconSizePx(sizePx)
        }

    val poiMarkerStyle: StateFlow<String> =
        settingsRepository.poiMarkerStyle
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.POI_MARKER_STYLE_THEME_ICON,
            )

    fun setPoiMarkerStyle(style: String) =
        viewModelScope.launch {
            settingsRepository.setPoiMarkerStyle(style)
        }

    val poiTapToCenterEnabled: StateFlow<Boolean> =
        settingsRepository.poiTapToCenterEnabled
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setPoiTapToCenterEnabled(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setPoiTapToCenterEnabled(enabled)
        }

    val linkGpxWaypointPoiFolders: StateFlow<Boolean> =
        settingsRepository.linkGpxWaypointPoiFolders
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.DEFAULT_LINK_GPX_WAYPOINT_POI_FOLDERS,
            )

    fun setLinkGpxWaypointPoiFolders(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setLinkGpxWaypointPoiFolders(enabled)
        }

    val poiPopupTimeoutSeconds: StateFlow<Int> =
        settingsRepository.poiPopupTimeoutSeconds
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SettingsRepository.POI_POPUP_TIMEOUT_DEFAULT_SECONDS,
            )

    fun setPoiPopupTimeoutSeconds(seconds: Int) =
        viewModelScope.launch {
            settingsRepository.setPoiPopupTimeoutSeconds(seconds)
        }

    val poiPopupManualCloseOnly: StateFlow<Boolean> =
        settingsRepository.poiPopupManualCloseOnly
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setPoiPopupManualCloseOnly(enabled: Boolean) =
        viewModelScope.launch {
            settingsRepository.setPoiPopupManualCloseOnly(enabled)
        }

    fun resetToDefaults() =
        viewModelScope.launch {
            resetToDefaultsAndWait()
        }

    suspend fun resetToDefaultsAndWait() {
        settingsRepository.resetToDefaults()
    }
}
