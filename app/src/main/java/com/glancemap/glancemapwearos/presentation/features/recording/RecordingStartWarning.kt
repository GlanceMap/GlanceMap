package com.glancemap.glancemapwearos.presentation.features.recording

import android.content.res.Resources
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.service.location.model.GpsSignalSnapshot
import com.glancemap.glancemapwearos.core.service.location.model.effectiveAccuracyMeters
import com.glancemap.glancemapwearos.data.repository.SettingsRepository

data class RecordingLocationStartWarning(
    val kind: Kind,
    val rawAccuracyMeters: Float? = null,
    val effectiveAccuracyMeters: Float? = null,
    val fixAgeMs: Long = -1L,
    val provider: String? = null,
    val sourceMode: String? = null,
) {
    enum class Kind {
        GPS_UNAVAILABLE,
        LOW_ACCURACY,
    }
}

internal const val RECORDING_START_PENDING_MESSAGE = "Starting REC…"

internal fun isRecordingStartLocationReady(
    hasUsableLocation: Boolean,
    gpsSignalSnapshot: GpsSignalSnapshot,
    decisionFixAgeMs: Long,
    activityProfile: String = SettingsRepository.ACTIVITY_PROFILE_HIKE,
): Boolean =
    hasUsableLocation &&
        gpsSignalSnapshot.isLocationAvailable &&
        decisionFixAgeMs != Long.MAX_VALUE &&
        decisionFixAgeMs <= gpsSignalSnapshot.lastFixFreshMaxAgeMs &&
        gpsSignalSnapshot
            .effectiveAccuracyMeters()
            .let { accuracy ->
                accuracy.isFinite() && accuracy <= recordingFixProfileAccuracyLimitMeters(activityProfile)
            }

internal fun recordingLocationStartWarning(
    gpsSignalSnapshot: GpsSignalSnapshot,
    rawAccuracyMeters: Float?,
    decisionFixAgeMs: Long,
    provider: String?,
): RecordingLocationStartWarning =
    RecordingLocationStartWarning(
        kind =
            if (gpsSignalSnapshot.isLocationAvailable) {
                RecordingLocationStartWarning.Kind.LOW_ACCURACY
            } else {
                RecordingLocationStartWarning.Kind.GPS_UNAVAILABLE
            },
        rawAccuracyMeters = rawAccuracyMeters?.takeIf { it.isFinite() && it >= 0f },
        effectiveAccuracyMeters =
            gpsSignalSnapshot
                .effectiveAccuracyMeters()
                .takeIf { it.isFinite() && it >= 0f },
        fixAgeMs = decisionFixAgeMs.takeIf { it != Long.MAX_VALUE } ?: -1L,
        provider = provider,
        sourceMode = gpsSignalSnapshot.activeSourceModeValue,
    )

data class RecordingStartWarning(
    val unlinkedDevices: List<Int>,
    val disconnectedDevices: List<Int>,
) {
    fun message(resources: Resources): String =
        buildList {
            if (unlinkedDevices.isNotEmpty()) {
                add(
                    resources.getString(
                        R.string.recording_no_linked_device,
                        unlinkedDevices.joinToString { resources.getString(it) },
                    ),
                )
            }
            if (disconnectedDevices.isNotEmpty()) {
                add(
                    resources.getString(
                        R.string.recording_not_connected_yet,
                        disconnectedDevices.joinToString {
                            resources.getString(it)
                        },
                    ),
                )
            }
            if (disconnectedDevices.isNotEmpty()) {
                add(
                    resources.getString(
                        R.string.recording_recording_will_try_to_connect_linked_sensors_after_it_starts,
                    ),
                )
            }
            add(resources.getString(R.string.recording_choose_how_to_start_recording))
        }.joinToString(separator = "\n\n")
}

internal fun resolveRecordingStartWarning(
    heartRateSource: String,
    cadenceSource: String,
    speedSource: String,
    distanceSource: String,
    externalHeartRateAddress: String?,
    externalRunPodAddress: String?,
    connectedExternalAddresses: Set<String> = emptySet(),
): RecordingStartWarning? {
    val heartRateStrapSelected =
        heartRateSource == SettingsRepository.RECORDING_HEART_RATE_SOURCE_STRAP
    val runPodSelected =
        cadenceSource == SettingsRepository.RECORDING_SENSOR_SOURCE_POD ||
            speedSource == SettingsRepository.RECORDING_SENSOR_SOURCE_POD ||
            distanceSource == SettingsRepository.RECORDING_SENSOR_SOURCE_POD

    if (!heartRateStrapSelected && !runPodSelected) return null

    val unlinkedDevices =
        buildList {
            if (heartRateStrapSelected && externalHeartRateAddress.isNullOrBlank()) {
                add(R.string.recording_heart_rate_strap)
            }
            if (runPodSelected && externalRunPodAddress.isNullOrBlank()) {
                add(R.string.recording_external_sensor)
            }
        }
    val disconnectedDevices =
        buildList {
            if (
                heartRateStrapSelected &&
                !externalHeartRateAddress.isNullOrBlank() &&
                externalHeartRateAddress.normalizedBluetoothAddress() !in connectedExternalAddresses
            ) {
                add(R.string.recording_heart_rate_strap)
            }
            if (
                runPodSelected &&
                !externalRunPodAddress.isNullOrBlank() &&
                externalRunPodAddress.normalizedBluetoothAddress() !in connectedExternalAddresses
            ) {
                add(R.string.recording_external_sensor)
            }
        }

    return if (unlinkedDevices.isEmpty() && disconnectedDevices.isEmpty()) {
        null
    } else {
        RecordingStartWarning(
            unlinkedDevices = unlinkedDevices,
            disconnectedDevices = disconnectedDevices,
        )
    }
}

private fun String.normalizedBluetoothAddress(): String = trim().uppercase()
