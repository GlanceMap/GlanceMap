package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import kotlin.math.abs

internal enum class PhoneMapMarkerAnchor(
    val storageValue: String,
) {
    CENTER("CENTER"),
    LOWER("LOWER"),
    ;

    companion object {
        fun fromStorageValue(value: String?): PhoneMapMarkerAnchor =
            when (value) {
                LOWER.storageValue -> LOWER
                else -> CENTER
            }
    }
}

internal enum class PhoneMapNorthIndicatorMode(
    val storageValue: String,
) {
    ALWAYS("ALWAYS"),
    COMPASS_ONLY("COMPASS_ONLY"),
    NORTH_UP_ONLY("NORTH_UP_ONLY"),
    NEVER("NEVER"),
    ;

    companion object {
        fun fromStorageValue(value: String?): PhoneMapNorthIndicatorMode =
            when (value) {
                COMPASS_ONLY.storageValue -> COMPASS_ONLY
                NORTH_UP_ONLY.storageValue -> NORTH_UP_ONLY
                NEVER.storageValue -> NEVER
                else -> ALWAYS
            }
    }
}

/** North reference used by the phone compass and both map renderers. */
internal enum class PhoneMapNorthReferenceMode(
    val storageValue: String,
) {
    TRUE("TRUE"),
    MAGNETIC("MAGNETIC"),
    ;

    companion object {
        fun fromStorageValue(value: String?): PhoneMapNorthReferenceMode =
            entries
                .firstOrNull { mode -> mode.storageValue == value }
                ?: TRUE
    }
}

internal enum class PhoneMapMarkerStyle(
    val storageValue: String,
) {
    DOT("DOT"),
    TRIANGLE("TRIANGLE"),
    ;

    companion object {
        fun fromStorageValue(value: String?): PhoneMapMarkerStyle =
            when (value) {
                TRIANGLE.storageValue -> TRIANGLE
                else -> DOT
            }
    }
}

internal enum class PhoneMapZoomButtonsMode(
    val storageValue: String,
) {
    BOTH("BOTH"),
    HIDE_BOTH("HIDE_BOTH"),
    HIDE_PLUS("HIDE_PLUS"),
    ;

    companion object {
        fun fromStorageValue(value: String?): PhoneMapZoomButtonsMode =
            when (value) {
                HIDE_BOTH.storageValue -> HIDE_BOTH
                HIDE_PLUS.storageValue -> HIDE_PLUS
                else -> BOTH
            }
    }
}

/** Phone-side equivalent of the watch Maps settings that both phone renderers can honor. */
internal data class PhoneMapSettings(
    val markerAnchor: PhoneMapMarkerAnchor = PhoneMapMarkerAnchor.CENTER,
    val autoRecenterEnabled: Boolean = false,
    val autoRecenterDelaySeconds: Int = DEFAULT_PHONE_MAP_AUTO_RECENTER_DELAY_SECONDS,
    val northIndicatorMode: PhoneMapNorthIndicatorMode = PhoneMapNorthIndicatorMode.ALWAYS,
    val markerStyle: PhoneMapMarkerStyle = PhoneMapMarkerStyle.DOT,
    val zoomButtonsMode: PhoneMapZoomButtonsMode = PhoneMapZoomButtonsMode.BOTH,
    val gpsAccuracyCircleEnabled: Boolean = false,
    val zoomDefaultScaleMeters: Int = DEFAULT_PHONE_MAP_ZOOM_DEFAULT_SCALE_METERS,
    val zoomMinScaleMeters: Int = DEFAULT_PHONE_MAP_ZOOM_MIN_SCALE_METERS,
    val zoomMaxScaleMeters: Int = DEFAULT_PHONE_MAP_ZOOM_MAX_SCALE_METERS,
    val liveElevationEnabled: Boolean = false,
    val liveDistanceEnabled: Boolean = false,
    val hillShadingEnabled: Boolean = false,
    val reliefOverlayEnabled: Boolean = false,
    val nightModeEnabled: Boolean = false,
    val northReferenceMode: PhoneMapNorthReferenceMode = PhoneMapNorthReferenceMode.TRUE,
    val demSource: PhoneOfflineDemSource = PhoneOfflineDemSource.DEFAULT,
) {
    fun normalized(): PhoneMapSettings {
        val farthestOut = nearestPhoneMapScaleStep(zoomMinScaleMeters)
        val closestIn = nearestPhoneMapScaleStep(zoomMaxScaleMeters)
        val orderedFarthestOut = maxOf(farthestOut, closestIn)
        val orderedClosestIn = minOf(farthestOut, closestIn)
        return copy(
            autoRecenterDelaySeconds =
                autoRecenterDelaySeconds.coerceIn(
                    MIN_PHONE_MAP_AUTO_RECENTER_DELAY_SECONDS,
                    MAX_PHONE_MAP_AUTO_RECENTER_DELAY_SECONDS,
                ),
            zoomDefaultScaleMeters =
                nearestPhoneMapScaleStep(zoomDefaultScaleMeters)
                    .coerceIn(orderedClosestIn, orderedFarthestOut),
            zoomMinScaleMeters = orderedFarthestOut,
            zoomMaxScaleMeters = orderedClosestIn,
        )
    }
}

