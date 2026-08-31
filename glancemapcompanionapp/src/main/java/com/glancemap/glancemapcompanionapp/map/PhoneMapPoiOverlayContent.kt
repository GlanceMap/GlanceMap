package com.glancemap.glancemapcompanionapp.map

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.maplibre.poiIdAt
import com.glancemap.glancemapcompanionapp.map.maplibre.renderPois
import com.glancemap.trailcore.poi.PoiType
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.roundToInt

@Composable
internal fun synchronizePoiOverlay(
    runtime: MapRuntime,
    pois: List<PhoneMapPoi>,
    isVisible: Boolean,
    settings: PhoneMapPoiSettings,
) {
    val currentRuntime by rememberUpdatedState(runtime)
    val currentPois by rememberUpdatedState(pois)
    val currentIsVisible by rememberUpdatedState(isVisible)
    val currentSettings by rememberUpdatedState(settings)
    LaunchedEffect(runtime.map, runtime.generation.styleRevision, pois, isVisible, settings) {
        runtime.withCurrentLoadedStyle(latestRuntime = { currentRuntime }) { _, mapView, style ->
            style.renderPois(
                pois = currentPois,
                isVisible = currentIsVisible,
                settings = currentSettings,
                context = mapView.context,
            )
        }
    }
}

@Composable
internal fun observePoiViewport(
    runtime: MapRuntime,
    isVisible: Boolean,
    onViewportChanged: (PhoneMapViewport) -> Unit,
) {
    val currentRuntime by rememberUpdatedState(runtime)
    val currentOnViewportChanged by rememberUpdatedState(onViewportChanged)
    DisposableEffect(runtime.map, isVisible) {
        val activeMap = runtime.map
        if (activeMap == null || !isVisible) return@DisposableEffect onDispose {}

        val listener =
            MapLibreMap.OnCameraIdleListener {
                if (runtime.isCurrentIn(currentRuntime)) {
                    activeMap.phoneMapViewportOrNull()?.let(currentOnViewportChanged)
                }
            }
        activeMap.addOnCameraIdleListener(listener)
        listener.onCameraIdle()
        onDispose { activeMap.removeOnCameraIdleListener(listener) }
    }
}

@Composable
internal fun observePoiSelection(
    runtime: MapRuntime,
    pois: List<PhoneMapPoi>,
    isVisible: Boolean,
    onPoiSelected: (PhoneMapPoi) -> Unit,
) {
    val poiById = remember(pois) { pois.associateBy(PhoneMapPoi::id) }
    val currentRuntime by rememberUpdatedState(runtime)
    val currentPois by rememberUpdatedState(poiById)
    val currentOnPoiSelected by rememberUpdatedState(onPoiSelected)
    DisposableEffect(runtime.map, isVisible) {
        val activeMap = runtime.map
        if (activeMap == null || !isVisible) return@DisposableEffect onDispose {}

        val listener =
            MapLibreMap.OnMapClickListener { point ->
                if (!runtime.isCurrentIn(currentRuntime)) {
                    false
                } else {
                    val poi =
                        activeMap
                            .poiIdAt(activeMap.projection.toScreenLocation(point))
                            ?.let(currentPois::get)
                    if (poi == null) {
                        false
                    } else {
                        currentOnPoiSelected(poi)
                        true
                    }
                }
            }
        activeMap.addOnMapClickListener(listener)
        onDispose { activeMap.removeOnMapClickListener(listener) }
    }
}

@Composable
internal fun phoneMapPoiDetailsCard(
    poi: PhoneMapPoi,
    isMetric: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val details = poi.details
    PhoneMapPopupCard(
        modifier = modifier,
        title = poi.label ?: stringResource(poi.type.labelResource()),
        onDismiss = onDismiss,
    ) {
        Text(text = stringResource(poi.type.labelResource()))
        details?.typeLabel?.let { typeLabel -> Text(text = typeLabel) }
        details?.elevationMeters?.let { elevationMeters ->
            Text(
                text =
                    stringResource(
                        if (isMetric) {
                            R.string.map_poi_elevation_value
                        } else {
                            R.string.map_poi_elevation_imperial_value
                        },
                        if (isMetric) elevationMeters else (elevationMeters * METERS_TO_FEET).roundToInt(),
                    ),
            )
        }
        details?.sleepingPlaces?.let { sleepingPlaces ->
            Text(
                text =
                    pluralStringResource(
                        R.plurals.map_poi_sleeping_places,
                        sleepingPlaces,
                        sleepingPlaces,
                    ),
            )
        }
        details?.state?.let { state ->
            Text(text = stringResource(R.string.map_poi_state_value, state))
        }
        details?.shortDescription?.let { description -> Text(text = description) }
    }
}

private const val METERS_TO_FEET = 3.28084

private fun PoiType.labelResource(): Int =
    when (this) {
        PoiType.PEAK -> R.string.map_poi_type_peak
        PoiType.WATER -> R.string.map_poi_type_water
        PoiType.HUT -> R.string.map_poi_type_hut
        PoiType.CAMP -> R.string.map_poi_type_camp
        PoiType.FOOD -> R.string.map_poi_type_food
        PoiType.TOILET -> R.string.map_poi_type_toilet
        PoiType.TRANSPORT -> R.string.map_poi_type_transport
        PoiType.BIKE -> R.string.map_poi_type_bike
        PoiType.VIEWPOINT -> R.string.map_poi_type_viewpoint
        PoiType.PARKING -> R.string.map_poi_type_parking
        PoiType.SHOP -> R.string.map_poi_type_shop
        PoiType.GENERIC -> R.string.map_poi_type_generic
        PoiType.CUSTOM -> R.string.map_poi_type_custom
    }

private fun MapLibreMap.phoneMapViewportOrNull(): PhoneMapViewport? =
    runCatching {
        val bounds = projection.visibleRegion.latLngBounds
        PhoneMapViewport(
            minLat = bounds.latitudeSouth,
            maxLat = bounds.latitudeNorth,
            minLon = bounds.longitudeWest,
            maxLon = bounds.longitudeEast,
            zoom = cameraPosition.zoom,
        )
    }.getOrNull()
