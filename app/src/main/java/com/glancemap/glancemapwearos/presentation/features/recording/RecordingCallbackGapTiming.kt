package com.glancemap.glancemapwearos.presentation.features.recording

internal data class RecordingCallbackGapTiming(
    val callbackGapMillis: Long,
    val expectedIntervalMillis: Long,
)

internal class RecordingCallbackGapTracker(
    initialEffectiveIntervalMillis: Long,
) {
    private var previousCallbackElapsedMillis = Long.MIN_VALUE
    private var currentEffectiveIntervalMillis = initialEffectiveIntervalMillis.coerceAtLeast(0L)
    private var maximumEffectiveIntervalMillis = currentEffectiveIntervalMillis

    fun updateEffectiveInterval(intervalMillis: Long) {
        currentEffectiveIntervalMillis = intervalMillis.coerceAtLeast(0L)
        maximumEffectiveIntervalMillis = maxOf(maximumEffectiveIntervalMillis, currentEffectiveIntervalMillis)
    }

    fun reset() {
        previousCallbackElapsedMillis = Long.MIN_VALUE
        maximumEffectiveIntervalMillis = currentEffectiveIntervalMillis
    }

    fun observeCallback(callbackElapsedMillis: Long): RecordingCallbackGapTiming {
        val previousCallbackElapsed = previousCallbackElapsedMillis
        previousCallbackElapsedMillis = callbackElapsedMillis
        val timing =
            if (previousCallbackElapsed == Long.MIN_VALUE || callbackElapsedMillis <= previousCallbackElapsed) {
                RecordingCallbackGapTiming(
                    callbackGapMillis = 0L,
                    expectedIntervalMillis = maximumEffectiveIntervalMillis,
                )
            } else {
                RecordingCallbackGapTiming(
                    callbackGapMillis = callbackElapsedMillis - previousCallbackElapsed,
                    expectedIntervalMillis = maximumEffectiveIntervalMillis,
                )
            }
        maximumEffectiveIntervalMillis = currentEffectiveIntervalMillis
        return timing
    }
}
