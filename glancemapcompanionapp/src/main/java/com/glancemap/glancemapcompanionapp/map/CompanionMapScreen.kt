package com.glancemap.glancemapcompanionapp.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.ensureMapLibreConfigured
import com.glancemap.glancemapcompanionapp.map.maplibre.fitGpxTrackBounds
import com.glancemap.glancemapcompanionapp.map.maplibre.mapLibreRasterStyleJson
import com.glancemap.glancemapcompanionapp.map.maplibre.renderGpxTrack
import com.glancemap.trailcore.map.MapContentVisibility
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

private val defaultMapCamera = CameraUpdateFactory.newLatLngZoom(LatLng(20.0, 0.0), 2.0)
private const val RECENTER_ZOOM = 14.0
private val locationPermissions =
    arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

private data class MapRuntime(
    val map: MapLibreMap?,
    val mapView: MapView?,
    val style: Style?,
)

private data class MapLocationState(
    val hasPermission: Boolean,
    val pendingRecenter: Boolean,
)

private data class GpxOverlayState(
    val track: PhoneMapGpxTrack?,
    val segments: List<PhoneMapRouteSegment>,
    val isVisible: Boolean,
)

/** Keeps Compose-owned MapLibre, permission, and GPX overlay sequencing in one visible flow. */
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun CompanionMapScreen(
    gpxTrack: PhoneMapGpxTrack?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var hasLocationPermission by remember(context) { mutableStateOf(context.hasLocationPermission()) }
    var pendingRecenter by remember { mutableStateOf(false) }
    var contentVisibility by remember { mutableStateOf(MapContentVisibility()) }
    var fittedGpxTrackId by remember { mutableStateOf<String?>(null) }
    val gpxSegments = remember(gpxTrack) { gpxTrack?.toRouteSegments().orEmpty() }
    val hasRenderableGpxTrack = gpxSegments.isNotEmpty()
    val mapRuntime = MapRuntime(map = map, mapView = mapView, style = style)
    val locationState =
        MapLocationState(
            hasPermission = hasLocationPermission,
            pendingRecenter = pendingRecenter,
        )
    val gpxOverlayState =
        GpxOverlayState(
            track = gpxTrack,
            segments = gpxSegments,
            isVisible = contentVisibility.gpxTracks,
        )
    val locationPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions ->
            hasLocationPermission = permissions.values.any { granted -> granted }
            pendingRecenter = hasLocationPermission
        }

    mapViewLifecycle(mapView)

    synchronizeMapLocation(
        runtime = mapRuntime,
        locationState = locationState,
        onRecenterHandled = { pendingRecenter = false },
        context = context,
    )
    synchronizeGpxOverlay(
        runtime = mapRuntime,
        overlayState = gpxOverlayState,
        fittedGpxTrackId = fittedGpxTrackId,
        onTrackFitted = { fittedGpxTrackId = it },
    )

    Box(modifier = Modifier.fillMaxSize()) {
        mapSurface(
            onMapViewCreated = { mapView = it },
            onMapReady = { map = it },
            onStyleReady = { style = it },
        )

        mapControls(
            gpxTracksVisible = contentVisibility.gpxTracks.takeIf { hasRenderableGpxTrack },
            onBack = onBack,
            onRecenter = {
                if (context.hasLocationPermission()) {
                    hasLocationPermission = true
                    pendingRecenter = true
                } else {
                    locationPermissionLauncher.launch(locationPermissions)
                }
            },
            onGpxVisibilityToggle =
                if (hasRenderableGpxTrack) {
                    {
                        contentVisibility =
                            contentVisibility.copy(gpxTracks = !contentVisibility.gpxTracks)
                    }
                } else {
                    null
                },
        )
    }
}

@Composable
private fun synchronizeMapLocation(
    runtime: MapRuntime,
    locationState: MapLocationState,
    onRecenterHandled: () -> Unit,
    context: Context,
) {
    LaunchedEffect(runtime.map, runtime.style, locationState.hasPermission) {
        if (!locationState.hasPermission) return@LaunchedEffect
        val activeMap = runtime.map ?: return@LaunchedEffect
        val activeStyle = runtime.style ?: return@LaunchedEffect
        activeMap.enableLocationPuck(style = activeStyle, context = context)
    }

    LaunchedEffect(
        locationState.pendingRecenter,
        runtime.map,
        runtime.style,
        locationState.hasPermission,
    ) {
        if (!locationState.pendingRecenter || !locationState.hasPermission) return@LaunchedEffect
        val activeMap = runtime.map ?: return@LaunchedEffect
        val activeStyle = runtime.style ?: return@LaunchedEffect
        activeMap.recenterOnLocation(style = activeStyle, context = context)
        onRecenterHandled()
    }
}

@Composable
private fun synchronizeGpxOverlay(
    runtime: MapRuntime,
    overlayState: GpxOverlayState,
    fittedGpxTrackId: String?,
    onTrackFitted: (String) -> Unit,
) {
    LaunchedEffect(
        runtime.map,
        runtime.mapView,
        runtime.style,
        overlayState.track?.id,
        overlayState.segments,
        overlayState.isVisible,
    ) {
        val activeStyle = runtime.style ?: return@LaunchedEffect
        activeStyle.renderGpxTrack(
            segments = overlayState.segments,
            isVisible = overlayState.isVisible,
        )
        val activeMap = runtime.map ?: return@LaunchedEffect
        val activeMapView = runtime.mapView ?: return@LaunchedEffect
        val trackId = overlayState.track?.id ?: return@LaunchedEffect
        if (overlayState.isVisible && overlayState.segments.isNotEmpty() && fittedGpxTrackId != trackId) {
            activeMap.fitGpxTrackBounds(
                mapView = activeMapView,
                segments = overlayState.segments,
                onFitted = { onTrackFitted(trackId) },
            )
        }
    }
}

