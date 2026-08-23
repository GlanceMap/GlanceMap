package com.glancemap.glancemapwearos.presentation.features.navigate

import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.glancemap.glancemapwearos.core.service.diagnostics.MapHotPathDiagnostics
import org.mapsforge.map.android.view.MapView

@Composable
internal fun NavigateMapReadyEffect(
    mapView: MapView?,
    onMapViewReadyForRendering: () -> Unit,
) {
    DisposableEffect(mapView, onMapViewReadyForRendering) {
        if (mapView == null) return@DisposableEffect onDispose {}

        val focusListener =
            ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                MapHotPathDiagnostics.recordEvent(
                    stage = "map_lifecycle",
                    status = if (hasFocus) "window_focus" else "window_blur",
                    detail =
                        "mapView=${System.identityHashCode(mapView)} attached=${mapView.isAttachedToWindow} " +
                            "size=${mapView.width}x${mapView.height}",
                )
                if (hasFocus && mapView.isAttachedToWindow && mapView.width > 0 && mapView.height > 0) {
                    onMapViewReadyForRendering()
                }
            }

        val observer = mapView.viewTreeObserver
        observer.addOnWindowFocusChangeListener(focusListener)

        onDispose {
            MapHotPathDiagnostics.recordEvent(
                stage = "map_lifecycle",
                status = "map_ready_effect_disposed",
                detail = "mapView=${System.identityHashCode(mapView)} attached=${mapView.isAttachedToWindow}",
            )
            if (observer.isAlive) {
                observer.removeOnWindowFocusChangeListener(focusListener)
            }
        }
    }
}
