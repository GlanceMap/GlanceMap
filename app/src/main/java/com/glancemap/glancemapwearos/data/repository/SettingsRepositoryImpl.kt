package com.glancemap.glancemapwearos.data.repository

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.gpx.GpxElevationFilterDefaults
import com.glancemap.glancemapwearos.core.maps.DemSource
import com.glancemap.glancemapwearos.core.maps.MAP_ZOOM_REPRESENTATIVE_LATITUDE_DEGREES
import com.glancemap.glancemapwearos.core.maps.MAP_ZOOM_REPRESENTATIVE_VIEWPORT_WIDTH_PX
import com.glancemap.glancemapwearos.core.maps.sanitizeMapZoomScaleMeters
import com.glancemap.glancemapwearos.core.maps.scaleMetersForZoomLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
private const val RECORDING_DASHBOARD_SLOT_SEPARATOR = ","

class SettingsRepositoryImpl private constructor(
    private val context: Context,
) : SettingsRepository {
    private val markerStyleCachePrefs by lazy {
        context.getSharedPreferences(CACHE_PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val mapsHelpPrefs by lazy {
        context.getSharedPreferences(MAPS_HELP_PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val debugHelpPrefs by lazy {
        context.getSharedPreferences(DEBUG_HELP_PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val gpxHelpPrefs by lazy {
        context.getSharedPreferences(GPX_HELP_PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val poiHelpPrefs by lazy {
        context.getSharedPreferences(POI_HELP_PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val downloadInfoPrefs by lazy {
        context.getSharedPreferences(DOWNLOAD_INFO_PREFS_NAME, Context.MODE_PRIVATE)
    }

    private object PrefKeys {
        val GPS_INTERVAL = longPreferencesKey("gps_interval")
        val AMBIENT_GPS_INTERVAL = longPreferencesKey("ambient_gps_interval")
        val WATCH_GPS_ONLY = booleanPreferencesKey("watch_gps_only")
        val GPS_IN_AMBIENT_MODE = booleanPreferencesKey("gps_in_ambient_mode")
        val GPS_DEBUG_TELEMETRY = booleanPreferencesKey("gps_debug_telemetry")
        val DIAGNOSTICS_CAPTURE_MODE = stringPreferencesKey("diagnostics_capture_mode")
        val GPS_PASSIVE_LOCATION_EXPERIMENT = booleanPreferencesKey("gps_passive_location_experiment")
        val GPS_DEBUG_TELEMETRY_POPUP_ENABLED = booleanPreferencesKey("gps_debug_telemetry_popup_enabled")
        val GPS_USAGE_PROFILE = stringPreferencesKey("gps_usage_profile")
        val RECORDING_SAMPLE_INTERVAL_SECONDS = intPreferencesKey("recording_sample_interval_seconds")
        val RECORDING_SCREEN_OFF_SAMPLE_INTERVAL_SECONDS =
            intPreferencesKey("recording_screen_off_sample_interval_seconds")
        val RECORDING_AUTO_PAUSE_MODE = stringPreferencesKey("recording_auto_pause_mode")
        val RECORDING_AUTO_PAUSE_MODE_HIKE = stringPreferencesKey("recording_auto_pause_mode_hike")
        val RECORDING_AUTO_PAUSE_MODE_BIKE = stringPreferencesKey("recording_auto_pause_mode_bike")
        val RECORDING_TRACK_SMOOTHING_MODE_HIKE = stringPreferencesKey("recording_track_smoothing_mode_hike")
        val RECORDING_TRACK_SMOOTHING_MODE_BIKE = stringPreferencesKey("recording_track_smoothing_mode_bike")
        val RECORDING_PROGRESS_VIBRATION_MODE_HIKE = stringPreferencesKey("recording_progress_vibration_mode_hike")
        val RECORDING_PROGRESS_VIBRATION_MODE_BIKE = stringPreferencesKey("recording_progress_vibration_mode_bike")
        val RECORDING_PROGRESS_VIBRATION_DISTANCE_ENABLED_HIKE =
            booleanPreferencesKey("recording_progress_vibration_distance_enabled_hike")
        val RECORDING_PROGRESS_VIBRATION_DISTANCE_ENABLED_BIKE =
            booleanPreferencesKey("recording_progress_vibration_distance_enabled_bike")
        val RECORDING_PROGRESS_VIBRATION_DISTANCE_METERS_HIKE =
            intPreferencesKey("recording_progress_vibration_distance_meters_hike")
        val RECORDING_PROGRESS_VIBRATION_DISTANCE_METERS_BIKE =
            intPreferencesKey("recording_progress_vibration_distance_meters_bike")
        val RECORDING_PROGRESS_VIBRATION_TIME_ENABLED_HIKE =
            booleanPreferencesKey("recording_progress_vibration_time_enabled_hike")
        val RECORDING_PROGRESS_VIBRATION_TIME_ENABLED_BIKE =
            booleanPreferencesKey("recording_progress_vibration_time_enabled_bike")
        val RECORDING_PROGRESS_VIBRATION_TIME_MINUTES_HIKE =
            intPreferencesKey("recording_progress_vibration_time_minutes_hike")
        val RECORDING_PROGRESS_VIBRATION_TIME_MINUTES_BIKE =
            intPreferencesKey("recording_progress_vibration_time_minutes_bike")
        val RECORDING_ELEVATION_SOURCE = stringPreferencesKey("recording_elevation_source")
        val RECORDING_HEART_RATE_SOURCE = stringPreferencesKey("recording_heart_rate_source")
        val RECORDING_CADENCE_SOURCE = stringPreferencesKey("recording_cadence_source")
        val RECORDING_SPEED_SOURCE = stringPreferencesKey("recording_speed_source")
        val RECORDING_DISTANCE_SOURCE = stringPreferencesKey("recording_distance_source")
        val RECORDING_STEPS_SOURCE = stringPreferencesKey("recording_steps_source")
        val RECORDING_DASHBOARD_METRIC_SLOTS = stringPreferencesKey("recording_dashboard_metric_slots")
        val RECORDING_BIKE_DASHBOARD_METRIC_SLOTS =
            stringPreferencesKey("recording_bike_dashboard_metric_slots")
        val RECORDING_SHOW_SAVED_GPX_ON_MAP = booleanPreferencesKey("recording_show_saved_gpx_on_map")
        val RECORDING_START_WITH_TURN_BY_TURN = booleanPreferencesKey("recording_start_with_turn_by_turn")
        val RECORDING_EXTERNAL_HEART_RATE_ADDRESS = stringPreferencesKey("recording_external_heart_rate_address")
        val RECORDING_EXTERNAL_HEART_RATE_NAME = stringPreferencesKey("recording_external_heart_rate_name")
        val RECORDING_EXTERNAL_RUN_POD_ADDRESS = stringPreferencesKey("recording_external_run_pod_address")
        val RECORDING_EXTERNAL_RUN_POD_NAME = stringPreferencesKey("recording_external_run_pod_name")
        val ACTIVITY_PROFILE = stringPreferencesKey("activity_profile")
        val USER_WEIGHT_KG = floatPreferencesKey("user_weight_kg")
        val BACKPACK_WEIGHT_KG = floatPreferencesKey("backpack_weight_kg")
        val BIKE_WEIGHT_KG = floatPreferencesKey("bike_weight_kg")
        val CYCLING_WHEEL_CIRCUMFERENCE_METERS = floatPreferencesKey("cycling_wheel_circumference_meters")
        val TURN_BY_TURN_GUIDANCE_SOURCE = stringPreferencesKey("turn_by_turn_guidance_source")
        val TURN_BY_TURN_HAPTICS_ENABLED = booleanPreferencesKey("turn_by_turn_haptics_enabled")
        val TURN_BY_TURN_VOICE_GUIDANCE_ENABLED = booleanPreferencesKey("turn_by_turn_voice_guidance_enabled")
        val TURN_BY_TURN_TURN_ALERTS_MODE = stringPreferencesKey("turn_by_turn_turn_alerts_mode")
        val TURN_BY_TURN_OFF_ROUTE_ALERTS_ENABLED =
            booleanPreferencesKey("turn_by_turn_off_route_alerts_enabled")
        val TURN_BY_TURN_COMPACT_POPUP_ENABLED =
            booleanPreferencesKey("turn_by_turn_compact_popup_enabled")
        val TURN_BY_TURN_OFF_ROUTE_ALERT_THRESHOLD_METERS =
            intPreferencesKey("turn_by_turn_off_route_alert_threshold_meters")
        val TURN_BY_TURN_OFF_ROUTE_REPEAT_SECONDS =
            intPreferencesKey("turn_by_turn_off_route_repeat_seconds")
        val TURN_BY_TURN_GPS_IN_AMBIENT_MODE =
            booleanPreferencesKey("turn_by_turn_gps_in_ambient_mode")
        val TURN_BY_TURN_SCREEN_OFF_BATCHING_ENABLED =
            booleanPreferencesKey("turn_by_turn_screen_off_batching_enabled")
        val TURN_BY_TURN_GPS_INTERVAL_SECONDS =
            intPreferencesKey("turn_by_turn_gps_interval_seconds")
        val TURN_BY_TURN_SCREEN_OFF_GPS_INTERVAL_SECONDS =
            intPreferencesKey("turn_by_turn_screen_off_gps_interval_seconds")
        val TURN_BY_TURN_BROUTER_GUIDE_BACK_ENABLED =
            booleanPreferencesKey("turn_by_turn_brouter_guide_back_enabled")
        val TURN_BY_TURN_ROUTE_START_BEHAVIOR = stringPreferencesKey("turn_by_turn_route_start_behavior")
        val TURN_BY_TURN_REVERSE_SUGGESTION_MODE = stringPreferencesKey("turn_by_turn_reverse_suggestion_mode")
        val TURN_BY_TURN_DASHBOARD_METRIC_SLOTS = stringPreferencesKey("turn_by_turn_dashboard_metric_slots")
        val TURN_BY_TURN_ACTIVE_TRACK_PATH = stringPreferencesKey("turn_by_turn_active_track_path")
        val TURN_BY_TURN_ACTIVE_TRACK_REVERSED = booleanPreferencesKey("turn_by_turn_active_track_reversed")
        val TURN_BY_TURN_START_REACHED = booleanPreferencesKey("turn_by_turn_start_reached")
        val PROMPT_FOR_CALIBRATION = booleanPreferencesKey("prompt_for_calibration")
        val SHOW_TIME_IN_NAVIGATE = booleanPreferencesKey("show_time_in_navigate")
        val NAVIGATE_TIME_FORMAT = stringPreferencesKey("navigate_time_format")
        val MAP_ZOOM_BUTTONS_MODE = stringPreferencesKey("map_zoom_buttons_mode")
        val GPS_ACCURACY_CIRCLE_ENABLED = booleanPreferencesKey("gps_accuracy_circle_enabled")
        val MAP_ZOOM_DEFAULT = intPreferencesKey("map_zoom_default")
        val MAP_ZOOM_MIN = intPreferencesKey("map_zoom_min")
        val MAP_ZOOM_MAX = intPreferencesKey("map_zoom_max")
        val MAP_ZOOM_DEFAULT_SCALE_METERS = intPreferencesKey("map_zoom_default_scale_meters")
        val MAP_ZOOM_MIN_SCALE_METERS = intPreferencesKey("map_zoom_min_scale_meters")
        val MAP_ZOOM_MAX_SCALE_METERS = intPreferencesKey("map_zoom_max_scale_meters")
        val NORTH_INDICATOR_MODE = stringPreferencesKey("north_indicator_mode")
        val NORTH_REFERENCE_MODE = stringPreferencesKey("north_reference_mode")
        val COMPASS_SETTINGS_MODE = stringPreferencesKey("compass_settings_mode")
        val COMPASS_PROVIDER_MODE = stringPreferencesKey("compass_provider_mode")
        val COMPASS_HEADING_SOURCE_MODE = stringPreferencesKey("compass_heading_source_mode")
        val COMPASS_CONE_ACCURACY_COLORS_ENABLED =
            booleanPreferencesKey("compass_cone_accuracy_colors_enabled")
        val NAVIGATION_MARKER_STYLE = stringPreferencesKey("navigation_marker_style")
        val NAVIGATION_MARKER_ANCHOR_MODE = stringPreferencesKey("navigation_marker_anchor_mode")
        val LIVE_ELEVATION = booleanPreferencesKey("live_elevation")
        val LIVE_DISTANCE = booleanPreferencesKey("live_distance")
        val OFFLINE_MODE = booleanPreferencesKey("offline_mode")
        val DEM_SOURCE = stringPreferencesKey("dem_source")
        val CROWN_ZOOM_ENABLED = booleanPreferencesKey("crown_zoom_enabled")
        val CROWN_ZOOM_INVERTED = booleanPreferencesKey("crown_zoom_inverted")
        val GPX_TRACK_COLOR = intPreferencesKey("gpx_track_color")
        val GPX_TRACK_COLOR_MODE = stringPreferencesKey("gpx_track_color_mode")
        val GPX_TRACK_WIDTH = floatPreferencesKey("gpx_track_width")
        val GPX_TRACK_OPACITY_PERCENT = intPreferencesKey("gpx_track_opacity_percent")
        val GPX_TRACK_DIRECTION_ARROWS_ENABLED =
            booleanPreferencesKey("gpx_track_direction_arrows_enabled")
        val AUTO_RECENTER_ENABLED = booleanPreferencesKey("auto_recenter_enabled")
        val AUTO_RECENTER_DELAY = intPreferencesKey("auto_recenter_delay")
        val SELECTED_MAP_PATH = stringPreferencesKey("selected_map_path")
        val KEEP_APP_OPEN = booleanPreferencesKey("keep_app_open")
        val KEEP_APP_OPEN_TIP_SHOWN = booleanPreferencesKey("keep_app_open_tip_shown")
        val COMPASS_MODE = booleanPreferencesKey("compass_mode")
        val GPX_LAST_VISITED_LIST_PAGE = stringPreferencesKey("gpx_last_visited_list_page")
        val GPX_INSPECTION_ENABLED = booleanPreferencesKey("gpx_inspection_enabled")
        val GPX_FLAT_SPEED_MPS = floatPreferencesKey("gpx_flat_speed_mps")
        val GPX_ADVANCED_ETA_ENABLED = booleanPreferencesKey("gpx_advanced_eta_enabled")
        val GPX_STAMINA_ADJUSTMENT_ENABLED = booleanPreferencesKey("gpx_stamina_adjustment_enabled")
        val GPX_UPHILL_VERTICAL_METERS_PER_HOUR =
            floatPreferencesKey("gpx_uphill_vertical_meters_per_hour")
        val GPX_DOWNHILL_VERTICAL_METERS_PER_HOUR =
            floatPreferencesKey("gpx_downhill_vertical_meters_per_hour")
        val GPX_ELEVATION_SMOOTHING_DISTANCE_METERS =
            floatPreferencesKey("gpx_elevation_smoothing_distance_meters")
        val GPX_ELEVATION_NEUTRAL_DIFF_THRESHOLD_METERS =
            floatPreferencesKey("gpx_elevation_neutral_diff_threshold_meters")
        val GPX_ELEVATION_TREND_ACTIVATION_THRESHOLD_METERS =
            floatPreferencesKey("gpx_elevation_trend_activation_threshold_meters")
        val GPX_ELEVATION_AUTO_ADJUST_PER_GPX =
            booleanPreferencesKey("gpx_elevation_auto_adjust_per_gpx")
        val GPX_TOOL_ROUTE_STYLE = stringPreferencesKey("gpx_tool_route_style")
        val GPX_TOOL_HIKE_ROUTE_STYLE = stringPreferencesKey("gpx_tool_hike_route_style")
        val GPX_TOOL_BIKE_ROUTE_STYLE = stringPreferencesKey("gpx_tool_bike_route_style")
        val GPX_TOOL_USE_ELEVATION = booleanPreferencesKey("gpx_tool_use_elevation")
        val GPX_TOOL_ALLOW_FERRIES = booleanPreferencesKey("gpx_tool_allow_ferries")
        val GPX_TOOL_HIKE_HIKING_ROUTES_PREFERENCE =
            floatPreferencesKey("gpx_tool_hike_hiking_routes_preference")
        val GPX_TOOL_HIKE_PATH_PREFERENCE = floatPreferencesKey("gpx_tool_hike_path_preference")
        val GPX_TOOL_HIKE_SAC_SCALE_LIMIT = intPreferencesKey("gpx_tool_hike_sac_scale_limit")
        val GPX_TOOL_HIKE_SAC_SCALE_PREFERRED = intPreferencesKey("gpx_tool_hike_sac_scale_preferred")
        val GPX_TOOL_HIKE_CONSIDER_FOREST = booleanPreferencesKey("gpx_tool_hike_consider_forest")
        val IS_METRIC = booleanPreferencesKey("is_metric")
        val BACK_BUTTON_EXITS_NAVIGATION = booleanPreferencesKey("back_button_exits_navigation")
        val POI_ICON_SIZE_PX = intPreferencesKey("poi_icon_size_px")
        val POI_MARKER_STYLE = stringPreferencesKey("poi_marker_style")
        val POI_TAP_TO_CENTER_ENABLED = booleanPreferencesKey("poi_tap_to_center_enabled")
        val LINK_GPX_WAYPOINT_POI_FOLDERS = booleanPreferencesKey("link_gpx_waypoint_poi_folders")
        val POI_POPUP_TIMEOUT_SECONDS = intPreferencesKey("poi_popup_timeout_seconds")
        val POI_POPUP_MANUAL_CLOSE_ONLY = booleanPreferencesKey("poi_popup_manual_close_only")
    }

    // Fixed product choice: keep foreground motion behavior predictable and battery bounded.
    override val gpsInterval: Flow<Long> = flowOf(SettingsRepository.DEFAULT_GPS_INTERVAL_MS)

    override suspend fun setGpsInterval(interval: Long) {
        context.dataStore.edit { it.remove(PrefKeys.GPS_INTERVAL) }
    }

    override val ambientGpsInterval: Flow<Long> = flowOf(SettingsRepository.DEFAULT_AMBIENT_GPS_INTERVAL_MS)

    override suspend fun setAmbientGpsInterval(interval: Long) {
        context.dataStore.edit { it.remove(PrefKeys.AMBIENT_GPS_INTERVAL) }
    }

    override val watchGpsOnly: Flow<Boolean> = context.dataStore.data.map { it[PrefKeys.WATCH_GPS_ONLY] ?: false }

    override suspend fun setWatchGpsOnly(isOnly: Boolean) {
        context.dataStore.edit { it[PrefKeys.WATCH_GPS_ONLY] = isOnly }
    }

    // Fixed product choice: screen-off GPS is disabled by default and only feature-specific
    // overrides, such as active REC or TBT screen-off guidance, may keep GPS running.
    override val gpsInAmbientMode: Flow<Boolean> = flowOf(false)

    override suspend fun setGpsInAmbientMode(enabled: Boolean) {
        context.dataStore.edit { it.remove(PrefKeys.GPS_IN_AMBIENT_MODE) }
    }

    override val gpsDebugTelemetry: Flow<Boolean> = context.dataStore.data.map { it[PrefKeys.GPS_DEBUG_TELEMETRY] ?: false }

    override suspend fun setGpsDebugTelemetry(enabled: Boolean) {
        context.dataStore.edit { it[PrefKeys.GPS_DEBUG_TELEMETRY] = enabled }
    }

    override val diagnosticsCaptureMode: Flow<String> =
        context.dataStore.data.map {
            sanitizeDiagnosticsCaptureMode(it[PrefKeys.DIAGNOSTICS_CAPTURE_MODE])
        }

    override suspend fun setDiagnosticsCaptureMode(mode: String) {
        context.dataStore.edit {
            it[PrefKeys.DIAGNOSTICS_CAPTURE_MODE] = sanitizeDiagnosticsCaptureMode(mode)
        }
    }

    override val gpsPassiveLocationExperiment: Flow<Boolean> =
        context.dataStore.data.map { it[PrefKeys.GPS_PASSIVE_LOCATION_EXPERIMENT] ?: false }

    override suspend fun setGpsPassiveLocationExperiment(enabled: Boolean) {
        context.dataStore.edit { it[PrefKeys.GPS_PASSIVE_LOCATION_EXPERIMENT] = enabled }
    }

    override val gpsDebugTelemetryPopupEnabled: Flow<Boolean> =
        context.dataStore.data.map {
            val fullDiagnostics =
                sanitizeDiagnosticsCaptureMode(it[PrefKeys.DIAGNOSTICS_CAPTURE_MODE]) ==
                    SettingsRepository.DIAGNOSTICS_CAPTURE_MODE_FULL
            val popupEnabled =
                it[PrefKeys.GPS_DEBUG_TELEMETRY_POPUP_ENABLED]
                    ?: SettingsRepository.DEFAULT_GPS_DEBUG_TELEMETRY_POPUP_ENABLED
            fullDiagnostics && popupEnabled
        }

    override suspend fun setGpsDebugTelemetryPopupEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PrefKeys.GPS_DEBUG_TELEMETRY_POPUP_ENABLED] = enabled }
    }

    override val gpsUsageProfile: Flow<String> =
        context.dataStore.data.map { preferences ->
            val activityProfile = sanitizeActivityProfile(preferences[PrefKeys.ACTIVITY_PROFILE])
            inferGpsUsageProfile(
                activityProfile = activityProfile,
                timing = gpsUsageTimingFromPreferences(preferences, activityProfile),
            )
        }

    override suspend fun setGpsUsageProfile(profile: String) {
        context.dataStore.edit { preferences ->
            val selectedProfile =
                profile.takeIf { it in allowedSelectableGpsUsageProfiles }
                    ?: SettingsRepository.DEFAULT_GPS_USAGE_PROFILE
            val activityProfile = sanitizeActivityProfile(preferences[PrefKeys.ACTIVITY_PROFILE])
            preferences.applyGpsUsageTiming(
                usageProfile = selectedProfile,
                activityProfile = activityProfile,
            )
        }
    }

    override val recordingSampleIntervalSeconds: Flow<Int> =
        context.dataStore.data.map {
            it[PrefKeys.RECORDING_SAMPLE_INTERVAL_SECONDS]
                .takeIf { seconds -> seconds in allowedScreenOnGpsIntervalsSeconds }
                ?: defaultRecordingSampleIntervalSecondsForProfile(
                    sanitizeActivityProfile(it[PrefKeys.ACTIVITY_PROFILE]),
                )
        }

    override suspend fun setRecordingSampleIntervalSeconds(seconds: Int) {
        context.dataStore.edit {
            it[PrefKeys.GPS_USAGE_PROFILE] = SettingsRepository.GPS_USAGE_PROFILE_CUSTOM
            val profile = sanitizeActivityProfile(it[PrefKeys.ACTIVITY_PROFILE])
            it[PrefKeys.RECORDING_SAMPLE_INTERVAL_SECONDS] =
                if (seconds in allowedScreenOnGpsIntervalsSeconds) {
                    seconds
                } else {
                    defaultRecordingSampleIntervalSecondsForProfile(profile)
                }
        }
    }

    override val recordingScreenOffSampleIntervalSeconds: Flow<Int> =
        context.dataStore.data.map {
            val profile = sanitizeActivityProfile(it[PrefKeys.ACTIVITY_PROFILE])
            sanitizeScreenOffGpsIntervalSeconds(
                it[PrefKeys.RECORDING_SCREEN_OFF_SAMPLE_INTERVAL_SECONDS],
                defaultRecordingScreenOffSampleIntervalSecondsForProfile(profile),
            )
        }

    override suspend fun setRecordingScreenOffSampleIntervalSeconds(seconds: Int) {
        context.dataStore.edit {
            it[PrefKeys.GPS_USAGE_PROFILE] = SettingsRepository.GPS_USAGE_PROFILE_CUSTOM
            val profile = sanitizeActivityProfile(it[PrefKeys.ACTIVITY_PROFILE])
            it[PrefKeys.RECORDING_SCREEN_OFF_SAMPLE_INTERVAL_SECONDS] =
                sanitizeScreenOffGpsIntervalSeconds(
                    seconds,
                    defaultRecordingScreenOffSampleIntervalSecondsForProfile(profile),
                )
        }
    }

    override val recordingAutoPauseMode: Flow<String> =
        context.dataStore.data.map {
            val profile = sanitizeActivityProfile(it[PrefKeys.ACTIVITY_PROFILE])
            sanitizeRecordingAutoPauseMode(
                mode = it[autoPauseModeKeyFor(profile)],
                legacyMode = it[PrefKeys.RECORDING_AUTO_PAUSE_MODE],
                profile = profile,
            )
        }

    override suspend fun setRecordingAutoPauseMode(mode: String) {
        context.dataStore.edit {
            val profile = sanitizeActivityProfile(it[PrefKeys.ACTIVITY_PROFILE])
            it[autoPauseModeKeyFor(profile)] = sanitizeRecordingAutoPauseMode(mode)
        }
    }

    override val recordingTrackSmoothingMode: Flow<String> =
        context.dataStore.data.map {
            val profile = sanitizeActivityProfile(it[PrefKeys.ACTIVITY_PROFILE])
            sanitizeRecordingTrackSmoothingMode(it[recordingTrackSmoothingModeKeyFor(profile)])
        }

    override suspend fun setRecordingTrackSmoothingMode(mode: String) {
        context.dataStore.edit {
            val profile = sanitizeActivityProfile(it[PrefKeys.ACTIVITY_PROFILE])
            it[recordingTrackSmoothingModeKeyFor(profile)] = sanitizeRecordingTrackSmoothingMode(mode)
        }
    }

    override val recordingProgressVibrationSettings: Flow<RecordingProgressVibrationSettings> =
        context.dataStore.data.map {
            val profile = sanitizeActivityProfile(it[PrefKeys.ACTIVITY_PROFILE])
            recordingProgressVibrationSettingsFor(it, profile)
        }

    override suspend fun setRecordingProgressVibrationDistanceEnabled(enabled: Boolean) {
        context.dataStore.edit {
            val profile = sanitizeActivityProfile(it[PrefKeys.ACTIVITY_PROFILE])
            it.materializeRecordingProgressVibrationSettings(profile)
            it[recordingProgressVibrationDistanceEnabledKeyFor(profile)] = enabled
        }
    }

    override suspend fun setRecordingProgressVibrationDistanceMeters(distanceMeters: Int) {
        context.dataStore.edit {
            val profile = sanitizeActivityProfile(it[PrefKeys.ACTIVITY_PROFILE])
            it.materializeRecordingProgressVibrationSettings(profile)
            it[recordingProgressVibrationDistanceMetersKeyFor(profile)] =
                sanitizeRecordingProgressVibrationDistanceMeters(distanceMeters)
        }
    }

    override suspend fun setRecordingProgressVibrationTimeEnabled(enabled: Boolean) {
        context.dataStore.edit {
            val profile = sanitizeActivityProfile(it[PrefKeys.ACTIVITY_PROFILE])
            it.materializeRecordingProgressVibrationSettings(profile)
            it[recordingProgressVibrationTimeEnabledKeyFor(profile)] = enabled
        }
    }

    override suspend fun setRecordingProgressVibrationTimeMinutes(timeMinutes: Int) {
        context.dataStore.edit {
            val profile = sanitizeActivityProfile(it[PrefKeys.ACTIVITY_PROFILE])
            it.materializeRecordingProgressVibrationSettings(profile)
            it[recordingProgressVibrationTimeMinutesKeyFor(profile)] =
                sanitizeRecordingProgressVibrationTimeMinutes(timeMinutes)
        }
    }

    override val recordingElevationSource: Flow<String> =
        context.dataStore.data.map {
            it[PrefKeys.RECORDING_ELEVATION_SOURCE]
                .takeIf { source -> source in allowedRecordingElevationSources }
                ?: SettingsRepository.DEFAULT_RECORDING_ELEVATION_SOURCE
        }

    override suspend fun setRecordingElevationSource(source: String) {
        context.dataStore.edit {
            it[PrefKeys.RECORDING_ELEVATION_SOURCE] =
                source.takeIf { candidate -> candidate in allowedRecordingElevationSources }
                    ?: SettingsRepository.DEFAULT_RECORDING_ELEVATION_SOURCE
        }
    }

    override val recordingHeartRateSource: Flow<String> =
        context.dataStore.data.map {
            it[PrefKeys.RECORDING_HEART_RATE_SOURCE]
                .takeIf { source -> source in allowedRecordingHeartRateSources }
                ?: SettingsRepository.DEFAULT_RECORDING_HEART_RATE_SOURCE
        }

    override suspend fun setRecordingHeartRateSource(source: String) {
        context.dataStore.edit {
            it[PrefKeys.RECORDING_HEART_RATE_SOURCE] =
                source.takeIf { candidate -> candidate in allowedRecordingHeartRateSources }
                    ?: SettingsRepository.DEFAULT_RECORDING_HEART_RATE_SOURCE
        }
    }

    override val recordingCadenceSource: Flow<String> =
        context.dataStore.data.map {
            sanitizeRecordingSensorSource(
                source = it[PrefKeys.RECORDING_CADENCE_SOURCE],
                defaultSource = SettingsRepository.DEFAULT_RECORDING_CADENCE_SOURCE,
            )
        }

    override suspend fun setRecordingCadenceSource(source: String) {
        context.dataStore.edit {
            it[PrefKeys.RECORDING_CADENCE_SOURCE] =
                sanitizeRecordingSensorSource(source, SettingsRepository.DEFAULT_RECORDING_CADENCE_SOURCE)
        }
    }

    override val recordingSpeedSource: Flow<String> =
        context.dataStore.data.map {
            sanitizeRecordingSensorSource(
                source = it[PrefKeys.RECORDING_SPEED_SOURCE],
                defaultSource = SettingsRepository.DEFAULT_RECORDING_SPEED_SOURCE,
            )
        }

    override suspend fun setRecordingSpeedSource(source: String) {
        context.dataStore.edit {
            it[PrefKeys.RECORDING_SPEED_SOURCE] =
                sanitizeRecordingSensorSource(source, SettingsRepository.DEFAULT_RECORDING_SPEED_SOURCE)
        }
    }

    override val recordingDistanceSource: Flow<String> =
        context.dataStore.data.map {
            sanitizeRecordingSensorSource(
                source = it[PrefKeys.RECORDING_DISTANCE_SOURCE],
                defaultSource = SettingsRepository.DEFAULT_RECORDING_DISTANCE_SOURCE,
            )
        }

    override suspend fun setRecordingDistanceSource(source: String) {
        context.dataStore.edit {
            it[PrefKeys.RECORDING_DISTANCE_SOURCE] =
                sanitizeRecordingSensorSource(source, SettingsRepository.DEFAULT_RECORDING_DISTANCE_SOURCE)
        }
    }

    override val recordingStepsSource: Flow<String> =
        context.dataStore.data.map {
            sanitizeRecordingStepsSource(it[PrefKeys.RECORDING_STEPS_SOURCE])
        }

    override suspend fun setRecordingStepsSource(source: String) {
        context.dataStore.edit {
            it[PrefKeys.RECORDING_STEPS_SOURCE] =
                sanitizeRecordingStepsSource(source)
        }
    }

    override val recordingDashboardMetricSlots: Flow<List<String>> =
        context.dataStore.data.map { preferences ->
            val profile = sanitizeActivityProfile(preferences[PrefKeys.ACTIVITY_PROFILE])
            sanitizeRecordingDashboardMetricSlots(
                raw = preferences[recordingDashboardMetricSlotsKeyForProfile(profile)],
                profile = profile,
            )
        }

    override suspend fun setRecordingDashboardMetricSlot(
        slotIndex: Int,
        metricId: String,
    ) {
        context.dataStore.edit {
            val profile = sanitizeActivityProfile(it[PrefKeys.ACTIVITY_PROFILE])
            val key = recordingDashboardMetricSlotsKeyForProfile(profile)
            val current =
                sanitizeRecordingDashboardMetricSlots(
                    raw = it[key],
                    profile = profile,
                )
            val sanitizedMetric =
                metricId.takeIf { candidate -> isRecordingDashboardMetricAllowedForProfile(profile, candidate) }
                    ?: fallbackRecordingDashboardMetricForSlot(profile, slotIndex)
            val next =
                current
                    .toMutableList()
                    .also { slots ->
                        if (slotIndex in slots.indices) {
                            slots[slotIndex] = sanitizedMetric
                        }
                    }
            it[key] = next.joinToString(RECORDING_DASHBOARD_SLOT_SEPARATOR)
        }
    }

    override suspend fun addRecordingDashboardPage() {
        context.dataStore.edit {
            val profile = sanitizeActivityProfile(it[PrefKeys.ACTIVITY_PROFILE])
            val key = recordingDashboardMetricSlotsKeyForProfile(profile)
            val current =
                sanitizeRecordingDashboardMetricSlots(
                    raw = it[key],
                    profile = profile,
                )
            if (current.size >= RECORDING_DASHBOARD_MAX_SLOT_COUNT) return@edit
            val next = current + newRecordingDashboardPageMetricSlotsForProfile(profile)
            it[key] = next.joinToString(RECORDING_DASHBOARD_SLOT_SEPARATOR)
        }
    }

    override suspend fun deleteRecordingDashboardPage(pageIndex: Int) {
        context.dataStore.edit {
            val profile = sanitizeActivityProfile(it[PrefKeys.ACTIVITY_PROFILE])
            val key = recordingDashboardMetricSlotsKeyForProfile(profile)
            val current =
                sanitizeRecordingDashboardMetricSlots(
                    raw = it[key],
                    profile = profile,
                )
            val pageCount = current.size / RECORDING_DASHBOARD_PAGE_SLOT_COUNT
            if (pageCount <= RECORDING_DASHBOARD_MIN_PAGE_COUNT || pageIndex !in 0 until pageCount) return@edit
            val startIndex = pageIndex * RECORDING_DASHBOARD_PAGE_SLOT_COUNT
            val next =
                current.filterIndexed { index, _ ->
                    index !in startIndex until startIndex + RECORDING_DASHBOARD_PAGE_SLOT_COUNT
                }
            it[key] = next.joinToString(RECORDING_DASHBOARD_SLOT_SEPARATOR)
        }
    }

    override val recordingShowSavedGpxOnMap: Flow<Boolean> =
        context.dataStore.data.map {
            it[PrefKeys.RECORDING_SHOW_SAVED_GPX_ON_MAP]
                ?: SettingsRepository.DEFAULT_RECORDING_SHOW_SAVED_GPX_ON_MAP
        }

    override suspend fun setRecordingShowSavedGpxOnMap(enabled: Boolean) {
        context.dataStore.edit {
            it[PrefKeys.RECORDING_SHOW_SAVED_GPX_ON_MAP] = enabled
        }
    }

    override val recordingStartWithTurnByTurn: Flow<Boolean> =
        context.dataStore.data.map {
            it[PrefKeys.RECORDING_START_WITH_TURN_BY_TURN]
                ?: SettingsRepository.DEFAULT_RECORDING_START_WITH_TURN_BY_TURN
        }

    override suspend fun setRecordingStartWithTurnByTurn(enabled: Boolean) {
        context.dataStore.edit {
            it[PrefKeys.RECORDING_START_WITH_TURN_BY_TURN] = enabled
        }
    }

    override val recordingExternalHeartRateAddress: Flow<String?> =
        context.dataStore.data.map {
            it[PrefKeys.RECORDING_EXTERNAL_HEART_RATE_ADDRESS]?.takeIf(String::isNotBlank)
        }

    override val recordingExternalHeartRateName: Flow<String?> =
        context.dataStore.data.map {
            it[PrefKeys.RECORDING_EXTERNAL_HEART_RATE_NAME]?.takeIf(String::isNotBlank)
        }

    override suspend fun setRecordingExternalHeartRateDevice(
        address: String?,
        name: String?,
    ) {
        context.dataStore.edit {
            val sanitizedAddress = address?.trim()?.takeIf(String::isNotBlank)
            if (sanitizedAddress == null) {
                it.remove(PrefKeys.RECORDING_EXTERNAL_HEART_RATE_ADDRESS)
                it.remove(PrefKeys.RECORDING_EXTERNAL_HEART_RATE_NAME)
            } else {
                it[PrefKeys.RECORDING_EXTERNAL_HEART_RATE_ADDRESS] = sanitizedAddress
                val sanitizedName = name?.trim()?.takeIf(String::isNotBlank)
                if (sanitizedName == null) {
                    it.remove(PrefKeys.RECORDING_EXTERNAL_HEART_RATE_NAME)
                } else {
                    it[PrefKeys.RECORDING_EXTERNAL_HEART_RATE_NAME] = sanitizedName
                }
            }
        }
    }

    override val recordingExternalRunPodAddress: Flow<String?> =
        context.dataStore.data.map {
            it[PrefKeys.RECORDING_EXTERNAL_RUN_POD_ADDRESS]?.takeIf(String::isNotBlank)
        }

    override val recordingExternalRunPodName: Flow<String?> =
        context.dataStore.data.map {
            it[PrefKeys.RECORDING_EXTERNAL_RUN_POD_NAME]?.takeIf(String::isNotBlank)
        }

    override suspend fun setRecordingExternalRunPodDevice(
        address: String?,
        name: String?,
    ) {
        context.dataStore.edit {
            val sanitizedAddress = address?.trim()?.takeIf(String::isNotBlank)
            if (sanitizedAddress == null) {
                it.remove(PrefKeys.RECORDING_EXTERNAL_RUN_POD_ADDRESS)
                it.remove(PrefKeys.RECORDING_EXTERNAL_RUN_POD_NAME)
            } else {
                it[PrefKeys.RECORDING_EXTERNAL_RUN_POD_ADDRESS] = sanitizedAddress
                val sanitizedName = name?.trim()?.takeIf(String::isNotBlank)
                if (sanitizedName == null) {
                    it.remove(PrefKeys.RECORDING_EXTERNAL_RUN_POD_NAME)
                } else {
                    it[PrefKeys.RECORDING_EXTERNAL_RUN_POD_NAME] = sanitizedName
                }
            }
        }
    }

    override val activityProfile: Flow<String> =
        context.dataStore.data.map {
            sanitizeActivityProfile(it[PrefKeys.ACTIVITY_PROFILE])
        }

    override suspend fun setActivityProfile(profile: String) {
        context.dataStore.edit {
            val previousProfile = sanitizeActivityProfile(it[PrefKeys.ACTIVITY_PROFILE])
            val nextProfile = sanitizeActivityProfile(profile)
            it[PrefKeys.ACTIVITY_PROFILE] = nextProfile
            if (previousProfile == nextProfile) return@edit

            applyProfileDefaultIfUncustomized(
                preferences = it,
                previousProfile = previousProfile,
                nextProfile = nextProfile,
            )
        }
    }

    override val userWeightKg: Flow<Float> =
        context.dataStore.data.map {
            sanitizeUserWeightKg(it[PrefKeys.USER_WEIGHT_KG])
        }

    override suspend fun setUserWeightKg(weightKg: Float) {
        context.dataStore.edit {
            it[PrefKeys.USER_WEIGHT_KG] = sanitizeUserWeightKg(weightKg)
        }
    }

    override val backpackWeightKg: Flow<Float> =
        context.dataStore.data.map {
            sanitizeBackpackWeightKg(it[PrefKeys.BACKPACK_WEIGHT_KG])
        }

    override suspend fun setBackpackWeightKg(weightKg: Float) {
        context.dataStore.edit {
            it[PrefKeys.BACKPACK_WEIGHT_KG] = sanitizeBackpackWeightKg(weightKg)
        }
    }

    override val bikeWeightKg: Flow<Float> =
        context.dataStore.data.map {
            sanitizeBikeWeightKg(it[PrefKeys.BIKE_WEIGHT_KG])
        }

    override suspend fun setBikeWeightKg(weightKg: Float) {
        context.dataStore.edit {
            it[PrefKeys.BIKE_WEIGHT_KG] = sanitizeBikeWeightKg(weightKg)
        }
    }

    override val cyclingWheelCircumferenceMeters: Flow<Float> =
        context.dataStore.data.map {
            sanitizeCyclingWheelCircumferenceMeters(it[PrefKeys.CYCLING_WHEEL_CIRCUMFERENCE_METERS])
        }

    override suspend fun setCyclingWheelCircumferenceMeters(meters: Float) {
        context.dataStore.edit {
            it[PrefKeys.CYCLING_WHEEL_CIRCUMFERENCE_METERS] = sanitizeCyclingWheelCircumferenceMeters(meters)
        }
    }

    override val turnByTurnGuidanceSource: Flow<String> =
        context.dataStore.data.map { SettingsRepository.TURN_BY_TURN_SOURCE_GPX_EXACT }

    override suspend fun setTurnByTurnGuidanceSource(source: String) {
        context.dataStore.edit {
            it[PrefKeys.TURN_BY_TURN_GUIDANCE_SOURCE] = SettingsRepository.TURN_BY_TURN_SOURCE_GPX_EXACT
        }
    }

    override val turnByTurnHapticsEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[PrefKeys.TURN_BY_TURN_HAPTICS_ENABLED] ?: true }

    override suspend fun setTurnByTurnHapticsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PrefKeys.TURN_BY_TURN_HAPTICS_ENABLED] = enabled }
    }

    override val turnByTurnVoiceGuidanceEnabled: Flow<Boolean> =
        context.dataStore.data.map {
            it[PrefKeys.TURN_BY_TURN_VOICE_GUIDANCE_ENABLED]
                ?: SettingsRepository.DEFAULT_TURN_BY_TURN_VOICE_GUIDANCE_ENABLED
        }

    override suspend fun setTurnByTurnVoiceGuidanceEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PrefKeys.TURN_BY_TURN_VOICE_GUIDANCE_ENABLED] = enabled }
    }

    override val turnByTurnTurnAlertsMode: Flow<String> =
        context.dataStore.data.map {
            it[PrefKeys.TURN_BY_TURN_TURN_ALERTS_MODE]
                .takeIf { mode -> mode in allowedTurnByTurnTurnAlertModes }
                ?: SettingsRepository.DEFAULT_TURN_BY_TURN_TURN_ALERTS_MODE
        }

    override suspend fun setTurnByTurnTurnAlertsMode(mode: String) {
        context.dataStore.edit {
            it[PrefKeys.TURN_BY_TURN_TURN_ALERTS_MODE] =
                if (mode in allowedTurnByTurnTurnAlertModes) {
                    mode
                } else {
                    SettingsRepository.DEFAULT_TURN_BY_TURN_TURN_ALERTS_MODE
                }
        }
    }

    override val turnByTurnOffRouteAlertsEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[PrefKeys.TURN_BY_TURN_OFF_ROUTE_ALERTS_ENABLED] ?: true }

    override suspend fun setTurnByTurnOffRouteAlertsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PrefKeys.TURN_BY_TURN_OFF_ROUTE_ALERTS_ENABLED] = enabled }
    }

    override val turnByTurnCompactPopupEnabled: Flow<Boolean> =
        context.dataStore.data.map {
            it[PrefKeys.TURN_BY_TURN_COMPACT_POPUP_ENABLED]
                ?: SettingsRepository.DEFAULT_TURN_BY_TURN_COMPACT_POPUP_ENABLED
        }

    override suspend fun setTurnByTurnCompactPopupEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PrefKeys.TURN_BY_TURN_COMPACT_POPUP_ENABLED] = enabled }
    }

    override val turnByTurnOffRouteAlertThresholdMeters: Flow<Int> =
        context.dataStore.data.map {
            it[PrefKeys.TURN_BY_TURN_OFF_ROUTE_ALERT_THRESHOLD_METERS]
                .takeIf { threshold -> threshold in allowedTurnByTurnOffRouteThresholdMeters }
                ?: SettingsRepository.DEFAULT_TURN_BY_TURN_OFF_ROUTE_ALERT_THRESHOLD_METERS
        }

    override suspend fun setTurnByTurnOffRouteAlertThresholdMeters(thresholdMeters: Int) {
        context.dataStore.edit {
            it[PrefKeys.TURN_BY_TURN_OFF_ROUTE_ALERT_THRESHOLD_METERS] =
                if (thresholdMeters in allowedTurnByTurnOffRouteThresholdMeters) {
                    thresholdMeters
                } else {
                    SettingsRepository.DEFAULT_TURN_BY_TURN_OFF_ROUTE_ALERT_THRESHOLD_METERS
                }
        }
    }

    override val turnByTurnOffRouteRepeatSeconds: Flow<Int> =
        context.dataStore.data.map {
            it[PrefKeys.TURN_BY_TURN_OFF_ROUTE_REPEAT_SECONDS]
                .takeIf { seconds -> seconds in allowedTurnByTurnOffRouteRepeatSeconds }
                ?: SettingsRepository.DEFAULT_TURN_BY_TURN_OFF_ROUTE_REPEAT_SECONDS
        }

    override suspend fun setTurnByTurnOffRouteRepeatSeconds(seconds: Int) {
        context.dataStore.edit {
            it[PrefKeys.TURN_BY_TURN_OFF_ROUTE_REPEAT_SECONDS] =
                if (seconds in allowedTurnByTurnOffRouteRepeatSeconds) {
                    seconds
                } else {
                    SettingsRepository.DEFAULT_TURN_BY_TURN_OFF_ROUTE_REPEAT_SECONDS
                }
        }
    }

    override val turnByTurnGpsInAmbientMode: Flow<Boolean> =
        context.dataStore.data.map {
            val profile = sanitizeActivityProfile(it[PrefKeys.ACTIVITY_PROFILE])
            val screenOnSeconds =
                sanitizeScreenOnGpsIntervalSeconds(
                    it[PrefKeys.TURN_BY_TURN_GPS_INTERVAL_SECONDS],
                    defaultTurnByTurnGpsIntervalSecondsForProfile(profile),
                )
            when (sanitizeTurnByTurnScreenOffGpsIntervalSeconds(it)) {
                SettingsRepository.GPS_INTERVAL_SAME_AS_SCREEN_ON_SECONDS ->
                    screenOnSeconds != SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS

                SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS -> false
                else -> true
            }
        }

    override suspend fun setTurnByTurnGpsInAmbientMode(enabled: Boolean) {
        context.dataStore.edit {
            it[PrefKeys.GPS_USAGE_PROFILE] = SettingsRepository.GPS_USAGE_PROFILE_CUSTOM
            val profile = sanitizeActivityProfile(it[PrefKeys.ACTIVITY_PROFILE])
            val screenOnSeconds =
                sanitizeScreenOnGpsIntervalSeconds(
                    it[PrefKeys.TURN_BY_TURN_GPS_INTERVAL_SECONDS],
                    defaultTurnByTurnGpsIntervalSecondsForProfile(profile),
                )
            it[PrefKeys.TURN_BY_TURN_SCREEN_OFF_GPS_INTERVAL_SECONDS] =
                if (enabled) {
                    if (screenOnSeconds == SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS) {
                        defaultTurnByTurnGpsIntervalSecondsForProfile(profile)
                    } else {
                        SettingsRepository.GPS_INTERVAL_SAME_AS_SCREEN_ON_SECONDS
                    }
                } else {
                    SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS
                }
            it[PrefKeys.TURN_BY_TURN_GPS_IN_AMBIENT_MODE] = enabled
        }
    }

    override val turnByTurnScreenOffBatchingEnabled: Flow<Boolean> =
        context.dataStore.data.map {
            it[PrefKeys.TURN_BY_TURN_SCREEN_OFF_BATCHING_ENABLED]
                ?: SettingsRepository.DEFAULT_TURN_BY_TURN_SCREEN_OFF_BATCHING_ENABLED
        }

    override suspend fun setTurnByTurnScreenOffBatchingEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PrefKeys.TURN_BY_TURN_SCREEN_OFF_BATCHING_ENABLED] = enabled }
    }

    override val turnByTurnGpsIntervalSeconds: Flow<Int> =
        context.dataStore.data.map {
            val profile = sanitizeActivityProfile(it[PrefKeys.ACTIVITY_PROFILE])
            sanitizeScreenOnGpsIntervalSeconds(
                it[PrefKeys.TURN_BY_TURN_GPS_INTERVAL_SECONDS],
                defaultTurnByTurnGpsIntervalSecondsForProfile(profile),
            )
        }

    override suspend fun setTurnByTurnGpsIntervalSeconds(seconds: Int) {
        context.dataStore.edit {
            it[PrefKeys.GPS_USAGE_PROFILE] = SettingsRepository.GPS_USAGE_PROFILE_CUSTOM
            val profile = sanitizeActivityProfile(it[PrefKeys.ACTIVITY_PROFILE])
            it[PrefKeys.TURN_BY_TURN_GPS_INTERVAL_SECONDS] =
                sanitizeScreenOnGpsIntervalSeconds(
                    seconds,
                    defaultTurnByTurnGpsIntervalSecondsForProfile(profile),
                )
        }
    }

    override val turnByTurnScreenOffGpsIntervalSeconds: Flow<Int> =
        context.dataStore.data.map(::sanitizeTurnByTurnScreenOffGpsIntervalSeconds)

    override suspend fun setTurnByTurnScreenOffGpsIntervalSeconds(seconds: Int) {
        context.dataStore.edit {
            it[PrefKeys.GPS_USAGE_PROFILE] = SettingsRepository.GPS_USAGE_PROFILE_CUSTOM
            val sanitized =
                sanitizeTurnByTurnScreenOffGpsIntervalSeconds(
                    seconds,
                    SettingsRepository.DEFAULT_TURN_BY_TURN_SCREEN_OFF_GPS_INTERVAL_SECONDS,
                )
            it[PrefKeys.TURN_BY_TURN_SCREEN_OFF_GPS_INTERVAL_SECONDS] = sanitized
            it[PrefKeys.TURN_BY_TURN_GPS_IN_AMBIENT_MODE] =
                sanitized != SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS
        }
    }

    override val turnByTurnBrouterGuideBackEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[PrefKeys.TURN_BY_TURN_BROUTER_GUIDE_BACK_ENABLED] ?: false }

    override suspend fun setTurnByTurnBrouterGuideBackEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PrefKeys.TURN_BY_TURN_BROUTER_GUIDE_BACK_ENABLED] = enabled }
    }

    override val turnByTurnRouteStartBehavior: Flow<String> =
        context.dataStore.data.map {
            it[PrefKeys.TURN_BY_TURN_ROUTE_START_BEHAVIOR]
                .takeIf { behavior -> behavior in allowedTurnByTurnRouteStartBehaviors }
                ?: SettingsRepository.TURN_BY_TURN_ROUTE_START_GO_TO_START
        }

    override suspend fun setTurnByTurnRouteStartBehavior(behavior: String) {
        context.dataStore.edit {
            it[PrefKeys.TURN_BY_TURN_ROUTE_START_BEHAVIOR] =
                if (behavior in allowedTurnByTurnRouteStartBehaviors) {
                    behavior
                } else {
                    SettingsRepository.TURN_BY_TURN_ROUTE_START_GO_TO_START
                }
        }
    }

    override val turnByTurnReverseSuggestionMode: Flow<String> =
        context.dataStore.data.map {
            it[PrefKeys.TURN_BY_TURN_REVERSE_SUGGESTION_MODE]
                .takeIf { mode -> mode in allowedTurnByTurnReverseSuggestionModes }
                ?: SettingsRepository.TURN_BY_TURN_REVERSE_SUGGESTION_ASK
        }

    override suspend fun setTurnByTurnReverseSuggestionMode(mode: String) {
        context.dataStore.edit {
            it[PrefKeys.TURN_BY_TURN_REVERSE_SUGGESTION_MODE] =
                if (mode in allowedTurnByTurnReverseSuggestionModes) {
                    mode
                } else {
                    SettingsRepository.TURN_BY_TURN_REVERSE_SUGGESTION_ASK
                }
        }
    }

    override val turnByTurnDashboardMetricSlots: Flow<List<String>> =
        context.dataStore.data.map {
            sanitizeTurnByTurnDashboardMetricSlots(it[PrefKeys.TURN_BY_TURN_DASHBOARD_METRIC_SLOTS])
        }

    override suspend fun setTurnByTurnDashboardMetricSlot(
        slotIndex: Int,
        metricId: String,
    ) {
        context.dataStore.edit {
            val current = sanitizeTurnByTurnDashboardMetricSlots(it[PrefKeys.TURN_BY_TURN_DASHBOARD_METRIC_SLOTS])
            if (slotIndex !in current.indices) return@edit
            val sanitizedMetric =
                metricId.takeIf { candidate -> candidate in allowedTurnByTurnDashboardMetricIds }
                    ?: SettingsRepository.TURN_BY_TURN_METRIC_REMAINING_DISTANCE
            val next = current.toMutableList().also { slots -> slots[slotIndex] = sanitizedMetric }
            it[PrefKeys.TURN_BY_TURN_DASHBOARD_METRIC_SLOTS] =
                next.joinToString(RECORDING_DASHBOARD_SLOT_SEPARATOR)
        }
    }

    override suspend fun addTurnByTurnDashboardPage() {
        context.dataStore.edit {
            val current = sanitizeTurnByTurnDashboardMetricSlots(it[PrefKeys.TURN_BY_TURN_DASHBOARD_METRIC_SLOTS])
            if (current.size >= TURN_BY_TURN_DASHBOARD_MAX_SLOT_COUNT) return@edit
            it[PrefKeys.TURN_BY_TURN_DASHBOARD_METRIC_SLOTS] =
                (current + SettingsRepository.DEFAULT_TURN_BY_TURN_DASHBOARD_NEW_PAGE_METRICS)
                    .joinToString(RECORDING_DASHBOARD_SLOT_SEPARATOR)
        }
    }

    override suspend fun deleteTurnByTurnDashboardPage(pageIndex: Int) {
        context.dataStore.edit {
            val current = sanitizeTurnByTurnDashboardMetricSlots(it[PrefKeys.TURN_BY_TURN_DASHBOARD_METRIC_SLOTS])
            val pageCount = current.size / TURN_BY_TURN_DASHBOARD_PAGE_SLOT_COUNT
            if (pageCount <= TURN_BY_TURN_DASHBOARD_MIN_PAGE_COUNT || pageIndex !in 0 until pageCount) return@edit
            val startIndex = pageIndex * TURN_BY_TURN_DASHBOARD_PAGE_SLOT_COUNT
            it[PrefKeys.TURN_BY_TURN_DASHBOARD_METRIC_SLOTS] =
                current
                    .filterIndexed { index, _ ->
                        index !in startIndex until startIndex + TURN_BY_TURN_DASHBOARD_PAGE_SLOT_COUNT
                    }.joinToString(RECORDING_DASHBOARD_SLOT_SEPARATOR)
        }
    }

    override val turnByTurnActiveTrackPath: Flow<String?> =
        context.dataStore.data.map { it[PrefKeys.TURN_BY_TURN_ACTIVE_TRACK_PATH] }

    override suspend fun setTurnByTurnActiveTrackPath(path: String?) {
        context.dataStore.edit {
            if (path.isNullOrBlank()) {
                it.remove(PrefKeys.TURN_BY_TURN_ACTIVE_TRACK_PATH)
            } else {
                it[PrefKeys.TURN_BY_TURN_ACTIVE_TRACK_PATH] = path
            }
        }
    }

    override val turnByTurnActiveTrackReversed: Flow<Boolean> =
        context.dataStore.data.map { it[PrefKeys.TURN_BY_TURN_ACTIVE_TRACK_REVERSED] ?: false }

    override suspend fun setTurnByTurnActiveTrackReversed(reversed: Boolean) {
        context.dataStore.edit {
            if (reversed) {
                it[PrefKeys.TURN_BY_TURN_ACTIVE_TRACK_REVERSED] = true
            } else {
                it.remove(PrefKeys.TURN_BY_TURN_ACTIVE_TRACK_REVERSED)
            }
        }
    }

    override val turnByTurnStartReached: Flow<Boolean> =
        context.dataStore.data.map { it[PrefKeys.TURN_BY_TURN_START_REACHED] ?: false }

    override suspend fun setTurnByTurnStartReached(reached: Boolean) {
        context.dataStore.edit {
            if (reached) {
                it[PrefKeys.TURN_BY_TURN_START_REACHED] = true
            } else {
                it.remove(PrefKeys.TURN_BY_TURN_START_REACHED)
            }
        }
    }

    override val promptForCalibration: Flow<Boolean> = context.dataStore.data.map { it[PrefKeys.PROMPT_FOR_CALIBRATION] ?: false }

    override suspend fun setPromptForCalibration(enabled: Boolean) {
        context.dataStore.edit { it[PrefKeys.PROMPT_FOR_CALIBRATION] = enabled }
    }

    override val showTimeInNavigate: Flow<Boolean> = context.dataStore.data.map { it[PrefKeys.SHOW_TIME_IN_NAVIGATE] ?: true }

    override suspend fun setShowTimeInNavigate(enabled: Boolean) {
        context.dataStore.edit { it[PrefKeys.SHOW_TIME_IN_NAVIGATE] = enabled }
    }

    override val navigateTimeFormat: Flow<String> =
        context.dataStore.data.map {
            val stored = it[PrefKeys.NAVIGATE_TIME_FORMAT]
            when {
                stored == LEGACY_TIME_FORMAT_SYSTEM -> SettingsRepository.TIME_FORMAT_24_HOUR
                stored != null && stored in allowedTimeFormats -> stored
                else -> SettingsRepository.TIME_FORMAT_24_HOUR
            }
        }

    override suspend fun setNavigateTimeFormat(format: String) {
        context.dataStore.edit {
            it[PrefKeys.NAVIGATE_TIME_FORMAT] =
                if (format in allowedTimeFormats) format else SettingsRepository.TIME_FORMAT_24_HOUR
        }
    }

    override val mapZoomButtonsMode: Flow<String> =
        context.dataStore.data.map {
            val stored = it[PrefKeys.MAP_ZOOM_BUTTONS_MODE]
            when {
                stored == LEGACY_ZOOM_BUTTONS_HIDE_MINUS -> SettingsRepository.ZOOM_BUTTONS_HIDE_PLUS
                stored != null && stored in allowedZoomButtonModes -> stored
                else -> SettingsRepository.ZOOM_BUTTONS_BOTH
            }
        }

    override suspend fun setMapZoomButtonsMode(mode: String) {
        context.dataStore.edit {
            it[PrefKeys.MAP_ZOOM_BUTTONS_MODE] =
                if (mode in allowedZoomButtonModes) mode else SettingsRepository.ZOOM_BUTTONS_BOTH
        }
    }

    override val gpsAccuracyCircleEnabled: Flow<Boolean> =
        context.dataStore.data.map {
            it[PrefKeys.GPS_ACCURACY_CIRCLE_ENABLED] ?: false
        }

    override suspend fun setGpsAccuracyCircleEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PrefKeys.GPS_ACCURACY_CIRCLE_ENABLED] = enabled }
    }

    override val mapZoomDefault: Flow<Int> = context.dataStore.data.map { it[PrefKeys.MAP_ZOOM_DEFAULT] ?: 16 }

    override suspend fun setMapZoomDefault(zoom: Int) {
        context.dataStore.edit { it[PrefKeys.MAP_ZOOM_DEFAULT] = zoom }
    }

    override val mapZoomMin: Flow<Int> = context.dataStore.data.map { it[PrefKeys.MAP_ZOOM_MIN] ?: 8 }

    override suspend fun setMapZoomMin(zoom: Int) {
        context.dataStore.edit { it[PrefKeys.MAP_ZOOM_MIN] = zoom }
    }

    override val mapZoomMax: Flow<Int> = context.dataStore.data.map { it[PrefKeys.MAP_ZOOM_MAX] ?: 20 }

    override suspend fun setMapZoomMax(zoom: Int) {
        context.dataStore.edit { it[PrefKeys.MAP_ZOOM_MAX] = zoom }
    }

    override val mapZoomDefaultScaleMeters: Flow<Int> =
        context.dataStore.data.map { prefs ->
            prefs.mapZoomScaleMeters(
                scaleKey = PrefKeys.MAP_ZOOM_DEFAULT_SCALE_METERS,
                legacyZoomKey = PrefKeys.MAP_ZOOM_DEFAULT,
                defaultScaleMeters = SettingsRepository.DEFAULT_MAP_ZOOM_DEFAULT_SCALE_METERS,
            )
        }

    override suspend fun setMapZoomDefaultScaleMeters(scaleMeters: Int) {
        context.dataStore.edit {
            it[PrefKeys.MAP_ZOOM_DEFAULT_SCALE_METERS] = sanitizeMapZoomScaleMeters(scaleMeters)
        }
    }

    override val mapZoomMinScaleMeters: Flow<Int> =
        context.dataStore.data.map { prefs ->
            prefs.mapZoomScaleMeters(
                scaleKey = PrefKeys.MAP_ZOOM_MIN_SCALE_METERS,
                legacyZoomKey = PrefKeys.MAP_ZOOM_MIN,
                defaultScaleMeters = SettingsRepository.DEFAULT_MAP_ZOOM_MIN_SCALE_METERS,
            )
        }

    override suspend fun setMapZoomMinScaleMeters(scaleMeters: Int) {
        context.dataStore.edit {
            it[PrefKeys.MAP_ZOOM_MIN_SCALE_METERS] = sanitizeMapZoomScaleMeters(scaleMeters)
        }
    }

    override val mapZoomMaxScaleMeters: Flow<Int> =
        context.dataStore.data.map { prefs ->
            prefs.mapZoomScaleMeters(
                scaleKey = PrefKeys.MAP_ZOOM_MAX_SCALE_METERS,
                legacyZoomKey = PrefKeys.MAP_ZOOM_MAX,
                defaultScaleMeters = SettingsRepository.DEFAULT_MAP_ZOOM_MAX_SCALE_METERS,
            )
        }

    override suspend fun setMapZoomMaxScaleMeters(scaleMeters: Int) {
        context.dataStore.edit {
            it[PrefKeys.MAP_ZOOM_MAX_SCALE_METERS] = sanitizeMapZoomScaleMeters(scaleMeters)
        }
    }

    override val northIndicatorMode: Flow<String> = context.dataStore.data.map { it[PrefKeys.NORTH_INDICATOR_MODE] ?: "ALWAYS" }

    override suspend fun setNorthIndicatorMode(mode: String) {
        context.dataStore.edit { it[PrefKeys.NORTH_INDICATOR_MODE] = mode }
    }

    override val northReferenceMode: Flow<String> =
        context.dataStore.data.map {
            val stored = it[PrefKeys.NORTH_REFERENCE_MODE]
            if (stored != null && stored in allowedNorthReferenceModes) {
                stored
            } else {
                SettingsRepository.NORTH_REFERENCE_TRUE
            }
        }

    override suspend fun setNorthReferenceMode(mode: String) {
        context.dataStore.edit {
            it[PrefKeys.NORTH_REFERENCE_MODE] =
                if (mode in allowedNorthReferenceModes) mode else SettingsRepository.NORTH_REFERENCE_TRUE
        }
    }

    override val compassSettingsMode: Flow<String> =
        context.dataStore.data.map {
            val stored = it[PrefKeys.COMPASS_SETTINGS_MODE]
            if (stored != null && stored in allowedCompassSettingsModes) {
                stored
            } else {
                SettingsRepository.COMPASS_SETTINGS_MODE_AUTOMATIC
            }
        }

    override suspend fun setCompassSettingsMode(mode: String) {
        context.dataStore.edit {
            it[PrefKeys.COMPASS_SETTINGS_MODE] =
                if (mode in allowedCompassSettingsModes) {
                    mode
                } else {
                    SettingsRepository.COMPASS_SETTINGS_MODE_AUTOMATIC
                }
        }
    }

    override val compassProviderMode: Flow<String> =
        context.dataStore.data.map {
            val stored = it[PrefKeys.COMPASS_PROVIDER_MODE]
            if (stored != null && stored in allowedCompassProviderModes) {
                stored
            } else {
                SettingsRepository.COMPASS_PROVIDER_GOOGLE_FUSED
            }
        }

    override suspend fun setCompassProviderMode(mode: String) {
        context.dataStore.edit {
            it[PrefKeys.COMPASS_PROVIDER_MODE] =
                if (mode in allowedCompassProviderModes) {
                    mode
                } else {
                    SettingsRepository.COMPASS_PROVIDER_GOOGLE_FUSED
                }
        }
    }

    override val compassHeadingSourceMode: Flow<String> =
        context.dataStore.data.map {
            val stored = it[PrefKeys.COMPASS_HEADING_SOURCE_MODE]
            if (stored != null && stored in allowedCompassHeadingSourceModes) {
                stored
            } else {
                SettingsRepository.COMPASS_HEADING_SOURCE_AUTO
            }
        }

    override suspend fun setCompassHeadingSourceMode(mode: String) {
        context.dataStore.edit {
            it[PrefKeys.COMPASS_HEADING_SOURCE_MODE] =
                if (mode in allowedCompassHeadingSourceModes) {
                    mode
                } else {
                    SettingsRepository.COMPASS_HEADING_SOURCE_AUTO
                }
        }
    }

    override val compassConeAccuracyColorsEnabled: Flow<Boolean> =
        context.dataStore.data.map {
            it[PrefKeys.COMPASS_CONE_ACCURACY_COLORS_ENABLED] ?: true
        }

    override suspend fun setCompassConeAccuracyColorsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PrefKeys.COMPASS_CONE_ACCURACY_COLORS_ENABLED] = enabled }
    }

    override val navigationMarkerStyleInitial: String
        get() = readCachedNavigationMarkerStyle()

    override val navigationMarkerStyle: Flow<String> =
        context.dataStore.data.map {
            val stored = it[PrefKeys.NAVIGATION_MARKER_STYLE]
            val resolved =
                if (stored != null && stored in allowedMarkerStyles) {
                    stored
                } else {
                    readCachedNavigationMarkerStyle()
                }
            writeCachedNavigationMarkerStyle(resolved)
            resolved
        }

    override suspend fun setNavigationMarkerStyle(style: String) {
        val resolved = if (style in allowedMarkerStyles) style else SettingsRepository.MARKER_STYLE_DOT
        context.dataStore.edit {
            it[PrefKeys.NAVIGATION_MARKER_STYLE] = resolved
        }
        writeCachedNavigationMarkerStyle(resolved)
    }

    override val navigationMarkerAnchorMode: Flow<String> =
        context.dataStore.data.map {
            val stored = it[PrefKeys.NAVIGATION_MARKER_ANCHOR_MODE]
            if (stored != null && stored in allowedMarkerAnchorModes) {
                stored
            } else {
                SettingsRepository.NAVIGATION_MARKER_ANCHOR_CENTER
            }
        }

    override suspend fun setNavigationMarkerAnchorMode(mode: String) {
        context.dataStore.edit {
            it[PrefKeys.NAVIGATION_MARKER_ANCHOR_MODE] =
                if (mode in allowedMarkerAnchorModes) {
                    mode
                } else {
                    SettingsRepository.NAVIGATION_MARKER_ANCHOR_CENTER
                }
        }
    }

    override val liveElevation: Flow<Boolean> =
        context.dataStore.data.map {
            it[PrefKeys.LIVE_ELEVATION] ?: false
        }

    override suspend fun setLiveElevation(enabled: Boolean) {
        context.dataStore.edit { it[PrefKeys.LIVE_ELEVATION] = enabled }
    }

    override val liveDistance: Flow<Boolean> =
        context.dataStore.data.map {
            it[PrefKeys.LIVE_DISTANCE] ?: false
        }

    override suspend fun setLiveDistance(enabled: Boolean) {
        context.dataStore.edit { it[PrefKeys.LIVE_DISTANCE] = enabled }
    }

    override val offlineMode: Flow<Boolean> =
        context.dataStore.data.map {
            it[PrefKeys.OFFLINE_MODE] ?: false
        }

    override suspend fun setOfflineMode(enabled: Boolean) {
        context.dataStore.edit { it[PrefKeys.OFFLINE_MODE] = enabled }
    }

    override val demSource: Flow<DemSource> =
        context.dataStore.data.map {
            DemSource.fromId(it[PrefKeys.DEM_SOURCE])
        }

    override suspend fun setDemSource(source: DemSource) {
        context.dataStore.edit { it[PrefKeys.DEM_SOURCE] = source.id }
    }

    override val crownZoomEnabled: Flow<Boolean> = context.dataStore.data.map { it[PrefKeys.CROWN_ZOOM_ENABLED] ?: true }

    override suspend fun setCrownZoomEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PrefKeys.CROWN_ZOOM_ENABLED] = enabled }
    }

    override val crownZoomInverted: Flow<Boolean> = context.dataStore.data.map { it[PrefKeys.CROWN_ZOOM_INVERTED] ?: true }

    override suspend fun setCrownZoomInverted(inverted: Boolean) {
        context.dataStore.edit { it[PrefKeys.CROWN_ZOOM_INVERTED] = inverted }
    }

    override val gpxTrackColor: Flow<Int> = context.dataStore.data.map { it[PrefKeys.GPX_TRACK_COLOR] ?: ContextCompat.getColor(context, R.color.default_gpx_track) }

    override suspend fun setGpxTrackColor(color: Int) {
        context.dataStore.edit { it[PrefKeys.GPX_TRACK_COLOR] = color }
    }

    override val gpxTrackColorMode: Flow<String> =
        context.dataStore.data.map {
            val stored = it[PrefKeys.GPX_TRACK_COLOR_MODE]
            if (stored != null && stored in allowedGpxTrackColorModes) {
                stored
            } else {
                SettingsRepository.DEFAULT_GPX_TRACK_COLOR_MODE
            }
        }

    override suspend fun setGpxTrackColorMode(mode: String) {
        context.dataStore.edit {
            val sanitizedMode =
                if (mode in allowedGpxTrackColorModes) {
                    mode
                } else {
                    SettingsRepository.DEFAULT_GPX_TRACK_COLOR_MODE
                }
            it[PrefKeys.GPX_TRACK_COLOR_MODE] = sanitizedMode
            it[PrefKeys.GPX_TRACK_OPACITY_PERCENT] = defaultGpxTrackOpacityPercentFor(sanitizedMode)
        }
    }

    override val gpxTrackWidth: Flow<Float> = context.dataStore.data.map { it[PrefKeys.GPX_TRACK_WIDTH] ?: 8f }

    override suspend fun setGpxTrackWidth(width: Float) {
        context.dataStore.edit { it[PrefKeys.GPX_TRACK_WIDTH] = width }
    }

    override val gpxTrackOpacityPercent: Flow<Int> =
        context.dataStore.data.map {
            sanitizeGpxTrackOpacityPercent(
                it[PrefKeys.GPX_TRACK_OPACITY_PERCENT]
                    ?: SettingsRepository.DEFAULT_GPX_TRACK_OPACITY_PERCENT,
            )
        }

    override suspend fun setGpxTrackOpacityPercent(opacityPercent: Int) {
        context.dataStore.edit {
            it[PrefKeys.GPX_TRACK_OPACITY_PERCENT] = sanitizeGpxTrackOpacityPercent(opacityPercent)
        }
    }

    override val gpxTrackDirectionArrowsEnabled: Flow<Boolean> =
        context.dataStore.data.map {
            it[PrefKeys.GPX_TRACK_DIRECTION_ARROWS_ENABLED]
                ?: SettingsRepository.DEFAULT_GPX_TRACK_DIRECTION_ARROWS_ENABLED
        }

    override suspend fun setGpxTrackDirectionArrowsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PrefKeys.GPX_TRACK_DIRECTION_ARROWS_ENABLED] = enabled }
    }

    override val autoRecenterEnabled: Flow<Boolean> = context.dataStore.data.map { it[PrefKeys.AUTO_RECENTER_ENABLED] ?: false }

    override suspend fun setAutoRecenterEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PrefKeys.AUTO_RECENTER_ENABLED] = enabled }
    }

    override val autoRecenterDelay: Flow<Int> = context.dataStore.data.map { it[PrefKeys.AUTO_RECENTER_DELAY] ?: 5 }

    override suspend fun setAutoRecenterDelay(delay: Int) {
        context.dataStore.edit { it[PrefKeys.AUTO_RECENTER_DELAY] = delay }
    }

    override val selectedMapPath: Flow<String?> = context.dataStore.data.map { it[PrefKeys.SELECTED_MAP_PATH] }

    override suspend fun setSelectedMapPath(path: String?) {
        if (path != null) {
            context.dataStore.edit { it[PrefKeys.SELECTED_MAP_PATH] = path }
        } else {
            context.dataStore.edit { it.remove(PrefKeys.SELECTED_MAP_PATH) }
        }
    }

    override val keepAppOpen: Flow<Boolean> = context.dataStore.data.map { it[PrefKeys.KEEP_APP_OPEN] ?: false }

    override suspend fun setKeepAppOpen(enabled: Boolean) {
        context.dataStore.edit { it[PrefKeys.KEEP_APP_OPEN] = enabled }
    }

    override val keepAppOpenTipShown: Flow<Boolean> =
        context.dataStore.data.map {
            it[PrefKeys.KEEP_APP_OPEN_TIP_SHOWN] ?: false
        }

    override suspend fun setKeepAppOpenTipShown(shown: Boolean) {
        context.dataStore.edit { it[PrefKeys.KEEP_APP_OPEN_TIP_SHOWN] = shown }
    }

    override val compassMode: Flow<Boolean> = context.dataStore.data.map { it[PrefKeys.COMPASS_MODE] ?: true }

    override suspend fun setCompassMode(isCompassMode: Boolean) {
        context.dataStore.edit { it[PrefKeys.COMPASS_MODE] = isCompassMode }
    }

    override val gpxLastVisitedListPage: Flow<String> =
        context.dataStore.data.map { preferences ->
            preferences[PrefKeys.GPX_LAST_VISITED_LIST_PAGE]
                .takeIf { it in allowedGpxListPages }
                ?: SettingsRepository.DEFAULT_GPX_LIST_PAGE
        }

    override suspend fun setGpxLastVisitedListPage(page: String) {
        context.dataStore.edit { preferences ->
            preferences[PrefKeys.GPX_LAST_VISITED_LIST_PAGE] =
                page.takeIf { it in allowedGpxListPages }
                    ?: SettingsRepository.DEFAULT_GPX_LIST_PAGE
        }
    }

    override val isGpxInspectionEnabled: Flow<Boolean> = context.dataStore.data.map { it[PrefKeys.GPX_INSPECTION_ENABLED] ?: true }

    override suspend fun setGpxInspectionEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PrefKeys.GPX_INSPECTION_ENABLED] = enabled }
    }

    override val gpxFlatSpeedMps: Flow<Float> =
        context.dataStore.data.map {
            (
                it[PrefKeys.GPX_FLAT_SPEED_MPS]
                    ?: defaultGpxFlatSpeedMpsForProfile(
                        sanitizeActivityProfile(it[PrefKeys.ACTIVITY_PROFILE]),
                    )
            ).coerceIn(0f, SettingsRepository.MAX_GPX_FLAT_SPEED_MPS)
        }

    override suspend fun setGpxFlatSpeedMps(speedMps: Float) {
        context.dataStore.edit {
            it[PrefKeys.GPX_FLAT_SPEED_MPS] =
                speedMps.coerceIn(0f, SettingsRepository.MAX_GPX_FLAT_SPEED_MPS)
        }
    }

    override val gpxAdvancedEtaEnabled: Flow<Boolean> =
        context.dataStore.data.map {
            it[PrefKeys.GPX_ADVANCED_ETA_ENABLED] ?: SettingsRepository.DEFAULT_GPX_ADVANCED_ETA_ENABLED
        }

    override suspend fun setGpxAdvancedEtaEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PrefKeys.GPX_ADVANCED_ETA_ENABLED] = enabled }
    }

    override val gpxStaminaAdjustmentEnabled: Flow<Boolean> =
        context.dataStore.data.map {
            it[PrefKeys.GPX_STAMINA_ADJUSTMENT_ENABLED]
                ?: SettingsRepository.DEFAULT_GPX_STAMINA_ADJUSTMENT_ENABLED
        }

    override suspend fun setGpxStaminaAdjustmentEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PrefKeys.GPX_STAMINA_ADJUSTMENT_ENABLED] = enabled }
    }

    override val gpxUphillVerticalMetersPerHour: Flow<Float> =
        context.dataStore.data.map {
            sanitizeGpxUphillVerticalMetersPerHour(
                it[PrefKeys.GPX_UPHILL_VERTICAL_METERS_PER_HOUR]
                    ?: SettingsRepository.DEFAULT_GPX_UPHILL_VERTICAL_METERS_PER_HOUR,
            )
        }

    override suspend fun setGpxUphillVerticalMetersPerHour(metersPerHour: Float) {
        context.dataStore.edit {
            it[PrefKeys.GPX_UPHILL_VERTICAL_METERS_PER_HOUR] =
                sanitizeGpxUphillVerticalMetersPerHour(metersPerHour)
        }
    }

    override val gpxDownhillVerticalMetersPerHour: Flow<Float> =
        context.dataStore.data.map {
            sanitizeGpxDownhillVerticalMetersPerHour(
                it[PrefKeys.GPX_DOWNHILL_VERTICAL_METERS_PER_HOUR]
                    ?: SettingsRepository.DEFAULT_GPX_DOWNHILL_VERTICAL_METERS_PER_HOUR,
            )
        }

    override suspend fun setGpxDownhillVerticalMetersPerHour(metersPerHour: Float) {
        context.dataStore.edit {
            it[PrefKeys.GPX_DOWNHILL_VERTICAL_METERS_PER_HOUR] =
                sanitizeGpxDownhillVerticalMetersPerHour(metersPerHour)
        }
    }

    override val gpxElevationSmoothingDistanceMeters: Flow<Float> =
        context.dataStore.data.map {
            GpxElevationFilterDefaults.sanitizeSmoothingDistanceMeters(
                it[PrefKeys.GPX_ELEVATION_SMOOTHING_DISTANCE_METERS]
                    ?: SettingsRepository.DEFAULT_GPX_ELEVATION_SMOOTHING_DISTANCE_METERS,
            )
        }

    override suspend fun setGpxElevationSmoothingDistanceMeters(distanceMeters: Float) {
        context.dataStore.edit {
            it[PrefKeys.GPX_ELEVATION_SMOOTHING_DISTANCE_METERS] =
                GpxElevationFilterDefaults.sanitizeSmoothingDistanceMeters(distanceMeters)
        }
    }

    override val gpxElevationNeutralDiffThresholdMeters: Flow<Float> =
        context.dataStore.data.map {
            GpxElevationFilterDefaults.sanitizeNeutralDiffThresholdMeters(
                it[PrefKeys.GPX_ELEVATION_NEUTRAL_DIFF_THRESHOLD_METERS]
                    ?: SettingsRepository.DEFAULT_GPX_ELEVATION_NEUTRAL_DIFF_THRESHOLD_METERS,
            )
        }

    override suspend fun setGpxElevationNeutralDiffThresholdMeters(thresholdMeters: Float) {
        context.dataStore.edit {
            it[PrefKeys.GPX_ELEVATION_NEUTRAL_DIFF_THRESHOLD_METERS] =
                GpxElevationFilterDefaults.sanitizeNeutralDiffThresholdMeters(thresholdMeters)
        }
    }

    override val gpxElevationTrendActivationThresholdMeters: Flow<Float> =
        context.dataStore.data.map {
            GpxElevationFilterDefaults.sanitizeTrendActivationThresholdMeters(
                it[PrefKeys.GPX_ELEVATION_TREND_ACTIVATION_THRESHOLD_METERS]
                    ?: SettingsRepository.DEFAULT_GPX_ELEVATION_TREND_ACTIVATION_THRESHOLD_METERS,
            )
        }

    override suspend fun setGpxElevationTrendActivationThresholdMeters(thresholdMeters: Float) {
        context.dataStore.edit {
            it[PrefKeys.GPX_ELEVATION_TREND_ACTIVATION_THRESHOLD_METERS] =
                GpxElevationFilterDefaults.sanitizeTrendActivationThresholdMeters(thresholdMeters)
        }
    }

    override val gpxElevationAutoAdjustPerGpx: Flow<Boolean> =
        context.dataStore.data.map {
            it[PrefKeys.GPX_ELEVATION_AUTO_ADJUST_PER_GPX]
                ?: SettingsRepository.DEFAULT_GPX_ELEVATION_AUTO_ADJUST_PER_GPX
        }

    override suspend fun setGpxElevationAutoAdjustPerGpx(enabled: Boolean) {
        context.dataStore.edit { it[PrefKeys.GPX_ELEVATION_AUTO_ADJUST_PER_GPX] = enabled }
    }

    override val gpxToolRouteStyle: Flow<String> =
        context.dataStore.data.map { preferences ->
            val profile = sanitizeActivityProfile(preferences[PrefKeys.ACTIVITY_PROFILE])
            sanitizeGpxToolRouteStyleForProfile(
                profile = profile,
                style =
                    preferences[gpxToolRouteStyleKeyForProfile(profile)]
                        ?: preferences[PrefKeys.GPX_TOOL_ROUTE_STYLE],
            )
        }

    override suspend fun setGpxToolRouteStyle(style: String) {
        context.dataStore.edit { preferences ->
            val profile = sanitizeActivityProfile(preferences[PrefKeys.ACTIVITY_PROFILE])
            preferences[gpxToolRouteStyleKeyForProfile(profile)] =
                sanitizeGpxToolRouteStyleForProfile(profile, style)
        }
    }

    override val gpxToolUseElevation: Flow<Boolean> =
        context.dataStore.data.map {
            it[PrefKeys.GPX_TOOL_USE_ELEVATION] ?: SettingsRepository.DEFAULT_GPX_TOOL_USE_ELEVATION
        }

    override suspend fun setGpxToolUseElevation(enabled: Boolean) {
        context.dataStore.edit { it[PrefKeys.GPX_TOOL_USE_ELEVATION] = enabled }
    }

    override val gpxToolAllowFerries: Flow<Boolean> =
        context.dataStore.data.map {
            it[PrefKeys.GPX_TOOL_ALLOW_FERRIES] ?: SettingsRepository.DEFAULT_GPX_TOOL_ALLOW_FERRIES
        }

    override suspend fun setGpxToolAllowFerries(enabled: Boolean) {
        context.dataStore.edit { it[PrefKeys.GPX_TOOL_ALLOW_FERRIES] = enabled }
    }

    override val gpxToolHikeHikingRoutesPreference: Flow<Float> =
        context.dataStore.data.map {
            sanitizeGpxToolHikeHikingRoutesPreference(
                it[PrefKeys.GPX_TOOL_HIKE_HIKING_ROUTES_PREFERENCE],
            )
        }

    override suspend fun setGpxToolHikeHikingRoutesPreference(preference: Float) {
        context.dataStore.edit {
            it[PrefKeys.GPX_TOOL_HIKE_HIKING_ROUTES_PREFERENCE] =
                sanitizeGpxToolHikeHikingRoutesPreference(preference)
            it[PrefKeys.GPX_TOOL_HIKE_ROUTE_STYLE] = SettingsRepository.GPX_TOOL_ROUTE_STYLE_CUSTOM_HIKE
        }
    }

    override val gpxToolHikePathPreference: Flow<Float> =
        context.dataStore.data.map {
            sanitizeGpxToolHikePathPreference(
                it[PrefKeys.GPX_TOOL_HIKE_PATH_PREFERENCE],
            )
        }

    override suspend fun setGpxToolHikePathPreference(preference: Float) {
        context.dataStore.edit {
            it[PrefKeys.GPX_TOOL_HIKE_PATH_PREFERENCE] = sanitizeGpxToolHikePathPreference(preference)
            it[PrefKeys.GPX_TOOL_HIKE_ROUTE_STYLE] = SettingsRepository.GPX_TOOL_ROUTE_STYLE_CUSTOM_HIKE
        }
    }

    override val gpxToolHikeSacScaleLimit: Flow<Int> =
        context.dataStore.data.map {
            sanitizeGpxToolHikeSacScale(
                it[PrefKeys.GPX_TOOL_HIKE_SAC_SCALE_LIMIT],
                SettingsRepository.DEFAULT_GPX_TOOL_HIKE_SAC_SCALE_LIMIT,
            )
        }

    override suspend fun setGpxToolHikeSacScaleLimit(scale: Int) {
        context.dataStore.edit {
            it[PrefKeys.GPX_TOOL_HIKE_SAC_SCALE_LIMIT] =
                sanitizeGpxToolHikeSacScale(scale, SettingsRepository.DEFAULT_GPX_TOOL_HIKE_SAC_SCALE_LIMIT)
            it[PrefKeys.GPX_TOOL_HIKE_ROUTE_STYLE] = SettingsRepository.GPX_TOOL_ROUTE_STYLE_CUSTOM_HIKE
        }
    }

    override val gpxToolHikeSacScalePreferred: Flow<Int> =
        context.dataStore.data.map {
            sanitizeGpxToolHikeSacScale(
                it[PrefKeys.GPX_TOOL_HIKE_SAC_SCALE_PREFERRED],
                SettingsRepository.DEFAULT_GPX_TOOL_HIKE_SAC_SCALE_PREFERRED,
            )
        }

    override suspend fun setGpxToolHikeSacScalePreferred(scale: Int) {
        context.dataStore.edit {
            it[PrefKeys.GPX_TOOL_HIKE_SAC_SCALE_PREFERRED] =
                sanitizeGpxToolHikeSacScale(scale, SettingsRepository.DEFAULT_GPX_TOOL_HIKE_SAC_SCALE_PREFERRED)
            it[PrefKeys.GPX_TOOL_HIKE_ROUTE_STYLE] = SettingsRepository.GPX_TOOL_ROUTE_STYLE_CUSTOM_HIKE
        }
    }

    override val gpxToolHikeConsiderForest: Flow<Boolean> =
        context.dataStore.data.map {
            it[PrefKeys.GPX_TOOL_HIKE_CONSIDER_FOREST]
                ?: SettingsRepository.DEFAULT_GPX_TOOL_HIKE_CONSIDER_FOREST
        }

    override suspend fun setGpxToolHikeConsiderForest(enabled: Boolean) {
        context.dataStore.edit {
            it[PrefKeys.GPX_TOOL_HIKE_CONSIDER_FOREST] = enabled
        }
    }

    override suspend fun setGpxToolCustomHikeProfile(
        hikingRoutesPreference: Float,
        pathPreference: Float,
        sacScaleLimit: Int,
        sacScalePreferred: Int,
        considerForest: Boolean,
    ) {
        context.dataStore.edit {
            it[PrefKeys.GPX_TOOL_HIKE_HIKING_ROUTES_PREFERENCE] =
                sanitizeGpxToolHikeHikingRoutesPreference(hikingRoutesPreference)
            it[PrefKeys.GPX_TOOL_HIKE_PATH_PREFERENCE] = sanitizeGpxToolHikePathPreference(pathPreference)
            it[PrefKeys.GPX_TOOL_HIKE_SAC_SCALE_LIMIT] =
                sanitizeGpxToolHikeSacScale(
                    sacScaleLimit,
                    SettingsRepository.DEFAULT_GPX_TOOL_HIKE_SAC_SCALE_LIMIT,
                )
            it[PrefKeys.GPX_TOOL_HIKE_SAC_SCALE_PREFERRED] =
                sanitizeGpxToolHikeSacScale(
                    sacScalePreferred,
                    SettingsRepository.DEFAULT_GPX_TOOL_HIKE_SAC_SCALE_PREFERRED,
                )
            it[PrefKeys.GPX_TOOL_HIKE_CONSIDER_FOREST] = considerForest
            it[PrefKeys.GPX_TOOL_HIKE_ROUTE_STYLE] = SettingsRepository.GPX_TOOL_ROUTE_STYLE_CUSTOM_HIKE
        }
    }

    override val isMetric: Flow<Boolean> = context.dataStore.data.map { it[PrefKeys.IS_METRIC] ?: true }

    override suspend fun setMetric(isMetric: Boolean) {
        context.dataStore.edit { it[PrefKeys.IS_METRIC] = isMetric }
    }

    override val backButtonExitsNavigation: Flow<Boolean> =
        context.dataStore.data.map { it[PrefKeys.BACK_BUTTON_EXITS_NAVIGATION] ?: false }

    override suspend fun setBackButtonExitsNavigation(enabled: Boolean) {
        context.dataStore.edit { it[PrefKeys.BACK_BUTTON_EXITS_NAVIGATION] = enabled }
    }

    override val poiIconSizePx: Flow<Int> =
        context.dataStore.data.map {
            sanitizePoiIconSizePx(it[PrefKeys.POI_ICON_SIZE_PX])
        }

    override suspend fun setPoiIconSizePx(sizePx: Int) {
        context.dataStore.edit {
            it[PrefKeys.POI_ICON_SIZE_PX] =
                sanitizePoiIconSizePx(sizePx)
        }
    }

    override val poiMarkerStyle: Flow<String> =
        context.dataStore.data.map {
            sanitizePoiMarkerStyle(it[PrefKeys.POI_MARKER_STYLE])
        }

    override suspend fun setPoiMarkerStyle(style: String) {
        context.dataStore.edit {
            it[PrefKeys.POI_MARKER_STYLE] = sanitizePoiMarkerStyle(style)
        }
    }

    override val poiTapToCenterEnabled: Flow<Boolean> =
        context.dataStore.data.map {
            it[PrefKeys.POI_TAP_TO_CENTER_ENABLED] ?: true
        }

    override suspend fun setPoiTapToCenterEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PrefKeys.POI_TAP_TO_CENTER_ENABLED] = enabled }
    }

    override val linkGpxWaypointPoiFolders: Flow<Boolean> =
        context.dataStore.data.map {
            it[PrefKeys.LINK_GPX_WAYPOINT_POI_FOLDERS]
                ?: SettingsRepository.DEFAULT_LINK_GPX_WAYPOINT_POI_FOLDERS
        }

    override suspend fun setLinkGpxWaypointPoiFolders(enabled: Boolean) {
        context.dataStore.edit { it[PrefKeys.LINK_GPX_WAYPOINT_POI_FOLDERS] = enabled }
    }

    override val poiPopupTimeoutSeconds: Flow<Int> =
        context.dataStore.data.map {
            val stored =
                it[PrefKeys.POI_POPUP_TIMEOUT_SECONDS]
                    ?: SettingsRepository.POI_POPUP_TIMEOUT_DEFAULT_SECONDS
            sanitizePoiPopupTimeoutSeconds(stored)
        }

    override suspend fun setPoiPopupTimeoutSeconds(seconds: Int) {
        context.dataStore.edit {
            it[PrefKeys.POI_POPUP_TIMEOUT_SECONDS] = sanitizePoiPopupTimeoutSeconds(seconds)
        }
    }

    override val poiPopupManualCloseOnly: Flow<Boolean> =
        context.dataStore.data.map {
            it[PrefKeys.POI_POPUP_MANUAL_CLOSE_ONLY] ?: false
        }

    override suspend fun setPoiPopupManualCloseOnly(enabled: Boolean) {
        context.dataStore.edit { it[PrefKeys.POI_POPUP_MANUAL_CLOSE_ONLY] = enabled }
    }

    override suspend fun resetToDefaults() {
        context.dataStore.edit { prefs ->
            prefs.clear()
        }
        markerStyleCachePrefs.edit().remove(CACHE_KEY_NAVIGATION_MARKER_STYLE).apply()
        mapsHelpPrefs.edit().clear().apply()
        debugHelpPrefs.edit().clear().apply()
        gpxHelpPrefs.edit().clear().apply()
        poiHelpPrefs.edit().clear().apply()
        downloadInfoPrefs.edit().clear().apply()
    }

    private fun Preferences.mapZoomScaleMeters(
        scaleKey: Preferences.Key<Int>,
        legacyZoomKey: Preferences.Key<Int>,
        defaultScaleMeters: Int,
    ): Int {
        val storedScaleMeters = this[scaleKey]
        val legacyZoom = this[legacyZoomKey]
        val scaleMeters =
            when {
                storedScaleMeters != null -> storedScaleMeters
                legacyZoom != null -> legacyZoomScaleMeters(legacyZoom)
                else -> defaultScaleMeters
            }
        return sanitizeMapZoomScaleMeters(scaleMeters)
    }

    companion object {
        private val allowedTimeFormats =
            setOf(
                SettingsRepository.TIME_FORMAT_24_HOUR,
                SettingsRepository.TIME_FORMAT_12_HOUR,
            )
        private const val LEGACY_TIME_FORMAT_SYSTEM = "SYSTEM"
        private val allowedZoomButtonModes =
            setOf(
                SettingsRepository.ZOOM_BUTTONS_BOTH,
                SettingsRepository.ZOOM_BUTTONS_HIDE_BOTH,
                SettingsRepository.ZOOM_BUTTONS_HIDE_PLUS,
            )
        private val allowedMarkerStyles =
            setOf(
                SettingsRepository.MARKER_STYLE_DOT,
                SettingsRepository.MARKER_STYLE_TRIANGLE,
            )
        private val allowedMarkerAnchorModes =
            setOf(
                SettingsRepository.NAVIGATION_MARKER_ANCHOR_CENTER,
                SettingsRepository.NAVIGATION_MARKER_ANCHOR_LOWER,
            )
        private val allowedGpxListPages =
            setOf(
                SettingsRepository.GPX_LIST_PAGE_TRACKS,
                SettingsRepository.GPX_LIST_PAGE_HIKE_ACTIVITIES,
                SettingsRepository.GPX_LIST_PAGE_BIKE_ACTIVITIES,
            )
        private val allowedNorthReferenceModes =
            setOf(
                SettingsRepository.NORTH_REFERENCE_TRUE,
                SettingsRepository.NORTH_REFERENCE_MAGNETIC,
            )
        private val allowedCompassSettingsModes =
            setOf(
                SettingsRepository.COMPASS_SETTINGS_MODE_AUTOMATIC,
                SettingsRepository.COMPASS_SETTINGS_MODE_ADVANCED,
            )
        private val allowedCompassProviderModes =
            setOf(
                SettingsRepository.COMPASS_PROVIDER_GOOGLE_FUSED,
                SettingsRepository.COMPASS_PROVIDER_SENSOR_MANAGER,
            )
        private val allowedCompassHeadingSourceModes =
            setOf(
                SettingsRepository.COMPASS_HEADING_SOURCE_AUTO,
                SettingsRepository.COMPASS_HEADING_SOURCE_TYPE_HEADING,
                SettingsRepository.COMPASS_HEADING_SOURCE_ROTATION_VECTOR,
                SettingsRepository.COMPASS_HEADING_SOURCE_MAGNETOMETER,
            )
        private val allowedTurnByTurnTurnAlertModes =
            setOf(
                SettingsRepository.TURN_BY_TURN_TURN_ALERTS_OFF,
                SettingsRepository.TURN_BY_TURN_TURN_ALERTS_IMPORTANT,
                SettingsRepository.TURN_BY_TURN_TURN_ALERTS_ALL,
            )
        private val allowedTurnByTurnOffRouteThresholdMeters =
            setOf(20, 40, 60, 80, 100)
        private val allowedTurnByTurnOffRouteRepeatSeconds =
            setOf(30, 60, 120)
        private val allowedTurnByTurnRouteStartBehaviors =
            setOf(
                SettingsRepository.TURN_BY_TURN_ROUTE_START_GO_TO_START,
                SettingsRepository.TURN_BY_TURN_ROUTE_START_NEAREST_POINT,
                SettingsRepository.TURN_BY_TURN_ROUTE_START_ASK,
            )
        private val allowedTurnByTurnReverseSuggestionModes =
            setOf(
                SettingsRepository.TURN_BY_TURN_REVERSE_SUGGESTION_ASK,
                SettingsRepository.TURN_BY_TURN_REVERSE_SUGGESTION_NEVER,
            )
        private val allowedActivityProfiles =
            setOf(
                SettingsRepository.ACTIVITY_PROFILE_HIKE,
                SettingsRepository.ACTIVITY_PROFILE_WALK_HIKE,
                SettingsRepository.ACTIVITY_PROFILE_BIKE,
            )
        private val LEGACY_RECORDING_DASHBOARD_ALL_METRICS =
            listOf(
                SettingsRepository.RECORDING_METRIC_DISTANCE,
                SettingsRepository.RECORDING_METRIC_ELEVATION_GAIN,
                SettingsRepository.RECORDING_METRIC_ELEVATION_LOSS,
                SettingsRepository.RECORDING_METRIC_DURATION,
                SettingsRepository.RECORDING_METRIC_CURRENT_ELEVATION,
                SettingsRepository.RECORDING_METRIC_CURRENT_SPEED,
                SettingsRepository.RECORDING_METRIC_AVERAGE_SPEED,
                SettingsRepository.RECORDING_METRIC_CURRENT_PACE,
            )
        private val LEGACY_BIKE_RECORDING_DASHBOARD_METRICS =
            listOf(
                SettingsRepository.RECORDING_METRIC_DISTANCE,
                SettingsRepository.RECORDING_METRIC_DURATION,
                SettingsRepository.RECORDING_METRIC_CURRENT_SPEED,
                SettingsRepository.RECORDING_METRIC_AVERAGE_SPEED,
                SettingsRepository.RECORDING_METRIC_HEART_RATE,
                SettingsRepository.RECORDING_METRIC_CADENCE,
                SettingsRepository.RECORDING_METRIC_POWER,
                SettingsRepository.RECORDING_METRIC_CURRENT_ELEVATION,
            )
        private val allowedTurnByTurnDashboardMetricIds =
            setOf(
                SettingsRepository.TURN_BY_TURN_METRIC_REMAINING_DISTANCE,
                SettingsRepository.TURN_BY_TURN_METRIC_REMAINING_ASCENT,
                SettingsRepository.TURN_BY_TURN_METRIC_REMAINING_DESCENT,
                SettingsRepository.TURN_BY_TURN_METRIC_ETA,
                SettingsRepository.TURN_BY_TURN_METRIC_REMAINING_TIME,
                SettingsRepository.TURN_BY_TURN_METRIC_PROGRESS,
                SettingsRepository.TURN_BY_TURN_METRIC_DISTANCE_COVERED,
                SettingsRepository.TURN_BY_TURN_METRIC_CURRENT_ALTITUDE,
            )
        private val allowedRecordingElevationSources =
            setOf(
                SettingsRepository.RECORDING_ELEVATION_SOURCE_GPS,
                SettingsRepository.RECORDING_ELEVATION_SOURCE_DEM,
                SettingsRepository.RECORDING_ELEVATION_SOURCE_AUTO,
                SettingsRepository.RECORDING_SOURCE_DISABLED,
            )
        private val allowedRecordingHeartRateSources =
            setOf(
                SettingsRepository.RECORDING_HEART_RATE_SOURCE_WATCH,
                SettingsRepository.RECORDING_HEART_RATE_SOURCE_STRAP,
                SettingsRepository.RECORDING_SOURCE_DISABLED,
            )
        private val allowedRecordingSensorSources =
            setOf(
                SettingsRepository.RECORDING_SENSOR_SOURCE_WATCH_GPS,
                SettingsRepository.RECORDING_SENSOR_SOURCE_POD,
                SettingsRepository.RECORDING_SOURCE_DISABLED,
            )
        private val allowedRecordingStepsSources =
            setOf(
                SettingsRepository.RECORDING_SENSOR_SOURCE_WATCH_GPS,
                SettingsRepository.RECORDING_SOURCE_DISABLED,
            )
        private val allowedRecordingAutoPauseModes =
            setOf(
                SettingsRepository.RECORDING_AUTO_PAUSE_OFF,
                SettingsRepository.RECORDING_AUTO_PAUSE_ALWAYS,
            )
        private val allowedRecordingTrackSmoothingModes =
            setOf(
                SettingsRepository.RECORDING_TRACK_SMOOTHING_OFF,
                SettingsRepository.RECORDING_TRACK_SMOOTHING_ADAPTIVE,
                SettingsRepository.RECORDING_TRACK_SMOOTHING_STRONG,
            )
        private val allowedRecordingProgressVibrationModes =
            setOf(
                SettingsRepository.RECORDING_PROGRESS_VIBRATION_OFF,
                SettingsRepository.RECORDING_PROGRESS_VIBRATION_DISTANCE_500_METERS,
                SettingsRepository.RECORDING_PROGRESS_VIBRATION_DISTANCE_1_KILOMETER,
                SettingsRepository.RECORDING_PROGRESS_VIBRATION_DISTANCE_2_KILOMETERS,
                SettingsRepository.RECORDING_PROGRESS_VIBRATION_DISTANCE_5_KILOMETERS,
                SettingsRepository.RECORDING_PROGRESS_VIBRATION_TIME_15_MINUTES,
                SettingsRepository.RECORDING_PROGRESS_VIBRATION_TIME_30_MINUTES,
                SettingsRepository.RECORDING_PROGRESS_VIBRATION_TIME_60_MINUTES,
            )
        private val allowedPoiIconSizesPx =
            setOf(
                SettingsRepository.POI_ICON_SIZE_SMALL_PX,
                SettingsRepository.POI_ICON_SIZE_MEDIUM_PX,
                SettingsRepository.POI_ICON_SIZE_LARGE_PX,
            )

        private fun sanitizePoiIconSizePx(sizePx: Int?): Int =
            when (sizePx) {
                18 -> SettingsRepository.POI_ICON_SIZE_SMALL_PX
                22, 24 -> SettingsRepository.POI_ICON_SIZE_MEDIUM_PX
                26 -> SettingsRepository.POI_ICON_SIZE_LARGE_PX
                in allowedPoiIconSizesPx -> sizePx ?: SettingsRepository.POI_ICON_SIZE_DEFAULT_PX
                else -> SettingsRepository.POI_ICON_SIZE_DEFAULT_PX
            }

        private val allowedPoiMarkerStyles =
            setOf(
                SettingsRepository.POI_MARKER_STYLE_BADGE,
                SettingsRepository.POI_MARKER_STYLE_THEME_ICON,
            )
        private val allowedGpxTrackColorModes =
            setOf(
                SettingsRepository.GPX_TRACK_COLOR_MODE_SOLID,
                SettingsRepository.GPX_TRACK_COLOR_MODE_ELEVATION,
            )
        private val allowedGpxToolRouteStyles =
            setOf(
                SettingsRepository.GPX_TOOL_ROUTE_STYLE_BALANCED_HIKE,
                SettingsRepository.GPX_TOOL_ROUTE_STYLE_PREFER_TRAILS,
                SettingsRepository.GPX_TOOL_ROUTE_STYLE_PREFER_EASIEST,
                SettingsRepository.GPX_TOOL_ROUTE_STYLE_CUSTOM_HIKE,
                SettingsRepository.GPX_TOOL_ROUTE_STYLE_BIKE_TOURING,
                SettingsRepository.GPX_TOOL_ROUTE_STYLE_BIKE_ROAD,
                SettingsRepository.GPX_TOOL_ROUTE_STYLE_BIKE_QUIET_ROAD,
                SettingsRepository.GPX_TOOL_ROUTE_STYLE_BIKE_GRAVEL,
                SettingsRepository.GPX_TOOL_ROUTE_STYLE_BIKE_MTB,
            )
        private val allowedHikeGpxToolRouteStyles =
            setOf(
                SettingsRepository.GPX_TOOL_ROUTE_STYLE_BALANCED_HIKE,
                SettingsRepository.GPX_TOOL_ROUTE_STYLE_PREFER_TRAILS,
                SettingsRepository.GPX_TOOL_ROUTE_STYLE_PREFER_EASIEST,
                SettingsRepository.GPX_TOOL_ROUTE_STYLE_CUSTOM_HIKE,
            )
        private val allowedBikeGpxToolRouteStyles =
            setOf(
                SettingsRepository.GPX_TOOL_ROUTE_STYLE_BIKE_TOURING,
                SettingsRepository.GPX_TOOL_ROUTE_STYLE_BIKE_ROAD,
                SettingsRepository.GPX_TOOL_ROUTE_STYLE_BIKE_QUIET_ROAD,
                SettingsRepository.GPX_TOOL_ROUTE_STYLE_BIKE_GRAVEL,
                SettingsRepository.GPX_TOOL_ROUTE_STYLE_BIKE_MTB,
            )
        private val allowedScreenOnGpsIntervalsSeconds =
            setOf(SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS) + (1..60) + setOf(90, 120)
        private val allowedScreenOffGpsIntervalsSeconds =
            allowedScreenOnGpsIntervalsSeconds + SettingsRepository.GPS_INTERVAL_SAME_AS_SCREEN_ON_SECONDS
        private val allowedTurnByTurnScreenOffGpsIntervalsSeconds =
            allowedScreenOffGpsIntervalsSeconds + SettingsRepository.GPS_INTERVAL_ADAPTIVE_SCREEN_OFF_SECONDS
        private val allowedSelectableGpsUsageProfiles =
            setOf(
                SettingsRepository.GPS_USAGE_PROFILE_BEST_TRACE,
                SettingsRepository.GPS_USAGE_PROFILE_BALANCED,
                SettingsRepository.GPS_USAGE_PROFILE_LONG_BATTERY,
            )
        private const val LEGACY_ZOOM_BUTTONS_HIDE_MINUS = "HIDE_MINUS"
        private const val CACHE_PREFS_NAME = "settings_runtime_cache"
        private const val CACHE_KEY_NAVIGATION_MARKER_STYLE = "navigation_marker_style"
        private const val MAPS_HELP_PREFS_NAME = "maps_screen_help_prefs"
        private const val DEBUG_HELP_PREFS_NAME = "debug_settings_help_prefs"
        private const val GPX_HELP_PREFS_NAME = "gpx_screen_help_prefs"
        private const val POI_HELP_PREFS_NAME = "poi_screen_help_prefs"
        private const val DOWNLOAD_INFO_PREFS_NAME = "download_screen_info_prefs"

        @Volatile
        private var INSTANCE: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepositoryImpl(context.applicationContext).also {
                    INSTANCE = it
                }
            }

        private fun sanitizePoiPopupTimeoutSeconds(seconds: Int): Int =
            seconds.coerceIn(
                SettingsRepository.POI_POPUP_TIMEOUT_MIN_SECONDS,
                SettingsRepository.POI_POPUP_TIMEOUT_MAX_SECONDS,
            )

        private fun sanitizePoiMarkerStyle(style: String?): String =
            if (style in allowedPoiMarkerStyles) {
                style.orEmpty()
            } else {
                SettingsRepository.POI_MARKER_STYLE_THEME_ICON
            }

        private fun sanitizeGpxTrackOpacityPercent(opacityPercent: Int): Int =
            opacityPercent.coerceIn(
                SettingsRepository.MIN_GPX_TRACK_OPACITY_PERCENT,
                SettingsRepository.MAX_GPX_TRACK_OPACITY_PERCENT,
            )

        private fun defaultGpxTrackOpacityPercentFor(mode: String): Int =
            when (mode) {
                SettingsRepository.GPX_TRACK_COLOR_MODE_ELEVATION ->
                    SettingsRepository.DEFAULT_GPX_ELEVATION_TRACK_OPACITY_PERCENT
                else -> SettingsRepository.DEFAULT_GPX_SOLID_TRACK_OPACITY_PERCENT
            }

        private fun sanitizeGpxUphillVerticalMetersPerHour(metersPerHour: Float): Float =
            metersPerHour.coerceIn(
                SettingsRepository.MIN_GPX_VERTICAL_METERS_PER_HOUR,
                SettingsRepository.MAX_GPX_UPHILL_VERTICAL_METERS_PER_HOUR,
            )

        private fun sanitizeGpxDownhillVerticalMetersPerHour(metersPerHour: Float): Float =
            metersPerHour.coerceIn(
                SettingsRepository.MIN_GPX_VERTICAL_METERS_PER_HOUR,
                SettingsRepository.MAX_GPX_DOWNHILL_VERTICAL_METERS_PER_HOUR,
            )

        private fun sanitizeGpxToolRouteStyle(style: String?): String =
            style
                ?.takeIf { it in allowedGpxToolRouteStyles }
                ?: SettingsRepository.DEFAULT_GPX_TOOL_ROUTE_STYLE

        private fun sanitizeGpxToolRouteStyleForProfile(
            profile: String,
            style: String?,
        ): String {
            val normalized = sanitizeGpxToolRouteStyle(style)
            return if (profile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
                normalized
                    .takeIf { it in allowedBikeGpxToolRouteStyles }
                    ?: SettingsRepository.DEFAULT_BIKE_GPX_TOOL_ROUTE_STYLE
            } else {
                normalized
                    .takeIf { it in allowedHikeGpxToolRouteStyles }
                    ?: SettingsRepository.DEFAULT_GPX_TOOL_ROUTE_STYLE
            }
        }

        private fun sanitizeGpxToolHikeHikingRoutesPreference(preference: Float?): Float =
            (preference ?: SettingsRepository.DEFAULT_GPX_TOOL_HIKE_HIKING_ROUTES_PREFERENCE)
                .coerceIn(
                    SettingsRepository.MIN_GPX_TOOL_HIKE_HIKING_ROUTES_PREFERENCE,
                    SettingsRepository.MAX_GPX_TOOL_HIKE_HIKING_ROUTES_PREFERENCE,
                )

        private fun sanitizeGpxToolHikePathPreference(preference: Float?): Float =
            (preference ?: SettingsRepository.DEFAULT_GPX_TOOL_HIKE_PATH_PREFERENCE)
                .coerceIn(
                    SettingsRepository.MIN_GPX_TOOL_HIKE_PATH_PREFERENCE,
                    SettingsRepository.MAX_GPX_TOOL_HIKE_PATH_PREFERENCE,
                )

        private fun sanitizeGpxToolHikeSacScale(
            scale: Int?,
            default: Int,
        ): Int =
            (scale ?: default)
                .coerceIn(
                    SettingsRepository.MIN_GPX_TOOL_HIKE_SAC_SCALE,
                    SettingsRepository.MAX_GPX_TOOL_HIKE_SAC_SCALE,
                )

        private fun gpxToolRouteStyleKeyForProfile(profile: String): Preferences.Key<String> =
            if (profile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
                PrefKeys.GPX_TOOL_BIKE_ROUTE_STYLE
            } else {
                PrefKeys.GPX_TOOL_HIKE_ROUTE_STYLE
            }

        private fun sanitizeActivityProfile(profile: String?): String =
            when (profile?.takeIf { it in allowedActivityProfiles }) {
                SettingsRepository.ACTIVITY_PROFILE_WALK_HIKE -> SettingsRepository.ACTIVITY_PROFILE_HIKE
                SettingsRepository.ACTIVITY_PROFILE_BIKE -> SettingsRepository.ACTIVITY_PROFILE_BIKE
                else -> SettingsRepository.DEFAULT_ACTIVITY_PROFILE
            }

        private fun recordingDashboardMetricSlotsKeyForProfile(profile: String): Preferences.Key<String> =
            if (profile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
                PrefKeys.RECORDING_BIKE_DASHBOARD_METRIC_SLOTS
            } else {
                PrefKeys.RECORDING_DASHBOARD_METRIC_SLOTS
            }

        private fun defaultGpxFlatSpeedMpsForProfile(profile: String): Float =
            when (profile) {
                SettingsRepository.ACTIVITY_PROFILE_BIKE -> SettingsRepository.DEFAULT_BIKE_GPX_FLAT_SPEED_MPS
                else -> SettingsRepository.DEFAULT_GPX_FLAT_SPEED_MPS
            }

        private fun defaultRecordingSampleIntervalSecondsForProfile(profile: String): Int =
            when (profile) {
                SettingsRepository.ACTIVITY_PROFILE_BIKE ->
                    SettingsRepository.DEFAULT_BIKE_RECORDING_SAMPLE_INTERVAL_SECONDS
                else -> SettingsRepository.DEFAULT_RECORDING_SAMPLE_INTERVAL_SECONDS
            }

        private fun defaultRecordingScreenOffSampleIntervalSecondsForProfile(profile: String): Int =
            when (profile) {
                SettingsRepository.ACTIVITY_PROFILE_BIKE ->
                    SettingsRepository.DEFAULT_BIKE_RECORDING_SCREEN_OFF_SAMPLE_INTERVAL_SECONDS
                else -> SettingsRepository.DEFAULT_RECORDING_SCREEN_OFF_SAMPLE_INTERVAL_SECONDS
            }

        private fun defaultTurnByTurnGpsIntervalSecondsForProfile(profile: String): Int =
            when (profile) {
                SettingsRepository.ACTIVITY_PROFILE_BIKE ->
                    SettingsRepository.DEFAULT_BIKE_TURN_BY_TURN_GPS_INTERVAL_SECONDS
                else -> SettingsRepository.DEFAULT_TURN_BY_TURN_GPS_INTERVAL_SECONDS
            }

        private fun gpsUsageTimingFromPreferences(
            preferences: Preferences,
            activityProfile: String,
        ): GpsUsageTiming =
            GpsUsageTiming(
                recordingScreenOnSeconds =
                    sanitizeScreenOnGpsIntervalSeconds(
                        preferences[PrefKeys.RECORDING_SAMPLE_INTERVAL_SECONDS],
                        defaultRecordingSampleIntervalSecondsForProfile(activityProfile),
                    ),
                recordingScreenOffSeconds =
                    sanitizeScreenOffGpsIntervalSeconds(
                        preferences[PrefKeys.RECORDING_SCREEN_OFF_SAMPLE_INTERVAL_SECONDS],
                        defaultRecordingScreenOffSampleIntervalSecondsForProfile(activityProfile),
                    ),
                turnByTurnScreenOnSeconds =
                    sanitizeScreenOnGpsIntervalSeconds(
                        preferences[PrefKeys.TURN_BY_TURN_GPS_INTERVAL_SECONDS],
                        defaultTurnByTurnGpsIntervalSecondsForProfile(activityProfile),
                    ),
                turnByTurnScreenOffSeconds =
                    sanitizeTurnByTurnScreenOffGpsIntervalSeconds(
                        preferences[PrefKeys.TURN_BY_TURN_SCREEN_OFF_GPS_INTERVAL_SECONDS],
                        SettingsRepository.DEFAULT_TURN_BY_TURN_SCREEN_OFF_GPS_INTERVAL_SECONDS,
                    ),
            )

        private fun MutablePreferences.applyGpsUsageTiming(
            usageProfile: String,
            activityProfile: String,
        ) {
            val timing = gpsUsageTiming(usageProfile, activityProfile)
            this[PrefKeys.GPS_USAGE_PROFILE] = usageProfile
            this[PrefKeys.RECORDING_SAMPLE_INTERVAL_SECONDS] = timing.recordingScreenOnSeconds
            this[PrefKeys.RECORDING_SCREEN_OFF_SAMPLE_INTERVAL_SECONDS] = timing.recordingScreenOffSeconds
            this[PrefKeys.TURN_BY_TURN_GPS_INTERVAL_SECONDS] = timing.turnByTurnScreenOnSeconds
            this[PrefKeys.TURN_BY_TURN_SCREEN_OFF_GPS_INTERVAL_SECONDS] = timing.turnByTurnScreenOffSeconds
            this[PrefKeys.TURN_BY_TURN_GPS_IN_AMBIENT_MODE] =
                timing.turnByTurnScreenOffSeconds != SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS
        }

        private fun sanitizeScreenOnGpsIntervalSeconds(
            seconds: Int?,
            defaultSeconds: Int,
        ): Int =
            seconds
                ?.takeIf { it in allowedScreenOnGpsIntervalsSeconds }
                ?: defaultSeconds

        private fun sanitizeScreenOffGpsIntervalSeconds(
            seconds: Int?,
            defaultSeconds: Int,
        ): Int =
            seconds
                ?.takeIf { it in allowedScreenOffGpsIntervalsSeconds }
                ?: defaultSeconds

        private fun sanitizeTurnByTurnScreenOffGpsIntervalSeconds(
            seconds: Int?,
            defaultSeconds: Int,
        ): Int =
            seconds
                ?.takeIf { it in allowedTurnByTurnScreenOffGpsIntervalsSeconds }
                ?: defaultSeconds

        private fun sanitizeRecordingAutoPauseMode(mode: String?): String =
            mode
                ?.takeIf { it in allowedRecordingAutoPauseModes }
                ?: SettingsRepository.DEFAULT_RECORDING_AUTO_PAUSE_MODE

        private fun sanitizeRecordingAutoPauseMode(
            mode: String?,
            legacyMode: String?,
            profile: String,
        ): String =
            mode
                ?.let(::sanitizeRecordingAutoPauseMode)
                ?: legacyRecordingAutoPauseModeForProfile(
                    legacyMode = legacyMode,
                    profile = profile,
                )

        private fun legacyRecordingAutoPauseModeForProfile(
            legacyMode: String?,
            profile: String,
        ): String =
            when (legacyMode) {
                SettingsRepository.RECORDING_AUTO_PAUSE_ALWAYS -> SettingsRepository.RECORDING_AUTO_PAUSE_ALWAYS
                SettingsRepository.RECORDING_AUTO_PAUSE_BIKE_ONLY ->
                    if (profile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
                        SettingsRepository.RECORDING_AUTO_PAUSE_ALWAYS
                    } else {
                        SettingsRepository.RECORDING_AUTO_PAUSE_OFF
                    }
                else -> SettingsRepository.DEFAULT_RECORDING_AUTO_PAUSE_MODE
            }

        private fun autoPauseModeKeyFor(profile: String): Preferences.Key<String> =
            if (profile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
                PrefKeys.RECORDING_AUTO_PAUSE_MODE_BIKE
            } else {
                PrefKeys.RECORDING_AUTO_PAUSE_MODE_HIKE
            }

        private fun sanitizeRecordingTrackSmoothingMode(mode: String?): String =
            mode
                ?.takeIf { it in allowedRecordingTrackSmoothingModes }
                ?: SettingsRepository.DEFAULT_RECORDING_TRACK_SMOOTHING_MODE

        private fun recordingTrackSmoothingModeKeyFor(profile: String): Preferences.Key<String> =
            if (profile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
                PrefKeys.RECORDING_TRACK_SMOOTHING_MODE_BIKE
            } else {
                PrefKeys.RECORDING_TRACK_SMOOTHING_MODE_HIKE
            }

        private fun sanitizeRecordingProgressVibrationMode(mode: String?): String =
            mode
                ?.takeIf { it in allowedRecordingProgressVibrationModes }
                ?: SettingsRepository.DEFAULT_RECORDING_PROGRESS_VIBRATION_MODE

        private fun recordingProgressVibrationSettingsFor(
            preferences: Preferences,
            profile: String,
        ): RecordingProgressVibrationSettings {
            val legacySettings =
                legacyRecordingProgressVibrationSettings(
                    preferences[recordingProgressVibrationModeKeyFor(profile)],
                )
            return RecordingProgressVibrationSettings(
                distanceEnabled =
                    preferences[recordingProgressVibrationDistanceEnabledKeyFor(profile)]
                        ?: legacySettings.distanceEnabled,
                distanceMeters =
                    sanitizeRecordingProgressVibrationDistanceMeters(
                        preferences[recordingProgressVibrationDistanceMetersKeyFor(profile)]
                            ?: legacySettings.distanceMeters,
                    ),
                timeEnabled =
                    preferences[recordingProgressVibrationTimeEnabledKeyFor(profile)]
                        ?: legacySettings.timeEnabled,
                timeMinutes =
                    sanitizeRecordingProgressVibrationTimeMinutes(
                        preferences[recordingProgressVibrationTimeMinutesKeyFor(profile)]
                            ?: legacySettings.timeMinutes,
                    ),
            )
        }

        private fun MutablePreferences.materializeRecordingProgressVibrationSettings(profile: String) {
            val settings = recordingProgressVibrationSettingsFor(this, profile)
            this[recordingProgressVibrationDistanceEnabledKeyFor(profile)] = settings.distanceEnabled
            this[recordingProgressVibrationDistanceMetersKeyFor(profile)] = settings.distanceMeters
            this[recordingProgressVibrationTimeEnabledKeyFor(profile)] = settings.timeEnabled
            this[recordingProgressVibrationTimeMinutesKeyFor(profile)] = settings.timeMinutes
        }

        private fun legacyRecordingProgressVibrationSettings(mode: String?): RecordingProgressVibrationSettings =
            when (sanitizeRecordingProgressVibrationMode(mode)) {
                SettingsRepository.RECORDING_PROGRESS_VIBRATION_DISTANCE_500_METERS ->
                    RecordingProgressVibrationSettings(distanceEnabled = true, distanceMeters = 500)
                SettingsRepository.RECORDING_PROGRESS_VIBRATION_DISTANCE_1_KILOMETER ->
                    RecordingProgressVibrationSettings(distanceEnabled = true, distanceMeters = 1_000)
                SettingsRepository.RECORDING_PROGRESS_VIBRATION_DISTANCE_2_KILOMETERS ->
                    RecordingProgressVibrationSettings(distanceEnabled = true, distanceMeters = 2_000)
                SettingsRepository.RECORDING_PROGRESS_VIBRATION_DISTANCE_5_KILOMETERS ->
                    RecordingProgressVibrationSettings(distanceEnabled = true, distanceMeters = 5_000)
                SettingsRepository.RECORDING_PROGRESS_VIBRATION_TIME_15_MINUTES ->
                    RecordingProgressVibrationSettings(timeEnabled = true, timeMinutes = 15)
                SettingsRepository.RECORDING_PROGRESS_VIBRATION_TIME_30_MINUTES ->
                    RecordingProgressVibrationSettings(timeEnabled = true, timeMinutes = 30)
                SettingsRepository.RECORDING_PROGRESS_VIBRATION_TIME_60_MINUTES ->
                    RecordingProgressVibrationSettings(timeEnabled = true, timeMinutes = 60)
                else -> RecordingProgressVibrationSettings()
            }

        private fun sanitizeRecordingProgressVibrationDistanceMeters(distanceMeters: Int?): Int =
            sanitizeRecordingProgressVibrationInterval(
                value = distanceMeters ?: SettingsRepository.DEFAULT_RECORDING_PROGRESS_VIBRATION_DISTANCE_METERS,
                min = SettingsRepository.MIN_RECORDING_PROGRESS_VIBRATION_DISTANCE_METERS,
                max = SettingsRepository.MAX_RECORDING_PROGRESS_VIBRATION_DISTANCE_METERS,
                step = SettingsRepository.RECORDING_PROGRESS_VIBRATION_DISTANCE_STEP_METERS,
            )

        private fun sanitizeRecordingProgressVibrationTimeMinutes(timeMinutes: Int?): Int =
            sanitizeRecordingProgressVibrationInterval(
                value = timeMinutes ?: SettingsRepository.DEFAULT_RECORDING_PROGRESS_VIBRATION_TIME_MINUTES,
                min = SettingsRepository.MIN_RECORDING_PROGRESS_VIBRATION_TIME_MINUTES,
                max = SettingsRepository.MAX_RECORDING_PROGRESS_VIBRATION_TIME_MINUTES,
                step = SettingsRepository.RECORDING_PROGRESS_VIBRATION_TIME_STEP_MINUTES,
            )

        private fun sanitizeRecordingProgressVibrationInterval(
            value: Int,
            min: Int,
            max: Int,
            step: Int,
        ): Int = (((value.coerceIn(min, max) - min).toFloat() / step).roundToInt() * step) + min

        private fun recordingProgressVibrationModeKeyFor(profile: String): Preferences.Key<String> =
            if (profile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
                PrefKeys.RECORDING_PROGRESS_VIBRATION_MODE_BIKE
            } else {
                PrefKeys.RECORDING_PROGRESS_VIBRATION_MODE_HIKE
            }

        private fun recordingProgressVibrationDistanceEnabledKeyFor(profile: String): Preferences.Key<Boolean> =
            if (profile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
                PrefKeys.RECORDING_PROGRESS_VIBRATION_DISTANCE_ENABLED_BIKE
            } else {
                PrefKeys.RECORDING_PROGRESS_VIBRATION_DISTANCE_ENABLED_HIKE
            }

        private fun recordingProgressVibrationDistanceMetersKeyFor(profile: String): Preferences.Key<Int> =
            if (profile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
                PrefKeys.RECORDING_PROGRESS_VIBRATION_DISTANCE_METERS_BIKE
            } else {
                PrefKeys.RECORDING_PROGRESS_VIBRATION_DISTANCE_METERS_HIKE
            }

        private fun recordingProgressVibrationTimeEnabledKeyFor(profile: String): Preferences.Key<Boolean> =
            if (profile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
                PrefKeys.RECORDING_PROGRESS_VIBRATION_TIME_ENABLED_BIKE
            } else {
                PrefKeys.RECORDING_PROGRESS_VIBRATION_TIME_ENABLED_HIKE
            }

        private fun recordingProgressVibrationTimeMinutesKeyFor(profile: String): Preferences.Key<Int> =
            if (profile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
                PrefKeys.RECORDING_PROGRESS_VIBRATION_TIME_MINUTES_BIKE
            } else {
                PrefKeys.RECORDING_PROGRESS_VIBRATION_TIME_MINUTES_HIKE
            }

        private fun sanitizeTurnByTurnScreenOffGpsIntervalSeconds(preferences: Preferences): Int {
            preferences[PrefKeys.TURN_BY_TURN_SCREEN_OFF_GPS_INTERVAL_SECONDS]
                ?.takeIf { it in allowedTurnByTurnScreenOffGpsIntervalsSeconds }
                ?.let { return it }
            preferences[PrefKeys.TURN_BY_TURN_GPS_IN_AMBIENT_MODE]?.let { legacyEnabled ->
                // The legacy boolean captured only whether GPS continued while the screen was
                // off, not the cadence. Preserve an explicit modern cadence above, but migrate
                // the old enabled default to the new adaptive policy.
                return if (legacyEnabled) {
                    SettingsRepository.GPS_INTERVAL_ADAPTIVE_SCREEN_OFF_SECONDS
                } else {
                    SettingsRepository.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS
                }
            }
            return SettingsRepository.DEFAULT_TURN_BY_TURN_SCREEN_OFF_GPS_INTERVAL_SECONDS
        }

        private fun sanitizeRecordingSensorSource(
            source: String?,
            defaultSource: String,
        ): String =
            source
                ?.takeIf { it in allowedRecordingSensorSources }
                ?: defaultSource

        private fun sanitizeDiagnosticsCaptureMode(mode: String?): String =
            mode
                ?.takeIf {
                    it == SettingsRepository.DIAGNOSTICS_CAPTURE_MODE_FULL ||
                        it == SettingsRepository.DIAGNOSTICS_CAPTURE_MODE_BATTERY
                } ?: SettingsRepository.DEFAULT_DIAGNOSTICS_CAPTURE_MODE

        private fun sanitizeRecordingStepsSource(source: String?): String =
            source
                ?.takeIf { it in allowedRecordingStepsSources }
                ?: SettingsRepository.DEFAULT_RECORDING_STEPS_SOURCE

        private fun sanitizeRecordingDashboardMetricSlots(
            raw: String?,
            profile: String,
        ): List<String> {
            val rawSlots =
                raw
                    ?.split(RECORDING_DASHBOARD_SLOT_SEPARATOR)
                    ?.map(String::trim)
                    .orEmpty()
            val defaultMetricSlots = defaultRecordingDashboardMetricSlotsForProfile(profile)
            if (
                rawSlots == LEGACY_RECORDING_DASHBOARD_ALL_METRICS ||
                rawSlots == LEGACY_BIKE_RECORDING_DASHBOARD_METRICS
            ) {
                return defaultMetricSlots
            }
            val parsed =
                rawSlots.mapIndexed { index, metricId ->
                    metricId.takeIf { isRecordingDashboardMetricAllowedForProfile(profile, it) }
                        ?: fallbackRecordingDashboardMetricForSlot(profile, index)
                }
            return normalizeRecordingDashboardMetricSlots(
                metricSlots = parsed,
                defaultMetricSlots = defaultMetricSlots,
                newPageMetricSlots = newRecordingDashboardPageMetricSlotsForProfile(profile),
            )
        }

        private fun sanitizeTurnByTurnDashboardMetricSlots(raw: String?): List<String> {
            val parsed =
                raw
                    ?.split(RECORDING_DASHBOARD_SLOT_SEPARATOR)
                    ?.mapNotNull { value ->
                        value.trim().takeIf { it in allowedTurnByTurnDashboardMetricIds }
                    }.orEmpty()
            return normalizeTurnByTurnDashboardMetricSlots(parsed)
        }

        private fun sanitizeUserWeightKg(weightKg: Float?): Float =
            weightKg
                ?.takeIf { it.isFinite() }
                ?.coerceIn(SettingsRepository.MIN_USER_WEIGHT_KG, SettingsRepository.MAX_USER_WEIGHT_KG)
                ?: SettingsRepository.DEFAULT_USER_WEIGHT_KG

        private fun sanitizeBackpackWeightKg(weightKg: Float?): Float =
            weightKg
                ?.takeIf { it.isFinite() }
                ?.coerceIn(SettingsRepository.MIN_BACKPACK_WEIGHT_KG, SettingsRepository.MAX_BACKPACK_WEIGHT_KG)
                ?: SettingsRepository.DEFAULT_BACKPACK_WEIGHT_KG

        private fun sanitizeBikeWeightKg(weightKg: Float?): Float =
            weightKg
                ?.takeIf { it.isFinite() }
                ?.coerceIn(SettingsRepository.MIN_BIKE_WEIGHT_KG, SettingsRepository.MAX_BIKE_WEIGHT_KG)
                ?: SettingsRepository.DEFAULT_BIKE_WEIGHT_KG

        private fun sanitizeCyclingWheelCircumferenceMeters(meters: Float?): Float =
            meters
                ?.takeIf { it.isFinite() }
                ?.coerceIn(
                    SettingsRepository.MIN_CYCLING_WHEEL_CIRCUMFERENCE_METERS,
                    SettingsRepository.MAX_CYCLING_WHEEL_CIRCUMFERENCE_METERS,
                )
                ?: SettingsRepository.DEFAULT_CYCLING_WHEEL_CIRCUMFERENCE_METERS

        private fun legacyZoomScaleMeters(zoom: Int): Int =
            scaleMetersForZoomLevel(
                zoom = zoom,
                viewportWidthPx = MAP_ZOOM_REPRESENTATIVE_VIEWPORT_WIDTH_PX,
                latitudeDegrees = MAP_ZOOM_REPRESENTATIVE_LATITUDE_DEGREES,
            ).roundToInt()
    }

    private fun applyProfileDefaultIfUncustomized(
        preferences: MutablePreferences,
        previousProfile: String,
        nextProfile: String,
    ) {
        val nextTurnByTurnDashboard =
            if (nextProfile == SettingsRepository.ACTIVITY_PROFILE_BIKE) {
                SettingsRepository.DEFAULT_BIKE_TURN_BY_TURN_DASHBOARD_METRICS
            } else {
                SettingsRepository.DEFAULT_TURN_BY_TURN_DASHBOARD_METRICS
            }
        val previousFlatSpeed = defaultGpxFlatSpeedMpsForProfile(previousProfile)
        val nextFlatSpeed = defaultGpxFlatSpeedMpsForProfile(nextProfile)
        val managedGpsUsageProfile =
            preferences[PrefKeys.GPS_USAGE_PROFILE]
                ?.takeIf { it in allowedSelectableGpsUsageProfiles }
                ?: inferGpsUsageProfile(
                    activityProfile = previousProfile,
                    timing = gpsUsageTimingFromPreferences(preferences, previousProfile),
                )
        val currentTurnByTurnDashboard =
            sanitizeTurnByTurnDashboardMetricSlots(preferences[PrefKeys.TURN_BY_TURN_DASHBOARD_METRIC_SLOTS])
        val currentFlatSpeed =
            preferences[PrefKeys.GPX_FLAT_SPEED_MPS]
                ?.takeIf { it.isFinite() }
                ?.coerceIn(0f, SettingsRepository.MAX_GPX_FLAT_SPEED_MPS)

        if (currentTurnByTurnDashboard.isProfileDefaultTurnByTurnDashboard()) {
            preferences[PrefKeys.TURN_BY_TURN_DASHBOARD_METRIC_SLOTS] =
                nextTurnByTurnDashboard.joinToString(RECORDING_DASHBOARD_SLOT_SEPARATOR)
        }
        if (currentFlatSpeed == null || currentFlatSpeed.isProfileManagedFlatSpeed(previousFlatSpeed, nextFlatSpeed)) {
            preferences[PrefKeys.GPX_FLAT_SPEED_MPS] = nextFlatSpeed
        }
        if (managedGpsUsageProfile in allowedSelectableGpsUsageProfiles) {
            preferences.applyGpsUsageTiming(
                usageProfile = managedGpsUsageProfile,
                activityProfile = nextProfile,
            )
        } else {
            applyCustomGpsDefaultsForProfile(preferences, nextProfile)
        }
    }

    private fun applyCustomGpsDefaultsForProfile(
        preferences: MutablePreferences,
        nextProfile: String,
    ) {
        val currentRecordingSampleInterval = preferences[PrefKeys.RECORDING_SAMPLE_INTERVAL_SECONDS]
        if (currentRecordingSampleInterval == null ||
            currentRecordingSampleInterval == SettingsRepository.DEFAULT_RECORDING_SAMPLE_INTERVAL_SECONDS ||
            currentRecordingSampleInterval == SettingsRepository.DEFAULT_BIKE_RECORDING_SAMPLE_INTERVAL_SECONDS
        ) {
            preferences[PrefKeys.RECORDING_SAMPLE_INTERVAL_SECONDS] =
                defaultRecordingSampleIntervalSecondsForProfile(nextProfile)
        }
        val currentRecordingScreenOffInterval = preferences[PrefKeys.RECORDING_SCREEN_OFF_SAMPLE_INTERVAL_SECONDS]
        val profileDefaultRecordingScreenOffIntervals =
            setOf(
                SettingsRepository.DEFAULT_RECORDING_SCREEN_OFF_SAMPLE_INTERVAL_SECONDS,
                SettingsRepository.DEFAULT_BIKE_RECORDING_SCREEN_OFF_SAMPLE_INTERVAL_SECONDS,
            )
        if (currentRecordingScreenOffInterval == null ||
            currentRecordingScreenOffInterval in profileDefaultRecordingScreenOffIntervals
        ) {
            preferences[PrefKeys.RECORDING_SCREEN_OFF_SAMPLE_INTERVAL_SECONDS] =
                defaultRecordingScreenOffSampleIntervalSecondsForProfile(nextProfile)
        }
        val currentTurnByTurnGpsInterval = preferences[PrefKeys.TURN_BY_TURN_GPS_INTERVAL_SECONDS]
        if (currentTurnByTurnGpsInterval == null ||
            currentTurnByTurnGpsInterval == SettingsRepository.DEFAULT_TURN_BY_TURN_GPS_INTERVAL_SECONDS ||
            currentTurnByTurnGpsInterval == SettingsRepository.DEFAULT_BIKE_TURN_BY_TURN_GPS_INTERVAL_SECONDS
        ) {
            preferences[PrefKeys.TURN_BY_TURN_GPS_INTERVAL_SECONDS] =
                defaultTurnByTurnGpsIntervalSecondsForProfile(nextProfile)
        }
    }

    private fun List<String>.isProfileDefaultTurnByTurnDashboard(): Boolean =
        this == SettingsRepository.DEFAULT_TURN_BY_TURN_DASHBOARD_METRICS ||
            this == SettingsRepository.DEFAULT_BIKE_TURN_BY_TURN_DASHBOARD_METRICS

    private fun Float.isProfileManagedFlatSpeed(
        previousFlatSpeed: Float,
        nextFlatSpeed: Float,
    ): Boolean =
        approximatelyEquals(previousFlatSpeed) ||
            approximatelyEquals(nextFlatSpeed) ||
            approximatelyEquals(SettingsRepository.DEFAULT_GPX_FLAT_SPEED_MPS) ||
            approximatelyEquals(SettingsRepository.DEFAULT_BIKE_GPX_FLAT_SPEED_MPS)

    private fun Float.approximatelyEquals(other: Float): Boolean = kotlin.math.abs(this - other) < 0.001f

    private fun readCachedNavigationMarkerStyle(): String {
        val cached = markerStyleCachePrefs.getString(CACHE_KEY_NAVIGATION_MARKER_STYLE, null)
        return if (cached != null && cached in allowedMarkerStyles) {
            cached
        } else {
            SettingsRepository.MARKER_STYLE_DOT
        }
    }

    private fun writeCachedNavigationMarkerStyle(style: String) {
        if (style !in allowedMarkerStyles) return
        if (markerStyleCachePrefs.getString(CACHE_KEY_NAVIGATION_MARKER_STYLE, null) == style) return
        markerStyleCachePrefs.edit().putString(CACHE_KEY_NAVIGATION_MARKER_STYLE, style).apply()
    }
}
