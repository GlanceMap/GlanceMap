package com.glancemap.glancemapwearos.presentation.features.navigate

/** Keeps only the final crown zoom target until the next display frame. */
internal class CrownZoomCoalescer {
    private var pendingZoom: Int? = null
    private var frameScheduled = false

    fun enqueue(
        currentZoom: Int,
        step: Int,
        minZoom: Int,
        maxZoom: Int,
    ): Boolean {
        val baseZoom = pendingZoom ?: currentZoom
        val nextZoom = (baseZoom + step).coerceIn(minZoom, maxZoom)
        if (nextZoom == baseZoom) return false

        pendingZoom = nextZoom
        return true
    }

    fun shouldScheduleFrame(): Boolean = !frameScheduled && pendingZoom != null

    fun consumeFrameTarget(): Int? {
        frameScheduled = false
        val target = pendingZoom
        pendingZoom = null
        return target
    }

    fun markFrameScheduled() {
        frameScheduled = true
    }
}
