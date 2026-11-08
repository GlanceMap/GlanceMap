package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import java.util.concurrent.atomic.AtomicLong

internal data class WorldWindVisibleTiles(
    val ready: Int,
    val total: Int,
    val failed: Int = 0,
)

internal class WorldWindRenderProgress {
    private var quietSince: Long? = null

    fun update(
        visible: WorldWindVisibleTiles?,
        pendingElevation: Int,
        pendingImagery: Int,
        nowMs: Long,
    ): Int? =
        if (visible == null) {
            quietSince = null
            null
        } else {
            val percentage =
                if (visible.total > 0) {
                    (visible.ready.coerceIn(0, visible.total) * 100L / visible.total).toInt()
                } else {
                    100
                }
            val unseenImageryPending = visible.total == 0 && pendingImagery > 0
            if (percentage < 100 || pendingElevation > 0 || unseenImageryPending) {
                quietSince = null
                percentage.coerceAtMost(99)
            } else {
                val start = quietSince ?: nowMs.also { quietSince = it }
                if (nowMs - start >= 500) 100 else 99
            }
        }
}

/**
 * Times how long the loading ring takes to reach completion. A busy period starts when progress
 * drops below 100% and ends when it reaches 100%; [pause] discards a period interrupted by screen-off.
 */
internal class WorldWindRenderTiming(
    private val sessionStartedMs: Long,
) {
    private var busySince: Long? = sessionStartedMs

    @Volatile var firstCompleteMs: Long? = null
        private set
    val completions = AtomicLong()
    val busyTotalMs = AtomicLong()
    val busyMaxMs = AtomicLong()

    /** Returns the duration of a busy period that just completed, otherwise null. */
    fun update(
        complete: Boolean,
        nowMs: Long,
    ): Long? {
        val since = busySince
        val finished = if (complete && since != null) nowMs - since else null
        when {
            !complete -> if (since == null) busySince = nowMs
            finished != null -> {
                busySince = null
                if (firstCompleteMs == null) firstCompleteMs = nowMs - sessionStartedMs
                completions.incrementAndGet()
                busyTotalMs.addAndGet(finished)
                busyMaxMs.updateAndGet { maxOf(it, finished) }
            }
        }
        return finished
    }

    fun pause() {
        busySince = null
    }

    fun logFields(): String =
        "render_first_complete_ms=${firstCompleteMs ?: "unavailable"} " +
            "render_completions=${completions.get()} render_busy_ms_total=${busyTotalMs.get()} " +
            "render_busy_ms_max=${busyMaxMs.get()}"
}
