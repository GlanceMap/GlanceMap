package com.glancemap.glancemapwearos.presentation.features.navigate

import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.trailcore.guidance.turnAlertDistanceMeters as trailTurnAlertDistanceMeters
import com.glancemap.trailcore.guidance.turnAlertMaxDistanceMeters as trailTurnAlertMaxDistanceMeters
import com.glancemap.trailcore.guidance.turnHapticCrossingRecoveryMeters as trailTurnHapticCrossingRecoveryMeters
import com.glancemap.trailcore.guidance.turnHapticDistanceMeters as trailTurnHapticDistanceMeters

internal fun turnAlertDistanceMeters(
    speedMps: Float?,
    activityProfile: String = SettingsRepository.DEFAULT_ACTIVITY_PROFILE,
): Double = trailTurnAlertDistanceMeters(speedMps, activityProfile)

internal fun turnHapticDistanceMeters(
    speedMps: Float?,
    activityProfile: String = SettingsRepository.DEFAULT_ACTIVITY_PROFILE,
    gpsDeliveryIntervalMs: Long = SettingsRepository.DEFAULT_GPS_INTERVAL_MS,
): Double = trailTurnHapticDistanceMeters(speedMps, activityProfile, gpsDeliveryIntervalMs)

internal fun turnHapticCrossingRecoveryMeters(
    speedMps: Float?,
    activityProfile: String = SettingsRepository.DEFAULT_ACTIVITY_PROFILE,
    gpsDeliveryIntervalMs: Long = SettingsRepository.DEFAULT_GPS_INTERVAL_MS,
): Double = trailTurnHapticCrossingRecoveryMeters(speedMps, activityProfile, gpsDeliveryIntervalMs)

internal fun turnAlertMaxDistanceMeters(
    activityProfile: String,
): Double = trailTurnAlertMaxDistanceMeters(activityProfile)
