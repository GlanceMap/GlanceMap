package com.glancemap.glancemapwearos.presentation.features.navigate

import com.glancemap.glancemapwearos.data.repository.PoiType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidBitmap
import org.mapsforge.map.layer.overlay.Marker

internal suspend fun preparePoiMarkerBitmapsOnIo(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    prepare: suspend () -> Map<PoiType, AndroidBitmap>,
): Map<PoiType, AndroidBitmap> {
    // withContext may discard its result when cancellation interrupts dispatch back to the UI.
    var prepared = emptyMap<PoiType, AndroidBitmap>()
    var transferred = false
    try {
        withContext(dispatcher) { prepared = prepare() }
        transferred = true
        return prepared
    } finally {
        if (!transferred) prepared.values.forEach(AndroidBitmap::decrementRefCount)
    }
}

internal fun createPoiOverlayMarker(
    latLong: LatLong,
    bitmap: AndroidBitmap,
): Marker {
    bitmap.incrementRefCount()
    var markerCreated = false
    return try {
        object : Marker(latLong, bitmap, 0, 0) {
            @Synchronized
            override fun onDestroy() {
                // MapView teardown and Compose disposal can both destroy this marker.
                setBitmap(null)
            }
        }.also { markerCreated = true }
    } finally {
        if (!markerCreated) bitmap.decrementRefCount()
    }
}
