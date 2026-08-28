package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.MapPosition
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.cache.TileCache
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.model.common.Observer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes

private const val PHONE_OFFLINE_TILE_CACHE_CAPACITY = 64
private const val PHONE_OFFLINE_TILE_CACHE_ID = "phone-offline"

@Composable
internal fun offlineMapSurface(
    map: PhoneOfflineMap,
    initialCamera: PhoneMapCameraSnapshot,
    onCameraChanged: (PhoneMapCameraSnapshot) -> Unit,
    onMapError: (PhoneOfflineMapError) -> Unit,
) {
    val currentOnCameraChanged by rememberUpdatedState(onCameraChanged)
    val currentOnMapError by rememberUpdatedState(onMapError)
    var view by remember(map.file.absolutePath) { mutableStateOf<PhoneOfflineMapsforgeView?>(null) }

    AndroidView(
        factory = { context ->
            PhoneOfflineMapsforgeView(
                context = context,
                offlineMap = map,
                initialCamera = initialCamera,
                onCameraChanged = { currentOnCameraChanged(it) },
                onMapError = { currentOnMapError(it) },
            ).also { view = it }
        },
        modifier = Modifier.fillMaxSize(),
    )

    DisposableEffect(view) {
        onDispose { view?.dispose() }
    }
}

/** Minimal phone-only Mapsforge holder. It intentionally owns just one base-map layer and cache. */
private class PhoneOfflineMapsforgeView(
    context: Context,
    offlineMap: PhoneOfflineMap,
    initialCamera: PhoneMapCameraSnapshot,
    private val onCameraChanged: (PhoneMapCameraSnapshot) -> Unit,
    private val onMapError: (PhoneOfflineMapError) -> Unit,
) : FrameLayout(context) {
    private var mapView: MapView? = null
    private var tileCache: TileCache? = null
    private var mapFile: MapFile? = null
    private var tileLayer: TileRendererLayer? = null
    private var disposed = false
    private val cameraObserver = Observer { publishCamera() }

    init {
        if (!isPhoneOfflineMapCandidate(offlineMap.file)) {
            postMapError(PhoneOfflineMapError.MISSING)
        } else {
            runCatching {
                AndroidGraphicFactory.createInstance(context.applicationContext)
                val openedMapFile = MapFile(offlineMap.file)
                mapFile = openedMapFile
                val mapView =
                    MapView(context).apply {
                        isClickable = true
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setBuiltInZoomControls(false)
                        mapScaleBar.isVisible = false
                        setZoomLevelMin(openedMapFile.mapFileInfo.zoomLevelMin)
                        setZoomLevelMax(openedMapFile.mapFileInfo.zoomLevelMax)
                        model.mapViewPosition.setMapPosition(
                            initialCamera.forMap(openedMapFile).toMapsforgeMapPosition(),
                            false,
                        )
                    }
                this.mapView = mapView
                val tileCache =
                    AndroidUtil.createTileCache(
                        context.applicationContext,
                        PHONE_OFFLINE_TILE_CACHE_ID,
                        PHONE_OFFLINE_TILE_CACHE_CAPACITY,
                        1f,
                        mapView.model.frameBufferModel.overdrawFactor,
                        false,
                    )
                this.tileCache = tileCache
                val tileLayer =
                    TileRendererLayer(
                        tileCache,
                        openedMapFile,
                        mapView.model.mapViewPosition,
                        AndroidGraphicFactory.INSTANCE,
                    ).apply {
                        setXmlRenderTheme(MapsforgeThemes.DEFAULT)
                    }
                this.tileLayer = tileLayer
                mapView.layerManager.layers.add(tileLayer)
                mapView.model.mapViewPosition.addObserver(cameraObserver)
                addView(
                    mapView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                publishCamera()
            }.onFailure {
                dispose()
                postMapError(PhoneOfflineMapError.INVALID)
            }
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        val activeMapView = mapView
        activeMapView?.model?.mapViewPosition?.removeObserver(cameraObserver)
        tileLayer?.let { layer ->
            activeMapView?.layerManager?.layers?.remove(layer)
            runCatching { layer.onDestroy() }
        } ?: runCatching { mapFile?.close() }
        tileLayer = null
        mapFile = null
        runCatching { tileCache?.destroy() }
        tileCache = null
        runCatching { activeMapView?.destroyAll() }
        mapView = null
        removeAllViews()
    }

    private fun publishCamera() {
        if (!disposed) {
            mapView?.model?.mapViewPosition?.mapPosition?.let { position ->
                runCatching {
                    PhoneMapCameraSnapshot(
                        latitude = position.latLong.latitude,
                        longitude = position.latLong.longitude,
                        zoom = position.zoomLevel.toDouble(),
                    )
                }.getOrNull()?.let { snapshot ->
                    post { if (!disposed) onCameraChanged(snapshot) }
                }
            }
        }
    }

    private fun postMapError(error: PhoneOfflineMapError) {
        post { onMapError(error) }
    }
}

private fun PhoneMapCameraSnapshot.toMapsforgeMapPosition(): MapPosition =
    MapPosition(
        LatLong(latitude, longitude),
        zoom.toInt().coerceIn(0, Byte.MAX_VALUE.toInt()).toByte(),
    )

private fun PhoneMapCameraSnapshot.forMap(mapFile: MapFile): PhoneMapCameraSnapshot {
    val bounds = mapFile.boundingBox()
    if (bounds.contains(latitude, longitude)) return this
    val start = mapFile.startPosition() ?: bounds.centerPoint
    return PhoneMapCameraSnapshot(
        latitude = start.latitude,
        longitude = start.longitude,
        zoom = mapFile.startZoomLevel()?.toDouble() ?: mapFile.mapFileInfo.zoomLevelMin.toDouble(),
    )
}
