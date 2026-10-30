package com.glancemap.glancemapwearos.presentation.features.navigate.motion

/** Keeps the visual prediction horizon aligned with the GPS cadence selected by the user. */
internal class MarkerPredictionCadence(
    configuredIntervalMs: Long,
) {
    private var configuredIntervalMs = sanitizeInterval(configuredIntervalMs)

    fun updateConfiguredInterval(intervalMs: Long) {
        val sanitizedIntervalMs = sanitizeInterval(intervalMs)
        if (sanitizedIntervalMs == configuredIntervalMs) return
        configuredIntervalMs = sanitizedIntervalMs
    }

    fun expectedIntervalMs(): Long = configuredIntervalMs

    fun predictionWindow(
        configuredFreshnessMaxAgeMs: Long,
        serviceFreshnessMaxAgeMs: Long,
        startDelayMs: Long,
    ): MarkerPredictionWindow {
        val expectedIntervalMs = expectedIntervalMs()
        val freshnessLimitMs =
            minOf(
                configuredFreshnessMaxAgeMs.takeIf { it > 0L } ?: Long.MAX_VALUE,
                serviceFreshnessMaxAgeMs.takeIf { it > 0L } ?: Long.MAX_VALUE,
            ).coerceAtLeast(startDelayMs)
        val observedHorizonMs =
            expectedIntervalMs + maxOf(MIN_PREDICTION_GRACE_MS, expectedIntervalMs / 2L)
        val stopAtMs =
            minOf(freshnessLimitMs, observedHorizonMs)
                .coerceAtLeast(startDelayMs)
        val fullSpeedUntilMs =
            minOf(
                expectedIntervalMs,
                (stopAtMs - MIN_EASING_WINDOW_MS).coerceAtLeast(startDelayMs),
            )
        return MarkerPredictionWindow(
            startDelayMs = startDelayMs,
            fullSpeedUntilMs = fullSpeedUntilMs,
            stopAtMs = stopAtMs,
        )
    }

    private fun sanitizeInterval(intervalMs: Long): Long = intervalMs.coerceIn(CONFIGURED_INTERVAL_RANGE)
}

private const val MIN_CONFIGURED_INTERVAL_MS = 1_000L
private const val MAX_CONFIGURED_INTERVAL_MS = 30_000L
private val CONFIGURED_INTERVAL_RANGE = MIN_CONFIGURED_INTERVAL_MS..MAX_CONFIGURED_INTERVAL_MS
private const val MIN_PREDICTION_GRACE_MS = 500L
private const val MIN_EASING_WINDOW_MS = 400L
