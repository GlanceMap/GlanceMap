package com.glancemap.glancemapcompanionapp.map

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.maplibre.poiIdAt
import com.glancemap.glancemapcompanionapp.map.maplibre.renderPois
import com.glancemap.trailcore.poi.PoiType
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

@Composable
internal fun synchronizePoiOverlay(
    style: Style?,
    pois: List<PhoneMapPoi>,
    isVisible: Boolean,
) {
    LaunchedEffect(style, pois, isVisible) {
        style?.renderPois(pois = pois, isVisible = isVisible)
    }
}

@Composable
internal fun observePoiViewport(
    map: MapLibreMap?,
    isVisible: Boolean,
    onViewportChanged: (PhoneMapViewport) -> Unit,
) {
    val currentOnViewportChanged by rememberUpdatedState(onViewportChanged)
    DisposableEffect(map, isVisible) {
        val activeMap = map
        if (activeMap == null || !isVisible) return@DisposableEffect onDispose {}

        val listener =
            MapLibreMap.OnCameraIdleListener {
                activeMap.phoneMapViewportOrNull()?.let(currentOnViewportChanged)
            }
        activeMap.addOnCameraIdleListener(listener)
        listener.onCameraIdle()
        onDispose { activeMap.removeOnCameraIdleListener(listener) }
    }
}

@Composable
internal fun observePoiSelection(
    map: MapLibreMap?,
    pois: List<PhoneMapPoi>,
    isVisible: Boolean,
    onPoiSelected: (PhoneMapPoi) -> Unit,
) {
    val poiById = remember(pois) { pois.associateBy(PhoneMapPoi::id) }
    val currentPois by rememberUpdatedState(poiById)
    val currentOnPoiSelected by rememberUpdatedState(onPoiSelected)
    DisposableEffect(map, isVisible) {
        val activeMap = map
        if (activeMap == null || !isVisible) return@DisposableEffect onDispose {}

        val listener =
            MapLibreMap.OnMapClickListener { point ->
                val poi = activeMap.poiIdAt(activeMap.projection.toScreenLocation(point))?.let(currentPois::get)
                if (poi == null) {
                    false
                } else {
                    currentOnPoiSelected(poi)
                    true
                }
            }
        activeMap.addOnMapClickListener(listener)
        onDispose { activeMap.removeOnMapClickListener(listener) }
    }
}

@Composable
internal fun phoneMapPoiDetailsCard(
    poi: PhoneMapPoi,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val details = poi.details
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = poi.label ?: stringResource(poi.type.labelResource()))
                    Text(text = stringResource(poi.type.labelResource()))
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.map_poi_close_content_description),
                    )
                }
            }
            details?.typeLabel?.let { typeLabel -> Text(text = typeLabel) }
            details?.elevationMeters?.let { elevationMeters ->
                Text(text = stringResource(R.string.map_poi_elevation_value, elevationMeters))
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
}

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
