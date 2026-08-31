package com.glancemap.glancemapwearos.core.service.diagnostics

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class ScreenOffActivityCounters(
    val orientationFrameCount: Long,
    val orientationFrameNonInteractiveCount: Long,
    val liveHudTickCount: Long,
    val debugOverlayTickCount: Long,
    val mapRedrawRequestCount: Long,
    val mapViewportCallbackCount: Long,
    val locationCallbackCount: Long,
    val compassCallbackCount: Long,
    val dataLayerCallbackCount: Long,
)

/** Cheap process-wide counters, reset with each periodic energy sample. */
internal object ScreenOffActivityDiagnostics {
    private val captureActive = AtomicBoolean(false)
    private val orientationFrameCount = AtomicLong()
    private val orientationFrameNonInteractiveCount = AtomicLong()
    private val liveHudTickCount = AtomicLong()
    private val debugOverlayTickCount = AtomicLong()
    private val mapRedrawRequestCount = AtomicLong()
    private val mapViewportCallbackCount = AtomicLong()
    private val locationCallbackCount = AtomicLong()
    private val compassCallbackCount = AtomicLong()
    private val dataLayerCallbackCount = AtomicLong()

    fun configure(enabled: Boolean) {
        if (captureActive.getAndSet(enabled) != enabled) snapshotAndReset()
    }

    fun recordOrientationFrame(isInteractive: Boolean) {
        if (!captureActive.get()) return
        orientationFrameCount.incrementAndGet()
        if (!isInteractive) orientationFrameNonInteractiveCount.incrementAndGet()
    }

    fun recordLiveHudTick() {
        if (captureActive.get()) liveHudTickCount.incrementAndGet()
    }

    fun recordDebugOverlayTick() {
        if (captureActive.get()) debugOverlayTickCount.incrementAndGet()
    }

    fun recordMapRedrawRequest() {
        if (captureActive.get()) mapRedrawRequestCount.incrementAndGet()
    }

    fun recordMapViewportCallback() {
        if (captureActive.get()) mapViewportCallbackCount.incrementAndGet()
    }

    fun recordLocationCallback() {
        if (captureActive.get()) locationCallbackCount.incrementAndGet()
    }

    fun recordCompassCallback() {
        if (captureActive.get()) compassCallbackCount.incrementAndGet()
    }

    fun recordDataLayerCallback() {
        if (captureActive.get()) dataLayerCallbackCount.incrementAndGet()
    }

    fun snapshotAndReset(): ScreenOffActivityCounters =
        ScreenOffActivityCounters(
            orientationFrameCount = orientationFrameCount.getAndSet(0L),
            orientationFrameNonInteractiveCount = orientationFrameNonInteractiveCount.getAndSet(0L),
            liveHudTickCount = liveHudTickCount.getAndSet(0L),
            debugOverlayTickCount = debugOverlayTickCount.getAndSet(0L),
            mapRedrawRequestCount = mapRedrawRequestCount.getAndSet(0L),
            mapViewportCallbackCount = mapViewportCallbackCount.getAndSet(0L),
            locationCallbackCount = locationCallbackCount.getAndSet(0L),
            compassCallbackCount = compassCallbackCount.getAndSet(0L),
            dataLayerCallbackCount = dataLayerCallbackCount.getAndSet(0L),
        )
}