internal class PhoneMapSettingsPreferences(
    context: Context,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Suppress("LongMethod") // Preference keys are kept in one migration-safe read/write boundary.
    fun load(): PhoneMapSettings {
        val settings =
            PhoneMapSettings(
                markerAnchor =
                    PhoneMapMarkerAnchor.fromStorageValue(
                        preferences.getString(
                            KEY_MARKER_ANCHOR,
                            PhoneMapMarkerAnchor.CENTER.storageValue,
                        ),
                    ),
                autoRecenterEnabled = preferences.getBoolean(KEY_AUTO_RECENTER_ENABLED, false),
                autoRecenterDelaySeconds =
                    preferences.getInt(
                        KEY_AUTO_RECENTER_DELAY_SECONDS,
                        DEFAULT_PHONE_MAP_AUTO_RECENTER_DELAY_SECONDS,
                    ),
                northIndicatorMode =
                    PhoneMapNorthIndicatorMode.fromStorageValue(
                        preferences.getString(KEY_NORTH_INDICATOR_MODE, PhoneMapNorthIndicatorMode.ALWAYS.storageValue),
                    ),
                markerStyle =
                    PhoneMapMarkerStyle.fromStorageValue(
                        preferences.getString(KEY_MARKER_STYLE, PhoneMapMarkerStyle.DOT.storageValue),
                    ),
                zoomButtonsMode =
                    PhoneMapZoomButtonsMode.fromStorageValue(
                        preferences.getString(KEY_ZOOM_BUTTONS_MODE, PhoneMapZoomButtonsMode.BOTH.storageValue),
                    ),
                gpsAccuracyCircleEnabled = preferences.getBoolean(KEY_GPS_ACCURACY_CIRCLE_ENABLED, false),
                zoomDefaultScaleMeters =
                    preferences.getInt(
                        KEY_ZOOM_DEFAULT_SCALE_METERS,
                        DEFAULT_PHONE_MAP_ZOOM_DEFAULT_SCALE_METERS,
                    ),
                zoomMinScaleMeters =
                    preferences.getInt(
                        KEY_ZOOM_MIN_SCALE_METERS,
                        DEFAULT_PHONE_MAP_ZOOM_MIN_SCALE_METERS,
                    ),
                zoomMaxScaleMeters =
                    preferences.getInt(
                        KEY_ZOOM_MAX_SCALE_METERS,
                        DEFAULT_PHONE_MAP_ZOOM_MAX_SCALE_METERS,
                    ),
                liveElevationEnabled = preferences.getBoolean(KEY_LIVE_ELEVATION_ENABLED, false),
                liveDistanceEnabled = preferences.getBoolean(KEY_LIVE_DISTANCE_ENABLED, false),
                hillShadingEnabled = preferences.getBoolean(KEY_HILL_SHADING_ENABLED, false),
                reliefOverlayEnabled = preferences.getBoolean(KEY_RELIEF_OVERLAY_ENABLED, false),
                nightModeEnabled = preferences.getBoolean(KEY_NIGHT_MODE_ENABLED, false),
                northReferenceMode =
                    PhoneMapNorthReferenceMode.fromStorageValue(
                        preferences.getString(
                            KEY_NORTH_REFERENCE_MODE,
                            PhoneMapNorthReferenceMode.TRUE.storageValue,
                        ),
                    ),
                demSource =
                    PhoneOfflineDemSource.fromId(
                        preferences.getString(KEY_DEM_SOURCE, PhoneOfflineDemSource.DEFAULT.id),
                    ),
            ).normalized()
        save(settings)
        return settings
    }

    fun save(settings: PhoneMapSettings): PhoneMapSettings {
        val normalized = settings.normalized()
        preferences
            .edit()
            .putString(KEY_MARKER_ANCHOR, normalized.markerAnchor.storageValue)
            .putBoolean(KEY_AUTO_RECENTER_ENABLED, normalized.autoRecenterEnabled)
            .putInt(KEY_AUTO_RECENTER_DELAY_SECONDS, normalized.autoRecenterDelaySeconds)
            .putString(KEY_NORTH_INDICATOR_MODE, normalized.northIndicatorMode.storageValue)
            .putString(KEY_MARKER_STYLE, normalized.markerStyle.storageValue)
            .putString(KEY_ZOOM_BUTTONS_MODE, normalized.zoomButtonsMode.storageValue)
            .putBoolean(KEY_GPS_ACCURACY_CIRCLE_ENABLED, normalized.gpsAccuracyCircleEnabled)
            .putInt(KEY_ZOOM_DEFAULT_SCALE_METERS, normalized.zoomDefaultScaleMeters)
            .putInt(KEY_ZOOM_MIN_SCALE_METERS, normalized.zoomMinScaleMeters)
            .putInt(KEY_ZOOM_MAX_SCALE_METERS, normalized.zoomMaxScaleMeters)
            .putBoolean(KEY_LIVE_ELEVATION_ENABLED, normalized.liveElevationEnabled)
            .putBoolean(KEY_LIVE_DISTANCE_ENABLED, normalized.liveDistanceEnabled)
            .putBoolean(KEY_HILL_SHADING_ENABLED, normalized.hillShadingEnabled)
            .putBoolean(KEY_RELIEF_OVERLAY_ENABLED, normalized.reliefOverlayEnabled)
            .putBoolean(KEY_NIGHT_MODE_ENABLED, normalized.nightModeEnabled)
            .putString(KEY_NORTH_REFERENCE_MODE, normalized.northReferenceMode.storageValue)
            .putString(KEY_DEM_SOURCE, normalized.demSource.id)
            .apply()
        return normalized
    }

    private companion object {
        const val PREFERENCES_NAME = "phone_map_settings"
        const val KEY_MARKER_ANCHOR = "marker_anchor"
        const val KEY_AUTO_RECENTER_ENABLED = "auto_recenter_enabled"
        const val KEY_AUTO_RECENTER_DELAY_SECONDS = "auto_recenter_delay_seconds"
        const val KEY_NORTH_INDICATOR_MODE = "north_indicator_mode"
        const val KEY_MARKER_STYLE = "marker_style"
        const val KEY_ZOOM_BUTTONS_MODE = "zoom_buttons_mode"
        const val KEY_GPS_ACCURACY_CIRCLE_ENABLED = "gps_accuracy_circle_enabled"
        const val KEY_ZOOM_DEFAULT_SCALE_METERS = "zoom_default_scale_meters"
        const val KEY_ZOOM_MIN_SCALE_METERS = "zoom_min_scale_meters"
        const val KEY_ZOOM_MAX_SCALE_METERS = "zoom_max_scale_meters"
        const val KEY_LIVE_ELEVATION_ENABLED = "live_elevation_enabled"
        const val KEY_LIVE_DISTANCE_ENABLED = "live_distance_enabled"
        const val KEY_HILL_SHADING_ENABLED = "hill_shading_enabled"
        const val KEY_RELIEF_OVERLAY_ENABLED = "relief_overlay_enabled"
        const val KEY_NIGHT_MODE_ENABLED = "night_mode_enabled"
        const val KEY_NORTH_REFERENCE_MODE = "north_reference_mode"
        const val KEY_DEM_SOURCE = "dem_source"
    }
}

