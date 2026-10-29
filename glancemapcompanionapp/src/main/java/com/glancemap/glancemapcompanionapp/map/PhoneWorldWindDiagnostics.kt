package com.glancemap.glancemapcompanionapp.map

import android.os.SystemClock
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Aggregates are inexpensive; detailed events are recorded only during an explicit phone capture. */
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
                "heap_used=${runtime.totalMemory() - runtime.freeMemory()} ${summaryProvider()}",
        )
    }

    override fun close() {
        summary()
        removeListener()
    }
}
