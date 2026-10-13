package com.glancemap.trailcore.recording

import kotlin.math.abs

/** Recording identifiers and defaults shared by every platform adapter. */
object RecordingSettings {
    const val ACTIVITY_PROFILE_HIKE = "HIKE"
    const val ACTIVITY_PROFILE_WALK_HIKE = "WALK_HIKE"
    const val ACTIVITY_PROFILE_BIKE = "BIKE"
    const val DEFAULT_ACTIVITY_PROFILE = ACTIVITY_PROFILE_HIKE

    const val RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS = -1
    const val GPS_INTERVAL_ADAPTIVE_SCREEN_OFF_SECONDS = -2
    const val GPS_INTERVAL_SAME_AS_SCREEN_ON_SECONDS = 0
    const val DEFAULT_RECORDING_SAMPLE_INTERVAL_SECONDS = 3
    const val DEFAULT_BIKE_RECORDING_SAMPLE_INTERVAL_SECONDS = 1
    const val DEFAULT_RECORDING_SCREEN_OFF_SAMPLE_INTERVAL_SECONDS = 10
    const val DEFAULT_BIKE_RECORDING_SCREEN_OFF_SAMPLE_INTERVAL_SECONDS = 5

    const val GPS_USAGE_PROFILE_BEST_TRACE = "BEST_TRACE"
    const val GPS_USAGE_PROFILE_BALANCED = "BALANCED"
    const val GPS_USAGE_PROFILE_LONG_BATTERY = "LONG_BATTERY"
    const val GPS_USAGE_PROFILE_CUSTOM = "CUSTOM"
    const val DEFAULT_GPS_USAGE_PROFILE = GPS_USAGE_PROFILE_BALANCED

    const val RECORDING_AUTO_PAUSE_OFF = "OFF"
    const val RECORDING_AUTO_PAUSE_BIKE_ONLY = "BIKE_ONLY"
    const val RECORDING_AUTO_PAUSE_ALWAYS = "ALWAYS"
    const val DEFAULT_RECORDING_AUTO_PAUSE_MODE = RECORDING_AUTO_PAUSE_ALWAYS

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
    const val DEFAULT_RECORDING_PROGRESS_VIBRATION_DISTANCE_METERS = 1_000
    const val MIN_RECORDING_PROGRESS_VIBRATION_DISTANCE_METERS = 50
    const val MAX_RECORDING_PROGRESS_VIBRATION_DISTANCE_METERS = 10_000
    const val DEFAULT_RECORDING_PROGRESS_VIBRATION_TIME_MINUTES = 30
    const val MIN_RECORDING_PROGRESS_VIBRATION_TIME_MINUTES = 1
    const val MAX_RECORDING_PROGRESS_VIBRATION_TIME_MINUTES = 120

    const val RECORDING_SOURCE_DISABLED = "DISABLED"
    const val RECORDING_ELEVATION_SOURCE_GPS = "GPS"
    const val RECORDING_ELEVATION_SOURCE_DEM = "DEM"
    const val RECORDING_ELEVATION_SOURCE_AUTO = "AUTO"
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

    const val DEFAULT_USER_WEIGHT_KG = 75f
    const val MIN_USER_WEIGHT_KG = 35f
    const val MAX_USER_WEIGHT_KG = 160f
    const val DEFAULT_BACKPACK_WEIGHT_KG = 0f
    const val MIN_BACKPACK_WEIGHT_KG = 0f
    const val MAX_BACKPACK_WEIGHT_KG = 40f
    const val DEFAULT_BIKE_WEIGHT_KG = 12f
    const val MIN_BIKE_WEIGHT_KG = 5f
    const val MAX_BIKE_WEIGHT_KG = 40f

    const val AUTO_PAUSE_GPS_INTERVAL_MS = 5_000L
    const val AUTO_PAUSE_PROLONGED_GPS_INTERVAL_MS = 12_000L
    const val AUTO_PAUSE_PROLONGED_AFTER_MS = 60_000L

