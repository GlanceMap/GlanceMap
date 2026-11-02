package com.glancemap.glancemapcompanionapp.map

import android.os.SystemClock
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val PHONE_WORLDWIND_SLOW_FRAME_NANOS = 16_666_667L
private const val PHONE_WORLDWIND_NANOS_PER_MILLI = 1_000_000L

/** Lock-free frame durations, summarized as a count, slow count and worst case. */
internal class PhoneWorldWindFrameStats(
    private val slowNanos: Long = PHONE_WORLDWIND_SLOW_FRAME_NANOS,
) {
    private val count = AtomicLong()
    private val slow = AtomicLong()
    private val max = AtomicLong()

    fun record(nanos: Long) {
        count.incrementAndGet()
        if (nanos > slowNanos) slow.incrementAndGet()
        max.accumulateAndGet(nanos, ::maxOf)
    }

    fun summary(name: String): String {
        val maxMillis = max.get() / PHONE_WORLDWIND_NANOS_PER_MILLI
        return "${name}_frames=${count.get()} ${name}_slow=${slow.get()} ${name}_max_ms=$maxMillis"
    }
}

/** Aggregates are inexpensive; detailed events are recorded only during an explicit phone capture. */
@Suppress("TooManyFunctions") // One small recorder per capture event keeps call sites free of counter details.
internal class PhoneWorldWindDiagnostics : Closeable {
    private val started = SystemClock.elapsedRealtime()
    val hits = AtomicInteger()
    val misses = AtomicInteger()
    val coalesced = AtomicInteger()
    val cancelled = AtomicInteger()
    val evicted = AtomicInteger()
    val renders = AtomicInteger()
    val rendered256 = AtomicInteger()
    val rendered512 = AtomicInteger()
    val demCacheHits = AtomicInteger()
    val demCacheMisses = AtomicInteger()
    val demMissing = AtomicInteger()
    val demStandard = AtomicInteger()
    val demDetailed = AtomicInteger()
    val elevationRequests = AtomicInteger()
    val elevationMillis = AtomicLong()
    val elevationIncomplete = AtomicInteger()
    val demUnpacks = AtomicInteger()
    val demUnpackMillis = AtomicLong()
    val demFailures = AtomicInteger()
    val tileFailures = AtomicInteger()
    val tileRetriesScheduled = AtomicInteger()
    val tileRetriesFired = AtomicInteger()
    val cameraReanchors = AtomicInteger()
    val renderFrames = PhoneWorldWindFrameStats()
    val glFrames = PhoneWorldWindFrameStats()

    /** Time from a finger movement until a frame showing it finishes drawing; slow above two 60 Hz frames. */
    val moveToFrame = PhoneWorldWindFrameStats(slowNanos = 2 * PHONE_WORLDWIND_SLOW_FRAME_NANOS)
    private val firstImage = AtomicBoolean()
    private val firstElevation = AtomicBoolean()
    private var summaryProvider: () -> String = { "" }
    private val removeListener = PhoneDebugCapture.addBeforeStopListener { summary() }

    fun setSummaryProvider(provider: () -> String) {
        summaryProvider = provider
    }

    fun tile(
        source: String,
        key: PhoneWorldWindTileKey,
        ms: Long,
    ) {
        if (!PhoneDebugCapture.isActive()) return
        val tile = key.tile
        PhoneDebugCapture.log(
            "PhoneWorldWind",
            "event=tile source=$source z=${tile.z} x=${tile.x} y=${tile.y} " +
                "resolution=${key.resolution} ms=$ms ${summaryProvider()}",
        )
    }

    fun rendered(
        key: PhoneWorldWindTileKey,
        ms: Long,
    ) {
        renders.incrementAndGet()
        if (key.resolution == 512) rendered512.incrementAndGet() else rendered256.incrementAndGet()
        tile("rendered", key, ms)
        if (firstImage.compareAndSet(false, true)) {
            event("first_imagery", "elapsed_ms=${SystemClock.elapsedRealtime() - started}")
        }
    }

