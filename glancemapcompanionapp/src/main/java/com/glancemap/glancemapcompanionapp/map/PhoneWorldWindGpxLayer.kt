package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.geo.GeoPoint
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Position
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.shape.Path
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.PlacemarkAttributes
import earth.worldwind.shape.ShapeAttributes

internal fun phoneWorldWindGpxLayer(
    segments: List<PhoneMapRouteSegment>,
    color: Int,
    location: GeoPoint?,
    locationFresh: Boolean = true,
): RenderableLayer =
    RenderableLayer("GlanceMap overlays").apply {
        val attributes =
            ShapeAttributes().apply {
                outlineColor = Color().set(color)
                outlineWidth = 5f
                isDrawInterior = false
            }
        phoneWorldWindTrackSegments(segments).forEach { points ->
            addRenderable(
                Path(points.map { Position.fromDegrees(it.latitude, it.longitude, 3.0) }, attributes).apply {
                    altitudeMode = AltitudeMode.RELATIVE_TO_GROUND
                    isFollowTerrain = true
                },
            )
        }
        location?.let { point ->
            val marker =
                PlacemarkAttributes().apply {
                    imageColor =
                        if (locationFresh) Color(0.1f, 0.45f, 1f, 1f) else Color().set(PHONE_LOCATION_MARKER_HISTORICAL_ARGB)
                    imageScale = 14.0
                }
            addRenderable(
                Placemark(Position.fromDegrees(point.latitude, point.longitude, 5.0), marker).apply {
                    altitudeMode = AltitudeMode.RELATIVE_TO_GROUND
                },
            )
        }
    }
