package com.glancemap.glancemapcompanionapp.refuges

import com.glancemap.glancemapcompanionapp.R

internal data class OsmPoiImportCategory(
    val id: String,
    val labelResource: Int,
    val tagValues: Map<String, Set<String>>,
)

internal data class OsmPoiImportCategoryPreset(
    val labelResource: Int,
    val categoryIds: Set<String>,
)

private val OSM_POI_IMPORT_CATEGORIES =
    listOf(
        OsmPoiImportCategory(
            id = "huts",
            labelResource = R.string.refuges_osm_category_huts,
            tagValues =
                mapOf(
                    "tourism" to setOf("alpine_hut", "wilderness_hut", "hut", "hostel", "guest_house"),
                    "amenity" to setOf("shelter"),
                ),
        ),
        OsmPoiImportCategory(
            id = "water",
            labelResource = R.string.refuges_osm_category_water,
            tagValues =
                mapOf(
                    "amenity" to setOf("drinking_water", "water_point", "fountain"),
                    "natural" to setOf("spring", "waterfall"),
                ),
        ),
        OsmPoiImportCategory(
            id = "toilets",
            labelResource = R.string.refuges_osm_category_toilets,
            tagValues =
                mapOf(
                    "amenity" to setOf("toilets"),
                ),
        ),
        OsmPoiImportCategory(
            id = "food",
            labelResource = R.string.refuges_osm_category_food,
            tagValues =
                mapOf(
                    "amenity" to setOf("restaurant", "cafe", "fast_food", "bar", "pub"),
                ),
        ),
        OsmPoiImportCategory(
            id = "parking",
            labelResource = R.string.refuges_osm_category_parking,
            tagValues =
                mapOf(
                    "amenity" to setOf("parking", "parking_space"),
                ),
        ),
        OsmPoiImportCategory(
            id = "peaks",
            labelResource = R.string.refuges_osm_category_peaks,
            tagValues =
                mapOf(
                    "natural" to setOf("peak", "volcano"),
                ),
        ),
        OsmPoiImportCategory(
            id = "camping",
            labelResource = R.string.refuges_osm_category_camping,
            tagValues =
                mapOf(
                    "tourism" to setOf("camp_site", "caravan_site"),
                ),
        ),
        OsmPoiImportCategory(
            id = "viewpoints",
            labelResource = R.string.refuges_osm_category_viewpoints,
            tagValues =
                mapOf(
                    "tourism" to setOf("viewpoint"),
                ),
        ),
    )

private val DEFAULT_OSM_POI_CATEGORY_IDS =
    linkedSetOf(
        "huts",
        "water",
        "peaks",
    )

internal fun osmPoiImportCategories(): List<OsmPoiImportCategory> = OSM_POI_IMPORT_CATEGORIES

internal fun defaultOsmPoiCategoryIds(): Set<String> = DEFAULT_OSM_POI_CATEGORY_IDS

internal fun normalizeOsmPoiCategoryIds(categoryIds: Set<String>): Set<String> {
    val validIds = OSM_POI_IMPORT_CATEGORIES.map { it.id }.toSet()
    return categoryIds
        .map { it.trim() }
        .filter { it in validIds }
        .toCollection(linkedSetOf())
}

internal fun resolveOsmPoiImportCategories(categoryIds: Set<String>): List<OsmPoiImportCategory> {
    val normalized = normalizeOsmPoiCategoryIds(categoryIds)
    return OSM_POI_IMPORT_CATEGORIES.filter { it.id in normalized }
}

internal fun buildOsmPoiCategoryPresets(allCategoryIds: Set<String>): List<OsmPoiImportCategoryPreset> {
    val essentials = allCategoryIds.intersect(defaultOsmPoiCategoryIds())
    return listOf(
        OsmPoiImportCategoryPreset(
            labelResource = R.string.refuges_osm_category_essentials,
            categoryIds = essentials.ifEmpty { allCategoryIds },
        ),
        OsmPoiImportCategoryPreset(
            labelResource = R.string.refuges_osm_category_all,
            categoryIds = allCategoryIds,
        ),
    )
}
