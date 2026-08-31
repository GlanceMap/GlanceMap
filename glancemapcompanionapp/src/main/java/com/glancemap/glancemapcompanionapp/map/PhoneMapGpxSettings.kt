package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import com.glancemap.trailcore.geo.haversineDistanceMeters
import com.glancemap.trailcore.profile.TrailPacingConfig
import com.glancemap.trailcore.profile.TrailPoint

internal enum class PhoneMapGpxColorMode(
    val storageValue: String,
) {
    SOLID("SOLID"),
    ELEVATION("ELEVATION"),
    ;

    companion object {
        fun fromStorageValue(value: String?): PhoneMapGpxColorMode =
            entries
                .firstOrNull { mode -> mode.storageValue == value }
                ?: SOLID
    }
}

/** GPX appearance settings shared by the phone renderers. Values mirror the watch settings. */
internal data class PhoneMapGpxSettings(
    val trackColorArgb: Int = DEFAULT_PHONE_GPX_TRACK_COLOR_ARGB,
    val colorMode: PhoneMapGpxColorMode = PhoneMapGpxColorMode.SOLID,
    val trackWidth: Float = DEFAULT_PHONE_GPX_TRACK_WIDTH,
    val trackOpacityPercent: Int = DEFAULT_PHONE_GPX_TRACK_OPACITY_PERCENT,
    val directionArrowsEnabled: Boolean = DEFAULT_PHONE_GPX_DIRECTION_ARROWS_ENABLED,
    val inspectionEnabled: Boolean = DEFAULT_PHONE_GPX_INSPECTION_ENABLED,
    val flatSpeedMetersPerSecond: Float = DEFAULT_PHONE_GPX_FLAT_SPEED_METERS_PER_SECOND,
    val advancedEtaEnabled: Boolean = DEFAULT_PHONE_GPX_ADVANCED_ETA_ENABLED,
    val staminaAdjustmentEnabled: Boolean = DEFAULT_PHONE_GPX_STAMINA_ADJUSTMENT_ENABLED,
    val uphillVerticalMetersPerHour: Float = DEFAULT_PHONE_GPX_UPHILL_VERTICAL_METERS_PER_HOUR,
    val downhillVerticalMetersPerHour: Float = DEFAULT_PHONE_GPX_DOWNHILL_VERTICAL_METERS_PER_HOUR,
) {
    fun normalized(): PhoneMapGpxSettings =
        copy(
            trackWidth = trackWidth.coerceIn(MIN_PHONE_GPX_TRACK_WIDTH, MAX_PHONE_GPX_TRACK_WIDTH),
            trackOpacityPercent =
                trackOpacityPercent.coerceIn(
                    MIN_PHONE_GPX_TRACK_OPACITY_PERCENT,
                    MAX_PHONE_GPX_TRACK_OPACITY_PERCENT,
                ),
            flatSpeedMetersPerSecond =
                flatSpeedMetersPerSecond.coerceIn(
                    MIN_PHONE_GPX_FLAT_SPEED_METERS_PER_SECOND,
                    MAX_PHONE_GPX_FLAT_SPEED_METERS_PER_SECOND,
                ),
            uphillVerticalMetersPerHour =
                uphillVerticalMetersPerHour.coerceIn(
                    MIN_PHONE_GPX_VERTICAL_METERS_PER_HOUR,
                    MAX_PHONE_GPX_UPHILL_VERTICAL_METERS_PER_HOUR,
                ),
            downhillVerticalMetersPerHour =
                downhillVerticalMetersPerHour.coerceIn(
                    MIN_PHONE_GPX_VERTICAL_METERS_PER_HOUR,
                    MAX_PHONE_GPX_DOWNHILL_VERTICAL_METERS_PER_HOUR,
                ),
        )
}

