package com.glancemap.glancemapcompanionapp.map

/** One completed fill of the visible viewport, from first missing tile to fully drawable. */
internal data class PhoneTileFillEvent(
    val firstViewport: Boolean,
    val fillMs: Long,
    val drawableAtStart: Int,
    val coverage: PhoneFirstVisibleTileCoverage,
)

/**
 * Measures how long the visible tiles take to become drawable after the first draw and after each
 * later pan or zoom leaves tiles missing. Pure so the timing rules can be tested off-device.
 */
internal class PhoneTileFillTracker {
    private var incompleteSinceMs: Long? = null
    private var drawableAtStart = 0
    private var firstViewportReported = false

    /** True while a fill is being timed; callers sample coverage on every draw meanwhile. */
    val isFilling: Boolean
        get() = incompleteSinceMs != null

    fun onFirstDraw(
        nowMs: Long,
        coverage: PhoneFirstVisibleTileCoverage,
    ) {
        incompleteSinceMs = nowMs
        drawableAtStart = coverage.drawableVisibleTiles
    }

    fun onCoverage(
        coverage: PhoneFirstVisibleTileCoverage,
        nowMs: Long,
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
                }
                null
            }
            since == null -> null
            else -> completeFill(since, nowMs, coverage)
        }
    }

    private fun completeFill(
        sinceMs: Long,
        nowMs: Long,
        coverage: PhoneFirstVisibleTileCoverage,
    ): PhoneTileFillEvent {
        incompleteSinceMs = null
        val first = !firstViewportReported
        firstViewportReported = true
        return PhoneTileFillEvent(
            firstViewport = first,
            fillMs = (nowMs - sinceMs).coerceAtLeast(0L),
            drawableAtStart = drawableAtStart,
            coverage = coverage,
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
        append(" renderedTiles=").append(stats?.renderedTilesStored ?: "unknown")
        append(" diskReads=").append(stats?.diskFallbackReads ?: "unknown")
        append(" diskReadFailures=").append(stats?.diskFallbackFailures ?: "unknown")
    }
