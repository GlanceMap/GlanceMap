package com.glancemap.glancemapcompanionapp.map

import android.content.Context

internal enum class PhoneMapPoiIconSize(
    val pixels: Int,
    val storageValue: String,
) {
    SMALL(20, "SMALL"),
    MEDIUM(28, "MEDIUM"),
    LARGE(36, "LARGE"),
    ;

    companion object {
        fun fromStorageValue(
            value: String?,
        ): PhoneMapPoiIconSize =
            when (value) {
                SMALL.storageValue -> SMALL
                MEDIUM.storageValue -> MEDIUM
                LARGE.storageValue -> LARGE
                else -> MEDIUM
            }
    }
}

internal enum class PhoneMapPoiMarkerStyle(
    val storageValue: String,
) {
    BADGE("BADGE"),
    THEME_ICON("THEME_ICON"),
    ;

    companion object {
        fun fromStorageValue(
            value: String?,
        ): PhoneMapPoiMarkerStyle =
            when (value) {
                BADGE.storageValue -> BADGE
                THEME_ICON.storageValue -> THEME_ICON
                else -> BADGE
            }
    }
}

/** Phone equivalent of the watch POI appearance and popup settings. */
internal data class PhoneMapPoiSettings(
    val iconSize: PhoneMapPoiIconSize = PhoneMapPoiIconSize.MEDIUM,
    val markerStyle: PhoneMapPoiMarkerStyle = PhoneMapPoiMarkerStyle.BADGE,
    val linkGpxWaypointPoiFolders: Boolean = true,
    val popupAutoCloseEnabled: Boolean = true,
    val popupTimeoutSeconds: Int = DEFAULT_PHONE_MAP_POI_POPUP_TIMEOUT_SECONDS,
) {
    fun normalized(): PhoneMapPoiSettings =
        copy(
            popupTimeoutSeconds =
                popupTimeoutSeconds.coerceIn(
                    MIN_PHONE_MAP_POI_POPUP_TIMEOUT_SECONDS,
                    MAX_PHONE_MAP_POI_POPUP_TIMEOUT_SECONDS,
                ),
        )
}

internal class PhoneMapPoiSettingsPreferences(
    context: Context,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): PhoneMapPoiSettings {
        val settings =
            PhoneMapPoiSettings(
                iconSize =
                    PhoneMapPoiIconSize.fromStorageValue(
                        preferences.getString(KEY_ICON_SIZE, PhoneMapPoiIconSize.MEDIUM.storageValue),
                    ),
                markerStyle =
                    PhoneMapPoiMarkerStyle.fromStorageValue(
                        preferences.getString(KEY_MARKER_STYLE, PhoneMapPoiMarkerStyle.BADGE.storageValue),
                    ),
                linkGpxWaypointPoiFolders = preferences.getBoolean(KEY_LINK_GPX_WAYPOINT_POI_FOLDERS, true),
                popupAutoCloseEnabled = preferences.getBoolean(KEY_POPUP_AUTO_CLOSE_ENABLED, true),
                popupTimeoutSeconds =
                    preferences.getInt(
                        KEY_POPUP_TIMEOUT_SECONDS,
                        DEFAULT_PHONE_MAP_POI_POPUP_TIMEOUT_SECONDS,
                    ),
            ).normalized()
        save(settings)
        return settings
    }

    fun save(settings: PhoneMapPoiSettings): PhoneMapPoiSettings {
        val normalized = settings.normalized()
        preferences
            .edit()
            .putString(KEY_ICON_SIZE, normalized.iconSize.storageValue)
            .putString(KEY_MARKER_STYLE, normalized.markerStyle.storageValue)
            .putBoolean(KEY_LINK_GPX_WAYPOINT_POI_FOLDERS, normalized.linkGpxWaypointPoiFolders)
            .putBoolean(KEY_POPUP_AUTO_CLOSE_ENABLED, normalized.popupAutoCloseEnabled)
            .putInt(KEY_POPUP_TIMEOUT_SECONDS, normalized.popupTimeoutSeconds)
            .apply()
        return normalized
    }

    private companion object {
        const val PREFERENCES_NAME = "phone_map_poi_settings"
        const val KEY_ICON_SIZE = "icon_size"
        const val KEY_MARKER_STYLE = "marker_style"
        const val KEY_LINK_GPX_WAYPOINT_POI_FOLDERS = "link_gpx_waypoint_poi_folders"
        const val KEY_POPUP_AUTO_CLOSE_ENABLED = "popup_auto_close_enabled"
        const val KEY_POPUP_TIMEOUT_SECONDS = "popup_timeout_seconds"
    }
}

internal const val DEFAULT_PHONE_MAP_POI_POPUP_TIMEOUT_SECONDS = 5
internal const val MIN_PHONE_MAP_POI_POPUP_TIMEOUT_SECONDS = 1
internal const val MAX_PHONE_MAP_POI_POPUP_TIMEOUT_SECONDS = 20
