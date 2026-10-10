package com.glancemap.glancemapwearos.presentation.features.maps

import java.util.concurrent.atomic.AtomicBoolean

internal class MapStartupTilePrewarmPolicy {
    private val allowed = AtomicBoolean(true)
    var lastDrawnZoom: Byte? = null
        private set

    fun onDrawZoom(zoom: Byte): Boolean {
        val changed = lastDrawnZoom?.let { it != zoom } ?: false
        lastDrawnZoom = zoom
        if (changed) allowed.set(false)
        return changed
    }

    fun isAllowed(): Boolean = allowed.get()
}
