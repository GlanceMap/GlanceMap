package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import com.glancemap.trailcore.recording.RecordingPointSmoothingOptions
import com.glancemap.trailcore.recording.RecordingProgressVibrationSettings
import com.glancemap.trailcore.recording.RecordingSettings
import com.glancemap.trailcore.recording.recordingGpsUsageTiming
import com.glancemap.trailcore.recording.recordingTrajectoryContinuityIntervalSeconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class PhoneRecordingSettings(
    val gpsUsageProfile: String = RecordingSettings.DEFAULT_GPS_USAGE_PROFILE,
    val screenOnIntervalSeconds: Int = RecordingSettings.DEFAULT_RECORDING_SAMPLE_INTERVAL_SECONDS,
    val screenOffIntervalSeconds: Int = RecordingSettings.DEFAULT_RECORDING_SCREEN_OFF_SAMPLE_INTERVAL_SECONDS,
    val hikeAutoPauseMode: String = RecordingSettings.DEFAULT_RECORDING_AUTO_PAUSE_MODE,
    val bikeAutoPauseMode: String = RecordingSettings.DEFAULT_RECORDING_AUTO_PAUSE_MODE,
    val hikeSmoothingMode: String = RecordingSettings.DEFAULT_RECORDING_TRACK_SMOOTHING_MODE,
    val bikeSmoothingMode: String = RecordingSettings.DEFAULT_RECORDING_TRACK_SMOOTHING_MODE,
    val elevationSource: String = RecordingSettings.DEFAULT_RECORDING_ELEVATION_SOURCE,
    val progressVibrationMode: String = RecordingSettings.DEFAULT_RECORDING_PROGRESS_VIBRATION_MODE,
    val progressDistanceMeters: Int = RecordingSettings.DEFAULT_RECORDING_PROGRESS_VIBRATION_DISTANCE_METERS,
    val progressTimeMinutes: Int = RecordingSettings.DEFAULT_RECORDING_PROGRESS_VIBRATION_TIME_MINUTES,
    val showSavedActivities: Boolean = true,
) {
    val sampleIntervalSeconds: Int
        get() = screenOnIntervalSeconds.takeIf { it > 0 } ?: RecordingSettings.DEFAULT_RECORDING_SAMPLE_INTERVAL_SECONDS

    fun normalized(): PhoneRecordingSettings =
        copy(
            gpsUsageProfile = RecordingSettings.normalizeGpsUsageProfile(gpsUsageProfile),
            screenOnIntervalSeconds =
                RecordingSettings.normalizeScreenOnGpsInterval(
                    screenOnIntervalSeconds,
                    RecordingSettings.DEFAULT_RECORDING_SAMPLE_INTERVAL_SECONDS,
                ),
            screenOffIntervalSeconds =
                RecordingSettings.normalizeRecordingScreenOffGpsInterval(
                    screenOffIntervalSeconds,
                    RecordingSettings.DEFAULT_RECORDING_SCREEN_OFF_SAMPLE_INTERVAL_SECONDS,
                ),
            hikeAutoPauseMode = RecordingSettings.normalizeAutoPauseMode(hikeAutoPauseMode),
            bikeAutoPauseMode = RecordingSettings.normalizeAutoPauseMode(bikeAutoPauseMode),
            hikeSmoothingMode = RecordingSettings.normalizeTrackSmoothingMode(hikeSmoothingMode),
            bikeSmoothingMode = RecordingSettings.normalizeTrackSmoothingMode(bikeSmoothingMode),
            elevationSource = RecordingSettings.normalizeElevationSource(elevationSource),
            progressVibrationMode = RecordingSettings.normalizeProgressVibrationMode(progressVibrationMode),
            progressDistanceMeters = RecordingSettings.normalizeProgressDistanceMeters(progressDistanceMeters),
            progressTimeMinutes = RecordingSettings.normalizeProgressTimeMinutes(progressTimeMinutes),
        )

    fun autoPauseMode(profile: PhoneActivityProfile): String =
        when (profile) {
            PhoneActivityProfile.BIKE -> bikeAutoPauseMode
            else -> hikeAutoPauseMode
        }

    fun smoothingMode(profile: PhoneActivityProfile): String =
        when (profile) {
            PhoneActivityProfile.BIKE -> bikeSmoothingMode
            else -> hikeSmoothingMode
        }

    fun smoothingOptions(
        profile: PhoneActivityProfile,
        effectiveIntervalMillis: Long,
    ) = RecordingPointSmoothingOptions(
        mode = smoothingMode(profile),
        activityProfile = profile.storageValue,
        sampleIntervalSeconds = sampleIntervalSeconds,
        trajectoryContinuityIntervalSeconds =
            recordingTrajectoryContinuityIntervalSeconds(
                effectiveIntervalMillis,
                screenOnIntervalSeconds,
            ),
    )

    fun withGpsPreset(
        profile: PhoneActivityProfile,
        gpsUsageProfile: String,
    ): PhoneRecordingSettings {
        if (gpsUsageProfile ==
            RecordingSettings.GPS_USAGE_PROFILE_CUSTOM
        ) {
            return copy(gpsUsageProfile = gpsUsageProfile)
        }
        val timing = recordingGpsUsageTiming(gpsUsageProfile, profile.storageValue)
        return copy(
            gpsUsageProfile = gpsUsageProfile,
            screenOnIntervalSeconds = timing.recordingScreenOnSeconds,
            screenOffIntervalSeconds = timing.recordingScreenOffSeconds,
        )
    }

    fun progressVibrationSettings(): RecordingProgressVibrationSettings {
        val mode = RecordingSettings.normalizeProgressVibrationMode(progressVibrationMode)
        return RecordingProgressVibrationSettings(
            distanceEnabled = mode.startsWith("DISTANCE_"),
            distanceMeters = progressDistanceMeters,
            timeEnabled = mode.startsWith("TIME_"),
            timeMinutes = progressTimeMinutes,
        )
    }

    fun withProgressVibrationMode(mode: String): PhoneRecordingSettings =
        when {
            mode == RecordingSettings.RECORDING_PROGRESS_VIBRATION_DISTANCE_500_METERS ->
                copy(progressVibrationMode = mode, progressDistanceMeters = 500)
            mode == RecordingSettings.RECORDING_PROGRESS_VIBRATION_DISTANCE_1_KILOMETER ->
                copy(progressVibrationMode = mode, progressDistanceMeters = 1_000)
            mode == RecordingSettings.RECORDING_PROGRESS_VIBRATION_DISTANCE_2_KILOMETERS ->
                copy(progressVibrationMode = mode, progressDistanceMeters = 2_000)
            mode == RecordingSettings.RECORDING_PROGRESS_VIBRATION_DISTANCE_5_KILOMETERS ->
                copy(progressVibrationMode = mode, progressDistanceMeters = 5_000)
            mode == RecordingSettings.RECORDING_PROGRESS_VIBRATION_TIME_15_MINUTES ->
                copy(progressVibrationMode = mode, progressTimeMinutes = 15)
            mode == RecordingSettings.RECORDING_PROGRESS_VIBRATION_TIME_30_MINUTES ->
                copy(progressVibrationMode = mode, progressTimeMinutes = 30)
            mode == RecordingSettings.RECORDING_PROGRESS_VIBRATION_TIME_60_MINUTES ->
                copy(progressVibrationMode = mode, progressTimeMinutes = 60)
            else -> copy(progressVibrationMode = RecordingSettings.RECORDING_PROGRESS_VIBRATION_OFF)
        }
}

