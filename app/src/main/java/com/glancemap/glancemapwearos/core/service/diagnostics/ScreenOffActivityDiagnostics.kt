package com.glancemap.glancemapwearos.core.service.diagnostics

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class DataLayerEventContext(
    val type: String,
    val path: String?,
    val displayInteractive: Boolean?,
    val transferActive: Boolean,
    val activeTransferId: String?,
)

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
    val dataLayerMessageCount: Long,
    val dataLayerChannelOpenedCount: Long,
    val dataLayerPeerConnectedCount: Long,
    val dataLayerPeerDisconnectedCount: Long,
    val lastDataLayerEvent: DataLayerEventContext?,
)

internal data class DataLayerActivityCounters(
    val callbackCount: Long,
    val messageCount: Long,
    val channelOpenedCount: Long,
    val peerConnectedCount: Long,
    val peerDisconnectedCount: Long,
    val lastEvent: DataLayerEventContext?,
)

internal class DataLayerActivityCounterStore(
    private val captureActive: AtomicBoolean,
) {
    private val callbackCount = AtomicLong()
    private val messageCount = AtomicLong()
    private val channelOpenedCount = AtomicLong()
    private val peerConnectedCount = AtomicLong()
    private val peerDisconnectedCount = AtomicLong()
    private val lastEvent = AtomicReference<DataLayerEventContext?>(null)

    fun recordCallback() {
        if (captureActive.get()) callbackCount.incrementAndGet()
    }

    fun recordMessage() =
        record(
            total = callbackCount,
            specific = messageCount,
        )

    fun recordChannelOpened() =
        record(
            total = callbackCount,
            specific = channelOpenedCount,
        )

    fun recordPeerConnected() =
        record(
            total = callbackCount,
            specific = peerConnectedCount,
        )

    fun recordPeerDisconnected() =
        record(
            total = callbackCount,
            specific = peerDisconnectedCount,
        )

    fun recordLastEvent(context: DataLayerEventContext) {
        if (captureActive.get()) lastEvent.set(context)
    }

    fun snapshotAndReset(): DataLayerActivityCounters =
        DataLayerActivityCounters(
            callbackCount = callbackCount.getAndSet(0L),
            messageCount = messageCount.getAndSet(0L),
            channelOpenedCount = channelOpenedCount.getAndSet(0L),
            peerConnectedCount = peerConnectedCount.getAndSet(0L),
            peerDisconnectedCount = peerDisconnectedCount.getAndSet(0L),
            lastEvent = lastEvent.getAndSet(null),
        )

    private fun record(
        total: AtomicLong,
        specific: AtomicLong,
    ) {
        if (!captureActive.get()) return
        total.incrementAndGet()
        specific.incrementAndGet()
    }
}

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
    internal val dataLayer = DataLayerActivityCounterStore(captureActive)

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
        dataLayer.recordCallback()
    }

    fun snapshotAndReset(): ScreenOffActivityCounters {
        val dataLayerCounters = dataLayer.snapshotAndReset()
        return ScreenOffActivityCounters(
            orientationFrameCount = orientationFrameCount.getAndSet(0L),
            orientationFrameNonInteractiveCount = orientationFrameNonInteractiveCount.getAndSet(0L),
            liveHudTickCount = liveHudTickCount.getAndSet(0L),
            debugOverlayTickCount = debugOverlayTickCount.getAndSet(0L),
            mapRedrawRequestCount = mapRedrawRequestCount.getAndSet(0L),
            mapViewportCallbackCount = mapViewportCallbackCount.getAndSet(0L),
            locationCallbackCount = locationCallbackCount.getAndSet(0L),
            compassCallbackCount = compassCallbackCount.getAndSet(0L),
            dataLayerCallbackCount = dataLayerCounters.callbackCount,
            dataLayerMessageCount = dataLayerCounters.messageCount,
            dataLayerChannelOpenedCount = dataLayerCounters.channelOpenedCount,
            dataLayerPeerConnectedCount = dataLayerCounters.peerConnectedCount,
            dataLayerPeerDisconnectedCount = dataLayerCounters.peerDisconnectedCount,
            lastDataLayerEvent = dataLayerCounters.lastEvent,
        )
    }
}
