package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.discovery.PublishedTrail

/** Checked routes persist in memory even when the temporary details preview closes. */
internal fun discoveryMapOverlays(
    selected: List<PublishedTrail>,
    preview: PhoneMapGpxOverlay?,
): List<PhoneMapGpxOverlay> {
    val overlays =
        selected.map { trail ->
            PhoneMapGpxOverlay(
                id = "discovery/${trail.id}",
                displayName = trail.title,
                segments = PhoneMapGpxTrack(trail.id, trail.geometry).toRouteSegments(),
            )
        }
    return overlays + listOfNotNull(preview?.takeUnless { candidate -> overlays.any { it.id == candidate.id } })
}
