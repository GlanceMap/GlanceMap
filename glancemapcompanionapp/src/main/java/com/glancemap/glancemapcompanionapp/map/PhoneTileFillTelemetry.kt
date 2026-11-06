package com.glancemap.glancemapcompanionapp.map

import java.util.Locale

/** One completed fill of the visible viewport, from first missing tile to fully drawable. */
internal data class PhoneTileFillEvent(
    val firstViewport: Boolean,
    val fillMs: Long,
    val drawableAtStart: Int,
    val coverage: PhoneFirstVisibleTileCoverage,
    val renderedDuringFill: Int = 0,
) {
    /** Tiles rendered per second while this fill was timed; null when too short to rate. */
    val tilesPerSecond: Double?
        get() = if (fillMs > 0L && renderedDuringFill > 0) renderedDuringFill * MILLIS_PER_SECOND / fillMs else null
}

/**
 * Measures how long the visible tiles take to become drawable after the first draw and after each
 * later pan or zoom leaves tiles missing. Pure so the timing rules can be tested off-device.
 */
internal class PhoneTileFillTracker {
    private var incompleteSinceMs: Long? = null
    private var drawableAtStart = 0
    private var renderedAtStart = 0
    private var firstViewportReported = false

    /** True while a fill is being timed; callers sample coverage on every draw meanwhile. */
    val isFilling: Boolean
        get() = incompleteSinceMs != null

    fun onFirstDraw(
        nowMs: Long,
        coverage: PhoneFirstVisibleTileCoverage,
        renderedCount: Int = 0,
    ) {
        incompleteSinceMs = nowMs
        drawableAtStart = coverage.drawableVisibleTiles
        renderedAtStart = renderedCount
    }

    fun onCoverage(
        coverage: PhoneFirstVisibleTileCoverage,
        nowMs: Long,
        renderedCount: Int = 0,
    ): PhoneTileFillEvent? {
        val complete =
            coverage.drawableVisibleTiles >= coverage.visibleTileCount && coverage.pendingJobCount <= 0
        val since = incompleteSinceMs
        return when {
            coverage.visibleTileCount <= 0 -> null
            !complete -> {
                if (since == null) {
                    incompleteSinceMs = nowMs
                    drawableAtStart = coverage.drawableVisibleTiles
                    renderedAtStart = renderedCount
                }
                null
            }
            since == null -> null
            else -> completeFill(since, nowMs, coverage, renderedCount)
        }
    }

    private fun completeFill(
        sinceMs: Long,
        nowMs: Long,
        coverage: PhoneFirstVisibleTileCoverage,
        renderedCount: Int,
    ): PhoneTileFillEvent {
        incompleteSinceMs = null
        val first = !firstViewportReported
        firstViewportReported = true
        return PhoneTileFillEvent(
            firstViewport = first,
            fillMs = (nowMs - sinceMs).coerceAtLeast(0L),
            drawableAtStart = drawableAtStart,
            coverage = coverage,
            renderedDuringFill = (renderedCount - renderedAtStart).coerceAtLeast(0),
        )
    }
}

/** One debug-capture line per completed fill; `diskReads` counts tiles reused from a reopened bucket. */
internal fun phoneViewportFillDetail(
    rendererId: Long,
    layerId: Int,
    zoom: Int,
    fill: PhoneTileFillEvent,
    stats: PhoneMapsforgeTileCacheStats?,
): String =
    buildString {
        append("renderer=").append(rendererId)
        append(" layer=").append(layerId)
        append(" first=").append(fill.firstViewport)
        append(" zoom=").append(zoom)
        append(" fillMs=").append(fill.fillMs)
        append(" visible=").append(fill.coverage.visibleTileCount)
        append(" drawableAtStart=").append(fill.drawableAtStart)
        append(" renderedDuringFill=").append(fill.renderedDuringFill)
        append(" tilesPerSec=").append(fill.tilesPerSecond?.let { String.format(Locale.US, "%.1f", it) } ?: "na")
        append(" renderedTiles=").append(stats?.renderedTilesStored ?: "unknown")
        append(" diskReads=").append(stats?.diskFallbackReads ?: "unknown")
        append(" diskReadFailures=").append(stats?.diskFallbackFailures ?: "unknown")
    }

private const val MILLIS_PER_SECOND = 1_000.0