/** Small local preference store for phone-only GPX appearance settings. */
internal class PhoneMapGpxSettingsPreferences(
    context: Context,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): PhoneMapGpxSettings {
        val settings =
            PhoneMapGpxSettings(
                trackColorArgb =
                    preferences.getInt(KEY_TRACK_COLOR_ARGB, DEFAULT_PHONE_GPX_TRACK_COLOR_ARGB),
                colorMode =
                    PhoneMapGpxColorMode.fromStorageValue(
                        preferences.getString(KEY_COLOR_MODE, PhoneMapGpxColorMode.SOLID.storageValue),
                    ),
                trackWidth = preferences.getFloat(KEY_TRACK_WIDTH, DEFAULT_PHONE_GPX_TRACK_WIDTH),
                trackOpacityPercent =
                    preferences.getInt(
                        KEY_TRACK_OPACITY_PERCENT,
                        DEFAULT_PHONE_GPX_TRACK_OPACITY_PERCENT,
                    ),
                directionArrowsEnabled =
                    preferences.getBoolean(
                        KEY_DIRECTION_ARROWS_ENABLED,
                        DEFAULT_PHONE_GPX_DIRECTION_ARROWS_ENABLED,
                    ),
                inspectionEnabled =
                    preferences.getBoolean(
                        KEY_INSPECTION_ENABLED,
                        DEFAULT_PHONE_GPX_INSPECTION_ENABLED,
                    ),
                flatSpeedMetersPerSecond =
                    preferences.getFloat(
                        KEY_FLAT_SPEED_METERS_PER_SECOND,
                        DEFAULT_PHONE_GPX_FLAT_SPEED_METERS_PER_SECOND,
                    ),
                advancedEtaEnabled =
                    preferences.getBoolean(
                        KEY_ADVANCED_ETA_ENABLED,
                        DEFAULT_PHONE_GPX_ADVANCED_ETA_ENABLED,
                    ),
                staminaAdjustmentEnabled =
                    preferences.getBoolean(
                        KEY_STAMINA_ADJUSTMENT_ENABLED,
                        DEFAULT_PHONE_GPX_STAMINA_ADJUSTMENT_ENABLED,
                    ),
                uphillVerticalMetersPerHour =
                    preferences.getFloat(
                        KEY_UPHILL_VERTICAL_METERS_PER_HOUR,
                        DEFAULT_PHONE_GPX_UPHILL_VERTICAL_METERS_PER_HOUR,
                    ),
                downhillVerticalMetersPerHour =
                    preferences.getFloat(
                        KEY_DOWNHILL_VERTICAL_METERS_PER_HOUR,
                        DEFAULT_PHONE_GPX_DOWNHILL_VERTICAL_METERS_PER_HOUR,
                    ),
            ).normalized()
        save(settings)
        return settings
    }

    fun save(settings: PhoneMapGpxSettings): PhoneMapGpxSettings {
        val normalized = settings.normalized()
        preferences
            .edit()
            .putInt(KEY_TRACK_COLOR_ARGB, normalized.trackColorArgb)
            .putString(KEY_COLOR_MODE, normalized.colorMode.storageValue)
            .putFloat(KEY_TRACK_WIDTH, normalized.trackWidth)
            .putInt(KEY_TRACK_OPACITY_PERCENT, normalized.trackOpacityPercent)
            .putBoolean(KEY_DIRECTION_ARROWS_ENABLED, normalized.directionArrowsEnabled)
            .putBoolean(KEY_INSPECTION_ENABLED, normalized.inspectionEnabled)
            .putFloat(KEY_FLAT_SPEED_METERS_PER_SECOND, normalized.flatSpeedMetersPerSecond)
            .putBoolean(KEY_ADVANCED_ETA_ENABLED, normalized.advancedEtaEnabled)
            .putBoolean(KEY_STAMINA_ADJUSTMENT_ENABLED, normalized.staminaAdjustmentEnabled)
            .putFloat(KEY_UPHILL_VERTICAL_METERS_PER_HOUR, normalized.uphillVerticalMetersPerHour)
            .putFloat(KEY_DOWNHILL_VERTICAL_METERS_PER_HOUR, normalized.downhillVerticalMetersPerHour)
            .apply()
        return normalized
    }

    private companion object {
        const val PREFERENCES_NAME = "phone_gpx_settings"
        const val KEY_TRACK_COLOR_ARGB = "track_color_argb"
        const val KEY_COLOR_MODE = "color_mode"
        const val KEY_TRACK_WIDTH = "track_width"
        const val KEY_TRACK_OPACITY_PERCENT = "track_opacity_percent"
        const val KEY_DIRECTION_ARROWS_ENABLED = "direction_arrows_enabled"
        const val KEY_INSPECTION_ENABLED = "inspection_enabled"
        const val KEY_FLAT_SPEED_METERS_PER_SECOND = "flat_speed_meters_per_second"
        const val KEY_ADVANCED_ETA_ENABLED = "advanced_eta_enabled"
        const val KEY_STAMINA_ADJUSTMENT_ENABLED = "stamina_adjustment_enabled"
        const val KEY_UPHILL_VERTICAL_METERS_PER_HOUR = "uphill_vertical_meters_per_hour"
        const val KEY_DOWNHILL_VERTICAL_METERS_PER_HOUR = "downhill_vertical_meters_per_hour"
    }
}

