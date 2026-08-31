package com.glancemap.glancemapcompanionapp.map

import android.content.Context

internal enum class PhoneActivityProfile(
    val storageValue: String,
    val label: String,
) {
    HIKE("HIKE", "Hike"),
    BIKE("BIKE", "Bike"),
    ;

    companion object {
        fun fromStorageValue(value: String?) = entries.find { it.storageValue == value } ?: HIKE
    }
}

/** Phone-side general settings shared by map, routing, and future activity calculations. */
internal data class PhoneGeneralSettings(
    val isMetric: Boolean = true,
    val distanceMeasurementEnabled: Boolean = false,
    val activityProfile: PhoneActivityProfile = PhoneActivityProfile.HIKE,
    val userWeightKg: Float = DEFAULT_PHONE_USER_WEIGHT_KG,
    val backpackWeightKg: Float = DEFAULT_PHONE_BACKPACK_WEIGHT_KG,
    val bikeWeightKg: Float = DEFAULT_PHONE_BIKE_WEIGHT_KG,
) {
    fun normalized(): PhoneGeneralSettings =
        copy(
            userWeightKg = userWeightKg.coerceIn(MIN_PHONE_USER_WEIGHT_KG, MAX_PHONE_USER_WEIGHT_KG),
            backpackWeightKg = backpackWeightKg.coerceIn(MIN_PHONE_BACKPACK_WEIGHT_KG, MAX_PHONE_BACKPACK_WEIGHT_KG),
            bikeWeightKg = bikeWeightKg.coerceIn(MIN_PHONE_BIKE_WEIGHT_KG, MAX_PHONE_BIKE_WEIGHT_KG),
        )
}

internal class PhoneGeneralSettingsPreferences(
    context: Context,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): PhoneGeneralSettings =
        PhoneGeneralSettings(
            isMetric = preferences.getBoolean(KEY_IS_METRIC, true),
            distanceMeasurementEnabled = preferences.getBoolean(KEY_DISTANCE_MEASUREMENT_ENABLED, false),
            activityProfile =
                PhoneActivityProfile.fromStorageValue(
                    preferences.getString(KEY_ACTIVITY_PROFILE, PhoneActivityProfile.HIKE.storageValue),
                ),
            userWeightKg = preferences.getFloat(KEY_USER_WEIGHT_KG, DEFAULT_PHONE_USER_WEIGHT_KG),
            backpackWeightKg = preferences.getFloat(KEY_BACKPACK_WEIGHT_KG, DEFAULT_PHONE_BACKPACK_WEIGHT_KG),
            bikeWeightKg = preferences.getFloat(KEY_BIKE_WEIGHT_KG, DEFAULT_PHONE_BIKE_WEIGHT_KG),
        ).normalized()

    fun save(settings: PhoneGeneralSettings): PhoneGeneralSettings {
        val normalized = settings.normalized()
        preferences
            .edit()
            .putBoolean(KEY_IS_METRIC, normalized.isMetric)
            .putBoolean(KEY_DISTANCE_MEASUREMENT_ENABLED, normalized.distanceMeasurementEnabled)
            .putString(KEY_ACTIVITY_PROFILE, normalized.activityProfile.storageValue)
            .putFloat(KEY_USER_WEIGHT_KG, normalized.userWeightKg)
            .putFloat(KEY_BACKPACK_WEIGHT_KG, normalized.backpackWeightKg)
            .putFloat(KEY_BIKE_WEIGHT_KG, normalized.bikeWeightKg)
            .apply()
        return normalized
    }

    private companion object {
        const val PREFERENCES_NAME = "phone_general_settings"
        const val KEY_IS_METRIC = "is_metric"
        const val KEY_DISTANCE_MEASUREMENT_ENABLED = "distance_measurement_enabled"
        const val KEY_ACTIVITY_PROFILE = "activity_profile"
        const val KEY_USER_WEIGHT_KG = "user_weight_kg"
        const val KEY_BACKPACK_WEIGHT_KG = "backpack_weight_kg"
        const val KEY_BIKE_WEIGHT_KG = "bike_weight_kg"
    }
}

internal const val DEFAULT_PHONE_USER_WEIGHT_KG = 75f
internal const val MIN_PHONE_USER_WEIGHT_KG = 35f
internal const val MAX_PHONE_USER_WEIGHT_KG = 160f
internal const val DEFAULT_PHONE_BACKPACK_WEIGHT_KG = 0f
internal const val MIN_PHONE_BACKPACK_WEIGHT_KG = 0f
internal const val MAX_PHONE_BACKPACK_WEIGHT_KG = 40f
internal const val DEFAULT_PHONE_BIKE_WEIGHT_KG = 12f
internal const val MIN_PHONE_BIKE_WEIGHT_KG = 5f
internal const val MAX_PHONE_BIKE_WEIGHT_KG = 40f