    const val RECORDING_WATCH_GPS_ACCURACY_FLOOR_M = 125f
    const val RECORDING_WATCH_GPS_ACCURACY_FLOOR_TOLERANCE_M = 3f
    const val RECORDING_WATCH_GPS_EFFECTIVE_ACCURACY_M = 18f
    const val RECORDING_FIX_FALLBACK_ACCURACY_M = 12.0

    private val screenOnIntervals = setOf(RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS) + (1..60) + setOf(90, 120)
    private val screenOffIntervals = screenOnIntervals + GPS_INTERVAL_SAME_AS_SCREEN_ON_SECONDS
    private val progressDistanceIntervals = (50..1_000 step 50).toList() + (1_500..10_000 step 500).toList()
    private val progressTimeIntervals = (1..10).toList() + (15..120 step 5).toList()

    val recordingProgressVibrationDistanceMetersOptions: List<Int> get() = progressDistanceIntervals
    val recordingProgressVibrationTimeMinutesOptions: List<Int> get() = progressTimeIntervals

    fun normalizeActivityProfile(profile: String?): String =
        when (profile) {
            ACTIVITY_PROFILE_BIKE -> ACTIVITY_PROFILE_BIKE
            ACTIVITY_PROFILE_WALK_HIKE,
            ACTIVITY_PROFILE_HIKE,
            -> ACTIVITY_PROFILE_HIKE
            else -> DEFAULT_ACTIVITY_PROFILE
        }

    fun normalizeGpsUsageProfile(profile: String?): String = profile?.takeIf { it in selectableGpsUsageProfiles } ?: DEFAULT_GPS_USAGE_PROFILE

    fun normalizeScreenOnGpsInterval(
        seconds: Int?,
        defaultSeconds: Int,
    ): Int = seconds?.takeIf { it in screenOnIntervals } ?: defaultSeconds

    fun normalizeRecordingScreenOffGpsInterval(
        seconds: Int?,
        defaultSeconds: Int,
    ): Int = seconds?.takeIf { it in screenOffIntervals } ?: defaultSeconds

    fun normalizeAutoPauseMode(mode: String?): String = mode?.takeIf { it in autoPauseModes } ?: DEFAULT_RECORDING_AUTO_PAUSE_MODE

    fun normalizeLegacyAutoPauseMode(
        legacyMode: String?,
        activityProfile: String,
    ): String =
        when (legacyMode) {
            RECORDING_AUTO_PAUSE_ALWAYS -> RECORDING_AUTO_PAUSE_ALWAYS
            RECORDING_AUTO_PAUSE_BIKE_ONLY ->
                if (activityProfile == ACTIVITY_PROFILE_BIKE) RECORDING_AUTO_PAUSE_ALWAYS else RECORDING_AUTO_PAUSE_OFF
            else -> DEFAULT_RECORDING_AUTO_PAUSE_MODE
        }

    fun normalizeTrackSmoothingMode(mode: String?): String = mode?.takeIf { it in trackSmoothingModes } ?: DEFAULT_RECORDING_TRACK_SMOOTHING_MODE

    fun normalizeElevationSource(source: String?): String = source?.takeIf { it in elevationSources } ?: DEFAULT_RECORDING_ELEVATION_SOURCE

    fun normalizeHeartRateSource(source: String?): String = source?.takeIf { it in heartRateSources } ?: DEFAULT_RECORDING_HEART_RATE_SOURCE

    fun normalizeSensorSource(
        source: String?,
        defaultSource: String,
    ): String = source?.takeIf { it in sensorSources } ?: defaultSource

    fun normalizeStepsSource(source: String?): String = source?.takeIf { it in stepsSources } ?: DEFAULT_RECORDING_STEPS_SOURCE

    fun normalizeProgressVibrationMode(mode: String?): String = mode?.takeIf { it in progressVibrationModes } ?: DEFAULT_RECORDING_PROGRESS_VIBRATION_MODE

    fun normalizeProgressDistanceMeters(value: Int?): Int = nearestInterval(value ?: DEFAULT_RECORDING_PROGRESS_VIBRATION_DISTANCE_METERS, progressDistanceIntervals)

