package com.glancemap.glancemapcompanionapp.discovery

import com.glancemap.glancemapcompanionapp.routes.RouteLibraryRepository
import com.glancemap.glancemapcompanionapp.routes.RouteLibraryUiState

internal fun publishedTrailGpx(trail: PublishedTrail): ByteArray {
    require(trail.canSave) { "Complete route geometry is required" }
    val attribution =
        listOf(
            trail.description,
            trail.sources.joinToString("\n") { source ->
                listOfNotNull(
                    source.provider.label,
                    source.providerTrailId,
                    source.publisherName,
                    source.attribution,
                    source.license,
                    source.updatedAt,
                    source.sourceUrl,
                ).joinToString(" · ")
            },
        ).filter(String::isNotBlank).joinToString("\n\n")
    return buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<gpx version=\"1.1\" creator=\"GlanceMap\" xmlns=\"http://www.topografix.com/GPX/1/1\">")
        append("<metadata><name>${trail.title.gpxEscaped()}</name><desc>${attribution.gpxEscaped()}</desc>")
        trail.sources.forEach { source ->
            append(
                "<link href=\"${source.sourceUrl.gpxEscaped()}\"><text>" +
                    "${source.provider.label.gpxEscaped()}</text></link>",
            )
        }
        append(
            "</metadata><trk><name>${trail.title.gpxEscaped()}</name><desc>${attribution.gpxEscaped()}</desc><trkseg>",
        )
        trail.geometry.forEachIndexed { index, point ->
            if (index > 0 && point.startsNewSegment) append("</trkseg><trkseg>")
            append("<trkpt lat=\"${point.location.latitude}\" lon=\"${point.location.longitude}\">")
            point.elevationMeters?.let { append("<ele>$it</ele>") }
            append("</trkpt>")
        }
        append("</trkseg></trk></gpx>")
    }.toByteArray(Charsets.UTF_8)
}

/** Uses the existing import/index/profile flow; discovery never owns a second route store. */
internal suspend fun RouteLibraryRepository.saveDiscoveredTrail(trail: PublishedTrail): RouteLibraryUiState = saveGpxRoute(trail.title, publishedTrailGpx(trail), publishedTrailId = trail.id)

/** Retrying a failed metadata write reuses the same managed GPX instead of importing a duplicate. */
internal suspend fun downloadPublishedTrail(
    trail: PublishedTrail,
    library: RouteLibraryRepository,
    store: OfflinePublishedTrailStore,
): List<OfflinePublishedTrail> {
    val routeId = if (trail.canSave) library.saveDiscoveredTrail(trail).selectedRouteId else null
    return store.save(OfflinePublishedTrail(trail, routeId, System.currentTimeMillis()))
}

private fun String.gpxEscaped(): String =
    filter { it == '\n' || it == '\r' || it == '\t' || it.code >= 32 }
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