@Composable
private fun mapSurface(
    onMapViewCreated: (MapView) -> Unit,
    onMapReady: (MapLibreMap) -> Unit,
    onStyleReady: (Style) -> Unit,
) {
    AndroidView(
        factory = { viewContext ->
            createMapView(
                context = viewContext,
                onCreated = onMapViewCreated,
                onMapReady = onMapReady,
                onStyleReady = onStyleReady,
            )
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun mapControls(
    gpxTracksVisible: Boolean?,
    onBack: () -> Unit,
    onRecenter: () -> Unit,
    onGpxVisibilityToggle: (() -> Unit)?,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        FilledTonalIconButton(
            onClick = onBack,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.common_action_back),
            )
        }
        Column(
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalIconButton(onClick = onRecenter) {
                Icon(
                    imageVector = Icons.Filled.MyLocation,
                    contentDescription = stringResource(R.string.map_recenter_content_description),
                )
            }
            if (onGpxVisibilityToggle != null) {
                FilledTonalIconButton(onClick = onGpxVisibilityToggle) {
                    Icon(
                        imageVector =
                            if (gpxTracksVisible == true) {
                                Icons.Filled.Visibility
                            } else {
                                Icons.Filled.VisibilityOff
                            },
                        contentDescription =
                            stringResource(
                                if (gpxTracksVisible == true) {
                                    R.string.map_gpx_hide_content_description
                                } else {
                                    R.string.map_gpx_show_content_description
                                },
                            ),
                    )
                }
            }
        }
    }
}

private fun createMapView(
    context: Context,
    onCreated: (MapView) -> Unit,
    onMapReady: (MapLibreMap) -> Unit,
    onStyleReady: (Style) -> Unit,
): MapView {
    ensureMapLibreConfigured(context)
    return MapView(context).also { mapView ->
        onCreated(mapView)
        mapView.onCreate(null)
        mapView.getMapAsync { map ->
            if (mapView.isDestroyed) return@getMapAsync
            onMapReady(map)
            map.setStyle(
                Style.Builder().fromJson(
                    PhoneMapRendererCatalog.mainOnlineRasterProvider.mapLibreRasterStyleJson(),
                ),
            ) { style ->
                if (!mapView.isDestroyed) {
                    map.moveCamera(defaultMapCamera)
                    onStyleReady(style)
                }
            }
        }
    }
}

/** Keeps MapLibre's ordered lifecycle callbacks together rather than splitting the state machine. */
@Suppress("CyclomaticComplexMethod", "DEPRECATION", "OVERRIDE_DEPRECATION")
@Composable
private fun mapViewLifecycle(mapView: MapView?) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val applicationContext = LocalContext.current.applicationContext

    DisposableEffect(mapView, lifecycleOwner, applicationContext) {
        if (mapView == null) {
            return@DisposableEffect onDispose {}
        }

        var started = false
        var resumed = false
        var destroyed = false

        fun start() {
            if (!destroyed && !started) {
                mapView.onStart()
                started = true
            }
        }

        fun resume() {
            if (!destroyed && !resumed) {
                mapView.onResume()
                resumed = true
            }
        }

        fun pause() {
            if (!destroyed && resumed) {
                mapView.onPause()
                resumed = false
            }
        }

        fun stop() {
            if (!destroyed && started) {
                mapView.onStop()
                started = false
            }
        }

        fun destroy() {
            if (!destroyed) {
                pause()
                stop()
                mapView.onDestroy()
                destroyed = true
            }
        }

        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> start()
                    Lifecycle.Event.ON_RESUME -> resume()
                    Lifecycle.Event.ON_PAUSE -> pause()
                    Lifecycle.Event.ON_STOP -> stop()
                    Lifecycle.Event.ON_DESTROY -> destroy()
                    else -> Unit
                }
            }
        val memoryCallbacks =
            object : ComponentCallbacks2 {
                override fun onConfigurationChanged(newConfig: Configuration) = Unit

                override fun onLowMemory() {
                    if (!destroyed) {
                        mapView.onLowMemory()
                    }
                }

                override fun onTrimMemory(level: Int) {
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                        onLowMemory()
                    }
                }
            }

        lifecycleOwner.lifecycle.addObserver(observer)
        applicationContext.registerComponentCallbacks(memoryCallbacks)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            start()
        }
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            resume()
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            applicationContext.unregisterComponentCallbacks(memoryCallbacks)
            destroy()
        }
    }
}

@SuppressLint("MissingPermission")
private fun MapLibreMap.enableLocationPuck(
    style: Style,
    context: Context,
) {
    val locationComponent = locationComponent
    if (!locationComponent.isLocationComponentActivated) {
        locationComponent.activateLocationComponent(
            LocationComponentActivationOptions.builder(context.applicationContext, style).build(),
        )
    }
    locationComponent.isLocationComponentEnabled = true
    locationComponent.cameraMode = CameraMode.NONE
}

@SuppressLint("MissingPermission")
private fun MapLibreMap.recenterOnLocation(
    style: Style,
    context: Context,
) {
    enableLocationPuck(style = style, context = context)
    locationComponent.lastKnownLocation?.let { location ->
        animateCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(location.latitude, location.longitude),
                RECENTER_ZOOM,
            ),
        )
    }
}

private fun Context.hasLocationPermission(): Boolean =
    locationPermissions.any { permission ->
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
