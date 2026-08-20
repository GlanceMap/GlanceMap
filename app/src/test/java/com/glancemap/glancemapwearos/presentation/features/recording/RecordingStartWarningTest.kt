package com.glancemap.glancemapwearos.presentation.features.recording

import com.glancemap.glancemapwearos.core.service.location.model.GpsSignalSnapshot
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStartWarningTest {
    @Test
    fun `recording start requires a usable fresh location`() {
        val freshSignal =
            GpsSignalSnapshot(
                isLocationAvailable = true,
                lastFixFresh = true,
                lastFixAgeMs = 100L,
                lastFixFreshMaxAgeMs = 6_000L,
                lastFixAccuracyM = 12f,
            )

        assertTrue(
            isRecordingStartLocationReady(
                hasUsableLocation = true,
                gpsSignalSnapshot = freshSignal,
                decisionFixAgeMs = 100L,
            ),
        )
        assertFalse(
            isRecordingStartLocationReady(
                hasUsableLocation = false,
                gpsSignalSnapshot = freshSignal,
                decisionFixAgeMs = 100L,
            ),
        )
        assertFalse(
            isRecordingStartLocationReady(
                hasUsableLocation = true,
                gpsSignalSnapshot = freshSignal,
                decisionFixAgeMs = 6_001L,
            ),
        )
        assertFalse(
            isRecordingStartLocationReady(
                hasUsableLocation = true,
                gpsSignalSnapshot = freshSignal.copy(isLocationAvailable = false),
                decisionFixAgeMs = 100L,
            ),
        )
    }

    @Test
    fun `direct watch 125 accuracy uses the existing effective accuracy policy`() {
        val directWatchSignal =
            GpsSignalSnapshot(
                isLocationAvailable = true,
                lastFixFresh = true,
                lastFixAgeMs = 100L,
                lastFixFreshMaxAgeMs = 6_000L,
                lastFixAccuracyM = 125f,
                watchGpsOnlyActive = true,
            )
        val fusedSignal = directWatchSignal.copy(watchGpsOnlyActive = false)

        assertTrue(
            isRecordingStartLocationReady(
                hasUsableLocation = true,
                gpsSignalSnapshot = directWatchSignal,
                decisionFixAgeMs = 100L,
            ),
        )
        assertFalse(
            isRecordingStartLocationReady(
                hasUsableLocation = true,
                gpsSignalSnapshot = fusedSignal,
                decisionFixAgeMs = 100L,
            ),
        )
    }

    @Test
    fun `cached fresh state expires at the recording start decision`() {
        val cachedFreshSignal =
            GpsSignalSnapshot(
                isLocationAvailable = true,
                lastFixFresh = true,
                lastFixAgeMs = 5_900L,
                lastFixFreshMaxAgeMs = 6_000L,
                lastFixAccuracyM = 12f,
            )

        assertFalse(
            isRecordingStartLocationReady(
                hasUsableLocation = true,
                gpsSignalSnapshot = cachedFreshSignal,
                decisionFixAgeMs = 8_400L,
            ),
        )
        assertTrue(
            isRecordingStartLocationReady(
                hasUsableLocation = true,
                gpsSignalSnapshot = cachedFreshSignal.copy(lastFixAgeMs = 100L),
                decisionFixAgeMs = 100L,
            ),
        )
    }

    @Test
    fun `low accuracy remains distinct from GPS unavailable`() {
        val poorSignal =
            GpsSignalSnapshot(
                isLocationAvailable = true,
                lastFixFresh = true,
                lastFixAgeMs = 100L,
                lastFixFreshMaxAgeMs = 6_000L,
                lastFixAccuracyM = 60f,
            )

        assertEquals(
            RecordingLocationStartWarning.Kind.LOW_ACCURACY,
            recordingLocationStartWarning(
                poorSignal,
                rawAccuracyMeters = 60f,
                decisionFixAgeMs = 100L,
                provider = "fused",
            ).kind,
        )
        assertEquals(
            RecordingLocationStartWarning.Kind.GPS_UNAVAILABLE,
            recordingLocationStartWarning(
                poorSignal.copy(isLocationAvailable = false),
                rawAccuracyMeters = null,
                decisionFixAgeMs = Long.MAX_VALUE,
                provider = null,
            ).kind,
        )
    }

    @Test
    fun `internal sources do not require a warning`() {
        assertNull(
            resolveRecordingStartWarning(
                heartRateSource = SettingsRepository.RECORDING_HEART_RATE_SOURCE_WATCH,
                cadenceSource = SettingsRepository.RECORDING_SENSOR_SOURCE_WATCH_GPS,
                speedSource = SettingsRepository.RECORDING_SENSOR_SOURCE_WATCH_GPS,
                distanceSource = SettingsRepository.RECORDING_SENSOR_SOURCE_WATCH_GPS,
                externalHeartRateAddress = null,
                externalRunPodAddress = null,
            ),
        )
    }

    @Test
    fun `linked external sources are reported as not connected yet`() {
        val warning =
            resolveRecordingStartWarning(
                heartRateSource = SettingsRepository.RECORDING_HEART_RATE_SOURCE_STRAP,
                cadenceSource = SettingsRepository.RECORDING_SENSOR_SOURCE_POD,
                speedSource = SettingsRepository.RECORDING_SENSOR_SOURCE_WATCH_GPS,
                distanceSource = SettingsRepository.RECORDING_SENSOR_SOURCE_POD,
                externalHeartRateAddress = "AA:BB:CC:DD:EE:01",
                externalRunPodAddress = "AA:BB:CC:DD:EE:02",
            )

        requireNotNull(warning)
        assertTrue(warning.unlinkedDevices.isEmpty())
        assertEquals(listOf("heart-rate strap", "external sensor"), warning.disconnectedDevices)
        assertTrue(warning.message.contains("Recording will try to connect"))
    }

    @Test
    fun `connected external sources do not require a warning`() {
        assertNull(
            resolveRecordingStartWarning(
                heartRateSource = SettingsRepository.RECORDING_HEART_RATE_SOURCE_STRAP,
                cadenceSource = SettingsRepository.RECORDING_SENSOR_SOURCE_POD,
                speedSource = SettingsRepository.RECORDING_SENSOR_SOURCE_WATCH_GPS,
                distanceSource = SettingsRepository.RECORDING_SENSOR_SOURCE_POD,
                externalHeartRateAddress = "aa:bb:cc:dd:ee:01",
                externalRunPodAddress = "aa:bb:cc:dd:ee:02",
                connectedExternalAddresses =
                    setOf(
                        "AA:BB:CC:DD:EE:01",
                        "AA:BB:CC:DD:EE:02",
                    ),
            ),
        )
    }

    @Test
    fun `selected external source without a linked device is explicit`() {
        val warning =
            resolveRecordingStartWarning(
                heartRateSource = SettingsRepository.RECORDING_HEART_RATE_SOURCE_STRAP,
                cadenceSource = SettingsRepository.RECORDING_SENSOR_SOURCE_WATCH_GPS,
                speedSource = SettingsRepository.RECORDING_SENSOR_SOURCE_WATCH_GPS,
                distanceSource = SettingsRepository.RECORDING_SENSOR_SOURCE_WATCH_GPS,
                externalHeartRateAddress = null,
                externalRunPodAddress = null,
            )

        requireNotNull(warning)
        assertEquals(listOf("heart-rate strap"), warning.unlinkedDevices)
        assertTrue(warning.disconnectedDevices.isEmpty())
        assertTrue(warning.message.contains("No linked device"))
    }
}
