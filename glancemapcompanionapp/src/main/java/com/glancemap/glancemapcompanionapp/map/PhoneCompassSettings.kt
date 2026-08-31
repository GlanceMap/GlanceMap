package com.glancemap.glancemapcompanionapp.map

import android.content.Context

internal enum class PhoneCompassProviderMode(
    val storageValue: String,
    val label: String,
) {
    GOOGLE_FUSED("GOOGLE_FUSED", "Google Fused (default)"),
    SENSOR_MANAGER("SENSOR_MANAGER", "Android sensors"),
    ;

    companion object {
        fun fromStorageValue(value: String?) = entries.find { it.storageValue == value } ?: GOOGLE_FUSED
    }
}

internal enum class PhoneCompassSettingsMode(
    val storageValue: String,
    val label: String,
) {
    AUTOMATIC("AUTOMATIC", "Automatic (recommended)"),
    ADVANCED("ADVANCED", "Advanced"),
    ;

    companion object {
        fun fromStorageValue(value: String?) = entries.find { it.storageValue == value } ?: AUTOMATIC
    }
}

internal enum class PhoneCompassHeadingSourceMode(
    val storageValue: String,
    val label: String,
) {
    AUTO("AUTO", "Auto (recommended)"),
    TYPE_HEADING("TYPE_HEADING", "Heading sensor"),
    ROTATION_VECTOR("ROTATION_VECTOR", "Rotation vector"),
    MAGNETOMETER("MAGNETOMETER", "Magnetometer + accelerometer"),
    ;

    companion object {
        fun fromStorageValue(value: String?) = entries.find { it.storageValue == value } ?: AUTO
    }
}

internal data class PhoneCompassSettings(
    val providerMode: PhoneCompassProviderMode = PhoneCompassProviderMode.GOOGLE_FUSED,
    val settingsMode: PhoneCompassSettingsMode = PhoneCompassSettingsMode.AUTOMATIC,
    val headingSourceMode: PhoneCompassHeadingSourceMode = PhoneCompassHeadingSourceMode.AUTO,
    val calibrationAlertsEnabled: Boolean = false,
    val accuracyDisplayEnabled: Boolean = true,
) {
    fun normalized(): PhoneCompassSettings =
        if (settingsMode == PhoneCompassSettingsMode.AUTOMATIC) {
            copy(headingSourceMode = PhoneCompassHeadingSourceMode.AUTO)
        } else {
            this
        }
}

internal class PhoneCompassSettingsPreferences(
    context: Context,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): PhoneCompassSettings {
        val settings =
            PhoneCompassSettings(
                providerMode =
                    PhoneCompassProviderMode.fromStorageValue(
                        preferences.getString(KEY_PROVIDER_MODE, PhoneCompassProviderMode.GOOGLE_FUSED.storageValue),
                    ),
                settingsMode =
                    PhoneCompassSettingsMode.fromStorageValue(
                        preferences.getString(KEY_SETTINGS_MODE, PhoneCompassSettingsMode.AUTOMATIC.storageValue),
                    ),
                headingSourceMode =
                    PhoneCompassHeadingSourceMode.fromStorageValue(
                        preferences.getString(KEY_HEADING_SOURCE_MODE, PhoneCompassHeadingSourceMode.AUTO.storageValue),
                    ),
                calibrationAlertsEnabled = preferences.getBoolean(KEY_CALIBRATION_ALERTS, false),
                accuracyDisplayEnabled = preferences.getBoolean(KEY_ACCURACY_DISPLAY, true),
            ).normalized()
        save(settings)
        return settings
    }

    fun save(settings: PhoneCompassSettings): PhoneCompassSettings {
        val normalized = settings.normalized()
        preferences
            .edit()
            .putString(KEY_PROVIDER_MODE, normalized.providerMode.storageValue)
            .putString(KEY_SETTINGS_MODE, normalized.settingsMode.storageValue)
            .putString(KEY_HEADING_SOURCE_MODE, normalized.headingSourceMode.storageValue)
            .putBoolean(KEY_CALIBRATION_ALERTS, normalized.calibrationAlertsEnabled)
            .putBoolean(KEY_ACCURACY_DISPLAY, normalized.accuracyDisplayEnabled)
            .apply()
        return normalized
    }

    private companion object {
        const val PREFERENCES_NAME = "phone_compass_settings"
        const val KEY_PROVIDER_MODE = "provider_mode"
        const val KEY_SETTINGS_MODE = "settings_mode"
        const val KEY_HEADING_SOURCE_MODE = "heading_source_mode"
        const val KEY_CALIBRATION_ALERTS = "calibration_alerts"
        const val KEY_ACCURACY_DISPLAY = "accuracy_display"
    }
}