    fun elevation(
        ms: Long,
        samples: Int,
        valid: Int,
    ) {
        elevationRequests.incrementAndGet()
        elevationMillis.addAndGet(ms)
        event("elevation", "ms=$ms samples=$samples valid=$valid")
        if (valid > 0 && firstElevation.compareAndSet(false, true)) {
            event("first_elevation", "elapsed_ms=${SystemClock.elapsedRealtime() - started}")
        }
    }

    fun demUnpacked(
        tileId: String,
        ms: Long,
    ) {
        demUnpacks.incrementAndGet()
        demUnpackMillis.addAndGet(ms)
        event("dem_unpack", "tile=$tileId ms=$ms")
    }

    fun elevationIncomplete() {
        elevationIncomplete.incrementAndGet()
        event("elevation_incomplete", "retry=worldwind")
    }

    fun demFailure(
        tileId: String,
        error: Throwable,
    ) {
        demFailures.incrementAndGet()
        event("dem_failed", "tile=$tileId error=${error::class.simpleName}")
    }

    /** [reason] must never contain a URL: online tile URLs can carry provider API keys. */
    fun tileFailed(
        source: String,
        reason: String,
    ) {
        tileFailures.incrementAndGet()
        event("tile_failed", "source=$source reason=$reason")
    }

    fun tileRetryScheduled() {
        tileRetriesScheduled.incrementAndGet()
    }

    fun tileRetryFired() {
        tileRetriesFired.incrementAndGet()
        event("tile_retry", "fired=${tileRetriesFired.get()}")
    }

    fun cameraReanchored(detail: String) {
        cameraReanchors.incrementAndGet()
        event("camera_reanchor", detail)
    }

    fun demAccess(
        cacheHit: Boolean,
        axisLength: Int?,
    ) {
        if (cacheHit) demCacheHits.incrementAndGet() else demCacheMisses.incrementAndGet()
        if (cacheHit) return
        when {
            axisLength == null -> demMissing.incrementAndGet()
            axisLength >= 3600 -> demDetailed.incrementAndGet()
            else -> demStandard.incrementAndGet()
        }
    }

    fun event(
        name: String,
        detail: String,
    ) {
        if (PhoneDebugCapture.isActive()) PhoneDebugCapture.log("PhoneWorldWind", "event=$name $detail")
    }

    fun summary() {
        val runtime = Runtime.getRuntime()
        event(
            "summary",
            "hits=${hits.get()} misses=${misses.get()} coalesced=${coalesced.get()} " +
                "cancelled=${cancelled.get()} evictions=${evicted.get()} renders=${renders.get()} " +
                "tiles256=${rendered256.get()} tiles512=${rendered512.get()} " +
                "dem_hits=${demCacheHits.get()} dem_misses=${demCacheMisses.get()} dem_missing=${demMissing.get()} " +
                "dem_standard=${demStandard.get()} dem_detailed=${demDetailed.get()} " +
                "elevation_requests=${elevationRequests.get()} elevation_ms=${elevationMillis.get()} " +
                "elevation_incomplete=${elevationIncomplete.get()} dem_failures=${demFailures.get()} " +
                "dem_unpacks=${demUnpacks.get()} dem_unpack_ms=${demUnpackMillis.get()} " +
                "tile_failures=${tileFailures.get()} tile_retries_scheduled=${tileRetriesScheduled.get()} " +
                "tile_retries_fired=${tileRetriesFired.get()} camera_reanchors=${cameraReanchors.get()} " +
                "${renderFrames.summary("render")} ${glFrames.summary("gl")} " +
                "${moveToFrame.summary("move_to_frame")} " +
                "heap_used=${runtime.totalMemory() - runtime.freeMemory()} ${summaryProvider()}",
        )
    }

    override fun close() {
        summary()
        removeListener()
    }
}
