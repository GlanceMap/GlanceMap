package com.glancemap.glancemapcompanionapp.map

internal fun phoneCompassSampleIsFreshForRestart(
    sampleNanos: Long,
    restartNanos: Long,
    nowNanos: Long,
): Boolean = sampleNanos > 0L && sampleNanos in restartNanos..nowNanos

internal fun phoneCompassFusedCallbackIsCurrent(
    started: Boolean,
    pipeline: PhoneCompassPipeline,
    currentGeneration: Long,
    callbackGeneration: Long,
): Boolean = started && pipeline == PhoneCompassPipeline.GOOGLE_FUSED && currentGeneration == callbackGeneration

internal class PhoneCompassFallbackMemory {
    var preferSensor = false
        private set

    fun recordSample(
        provider: PhoneCompassProviderMode,
        pipeline: PhoneCompassPipeline,
        renderable: Boolean,
        sampleElapsedMillis: Long?,
    ): Boolean {
        val sensorPipeline = pipeline != PhoneCompassPipeline.NONE && pipeline != PhoneCompassPipeline.GOOGLE_FUSED
        val usableSample = renderable && (sampleElapsedMillis ?: 0L) > 0L
        val remember = provider == PhoneCompassProviderMode.GOOGLE_FUSED && sensorPipeline && usableSample
        if (remember) preferSensor = true
        return remember
    }

    fun clear() {
        preferSensor = false
    }
}