private fun nearestPhoneMapScaleStep(value: Int): Int =
    PHONE_MAP_SCALE_STEPS_METERS.minByOrNull { step -> abs(step - value) }
        ?: DEFAULT_PHONE_MAP_ZOOM_DEFAULT_SCALE_METERS

internal const val DEFAULT_PHONE_MAP_AUTO_RECENTER_DELAY_SECONDS = 5
internal const val MIN_PHONE_MAP_AUTO_RECENTER_DELAY_SECONDS = 1
internal const val MAX_PHONE_MAP_AUTO_RECENTER_DELAY_SECONDS = 30
internal const val DEFAULT_PHONE_MAP_ZOOM_DEFAULT_SCALE_METERS = 200
internal const val DEFAULT_PHONE_MAP_ZOOM_MIN_SCALE_METERS = 200_000
internal const val DEFAULT_PHONE_MAP_ZOOM_MAX_SCALE_METERS = 20

internal fun PhoneMapNorthIndicatorMode.isVisibleFor(
    mapMode: PhoneMapMode,
    compassRenderable: Boolean,
): Boolean =
    when (this) {
        PhoneMapNorthIndicatorMode.ALWAYS -> true
        PhoneMapNorthIndicatorMode.COMPASS_ONLY ->
            compassRenderable && mapMode.orientation == PhoneMapOrientation.HEADING_UP
        PhoneMapNorthIndicatorMode.NORTH_UP_ONLY -> mapMode.orientation == PhoneMapOrientation.NORTH_UP
        PhoneMapNorthIndicatorMode.NEVER -> false
    }
