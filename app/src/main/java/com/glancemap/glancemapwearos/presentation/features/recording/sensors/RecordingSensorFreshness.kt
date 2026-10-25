package com.glancemap.glancemapwearos.presentation.features.recording.sensors

internal const val RECORDING_SENSOR_MAX_AGE_MS = 15_000L

internal fun RecordingSensorMetrics.freshForRecording(nowMillis: Long): RecordingSensorMetrics? {
    val snapshot =
        RecordingSensorMetrics(
            heartRateBpm = heartRateBpm?.takeIf { heartRateUpdatedAtMillis.isFreshRecordingSensorTime(nowMillis) },
            stepCount = stepCount?.takeIf { stepCountUpdatedAtMillis.isFreshRecordingSensorTime(nowMillis) },
            cadenceSpm = cadenceSpm?.takeIf { cadenceUpdatedAtMillis.isFreshRecordingSensorTime(nowMillis) },
            externalPowerWatts =
                externalPowerWatts?.takeIf {
                    externalPowerUpdatedAtMillis.isFreshRecordingSensorTime(
                        nowMillis,
                    )
                },
            barometricPressureHpa =
                barometricPressureHpa?.takeIf {
                    barometricPressureUpdatedAtMillis.isFreshRecordingSensorTime(nowMillis)
                },
        )
    return snapshot.takeUnless {
        it.heartRateBpm == null &&
            it.stepCount == null &&
            it.cadenceSpm == null &&
            it.externalPowerWatts == null &&
            it.barometricPressureHpa == null
    }
}

internal fun Long.isFreshRecordingSensorTime(nowMillis: Long): Boolean {
    val ageMillis = nowMillis - this
    return this > 0L && ageMillis in 0L..RECORDING_SENSOR_MAX_AGE_MS
}

internal fun recordingSensorMeasurementTimeMillis(
    sourceElapsedMillis: Long,
    callbackElapsedMillis: Long,
    callbackWallMillis: Long,
): Long? =
    sourceElapsedMillis.takeIf { it in 1L..callbackElapsedMillis }?.let {
        callbackWallMillis - (callbackElapsedMillis - it)
    }
