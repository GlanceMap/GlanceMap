package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

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
