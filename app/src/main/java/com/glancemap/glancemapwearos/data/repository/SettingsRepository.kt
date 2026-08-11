package com.glancemap.glancemapwearos.data.repository

import com.glancemap.glancemapwearos.core.gpx.GpxElevationFilterDefaults
import com.glancemap.glancemapwearos.core.maps.DemSource
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    companion object {
        const val TIME_FORMAT_24_HOUR = "24_HOUR"
        const val TIME_FORMAT_12_HOUR = "12_HOUR"

        const val DEFAULT_GPS_INTERVAL_MS = 3000L
        const val DEFAULT_AMBIENT_GPS_INTERVAL_MS = 60_000L
        const val DIAGNOSTICS_CAPTURE_MODE_FULL = "FULL"
        const val DIAGNOSTICS_CAPTURE_MODE_BATTERY = "BATTERY"
        const val DEFAULT_DIAGNOSTICS_CAPTURE_MODE = DIAGNOSTICS_CAPTURE_MODE_FULL
        const val MIN_AMBIENT_GPS_INTERVAL_MS = 1_000L
        const val MAX_AMBIENT_GPS_INTERVAL_MS = 120_000L
        const val RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS = -1
        const val GPS_INTERVAL_ADAPTIVE_SCREEN_OFF_SECONDS = -2
        const val GPS_INTERVAL_SAME_AS_SCREEN_ON_SECONDS = 0
        const val DEFAULT_RECORDING_SAMPLE_INTERVAL_SECONDS = 3
        const val DEFAULT_BIKE_RECORDING_SAMPLE_INTERVAL_SECONDS = 1
        const val DEFAULT_RECORDING_SCREEN_OFF_SAMPLE_INTERVAL_SECONDS = 10
        const val DEFAULT_BIKE_RECORDING_SCREEN_OFF_SAMPLE_INTERVAL_SECONDS = 5
        const val RECORDING_AUTO_PAUSE_OFF = "OFF"
        const val RECORDING_AUTO_PAUSE_BIKE_ONLY = "BIKE_ONLY"
        const val RECORDING_AUTO_PAUSE_ALWAYS = "ALWAYS"
        const val DEFAULT_RECORDING_AUTO_PAUSE_MODE = RECORDING_AUTO_PAUSE_OFF
        const val RECORDING_TRACK_SMOOTHING_OFF = "OFF"
        const val RECORDING_TRACK_SMOOTHING_ADAPTIVE = "ADAPTIVE"
        const val RECORDING_TRACK_SMOOTHING_STRONG = "STRONG"
        const val DEFAULT_RECORDING_TRACK_SMOOTHING_MODE = RECORDING_TRACK_SMOOTHING_ADAPTIVE
        const val RECORDING_PROGRESS_VIBRATION_OFF = "OFF"
        const val RECORDING_PROGRESS_VIBRATION_DISTANCE_500_METERS = "DISTANCE_500_METERS"
        const val RECORDING_PROGRESS_VIBRATION_DISTANCE_1_KILOMETER = "DISTANCE_1_KILOMETER"
        const val RECORDING_PROGRESS_VIBRATION_DISTANCE_2_KILOMETERS = "DISTANCE_2_KILOMETERS"
        const val RECORDING_PROGRESS_VIBRATION_DISTANCE_5_KILOMETERS = "DISTANCE_5_KILOMETERS"
        const val RECORDING_PROGRESS_VIBRATION_TIME_15_MINUTES = "TIME_15_MINUTES"
        const val RECORDING_PROGRESS_VIBRATION_TIME_30_MINUTES = "TIME_30_MINUTES"
        const val RECORDING_PROGRESS_VIBRATION_TIME_60_MINUTES = "TIME_60_MINUTES"
        const val DEFAULT_RECORDING_PROGRESS_VIBRATION_MODE = RECORDING_PROGRESS_VIBRATION_OFF
        const val RECORDING_METRIC_DISTANCE = "distance"
        const val RECORDING_METRIC_TOTAL_TIME = "total_time"
        const val RECORDING_METRIC_DURATION = "duration"
        const val RECORDING_METRIC_ELEVATION_GAIN = "elevation_gain"
        const val RECORDING_METRIC_ELEVATION_LOSS = "elevation_loss"
        const val RECORDING_METRIC_CURRENT_ELEVATION = "current_elevation"
        const val RECORDING_METRIC_CURRENT_SPEED = "current_speed"
        const val RECORDING_METRIC_AVERAGE_SPEED = "average_speed"
        const val RECORDING_METRIC_MAX_SPEED = "max_speed"
        const val RECORDING_METRIC_CURRENT_PACE = "current_pace"
        const val RECORDING_METRIC_AVERAGE_PACE = "average_pace"
        const val RECORDING_METRIC_MAX_PACE = "max_pace"
        const val RECORDING_METRIC_HEART_RATE = "heart_rate"
        const val RECORDING_METRIC_MAX_HEART_RATE = "max_heart_rate"
        const val RECORDING_METRIC_STEPS = "steps"
        const val RECORDING_METRIC_CADENCE = "cadence"
        const val RECORDING_METRIC_AVERAGE_CADENCE = "average_cadence"
        const val RECORDING_METRIC_MAX_CADENCE = "max_cadence"
        const val RECORDING_METRIC_POWER = "power"
        const val RECORDING_METRIC_AVERAGE_POWER = "average_power"
        const val RECORDING_METRIC_MAX_POWER = "max_power"
        const val RECORDING_METRIC_BAROMETRIC_PRESSURE = "barometric_pressure"
        const val RECORDING_METRIC_CALORIES = "calories"
        const val RECORDING_METRIC_ACTIVE_CALORIES = "active_calories"
        const val RECORDING_METRIC_RESTING_CALORIES = "resting_calories"
        const val RECORDING_ELEVATION_SOURCE_GPS = "GPS"
        const val RECORDING_ELEVATION_SOURCE_DEM = "DEM"
        const val RECORDING_ELEVATION_SOURCE_AUTO = "AUTO"
        const val RECORDING_SOURCE_DISABLED = "DISABLED"
        const val DEFAULT_RECORDING_ELEVATION_SOURCE = RECORDING_ELEVATION_SOURCE_AUTO
        const val RECORDING_HEART_RATE_SOURCE_WATCH = "WATCH"
        const val RECORDING_HEART_RATE_SOURCE_STRAP = "STRAP"
        const val DEFAULT_RECORDING_HEART_RATE_SOURCE = RECORDING_HEART_RATE_SOURCE_WATCH
        const val RECORDING_SENSOR_SOURCE_WATCH_GPS = "WATCH_GPS"
        const val RECORDING_SENSOR_SOURCE_POD = "POD"
        const val DEFAULT_RECORDING_CADENCE_SOURCE = RECORDING_SENSOR_SOURCE_WATCH_GPS
        const val DEFAULT_RECORDING_SPEED_SOURCE = RECORDING_SENSOR_SOURCE_WATCH_GPS
        const val DEFAULT_RECORDING_DISTANCE_SOURCE = RECORDING_SENSOR_SOURCE_WATCH_GPS
        const val DEFAULT_RECORDING_STEPS_SOURCE = RECORDING_SENSOR_SOURCE_WATCH_GPS
        const val DEFAULT_RECORDING_SHOW_SAVED_GPX_ON_MAP = true
        const val DEFAULT_RECORDING_START_WITH_TURN_BY_TURN = false
        const val ACTIVITY_PROFILE_HIKE = "HIKE"
        const val ACTIVITY_PROFILE_WALK_HIKE = "WALK_HIKE"
        const val ACTIVITY_PROFILE_BIKE = "BIKE"
        const val DEFAULT_ACTIVITY_PROFILE = ACTIVITY_PROFILE_HIKE
        const val GPX_LIST_PAGE_TRACKS = "TRACKS"
        const val GPX_LIST_PAGE_HIKE_ACTIVITIES = "HIKE_ACTIVITIES"
        const val GPX_LIST_PAGE_BIKE_ACTIVITIES = "BIKE_ACTIVITIES"
        const val DEFAULT_GPX_LIST_PAGE = GPX_LIST_PAGE_TRACKS
        const val DEFAULT_LINK_GPX_WAYPOINT_POI_FOLDERS = true
        const val DEFAULT_USER_WEIGHT_KG = 75f
        const val MIN_USER_WEIGHT_KG = 35f
        const val MAX_USER_WEIGHT_KG = 160f
        const val DEFAULT_BACKPACK_WEIGHT_KG = 0f
        const val MIN_BACKPACK_WEIGHT_KG = 0f
        const val MAX_BACKPACK_WEIGHT_KG = 40f
        const val DEFAULT_BIKE_WEIGHT_KG = 12f
        const val MIN_BIKE_WEIGHT_KG = 5f
        const val MAX_BIKE_WEIGHT_KG = 40f
        const val DEFAULT_CYCLING_WHEEL_CIRCUMFERENCE_METERS = 2.105f
        const val MIN_CYCLING_WHEEL_CIRCUMFERENCE_METERS = 1.0f
        const val MAX_CYCLING_WHEEL_CIRCUMFERENCE_METERS = 2.5f
        val DEFAULT_RECORDING_DASHBOARD_METRICS =
            listOf(
                RECORDING_METRIC_DISTANCE,
                RECORDING_METRIC_ELEVATION_GAIN,
                RECORDING_METRIC_ELEVATION_LOSS,
                RECORDING_METRIC_DURATION,
            )
        val DEFAULT_RECORDING_DASHBOARD_PAGE_TWO_METRICS =
            listOf(
                RECORDING_METRIC_HEART_RATE,
                RECORDING_METRIC_AVERAGE_SPEED,
                RECORDING_METRIC_AVERAGE_PACE,
                RECORDING_METRIC_ACTIVE_CALORIES,
            )
        val DEFAULT_RECORDING_DASHBOARD_ALL_METRICS =
            DEFAULT_RECORDING_DASHBOARD_METRICS + DEFAULT_RECORDING_DASHBOARD_PAGE_TWO_METRICS
        val DEFAULT_RECORDING_DASHBOARD_NEW_PAGE_METRICS =
            listOf(
                RECORDING_METRIC_CURRENT_SPEED,
                RECORDING_METRIC_AVERAGE_SPEED,
                RECORDING_METRIC_CURRENT_PACE,
                RECORDING_METRIC_HEART_RATE,
            )
        val DEFAULT_BIKE_RECORDING_DASHBOARD_METRICS =
            listOf(
                RECORDING_METRIC_TOTAL_TIME,
                RECORDING_METRIC_AVERAGE_SPEED,
                RECORDING_METRIC_HEART_RATE,
                RECORDING_METRIC_DISTANCE,
                RECORDING_METRIC_CURRENT_SPEED,
                RECORDING_METRIC_ELEVATION_GAIN,
                RECORDING_METRIC_ELEVATION_LOSS,
                RECORDING_METRIC_ACTIVE_CALORIES,
            )

        const val ZOOM_BUTTONS_BOTH = "BOTH"
        const val ZOOM_BUTTONS_HIDE_BOTH = "HIDE_BOTH"
        const val ZOOM_BUTTONS_HIDE_PLUS = "HIDE_PLUS"

        const val NORTH_REFERENCE_TRUE = "TRUE"
        const val NORTH_REFERENCE_MAGNETIC = "MAGNETIC"
        const val COMPASS_SETTINGS_MODE_AUTOMATIC = "AUTOMATIC"
        const val COMPASS_SETTINGS_MODE_ADVANCED = "ADVANCED"
        const val COMPASS_PROVIDER_GOOGLE_FUSED = "GOOGLE_FUSED"
        const val COMPASS_PROVIDER_SENSOR_MANAGER = "SENSOR_MANAGER"
        const val COMPASS_HEADING_SOURCE_AUTO = "AUTO"
        const val COMPASS_HEADING_SOURCE_TYPE_HEADING = "TYPE_HEADING"
        const val COMPASS_HEADING_SOURCE_ROTATION_VECTOR = "ROTATION_VECTOR"
        const val COMPASS_HEADING_SOURCE_MAGNETOMETER = "MAGNETOMETER"

        const val MARKER_STYLE_DOT = "DOT"
        const val MARKER_STYLE_TRIANGLE = "TRIANGLE"
        const val NAVIGATION_MARKER_ANCHOR_CENTER = "CENTER"
        const val NAVIGATION_MARKER_ANCHOR_LOWER = "LOWER"

        const val TURN_BY_TURN_SOURCE_AUTO = "AUTO"
        const val TURN_BY_TURN_SOURCE_GPX_EXACT = "GPX_EXACT"
        const val TURN_BY_TURN_SOURCE_BROUTER_ENHANCED = "BROUTER_ENHANCED"
        const val TURN_BY_TURN_TURN_ALERTS_OFF = "OFF"
        const val TURN_BY_TURN_TURN_ALERTS_IMPORTANT = "IMPORTANT"
        const val TURN_BY_TURN_TURN_ALERTS_ALL = "ALL"
        const val DEFAULT_TURN_BY_TURN_TURN_ALERTS_MODE = TURN_BY_TURN_TURN_ALERTS_ALL
        const val DEFAULT_TURN_BY_TURN_VOICE_GUIDANCE_ENABLED = false
        const val DEFAULT_TURN_BY_TURN_COMPACT_POPUP_ENABLED = true
        const val DEFAULT_TURN_BY_TURN_OFF_ROUTE_ALERT_THRESHOLD_METERS = 40
        const val DEFAULT_TURN_BY_TURN_OFF_ROUTE_REPEAT_SECONDS = 60
        const val DEFAULT_TURN_BY_TURN_GPS_INTERVAL_SECONDS = 3
        const val DEFAULT_BIKE_TURN_BY_TURN_GPS_INTERVAL_SECONDS = 1
        const val DEFAULT_TURN_BY_TURN_SCREEN_OFF_GPS_INTERVAL_SECONDS =
            GPS_INTERVAL_ADAPTIVE_SCREEN_OFF_SECONDS
        const val DEFAULT_TURN_BY_TURN_GPS_IN_AMBIENT_MODE = true
        const val DEFAULT_TURN_BY_TURN_SCREEN_OFF_BATCHING_ENABLED = false
        const val TURN_BY_TURN_METRIC_REMAINING_DISTANCE = "remaining_distance"
        const val TURN_BY_TURN_METRIC_REMAINING_ASCENT = "remaining_ascent"
        const val TURN_BY_TURN_METRIC_REMAINING_DESCENT = "remaining_descent"
        const val TURN_BY_TURN_METRIC_ETA = "eta"
        const val TURN_BY_TURN_METRIC_REMAINING_TIME = "remaining_time"
        const val TURN_BY_TURN_METRIC_PROGRESS = "progress"
        const val TURN_BY_TURN_METRIC_DISTANCE_COVERED = "distance_covered"
        const val TURN_BY_TURN_METRIC_CURRENT_ALTITUDE = "current_altitude"
        val DEFAULT_TURN_BY_TURN_DASHBOARD_METRICS =
            listOf(
                TURN_BY_TURN_METRIC_REMAINING_DISTANCE,
                TURN_BY_TURN_METRIC_REMAINING_ASCENT,
                TURN_BY_TURN_METRIC_REMAINING_DESCENT,
                TURN_BY_TURN_METRIC_ETA,
            )
        val DEFAULT_TURN_BY_TURN_DASHBOARD_NEW_PAGE_METRICS =
            listOf(
                TURN_BY_TURN_METRIC_REMAINING_DISTANCE,
                TURN_BY_TURN_METRIC_REMAINING_TIME,
                TURN_BY_TURN_METRIC_PROGRESS,
                TURN_BY_TURN_METRIC_ETA,
            )
        val DEFAULT_BIKE_TURN_BY_TURN_DASHBOARD_METRICS =
            listOf(
                TURN_BY_TURN_METRIC_REMAINING_DISTANCE,
                TURN_BY_TURN_METRIC_REMAINING_TIME,
                TURN_BY_TURN_METRIC_ETA,
                TURN_BY_TURN_METRIC_PROGRESS,
            )
        const val TURN_BY_TURN_ROUTE_START_GO_TO_START = "GO_TO_START"
        const val TURN_BY_TURN_ROUTE_START_NEAREST_POINT = "NEAREST_POINT"
        const val TURN_BY_TURN_ROUTE_START_ASK = "ASK"
        const val TURN_BY_TURN_REVERSE_SUGGESTION_ASK = "ASK"
        const val TURN_BY_TURN_REVERSE_SUGGESTION_NEVER = "NEVER"

        const val DEFAULT_GPX_FLAT_SPEED_MPS = 3.5f / 3.6f
        const val DEFAULT_BIKE_GPX_FLAT_SPEED_MPS = 15f / 3.6f
        const val MAX_GPX_FLAT_SPEED_MPS = 60f / 3.6f
        const val DEFAULT_GPX_ADVANCED_ETA_ENABLED = false
        const val DEFAULT_GPX_STAMINA_ADJUSTMENT_ENABLED = false
        const val DEFAULT_GPX_UPHILL_VERTICAL_METERS_PER_HOUR = 600f
        const val DEFAULT_GPX_DOWNHILL_VERTICAL_METERS_PER_HOUR = 900f
        const val MIN_GPX_VERTICAL_METERS_PER_HOUR = 100f
        const val MAX_GPX_UPHILL_VERTICAL_METERS_PER_HOUR = 2_000f
        const val MAX_GPX_DOWNHILL_VERTICAL_METERS_PER_HOUR = 3_000f
        const val DEFAULT_GPX_ELEVATION_SMOOTHING_DISTANCE_METERS =
            GpxElevationFilterDefaults.DEFAULT_SMOOTHING_DISTANCE_METERS
        const val DEFAULT_GPX_ELEVATION_NEUTRAL_DIFF_THRESHOLD_METERS =
            GpxElevationFilterDefaults.DEFAULT_NEUTRAL_DIFF_THRESHOLD_METERS
        const val DEFAULT_GPX_ELEVATION_TREND_ACTIVATION_THRESHOLD_METERS =
            GpxElevationFilterDefaults.DEFAULT_TREND_ACTIVATION_THRESHOLD_METERS
        const val DEFAULT_GPX_ELEVATION_AUTO_ADJUST_PER_GPX = true
        const val DEFAULT_GPX_SOLID_TRACK_OPACITY_PERCENT = 70
        const val DEFAULT_GPX_ELEVATION_TRACK_OPACITY_PERCENT = 90
        const val DEFAULT_GPX_TRACK_OPACITY_PERCENT = DEFAULT_GPX_SOLID_TRACK_OPACITY_PERCENT
        const val MIN_GPX_TRACK_OPACITY_PERCENT = 10
        const val MAX_GPX_TRACK_OPACITY_PERCENT = 100
        const val GPX_TRACK_COLOR_MODE_SOLID = "SOLID"
        const val GPX_TRACK_COLOR_MODE_ELEVATION = "ELEVATION"
        const val DEFAULT_GPX_TRACK_COLOR_MODE = GPX_TRACK_COLOR_MODE_SOLID
        const val DEFAULT_GPX_TRACK_DIRECTION_ARROWS_ENABLED = false
        const val GPX_TOOL_ROUTE_STYLE_BALANCED_HIKE = "BALANCED_HIKE"
        const val GPX_TOOL_ROUTE_STYLE_PREFER_TRAILS = "PREFER_TRAILS"
        const val GPX_TOOL_ROUTE_STYLE_PREFER_EASIEST = "PREFER_EASIEST"
        const val GPX_TOOL_ROUTE_STYLE_CUSTOM_HIKE = "CUSTOM_HIKE"
        const val GPX_TOOL_ROUTE_STYLE_BIKE_TOURING = "BIKE_TOURING"
        const val GPX_TOOL_ROUTE_STYLE_BIKE_ROAD = "BIKE_ROAD"
        const val GPX_TOOL_ROUTE_STYLE_BIKE_QUIET_ROAD = "BIKE_QUIET_ROAD"
        const val GPX_TOOL_ROUTE_STYLE_BIKE_GRAVEL = "BIKE_GRAVEL"
        const val GPX_TOOL_ROUTE_STYLE_BIKE_MTB = "BIKE_MTB"
        const val GPX_TOOL_ROUTE_STYLE_BIKE = GPX_TOOL_ROUTE_STYLE_BIKE_TOURING
        const val DEFAULT_GPX_TOOL_ROUTE_STYLE = GPX_TOOL_ROUTE_STYLE_BALANCED_HIKE
        const val DEFAULT_BIKE_GPX_TOOL_ROUTE_STYLE = GPX_TOOL_ROUTE_STYLE_BIKE_TOURING
        const val DEFAULT_GPX_TOOL_USE_ELEVATION = true
        const val DEFAULT_GPX_TOOL_ALLOW_FERRIES = false
        const val DEFAULT_GPX_TOOL_HIKE_HIKING_ROUTES_PREFERENCE = 0.20f
        const val MIN_GPX_TOOL_HIKE_HIKING_ROUTES_PREFERENCE = 0f
        const val MAX_GPX_TOOL_HIKE_HIKING_ROUTES_PREFERENCE = 1f
        const val DEFAULT_GPX_TOOL_HIKE_PATH_PREFERENCE = 0f
        const val MIN_GPX_TOOL_HIKE_PATH_PREFERENCE = 0f
        const val MAX_GPX_TOOL_HIKE_PATH_PREFERENCE = 20f
        const val DEFAULT_GPX_TOOL_HIKE_SAC_SCALE_LIMIT = 3
        const val MIN_GPX_TOOL_HIKE_SAC_SCALE = 1
        const val MAX_GPX_TOOL_HIKE_SAC_SCALE = 4
        const val DEFAULT_GPX_TOOL_HIKE_SAC_SCALE_PREFERRED = 1
        const val DEFAULT_GPX_TOOL_HIKE_CONSIDER_FOREST = false

        const val POI_ICON_SIZE_SMALL_PX = 20
        const val POI_ICON_SIZE_MEDIUM_PX = 28
        const val POI_ICON_SIZE_LARGE_PX = 36
        const val POI_ICON_SIZE_DEFAULT_PX = POI_ICON_SIZE_MEDIUM_PX
        const val POI_MARKER_STYLE_BADGE = "BADGE"
        const val POI_MARKER_STYLE_THEME_ICON = "THEME_ICON"

        const val POI_POPUP_TIMEOUT_DEFAULT_SECONDS = 5
        const val POI_POPUP_TIMEOUT_MIN_SECONDS = 1
        const val POI_POPUP_TIMEOUT_MAX_SECONDS = 20

        const val DEFAULT_MAP_ZOOM_DEFAULT_SCALE_METERS = 200
        const val DEFAULT_MAP_ZOOM_MIN_SCALE_METERS = 200_000
        const val DEFAULT_MAP_ZOOM_MAX_SCALE_METERS = 20
    }

    val gpsInterval: Flow<Long>

    suspend fun setGpsInterval(interval: Long)

    val ambientGpsInterval: Flow<Long>

    suspend fun setAmbientGpsInterval(interval: Long)

    val watchGpsOnly: Flow<Boolean>

    suspend fun setWatchGpsOnly(isOnly: Boolean)

    val gpsInAmbientMode: Flow<Boolean>

    suspend fun setGpsInAmbientMode(enabled: Boolean)

    val gpsDebugTelemetry: Flow<Boolean>

    suspend fun setGpsDebugTelemetry(enabled: Boolean)

    val diagnosticsCaptureMode: Flow<String>

    suspend fun setDiagnosticsCaptureMode(mode: String)

    val gpsPassiveLocationExperiment: Flow<Boolean>

    suspend fun setGpsPassiveLocationExperiment(enabled: Boolean)

    val gpsDebugTelemetryPopupEnabled: Flow<Boolean>

    suspend fun setGpsDebugTelemetryPopupEnabled(enabled: Boolean)

    val recordingSampleIntervalSeconds: Flow<Int>

    suspend fun setRecordingSampleIntervalSeconds(seconds: Int)

    val recordingScreenOffSampleIntervalSeconds: Flow<Int>

    suspend fun setRecordingScreenOffSampleIntervalSeconds(seconds: Int)

    val recordingAutoPauseMode: Flow<String>

    suspend fun setRecordingAutoPauseMode(mode: String)

    val recordingTrackSmoothingMode: Flow<String>

    suspend fun setRecordingTrackSmoothingMode(mode: String)

    val recordingProgressVibrationMode: Flow<String>

    suspend fun setRecordingProgressVibrationMode(mode: String)

    val recordingElevationSource: Flow<String>

    suspend fun setRecordingElevationSource(source: String)

    val recordingHeartRateSource: Flow<String>

    suspend fun setRecordingHeartRateSource(source: String)

    val recordingCadenceSource: Flow<String>

    suspend fun setRecordingCadenceSource(source: String)

    val recordingSpeedSource: Flow<String>

    suspend fun setRecordingSpeedSource(source: String)

    val recordingDistanceSource: Flow<String>

    suspend fun setRecordingDistanceSource(source: String)

    val recordingStepsSource: Flow<String>

    suspend fun setRecordingStepsSource(source: String)

    val recordingDashboardMetricSlots: Flow<List<String>>

    suspend fun setRecordingDashboardMetricSlot(
        slotIndex: Int,
        metricId: String,
    )

    suspend fun addRecordingDashboardPage()

    suspend fun deleteRecordingDashboardPage(pageIndex: Int)

    val recordingShowSavedGpxOnMap: Flow<Boolean>

    suspend fun setRecordingShowSavedGpxOnMap(enabled: Boolean)

    val recordingStartWithTurnByTurn: Flow<Boolean>

    suspend fun setRecordingStartWithTurnByTurn(enabled: Boolean)

    val recordingExternalHeartRateAddress: Flow<String?>

    val recordingExternalHeartRateName: Flow<String?>

    suspend fun setRecordingExternalHeartRateDevice(
        address: String?,
        name: String?,
    )

    val recordingExternalRunPodAddress: Flow<String?>

    val recordingExternalRunPodName: Flow<String?>

    suspend fun setRecordingExternalRunPodDevice(
        address: String?,
        name: String?,
    )

    val activityProfile: Flow<String>

    suspend fun setActivityProfile(profile: String)

    val userWeightKg: Flow<Float>

    suspend fun setUserWeightKg(weightKg: Float)

    val backpackWeightKg: Flow<Float>

    suspend fun setBackpackWeightKg(weightKg: Float)

    val bikeWeightKg: Flow<Float>

    suspend fun setBikeWeightKg(weightKg: Float)

    val cyclingWheelCircumferenceMeters: Flow<Float>

    suspend fun setCyclingWheelCircumferenceMeters(meters: Float)

    val turnByTurnGuidanceSource: Flow<String>

    suspend fun setTurnByTurnGuidanceSource(source: String)

    val turnByTurnHapticsEnabled: Flow<Boolean>

    suspend fun setTurnByTurnHapticsEnabled(enabled: Boolean)

    val turnByTurnVoiceGuidanceEnabled: Flow<Boolean>

    suspend fun setTurnByTurnVoiceGuidanceEnabled(enabled: Boolean)

    val turnByTurnTurnAlertsMode: Flow<String>

    suspend fun setTurnByTurnTurnAlertsMode(mode: String)

    val turnByTurnOffRouteAlertsEnabled: Flow<Boolean>

    suspend fun setTurnByTurnOffRouteAlertsEnabled(enabled: Boolean)

    val turnByTurnCompactPopupEnabled: Flow<Boolean>

    suspend fun setTurnByTurnCompactPopupEnabled(enabled: Boolean)

    val turnByTurnOffRouteAlertThresholdMeters: Flow<Int>

    suspend fun setTurnByTurnOffRouteAlertThresholdMeters(thresholdMeters: Int)

    val turnByTurnOffRouteRepeatSeconds: Flow<Int>

    suspend fun setTurnByTurnOffRouteRepeatSeconds(seconds: Int)

    val turnByTurnGpsInAmbientMode: Flow<Boolean>

    suspend fun setTurnByTurnGpsInAmbientMode(enabled: Boolean)

    val turnByTurnScreenOffBatchingEnabled: Flow<Boolean>

    suspend fun setTurnByTurnScreenOffBatchingEnabled(enabled: Boolean)

    val turnByTurnGpsIntervalSeconds: Flow<Int>

    suspend fun setTurnByTurnGpsIntervalSeconds(seconds: Int)

    val turnByTurnScreenOffGpsIntervalSeconds: Flow<Int>

    suspend fun setTurnByTurnScreenOffGpsIntervalSeconds(seconds: Int)

    val turnByTurnBrouterGuideBackEnabled: Flow<Boolean>

    suspend fun setTurnByTurnBrouterGuideBackEnabled(enabled: Boolean)

    val turnByTurnRouteStartBehavior: Flow<String>

    suspend fun setTurnByTurnRouteStartBehavior(behavior: String)

    val turnByTurnReverseSuggestionMode: Flow<String>

    suspend fun setTurnByTurnReverseSuggestionMode(mode: String)

    val turnByTurnDashboardMetricSlots: Flow<List<String>>

    suspend fun setTurnByTurnDashboardMetricSlot(
        slotIndex: Int,
        metricId: String,
    )

    suspend fun addTurnByTurnDashboardPage()

    suspend fun deleteTurnByTurnDashboardPage(pageIndex: Int)

    val turnByTurnActiveTrackPath: Flow<String?>

    suspend fun setTurnByTurnActiveTrackPath(path: String?)

    val turnByTurnActiveTrackReversed: Flow<Boolean>

    suspend fun setTurnByTurnActiveTrackReversed(reversed: Boolean)

    val turnByTurnStartReached: Flow<Boolean>

    suspend fun setTurnByTurnStartReached(reached: Boolean)

    val promptForCalibration: Flow<Boolean>

    suspend fun setPromptForCalibration(enabled: Boolean)

    val showTimeInNavigate: Flow<Boolean>

    suspend fun setShowTimeInNavigate(enabled: Boolean)

    val navigateTimeFormat: Flow<String>

    suspend fun setNavigateTimeFormat(format: String)

    val mapZoomButtonsMode: Flow<String>

    suspend fun setMapZoomButtonsMode(mode: String)

    val gpsAccuracyCircleEnabled: Flow<Boolean>

    suspend fun setGpsAccuracyCircleEnabled(enabled: Boolean)

    val mapZoomDefault: Flow<Int>

    suspend fun setMapZoomDefault(zoom: Int)

    val mapZoomMin: Flow<Int>

    suspend fun setMapZoomMin(zoom: Int)

    val mapZoomMax: Flow<Int>

    suspend fun setMapZoomMax(zoom: Int)

    val mapZoomDefaultScaleMeters: Flow<Int>

    suspend fun setMapZoomDefaultScaleMeters(scaleMeters: Int)

    val mapZoomMinScaleMeters: Flow<Int>

    suspend fun setMapZoomMinScaleMeters(scaleMeters: Int)

    val mapZoomMaxScaleMeters: Flow<Int>

    suspend fun setMapZoomMaxScaleMeters(scaleMeters: Int)

    val northIndicatorMode: Flow<String>

    suspend fun setNorthIndicatorMode(mode: String)

    val northReferenceMode: Flow<String>

    suspend fun setNorthReferenceMode(mode: String)

    val compassSettingsMode: Flow<String>

    suspend fun setCompassSettingsMode(mode: String)

    val compassProviderMode: Flow<String>

    suspend fun setCompassProviderMode(mode: String)

    val compassHeadingSourceMode: Flow<String>

    suspend fun setCompassHeadingSourceMode(mode: String)

    val compassConeAccuracyColorsEnabled: Flow<Boolean>

    suspend fun setCompassConeAccuracyColorsEnabled(enabled: Boolean)

    val navigationMarkerStyleInitial: String
    val navigationMarkerStyle: Flow<String>

    suspend fun setNavigationMarkerStyle(style: String)

    val navigationMarkerAnchorMode: Flow<String>

    suspend fun setNavigationMarkerAnchorMode(mode: String)

    val liveElevation: Flow<Boolean>

    suspend fun setLiveElevation(enabled: Boolean)

    val liveDistance: Flow<Boolean>

    suspend fun setLiveDistance(enabled: Boolean)

    val offlineMode: Flow<Boolean>

    suspend fun setOfflineMode(enabled: Boolean)

    val demSource: Flow<DemSource>

    suspend fun setDemSource(source: DemSource)

    val crownZoomEnabled: Flow<Boolean>

    suspend fun setCrownZoomEnabled(enabled: Boolean)

    val crownZoomInverted: Flow<Boolean>

    suspend fun setCrownZoomInverted(inverted: Boolean)

    val gpxTrackColor: Flow<Int>

    suspend fun setGpxTrackColor(color: Int)

    val gpxTrackColorMode: Flow<String>

    suspend fun setGpxTrackColorMode(mode: String)

    val gpxTrackWidth: Flow<Float>

    suspend fun setGpxTrackWidth(width: Float)

    val gpxTrackOpacityPercent: Flow<Int>

    suspend fun setGpxTrackOpacityPercent(opacityPercent: Int)

    val gpxTrackDirectionArrowsEnabled: Flow<Boolean>

    suspend fun setGpxTrackDirectionArrowsEnabled(enabled: Boolean)

    // Auto-recenter settings
    val autoRecenterEnabled: Flow<Boolean>

    suspend fun setAutoRecenterEnabled(enabled: Boolean)

    val autoRecenterDelay: Flow<Int>

    suspend fun setAutoRecenterDelay(delay: Int)

    val selectedMapPath: Flow<String?>

    suspend fun setSelectedMapPath(path: String?)

    // Navigation Session Settings
    val keepAppOpen: Flow<Boolean>

    suspend fun setKeepAppOpen(enabled: Boolean)

    val keepAppOpenTipShown: Flow<Boolean>

    suspend fun setKeepAppOpenTipShown(shown: Boolean)

    val compassMode: Flow<Boolean>

    suspend fun setCompassMode(isCompassMode: Boolean)

    val gpxLastVisitedListPage: Flow<String>

    suspend fun setGpxLastVisitedListPage(page: String)

    val isGpxInspectionEnabled: Flow<Boolean>

    suspend fun setGpxInspectionEnabled(enabled: Boolean)

    val gpxFlatSpeedMps: Flow<Float>

    suspend fun setGpxFlatSpeedMps(speedMps: Float)

    val gpxAdvancedEtaEnabled: Flow<Boolean>

    suspend fun setGpxAdvancedEtaEnabled(enabled: Boolean)

    val gpxStaminaAdjustmentEnabled: Flow<Boolean>

    suspend fun setGpxStaminaAdjustmentEnabled(enabled: Boolean)

    val gpxUphillVerticalMetersPerHour: Flow<Float>

    suspend fun setGpxUphillVerticalMetersPerHour(metersPerHour: Float)

    val gpxDownhillVerticalMetersPerHour: Flow<Float>

    suspend fun setGpxDownhillVerticalMetersPerHour(metersPerHour: Float)

    val gpxElevationSmoothingDistanceMeters: Flow<Float>

    suspend fun setGpxElevationSmoothingDistanceMeters(distanceMeters: Float)

    val gpxElevationNeutralDiffThresholdMeters: Flow<Float>

    suspend fun setGpxElevationNeutralDiffThresholdMeters(thresholdMeters: Float)

    val gpxElevationTrendActivationThresholdMeters: Flow<Float>

    suspend fun setGpxElevationTrendActivationThresholdMeters(thresholdMeters: Float)

    val gpxElevationAutoAdjustPerGpx: Flow<Boolean>

    suspend fun setGpxElevationAutoAdjustPerGpx(enabled: Boolean)

    val gpxToolRouteStyle: Flow<String>

    suspend fun setGpxToolRouteStyle(style: String)

    val gpxToolUseElevation: Flow<Boolean>

    suspend fun setGpxToolUseElevation(enabled: Boolean)

    val gpxToolAllowFerries: Flow<Boolean>

    suspend fun setGpxToolAllowFerries(enabled: Boolean)

    val gpxToolHikeHikingRoutesPreference: Flow<Float>

    suspend fun setGpxToolHikeHikingRoutesPreference(preference: Float)

    val gpxToolHikePathPreference: Flow<Float>

    suspend fun setGpxToolHikePathPreference(preference: Float)

    val gpxToolHikeSacScaleLimit: Flow<Int>

    suspend fun setGpxToolHikeSacScaleLimit(scale: Int)

    val gpxToolHikeSacScalePreferred: Flow<Int>

    suspend fun setGpxToolHikeSacScalePreferred(scale: Int)

    val gpxToolHikeConsiderForest: Flow<Boolean>

    suspend fun setGpxToolHikeConsiderForest(enabled: Boolean)

    suspend fun setGpxToolCustomHikeProfile(
        hikingRoutesPreference: Float,
        pathPreference: Float,
        sacScaleLimit: Int,
        sacScalePreferred: Int,
        considerForest: Boolean,
    )

    val isMetric: Flow<Boolean>

    suspend fun setMetric(isMetric: Boolean)

    val backButtonExitsNavigation: Flow<Boolean>

    suspend fun setBackButtonExitsNavigation(enabled: Boolean)

    val poiIconSizePx: Flow<Int>

    suspend fun setPoiIconSizePx(sizePx: Int)

    val poiMarkerStyle: Flow<String>

    suspend fun setPoiMarkerStyle(style: String)

    val poiTapToCenterEnabled: Flow<Boolean>

    suspend fun setPoiTapToCenterEnabled(enabled: Boolean)

    val linkGpxWaypointPoiFolders: Flow<Boolean>

    suspend fun setLinkGpxWaypointPoiFolders(enabled: Boolean)

    val poiPopupTimeoutSeconds: Flow<Int>

    suspend fun setPoiPopupTimeoutSeconds(seconds: Int)

    val poiPopupManualCloseOnly: Flow<Boolean>

    suspend fun setPoiPopupManualCloseOnly(enabled: Boolean)

    suspend fun resetToDefaults()
}
