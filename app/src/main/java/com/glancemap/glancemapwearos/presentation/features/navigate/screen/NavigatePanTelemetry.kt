package com.glancemap.glancemapwearos.presentation.features.navigate

import android.os.SystemClock
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import java.util.Locale

/**
 * Debug-only measurements for a single finger pan. This distinguishes delayed Mapsforge
 * viewport updates from sparse input events and route-tool overlay invalidations.
 */
internal class NavigatePanTelemetry(
    private val nowElapsedMs: () -> Long = SystemClock::elapsedRealtime,
    private val debugEnabled: () -> Boolean = DebugTelemetry::isEnabled,
    private val log: (String) -> Unit = { message -> DebugTelemetry.log(NAVIGATION_TELEMETRY_TAG, message) },
) {
    private var active = false
    private var sessionId = 0L
    private var startedAtElapsedMs = 0L
    private var lastInputMoveAtElapsedMs = 0L
    private var lastViewportUpdateAtElapsedMs = 0L
    private var inputMoveCount = 0
    private var viewportUpdateCount = 0
    private var routeToolOverlayRefreshCount = 0
    private var firstViewportLatencyMs: Long? = null
    private var maxInputGapMs = 0L
    private var maxViewportGapMs = 0L
    private var slowViewportGapCount = 0

    fun onPanStarted(
        navMode: NavMode,
        routeToolSessionActive: Boolean,
        zoomLevel: Int,
    ) {
        if (!debugEnabled()) return
        if (active) {
            onPanFinished(
                navMode = navMode,
                reason = "interrupted",
                zoomLevel = zoomLevel,
            )
        }
        active = true
        sessionId += 1L
        startedAtElapsedMs = nowElapsedMs()
        lastInputMoveAtElapsedMs = 0L
        lastViewportUpdateAtElapsedMs = 0L
        inputMoveCount = 0
        viewportUpdateCount = 0
        routeToolOverlayRefreshCount = 0
        firstViewportLatencyMs = null
        maxInputGapMs = 0L
        maxViewportGapMs = 0L
        slowViewportGapCount = 0
        log(
            "event=map_pan stage=start id=$sessionId navMode=${navMode.name} " +
                "routeToolActive=$routeToolSessionActive zoom=$zoomLevel",
        )
    }

    fun onInputMove() {
        if (!active || !debugEnabled()) return
        val now = nowElapsedMs()
        if (lastInputMoveAtElapsedMs > 0L) {
            maxInputGapMs = maxOf(maxInputGapMs, now - lastInputMoveAtElapsedMs)
        }
        lastInputMoveAtElapsedMs = now
        inputMoveCount += 1
    }

    fun onViewportChanged(routeToolOverlayRefreshed: Boolean) {
        if (!active || !debugEnabled()) return
        val now = nowElapsedMs()
        if (firstViewportLatencyMs == null) {
            firstViewportLatencyMs = (now - startedAtElapsedMs).coerceAtLeast(0L)
        }
        if (lastViewportUpdateAtElapsedMs > 0L) {
            val gapMs = (now - lastViewportUpdateAtElapsedMs).coerceAtLeast(0L)
            maxViewportGapMs = maxOf(maxViewportGapMs, gapMs)
            if (gapMs >= SLOW_VIEWPORT_GAP_MS) slowViewportGapCount += 1
        }
        lastViewportUpdateAtElapsedMs = now
        viewportUpdateCount += 1
        if (routeToolOverlayRefreshed) routeToolOverlayRefreshCount += 1
    }

    fun onPanFinished(
        navMode: NavMode,
        reason: String,
        zoomLevel: Int,
    ): NavigationPanTelemetrySummary? {
        if (!active) return null
        if (!debugEnabled()) {
            reset()
            return null
        }
        val durationMs = (nowElapsedMs() - startedAtElapsedMs).coerceAtLeast(0L)
        val summary =
            NavigationPanTelemetrySummary(
                durationMs = durationMs,
                inputMoveCount = inputMoveCount,
                maxInputGapMs = maxInputGapMs,
                viewportUpdateCount = viewportUpdateCount,
                firstViewportLatencyMs = firstViewportLatencyMs,
                maxViewportGapMs = maxViewportGapMs,
                slowViewportGapCount = slowViewportGapCount,
                routeToolOverlayRefreshCount = routeToolOverlayRefreshCount,
            )
        log(
            "event=map_pan stage=end id=$sessionId reason=$reason navMode=${navMode.name} zoom=$zoomLevel " +
                "durationMs=${summary.durationMs} inputMoves=${summary.inputMoveCount} " +
                "inputHz=${summary.inputMovesPerSecond.formatPanRate()} " +
                "inputMaxGapMs=${summary.maxInputGapMs} viewportUpdates=${summary.viewportUpdateCount} " +
                "viewportHz=${summary.viewportUpdatesPerSecond.formatPanRate()} " +
                "firstViewportLatencyMs=${summary.firstViewportLatencyMs ?: "na"} " +
                "viewportMaxGapMs=${summary.maxViewportGapMs} " +
                "slowViewportGaps=${summary.slowViewportGapCount} " +
                "routeToolRefreshes=${summary.routeToolOverlayRefreshCount}",
        )
        reset()
        return summary
    }

    private fun reset() {
        active = false
        startedAtElapsedMs = 0L
        lastInputMoveAtElapsedMs = 0L
        lastViewportUpdateAtElapsedMs = 0L
        inputMoveCount = 0
        viewportUpdateCount = 0
        routeToolOverlayRefreshCount = 0
        firstViewportLatencyMs = null
        maxInputGapMs = 0L
        maxViewportGapMs = 0L
        slowViewportGapCount = 0
    }
}

internal data class NavigationPanTelemetrySummary(
    val durationMs: Long,
    val inputMoveCount: Int,
    val maxInputGapMs: Long,
    val viewportUpdateCount: Int,
    val firstViewportLatencyMs: Long?,
    val maxViewportGapMs: Long,
    val slowViewportGapCount: Int,
    val routeToolOverlayRefreshCount: Int,
) {
    val inputMovesPerSecond: Float
        get() = eventsPerSecond(inputMoveCount, durationMs)

    val viewportUpdatesPerSecond: Float
        get() = eventsPerSecond(viewportUpdateCount, durationMs)
}

private fun eventsPerSecond(
    count: Int,
    durationMs: Long,
): Float =
    if (durationMs > 0L) {
        count * 1_000f / durationMs
    } else {
        0f
    }

private fun Float.formatPanRate(): String = "%.1f".format(Locale.US, this)

private const val NAVIGATION_TELEMETRY_TAG = "NavigationTelemetry"
private const val SLOW_VIEWPORT_GAP_MS = 50L
