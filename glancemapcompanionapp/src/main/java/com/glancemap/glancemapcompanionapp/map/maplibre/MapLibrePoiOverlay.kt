package com.glancemap.glancemapcompanionapp.map.maplibre

import android.content.Context
import android.graphics.PointF
import com.glancemap.glancemapcompanionapp.map.PhoneMapPoi
import com.glancemap.glancemapcompanionapp.map.PhoneMapPoiSettings
import com.glancemap.glancemapcompanionapp.map.phoneMapPoiMarkerBitmap
import com.glancemap.glancemapcompanionapp.map.phoneMapPoiMarkerImageId
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** Renders semantic POIs with one reusable MapLibre GeoJSON source and symbol layer. */
internal fun Style.renderPois(
    pois: List<PhoneMapPoi>,
    isVisible: Boolean,
    settings: PhoneMapPoiSettings,
    context: Context,
) {
    pois.map(PhoneMapPoi::type).distinct().forEach { type ->
        val imageId = type.phoneMapPoiMarkerImageId(settings)
        if (getImage(imageId) == null) {
            addImage(imageId, context.phoneMapPoiMarkerBitmap(type, settings))
        }
    }
    val featureCollection =
        FeatureCollection.fromFeatures(pois.map { poi -> poi.toGeoJsonFeature(settings) })
    val source = getSourceAs<GeoJsonSource>(POI_SOURCE_ID)
    if (source == null) {
        addSource(GeoJsonSource(POI_SOURCE_ID, featureCollection))
    } else {
        source.setGeoJson(featureCollection)
    }

    val layerVisibility = if (isVisible && pois.isNotEmpty()) Property.VISIBLE else Property.NONE
    val existingLayer = getLayer(POI_LAYER_ID)
    if (existingLayer != null && existingLayer !is SymbolLayer) {
        removeLayer(existingLayer)
    }
    val layer = getLayerAs<SymbolLayer>(POI_LAYER_ID)
    if (layer == null) {
        addLayer(
            SymbolLayer(POI_LAYER_ID, POI_SOURCE_ID).withProperties(
                iconImage("{$POI_IMAGE_PROPERTY}"),
                iconSize(1f),
                iconAnchor("center"),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                visibility(layerVisibility),
            ),
        )
    } else {
        layer.setProperties(
            iconImage("{$POI_IMAGE_PROPERTY}"),
            iconSize(1f),
            iconAnchor("center"),
            iconAllowOverlap(true),
            iconIgnorePlacement(true),
            visibility(layerVisibility),
        )
    }
}

internal fun MapLibreMap.poiIdAt(screenPoint: PointF): String? =
    queryRenderedFeatures(screenPoint, POI_LAYER_ID)
        .firstOrNull()
        ?.getStringProperty(POI_ID_PROPERTY)

private fun PhoneMapPoi.toGeoJsonFeature(settings: PhoneMapPoiSettings): Feature =
    Feature
        .fromGeometry(
            Point.fromLngLat(location.longitude, location.latitude),
        ).also { feature ->
            feature.addStringProperty(POI_ID_PROPERTY, id)
            feature.addStringProperty(POI_IMAGE_PROPERTY, type.phoneMapPoiMarkerImageId(settings))
        }

private const val POI_ID_PROPERTY = "id"
private const val POI_IMAGE_PROPERTY = "poi_image"
private const val POI_LAYER_ID = "companion-poi-circle"
private const val POI_SOURCE_ID = "companion-poi"