internal class PhoneRecordingSettingsPreferences(
    context: Context,
) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): PhoneRecordingSettings =
        PhoneRecordingSettings(
            gpsUsageProfile =
                preferences.getString(KEY_GPS_PROFILE, RecordingSettings.DEFAULT_GPS_USAGE_PROFILE)
                    ?: RecordingSettings.DEFAULT_GPS_USAGE_PROFILE,
            screenOnIntervalSeconds =
                preferences.getInt(
                    KEY_SCREEN_ON_INTERVAL,
                    RecordingSettings.DEFAULT_RECORDING_SAMPLE_INTERVAL_SECONDS,
                ),
            screenOffIntervalSeconds =
                preferences.getInt(
                    KEY_SCREEN_OFF_INTERVAL,
                    RecordingSettings.DEFAULT_RECORDING_SCREEN_OFF_SAMPLE_INTERVAL_SECONDS,
                ),
            hikeAutoPauseMode =
                preferences.getString(
                    KEY_HIKE_AUTO_PAUSE,
                    RecordingSettings.DEFAULT_RECORDING_AUTO_PAUSE_MODE,
                ) ?: RecordingSettings.DEFAULT_RECORDING_AUTO_PAUSE_MODE,
            bikeAutoPauseMode =
                preferences.getString(
                    KEY_BIKE_AUTO_PAUSE,
                    RecordingSettings.DEFAULT_RECORDING_AUTO_PAUSE_MODE,
                ) ?: RecordingSettings.DEFAULT_RECORDING_AUTO_PAUSE_MODE,
            hikeSmoothingMode =
                preferences.getString(
                    KEY_HIKE_SMOOTHING,
                    RecordingSettings.DEFAULT_RECORDING_TRACK_SMOOTHING_MODE,
                ) ?: RecordingSettings.DEFAULT_RECORDING_TRACK_SMOOTHING_MODE,
            bikeSmoothingMode =
                preferences.getString(
                    KEY_BIKE_SMOOTHING,
                    RecordingSettings.DEFAULT_RECORDING_TRACK_SMOOTHING_MODE,
                ) ?: RecordingSettings.DEFAULT_RECORDING_TRACK_SMOOTHING_MODE,
            elevationSource =
                preferences.getString(
                    KEY_ELEVATION_SOURCE,
                    RecordingSettings.DEFAULT_RECORDING_ELEVATION_SOURCE,
                ) ?: RecordingSettings.DEFAULT_RECORDING_ELEVATION_SOURCE,
            progressVibrationMode =
                preferences.getString(
                    KEY_VIBRATION_MODE,
                    RecordingSettings.DEFAULT_RECORDING_PROGRESS_VIBRATION_MODE,
                ) ?: RecordingSettings.DEFAULT_RECORDING_PROGRESS_VIBRATION_MODE,
            progressDistanceMeters =
                preferences.getInt(
                    KEY_VIBRATION_DISTANCE,
                    RecordingSettings.DEFAULT_RECORDING_PROGRESS_VIBRATION_DISTANCE_METERS,
                ),
            progressTimeMinutes =
                preferences.getInt(
                    KEY_VIBRATION_TIME,
                    RecordingSettings.DEFAULT_RECORDING_PROGRESS_VIBRATION_TIME_MINUTES,
                ),
            showSavedActivities = preferences.getBoolean(KEY_SHOW_SAVED_ACTIVITIES, true),
        ).normalized()

    fun save(settings: PhoneRecordingSettings): PhoneRecordingSettings {
        val normalized = settings.normalized()
        preferences
            .edit()
            .putString(KEY_GPS_PROFILE, normalized.gpsUsageProfile)
            .putInt(KEY_SCREEN_ON_INTERVAL, normalized.screenOnIntervalSeconds)
            .putInt(KEY_SCREEN_OFF_INTERVAL, normalized.screenOffIntervalSeconds)
            .putString(KEY_HIKE_AUTO_PAUSE, normalized.hikeAutoPauseMode)
            .putString(KEY_BIKE_AUTO_PAUSE, normalized.bikeAutoPauseMode)
            .putString(KEY_HIKE_SMOOTHING, normalized.hikeSmoothingMode)
            .putString(KEY_BIKE_SMOOTHING, normalized.bikeSmoothingMode)
            .putString(KEY_ELEVATION_SOURCE, normalized.elevationSource)
            .putString(KEY_VIBRATION_MODE, normalized.progressVibrationMode)
            .putInt(KEY_VIBRATION_DISTANCE, normalized.progressDistanceMeters)
            .putInt(KEY_VIBRATION_TIME, normalized.progressTimeMinutes)
            .putBoolean(KEY_SHOW_SAVED_ACTIVITIES, normalized.showSavedActivities)
            .apply()
        PhoneRecordingSettingsState.publish(normalized)
        return normalized
    }

    fun applyGpsPreset(
        settings: PhoneRecordingSettings,
        profile: PhoneActivityProfile,
        gpsUsageProfile: String,
    ): PhoneRecordingSettings = save(settings.withGpsPreset(profile, gpsUsageProfile))

    private companion object {
        const val PREFERENCES_NAME = "phone_recording_settings"
        const val KEY_GPS_PROFILE = "gps_usage_profile"
        const val KEY_SCREEN_ON_INTERVAL = "screen_on_interval_seconds"
        const val KEY_SCREEN_OFF_INTERVAL = "screen_off_interval_seconds"
        const val KEY_HIKE_AUTO_PAUSE = "hike_auto_pause"
        const val KEY_BIKE_AUTO_PAUSE = "bike_auto_pause"
        const val KEY_HIKE_SMOOTHING = "hike_smoothing"
        const val KEY_BIKE_SMOOTHING = "bike_smoothing"
        const val KEY_ELEVATION_SOURCE = "elevation_source"
        const val KEY_VIBRATION_MODE = "progress_vibration_mode"
        const val KEY_VIBRATION_DISTANCE = "progress_vibration_distance_meters"
        const val KEY_VIBRATION_TIME = "progress_vibration_time_minutes"
        const val KEY_SHOW_SAVED_ACTIVITIES = "show_saved_activities"
    }
}

internal object PhoneRecordingSettingsState {
    private val mutableState = MutableStateFlow(PhoneRecordingSettings())
    val state: StateFlow<PhoneRecordingSettings> = mutableState.asStateFlow()

    fun publish(settings: PhoneRecordingSettings) {
        mutableState.value = settings
    }
}