    fun normalizeProgressTimeMinutes(value: Int?): Int = nearestInterval(value ?: DEFAULT_RECORDING_PROGRESS_VIBRATION_TIME_MINUTES, progressTimeIntervals)

    fun normalizeUserWeightKg(value: Float?): Float = value?.takeIf { it.isFinite() }?.coerceIn(MIN_USER_WEIGHT_KG, MAX_USER_WEIGHT_KG) ?: DEFAULT_USER_WEIGHT_KG

    fun normalizeBackpackWeightKg(value: Float?): Float =
        value?.takeIf { it.isFinite() }?.coerceIn(MIN_BACKPACK_WEIGHT_KG, MAX_BACKPACK_WEIGHT_KG)
            ?: DEFAULT_BACKPACK_WEIGHT_KG

    fun normalizeBikeWeightKg(value: Float?): Float = value?.takeIf { it.isFinite() }?.coerceIn(MIN_BIKE_WEIGHT_KG, MAX_BIKE_WEIGHT_KG) ?: DEFAULT_BIKE_WEIGHT_KG

    fun nearestProgressVibrationInterval(
        value: Int,
        options: List<Int>,
    ): Int = nearestInterval(value, options)

    private fun nearestInterval(
        value: Int,
        options: List<Int>,
    ): Int = options.minWithOrNull(compareBy<Int> { abs(it - value) }.thenBy { it }) ?: value

    private val selectableGpsUsageProfiles =
        setOf(GPS_USAGE_PROFILE_BEST_TRACE, GPS_USAGE_PROFILE_BALANCED, GPS_USAGE_PROFILE_LONG_BATTERY, GPS_USAGE_PROFILE_CUSTOM)
    private val autoPauseModes =
        setOf(RECORDING_AUTO_PAUSE_OFF, RECORDING_AUTO_PAUSE_ALWAYS)
    private val trackSmoothingModes =
        setOf(RECORDING_TRACK_SMOOTHING_OFF, RECORDING_TRACK_SMOOTHING_ADAPTIVE, RECORDING_TRACK_SMOOTHING_STRONG)
    private val elevationSources =
        setOf(RECORDING_ELEVATION_SOURCE_AUTO, RECORDING_ELEVATION_SOURCE_DEM, RECORDING_ELEVATION_SOURCE_GPS, RECORDING_SOURCE_DISABLED)
    private val heartRateSources =
        setOf(RECORDING_HEART_RATE_SOURCE_WATCH, RECORDING_HEART_RATE_SOURCE_STRAP, RECORDING_SOURCE_DISABLED)
    private val sensorSources =
        setOf(RECORDING_SENSOR_SOURCE_WATCH_GPS, RECORDING_SENSOR_SOURCE_POD, RECORDING_SOURCE_DISABLED)
    private val stepsSources = setOf(RECORDING_SENSOR_SOURCE_WATCH_GPS, RECORDING_SOURCE_DISABLED)
    private val progressVibrationModes =
        setOf(
            RECORDING_PROGRESS_VIBRATION_OFF,
            RECORDING_PROGRESS_VIBRATION_DISTANCE_500_METERS,
            RECORDING_PROGRESS_VIBRATION_DISTANCE_1_KILOMETER,
            RECORDING_PROGRESS_VIBRATION_DISTANCE_2_KILOMETERS,
            RECORDING_PROGRESS_VIBRATION_DISTANCE_5_KILOMETERS,
            RECORDING_PROGRESS_VIBRATION_TIME_15_MINUTES,
            RECORDING_PROGRESS_VIBRATION_TIME_30_MINUTES,
            RECORDING_PROGRESS_VIBRATION_TIME_60_MINUTES,
        )
}

data class RecordingProgressVibrationSettings(
    val distanceEnabled: Boolean = false,
    val distanceMeters: Int = RecordingSettings.DEFAULT_RECORDING_PROGRESS_VIBRATION_DISTANCE_METERS,
    val timeEnabled: Boolean = false,
    val timeMinutes: Int = RecordingSettings.DEFAULT_RECORDING_PROGRESS_VIBRATION_TIME_MINUTES,
)

