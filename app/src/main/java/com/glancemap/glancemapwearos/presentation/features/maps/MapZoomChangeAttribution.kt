package com.glancemap.glancemapwearos.presentation.features.maps

import android.os.SystemClock
import org.mapsforge.map.android.view.MapView
import java.util.WeakHashMap

/** Carries a programmatic zoom's source to the map-position observer that confirms it. */
internal object MapZoomChangeAttribution {
    private const val ATTRIBUTION_TIMEOUT_MS = 1_000L

    private data class PendingSource(
        val inputSource: String,
        val expiresAtElapsedMs: Long,
    )

    private val pendingSources = WeakHashMap<MapView, PendingSource>()

    fun prepare(
        mapView: MapView,
        inputSource: String,
    ) {
        synchronized(pendingSources) {
            pendingSources[mapView] =
                PendingSource(
                    inputSource = inputSource,
                    expiresAtElapsedMs = SystemClock.elapsedRealtime() + ATTRIBUTION_TIMEOUT_MS,
                )
        }
    }

    fun consume(mapView: MapView): String =
        synchronized(pendingSources) {
            val pendingSource = pendingSources.remove(mapView)
            if (pendingSource?.expiresAtElapsedMs ?: Long.MIN_VALUE >= SystemClock.elapsedRealtime()) {
                pendingSource?.inputSource ?: "mapsforge_gesture_or_external"
            } else {
                "mapsforge_gesture_or_external"
            }
        }
}
