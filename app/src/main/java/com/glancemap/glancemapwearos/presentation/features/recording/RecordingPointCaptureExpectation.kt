package com.glancemap.glancemapwearos.presentation.features.recording

import kotlin.math.floor

/** Tracks the configured cadence of samples that recording can store, not raw provider callbacks. */
internal class RecordingPointCaptureExpectation {
    private var activeSinceElapsedMs = 0L
    private var activeIntervalMs = 0L
    private var accumulatedExpectedSamples = 0.0
    private var running = false

    fun start(
        nowElapsedMs: Long,
        intervalMs: Long,
    ) {
        activeSinceElapsedMs = nowElapsedMs
        activeIntervalMs = intervalMs.coerceAtLeast(1_000L)
        accumulatedExpectedSamples = 0.0
        running = true
    }

    fun updateInterval(
        nowElapsedMs: Long,
        intervalMs: Long,
    ) {
        if (!running) return
        accumulateUntil(nowElapsedMs)
        activeSinceElapsedMs = nowElapsedMs
        activeIntervalMs = intervalMs.coerceAtLeast(1_000L)
    }

    fun pause(nowElapsedMs: Long) {
        if (!running) return
        accumulateUntil(nowElapsedMs)
        running = false
    }

    fun resume(
        nowElapsedMs: Long,
        intervalMs: Long,
    ) = startSegment(nowElapsedMs, intervalMs)

    fun expectedPointCount(nowElapsedMs: Long): Int {
        val total = accumulatedExpectedSamples + if (running) expectedSinceActive(nowElapsedMs) else 0.0
        return (floor(total).toLong() + 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun startSegment(
        nowElapsedMs: Long,
        intervalMs: Long,
    ) {
        activeSinceElapsedMs = nowElapsedMs
        activeIntervalMs = intervalMs.coerceAtLeast(1_000L)
        running = true
    }

    private fun accumulateUntil(nowElapsedMs: Long) {
        accumulatedExpectedSamples += expectedSinceActive(nowElapsedMs)
    }

    private fun expectedSinceActive(nowElapsedMs: Long): Double = (nowElapsedMs - activeSinceElapsedMs).coerceAtLeast(0L).toDouble() / activeIntervalMs.toDouble()
}
