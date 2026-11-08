package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import com.glancemap.glancemapwearos.presentation.features.gpx.TrackPoint
import com.glancemap.glancemapwearos.presentation.features.navigate.applyOpacityToColor
import com.glancemap.glancemapwearos.presentation.features.navigate.latLongSegments
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Position
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.render.Renderable
import earth.worldwind.shape.Path
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.PlacemarkAttributes
import earth.worldwind.shape.ShapeAttributes
import org.mapsforge.core.model.LatLong

internal fun worldWindTrackSegments(points: List<TrackPoint>): List<List<LatLong>> = points.latLongSegments()

internal fun worldWindGpxLayer(
    overlay: TerrainGpxOverlay,
    location: LatLong?,
): RenderableLayer =
    RenderableLayer("Tracks and location").apply {
        val attributes =
            ShapeAttributes().apply {
                outlineColor = Color().set(applyOpacityToColor(overlay.color, overlay.opacityPercent))
                outlineWidth = overlay.width.takeIf { it.isFinite() && it > 0f } ?: 3f
                isDrawInterior = false
            }
        overlay.tracks.forEach { track ->
            worldWindTrackSegments(track.trackPoints).forEach { points ->
                addRenderable(
                    worldWindTrackPath(points, attributes),
                )
            }
        }
        updateWorldWindLocation(this, null, worldWindLocationMarker(location))
    }

internal fun updateWorldWindLocation(
    layer: RenderableLayer,
    previous: Renderable?,
    next: Renderable?,
): Renderable? {
    previous?.let(layer::removeRenderable)
    next?.let(layer::addRenderable)
    return next
}

internal fun worldWindLocationMarker(location: LatLong?): Placemark? =
    location?.let {
        val attributes =
            PlacemarkAttributes().apply {
                imageColor = Color(0.1f, 0.45f, 1f, 1f)
                imageScale = 14.0
            }
        Placemark(Position.fromDegrees(it.latitude, it.longitude, 5.0), attributes).apply {
            altitudeMode = AltitudeMode.RELATIVE_TO_GROUND
        }
    }

internal fun worldWindTrackPath(
    points: List<LatLong>,
    attributes: ShapeAttributes,
): Path =
    Path(points.map { Position.fromDegrees(it.latitude, it.longitude, 0.0) }, attributes).apply {
        altitudeMode = AltitudeMode.CLAMP_TO_GROUND
        isFollowTerrain = true
    }