data class RecordingGpsUsageTiming(
    val recordingScreenOnSeconds: Int,
    val recordingScreenOffSeconds: Int,
    val turnByTurnScreenOnSeconds: Int,
    val turnByTurnScreenOffSeconds: Int,
)

fun recordingGpsUsageTiming(
    usageProfile: String,
    activityProfile: String,
): RecordingGpsUsageTiming {
    val bike = activityProfile == RecordingSettings.ACTIVITY_PROFILE_BIKE
    return when (usageProfile) {
        RecordingSettings.GPS_USAGE_PROFILE_BEST_TRACE ->
            RecordingGpsUsageTiming(
                recordingScreenOnSeconds = 1,
                recordingScreenOffSeconds = 3,
                turnByTurnScreenOnSeconds = 1,
                turnByTurnScreenOffSeconds = RecordingSettings.GPS_INTERVAL_ADAPTIVE_SCREEN_OFF_SECONDS,
            )
        RecordingSettings.GPS_USAGE_PROFILE_LONG_BATTERY ->
            RecordingGpsUsageTiming(
                recordingScreenOnSeconds = if (bike) 3 else 5,
                recordingScreenOffSeconds = if (bike) 10 else 20,
                turnByTurnScreenOnSeconds = if (bike) 3 else 5,
                turnByTurnScreenOffSeconds = RecordingSettings.GPS_INTERVAL_ADAPTIVE_SCREEN_OFF_SECONDS,
            )
        else ->
            RecordingGpsUsageTiming(
                recordingScreenOnSeconds =
                    if (bike) {
                        RecordingSettings.DEFAULT_BIKE_RECORDING_SAMPLE_INTERVAL_SECONDS
                    } else {
                        RecordingSettings.DEFAULT_RECORDING_SAMPLE_INTERVAL_SECONDS
                    },
                recordingScreenOffSeconds =
                    if (bike) {
                        RecordingSettings.DEFAULT_BIKE_RECORDING_SCREEN_OFF_SAMPLE_INTERVAL_SECONDS
                    } else {
                        RecordingSettings.DEFAULT_RECORDING_SCREEN_OFF_SAMPLE_INTERVAL_SECONDS
                    },
                turnByTurnScreenOnSeconds = if (bike) 1 else 3,
                turnByTurnScreenOffSeconds = RecordingSettings.GPS_INTERVAL_ADAPTIVE_SCREEN_OFF_SECONDS,
            )
    }
}

fun inferRecordingGpsUsageProfile(
    activityProfile: String,
    timing: RecordingGpsUsageTiming,
): String =
    listOf(
        RecordingSettings.GPS_USAGE_PROFILE_BEST_TRACE,
        RecordingSettings.GPS_USAGE_PROFILE_BALANCED,
        RecordingSettings.GPS_USAGE_PROFILE_LONG_BATTERY,
    ).firstOrNull { profile -> recordingGpsUsageTiming(profile, activityProfile) == timing }
        ?: RecordingSettings.GPS_USAGE_PROFILE_CUSTOM

fun recordingGpsIntervalMillis(seconds: Int): Long =
    if (seconds == RecordingSettings.RECORDING_SAMPLE_INTERVAL_DISABLED_SECONDS) {
        Long.MAX_VALUE
    } else {
        seconds.coerceAtLeast(1) * 1_000L
    }

/** REC only accepts fixed, disabled, or same-as-screen-on values. Adaptive is guidance-only. */
fun recordingScreenOffIntervalMillis(
    screenOnIntervalMs: Long,
    screenOffSeconds: Int,
): Long =
    when (screenOffSeconds) {
        RecordingSettings.GPS_INTERVAL_SAME_AS_SCREEN_ON_SECONDS -> screenOnIntervalMs
        else -> recordingGpsIntervalMillis(screenOffSeconds)
    }
