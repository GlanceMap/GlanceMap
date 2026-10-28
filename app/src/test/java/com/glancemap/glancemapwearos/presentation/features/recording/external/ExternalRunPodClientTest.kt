package com.glancemap.glancemapwearos.presentation.features.recording.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalRunPodClientTest {
    @Test
    fun decodesBasicRunningSpeedAndCadenceMeasurement() {
        val measurement =
            ExternalRunPodClient.decodeRscMeasurement(
                byteArrayOf(
                    0x00,
                    0x80.toByte(),
                    0x02,
                    164.toByte(),
                ),
            )

        requireNotNull(measurement)
        assertEquals(2.5f, measurement.speedMps)
        assertEquals(164, measurement.cadenceSpm)
        assertNull(measurement.rawTotalDistanceUnits)
        assertNull(measurement.totalDistanceMeters)
    }

    @Test
    fun decodesRunningMeasurementWithStrideAndDistance() {
        val measurement =
            ExternalRunPodClient.decodeRscMeasurement(
                byteArrayOf(
                    0x03,
                    0x00,
                    0x03,
                    180.toByte(),
                    0x78,
                    0x00,
                    0x39,
                    0x30,
                    0x00,
                    0x00,
                ),
            )

        requireNotNull(measurement)
        assertEquals(3.0f, measurement.speedMps)
        assertEquals(180, measurement.cadenceSpm)
        assertEquals(12_345L, measurement.rawTotalDistanceUnits)
        assertEquals(1_234.5, measurement.totalDistanceMeters)
    }

    @Test
    fun rejectsTruncatedOptionalRunningMeasurementFields() {
        assertNull(
            ExternalRunPodClient.decodeRscMeasurement(
                byteArrayOf(
                    0x02,
                    0x00,
                    0x03,
                    180.toByte(),
                    0x39,
                    0x30,
                ),
            ),
        )
    }

    @Test
    fun preservesMeasurementWhileRejectingImplausibleRunningSpeed() {
        val measurement =
            ExternalRunPodClient.decodeRscMeasurement(
                byteArrayOf(
                    0x00,
                    0x00,
                    0x0D,
                    180.toByte(),
                ),
            )

        requireNotNull(measurement)
        assertNull(measurement.speedMps)
        assertEquals(180, measurement.cadenceSpm)
    }

    @Test
    fun decodesCyclingPowerMeasurement() {
        val measurement =
            ExternalRunPodClient.decodeCyclingPowerMeasurement(
                byteArrayOf(
                    0x00,
                    0x00,
                    0x2C,
                    0x01,
                ),
            )

        requireNotNull(measurement)
        assertEquals(300, measurement.powerWatts)
    }

    @Test
    fun rejectsTruncatedCyclingPowerMeasurement() {
        assertNull(
            ExternalRunPodClient.decodeCyclingPowerMeasurement(
                byteArrayOf(0x00, 0x00, 0x2C),
            ),
        )
    }

    @Test
    fun preservesCyclingPowerMeasurementWhileRejectingImplausiblePower() {
        val measurement =
            ExternalRunPodClient.decodeCyclingPowerMeasurement(
                byteArrayOf(
                    0x00,
                    0x00,
                    0xB8.toByte(),
                    0x0B,
                ),
            )

        requireNotNull(measurement)
        assertNull(measurement.powerWatts)
    }

    @Test
    fun decodesCyclingSpeedCadenceMeasurementFromDeltas() {
        val decoder = CyclingSpeedCadenceDecoder(wheelCircumferenceMeters = 2.105)

        val first =
            decoder.decode(
                cscMeasurement(
                    wheelRevolutions = 1_000,
                    wheelEventTime = 1_000,
                    crankRevolutions = 50,
                    crankEventTime = 1_000,
                ),
                timeMillis = 1_000L,
            )
        requireNotNull(first)
        assertNull(first.speedMps)
        assertNull(first.wheelIntervalSeconds)
        assertNull(first.cadenceSpm)
        assertEquals(2_105.0, first.totalDistanceMeters!!, 0.01)

        val second =
            decoder.decode(
                cscMeasurement(
                    wheelRevolutions = 1_010,
                    wheelEventTime = 2_024,
                    crankRevolutions = 51,
                    crankEventTime = 2_024,
                ),
                timeMillis = 2_000L,
            )

        requireNotNull(second)
        assertEquals(21.05f, second.speedMps!!, 0.01f)
        assertEquals(1.0, second.wheelIntervalSeconds!!, 0.001)
        assertEquals(60, second.cadenceSpm)
        assertEquals(2_126.05, second.totalDistanceMeters!!, 0.01)
        assertEquals(1_010L, second.rawTotalDistanceUnits)
    }

    @Test
    fun decodesCyclingSpeedCadenceAcrossBleRollovers() {
        val decoder = CyclingSpeedCadenceDecoder(wheelCircumferenceMeters = 2.105)

        decoder.decode(
            cscMeasurement(
                wheelRevolutions = 4_294_967_294L,
                wheelEventTime = 65_530,
                crankRevolutions = 65_535,
                crankEventTime = 65_000,
            ),
            timeMillis = 1_000L,
        )

        val measurement =
            decoder.decode(
                cscMeasurement(
                    wheelRevolutions = 3,
                    wheelEventTime = 506,
                    crankRevolutions = 0,
                    crankEventTime = 488,
                ),
                timeMillis = 2_000L,
            )

        requireNotNull(measurement)
        assertEquals(21.05f, measurement.speedMps!!, 0.01f)
        assertEquals(60, measurement.cadenceSpm)
    }
}

private fun cscMeasurement(
    wheelRevolutions: Long,
    wheelEventTime: Int,
    crankRevolutions: Int,
    crankEventTime: Int,
): ByteArray =
    byteArrayOf(0x03) +
        uint32Le(wheelRevolutions) +
        uint16Le(wheelEventTime) +
        uint16Le(crankRevolutions) +
        uint16Le(crankEventTime)

private fun uint16Le(value: Int): ByteArray =
    byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
    )

private fun uint32Le(value: Long): ByteArray =
    byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
    )
