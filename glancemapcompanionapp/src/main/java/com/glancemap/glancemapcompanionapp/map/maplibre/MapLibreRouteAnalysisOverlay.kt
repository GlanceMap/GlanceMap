package com.glancemap.glancemapcompanionapp.map.maplibre

import com.glancemap.glancemapcompanionapp.map.PhoneMapRouteAnalysis
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

internal fun Style.renderRouteAnalysisMarkers(analysis: PhoneMapRouteAnalysis?) {
    val features =
        buildList {
            analysis?.let { value ->
                add(value.pointA.toFeature(ROUTE_ANALYSIS_A_COLOR))
                value.pointB?.let { point -> add(point.toFeature(ROUTE_ANALYSIS_B_COLOR)) }
            }
        }
    val featureCollection = FeatureCollection.fromFeatures(features)
    val source = getSourceAs<GeoJsonSource>(ROUTE_ANALYSIS_SOURCE_ID)
    if (source == null) {
        addSource(GeoJsonSource(ROUTE_ANALYSIS_SOURCE_ID, featureCollection))
    } else {
        source.setGeoJson(featureCollection)
    }

    val layerVisibility = if (features.isEmpty()) Property.NONE else Property.VISIBLE
    val existingLayer = getLayer(ROUTE_ANALYSIS_LAYER_ID)
    if (existingLayer != null && existingLayer !is CircleLayer) {
        removeLayer(existingLayer)
    }
    val layer = getLayerAs<CircleLayer>(ROUTE_ANALYSIS_LAYER_ID)
    if (layer == null) {
        addLayer(
            CircleLayer(ROUTE_ANALYSIS_LAYER_ID, ROUTE_ANALYSIS_SOURCE_ID).withProperties(
                circleColor(Expression.get(ROUTE_ANALYSIS_COLOR_PROPERTY)),
                circleRadius(9f),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(2f),
                visibility(layerVisibility),
            ),
        )
    } else {
        layer.setProperties(
            circleColor(Expression.get(ROUTE_ANALYSIS_COLOR_PROPERTY)),
            circleRadius(9f),
            circleStrokeColor("#FFFFFF"),
            circleStrokeWidth(2f),
            visibility(layerVisibility),
        )
    }
}

private fun com.glancemap.glancemapcompanionapp.map.PhoneMapCoordinate.toFeature(color: String): Feature =
    Feature.fromGeometry(Point.fromLngLat(longitude, latitude)).also { feature ->
        feature.addStringProperty(ROUTE_ANALYSIS_COLOR_PROPERTY, color)
    }

private const val ROUTE_ANALYSIS_LAYER_ID = "companion-route-analysis-points"
private const val ROUTE_ANALYSIS_SOURCE_ID = "companion-route-analysis"
private const val ROUTE_ANALYSIS_COLOR_PROPERTY = "route_analysis_color"
private const val ROUTE_ANALYSIS_A_COLOR = "#FACC15"
private const val ROUTE_ANALYSIS_B_COLOR = "#FB923C"
