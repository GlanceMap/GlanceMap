package com.glancemap.glancemapcompanionapp.map.maplibre

import com.glancemap.glancemapcompanionapp.map.PhoneMapPointSelectionMarker
import com.glancemap.glancemapcompanionapp.map.createPhoneMapPointSelectionMarkerBitmap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** Renders temporary point-selection markers above persistent POIs and GPX overlays. */
internal fun Style.renderPointSelectionMarkers(
    markers: List<PhoneMapPointSelectionMarker>,
) {
    markers.map { marker -> marker.kind }.distinct().forEach { kind ->
        val imageId = imageIdFor(kind.name)
        if (getImage(imageId) == null) addImage(imageId, createPhoneMapPointSelectionMarkerBitmap(kind))
    }
    val features =
        markers.map { marker ->
            Feature.fromGeometry(Point.fromLngLat(marker.point.longitude, marker.point.latitude)).also { feature ->
                feature.addStringProperty(IMAGE_PROPERTY, imageIdFor(marker.kind.name))
            }
        }
    val source = getSourceAs<GeoJsonSource>(SOURCE_ID)
    if (source == null) {
        addSource(GeoJsonSource(SOURCE_ID, FeatureCollection.fromFeatures(features)))
    } else {
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    val layerVisibility = if (markers.isEmpty()) Property.NONE else Property.VISIBLE
    val existingLayer = getLayer(LAYER_ID)
    if (existingLayer != null && existingLayer !is SymbolLayer) removeLayer(existingLayer)
    val layer = getLayerAs<SymbolLayer>(LAYER_ID)
    if (layer == null) {
        addLayer(
            SymbolLayer(LAYER_ID, SOURCE_ID).withProperties(
                iconImage("{$IMAGE_PROPERTY}"),
                iconAnchor("center"),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                visibility(layerVisibility),
            ),
        )
    } else {
        layer.setProperties(
            iconImage("{$IMAGE_PROPERTY}"),
            iconAnchor("center"),
            iconAllowOverlap(true),
            iconIgnorePlacement(true),
            visibility(layerVisibility),
        )
    }
}

private fun imageIdFor(kindName: String): String = "companion-point-selection-$kindName"

private const val IMAGE_PROPERTY = "point_selection_image"
private const val LAYER_ID = "companion-point-selection"
private const val SOURCE_ID = "companion-point-selection"