internal const val DEFAULT_PHONE_GPX_TRACK_COLOR_ARGB: Int = 0xFFFF00FF.toInt()
internal const val DEFAULT_PHONE_GPX_TRACK_WIDTH = 8f
internal const val DEFAULT_PHONE_GPX_TRACK_OPACITY_PERCENT = 70
internal const val MIN_PHONE_GPX_TRACK_WIDTH = 1f
internal const val MAX_PHONE_GPX_TRACK_WIDTH = 15f
internal const val MIN_PHONE_GPX_TRACK_OPACITY_PERCENT = 10
internal const val MAX_PHONE_GPX_TRACK_OPACITY_PERCENT = 100
internal const val DEFAULT_PHONE_GPX_DIRECTION_ARROWS_ENABLED = false
internal const val DEFAULT_PHONE_GPX_INSPECTION_ENABLED = true
internal const val DEFAULT_PHONE_GPX_FLAT_SPEED_METERS_PER_SECOND = 3.5f / 3.6f
internal const val DEFAULT_PHONE_BIKE_GPX_FLAT_SPEED_METERS_PER_SECOND = 15f / 3.6f
internal const val MIN_PHONE_GPX_FLAT_SPEED_METERS_PER_SECOND = 0.1f
internal const val MAX_PHONE_GPX_FLAT_SPEED_METERS_PER_SECOND = 60f / 3.6f
internal const val DEFAULT_PHONE_GPX_ADVANCED_ETA_ENABLED = false
internal const val DEFAULT_PHONE_GPX_STAMINA_ADJUSTMENT_ENABLED = false
internal const val DEFAULT_PHONE_GPX_UPHILL_VERTICAL_METERS_PER_HOUR = 600f
internal const val DEFAULT_PHONE_GPX_DOWNHILL_VERTICAL_METERS_PER_HOUR = 900f
internal const val MIN_PHONE_GPX_VERTICAL_METERS_PER_HOUR = 1f
internal const val MAX_PHONE_GPX_UPHILL_VERTICAL_METERS_PER_HOUR = 2_000f
internal const val MAX_PHONE_GPX_DOWNHILL_VERTICAL_METERS_PER_HOUR = 3_000f

/** Converts the phone controls to the shared profile model used by Route Library and mission planning. */
internal fun PhoneMapGpxSettings.toTrailPacingConfig(
    points: List<TrailPoint>,
    generalSettings: PhoneGeneralSettings = PhoneGeneralSettings(),
): TrailPacingConfig {
    val routeDistanceMeters =
        points
            .zipWithNext()
            .filterNot { (_, to) -> to.startsNewSegment }
            .sumOf { (from, to) -> haversineDistanceMeters(from.location, to.location) }
    val profileFlatSpeed =
        if (
            generalSettings.activityProfile == PhoneActivityProfile.BIKE &&
            flatSpeedMetersPerSecond == DEFAULT_PHONE_GPX_FLAT_SPEED_METERS_PER_SECOND
        ) {
            DEFAULT_PHONE_BIKE_GPX_FLAT_SPEED_METERS_PER_SECOND
        } else {
            flatSpeedMetersPerSecond
        }
    val adjustedFlatSpeed =
        if (staminaAdjustmentEnabled) {
            phoneStaminaAdjustedFlatSpeed(profileFlatSpeed.toDouble(), routeDistanceMeters)
        } else {
            profileFlatSpeed.toDouble()
        }
    return TrailPacingConfig(
        flatSpeedMetersPerSecond = adjustedFlatSpeed,
        uphillVerticalMetersPerHour =
            if (advancedEtaEnabled) {
                uphillVerticalMetersPerHour.toDouble()
            } else {
                DEFAULT_PHONE_GPX_UPHILL_VERTICAL_METERS_PER_HOUR.toDouble()
            },
        downhillVerticalMetersPerHour =
            if (advancedEtaEnabled) downhillVerticalMetersPerHour.toDouble() else 0.0,
    )
}

private fun phoneStaminaAdjustedFlatSpeed(
    flatSpeedMetersPerSecond: Double,
    routeDistanceMeters: Double,
): Double {
    if (routeDistanceMeters <= 0.0) return flatSpeedMetersPerSecond
    val estimatedHours = routeDistanceMeters / flatSpeedMetersPerSecond / 3_600.0
    val multiplier =
        when {
            estimatedHours <= 1.0 -> 1.0
            estimatedHours <= 3.0 -> 1.0 - (estimatedHours - 1.0) * 0.03 / 2.0
            estimatedHours <= 6.0 -> 0.97 - (estimatedHours - 3.0) * 0.04 / 3.0
            estimatedHours <= 10.0 -> 0.93 - (estimatedHours - 6.0) * 0.05 / 4.0
            else -> 0.85
        }
    return flatSpeedMetersPerSecond * multiplier
}
