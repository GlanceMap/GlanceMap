package com.glancemap.glancemapcompanionapp.discovery

import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.profile.TrailPoint
import com.glancemap.trailcore.profile.buildTrailRouteProfile
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

internal enum class TrailProvider(
    val label: String,
) {
    OPEN_STREET_MAP("OpenStreetMap"),
    WANDERER("Wanderer"),
    GEOTREK("Geotrek"),
    DATATOURISME("DATAtourisme"),
}

internal data class TrailSource(
    val provider: TrailProvider,
    val providerTrailId: String,
    val sourceUrl: String,
    val attribution: String,
    val license: String,
    val publisherName: String? = null,
    val updatedAt: String? = null,
)

/** Provider DTOs stop at the adapters. Segment boundaries survive preview, analysis and GPX export. */
internal data class PublishedTrail(
    val id: String,
    val title: String,
    val sources: List<TrailSource>,
    val description: String = "",
    val geometry: List<TrailPoint> = emptyList(),
    val geometryComplete: Boolean = false,
    val startLocation: GeoPoint? = geometry.firstOrNull()?.location,
    val providerDistanceMeters: Double? = null,
    val difficulty: String? = null,
    val activity: String = "Hiking",
) {
    val profile = geometry.takeIf { geometryComplete && it.size >= 2 }?.let { buildTrailRouteProfile(it) }
    val canSave: Boolean
        get() = profile != null && profile.totalDistanceMeters > 0.0
    val distanceMeters: Double?
        get() = profile?.totalDistanceMeters ?: providerDistanceMeters
}

internal data class TrailDiscoveryQuery(
    val center: GeoPoint,
    val radiusMeters: Int = 10000,
) {
    init {
        require(radiusMeters in 1000..25000)
    }
}

/** Only matching full geometry is strong enough to combine independently published routes. */
internal fun mergePublishedTrails(trails: List<PublishedTrail>): List<PublishedTrail> {
    val merged = mutableListOf<PublishedTrail>()
    trails.forEach { trail ->
        val index = merged.indexOfFirst { existing -> existing.id == trail.id || samePublishedTrail(existing, trail) }
        if (index < 0) {
            merged += trail
        } else {
            val existing = merged[index]
            val preferred = if (!existing.canSave && trail.canSave) trail else existing
            merged[index] = preferred.copy(sources = (existing.sources + trail.sources).distinct())
        }
    }
    return merged
}

private fun samePublishedTrail(
    first: PublishedTrail,
    second: PublishedTrail,
): Boolean {
    if (!first.canSave || !second.canSave || first.title.trailTitleKey() != second.title.trailTitleKey()) return false
    // shortcut: exact ordered geometry only; add spatial matching when real duplicate fixtures justify it.
    return first.geometry.size == second.geometry.size &&
        abs(first.profile!!.totalDistanceMeters - second.profile!!.totalDistanceMeters) <= 5.0 &&
        first.geometry == second.geometry
}

private fun String.trailTitleKey(): String =
    Normalizer
        .normalize(this, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
