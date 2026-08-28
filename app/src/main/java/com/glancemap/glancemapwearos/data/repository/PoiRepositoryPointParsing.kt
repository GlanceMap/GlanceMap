package com.glancemap.glancemapwearos.data.repository

import com.glancemap.trailcore.poi.PoiSemantics
import java.util.Locale

internal fun parseDisplayName(tags: Map<String, String>): String? = PoiSemantics.displayName(tags)

internal fun poiSearchMatchRank(
    name: String,
    queryLower: String,
): Int {
    val normalized = name.trim().lowercase(Locale.ROOT)
    return when {
        normalized == queryLower -> 0
        normalized.startsWith(queryLower) -> 1
        normalized.split(Regex("\\s+")).any { it.startsWith(queryLower) } -> 2
        normalized.contains(queryLower) -> 3
        else -> 4
    }
}

internal fun classifyPoiType(
    tags: Map<String, String>,
    categoryName: String,
    rawData: String,
): PoiType = PoiSemantics.classify(tags = tags, categoryName = categoryName, rawData = rawData)

internal fun buildPoiPointDetails(
    tags: Map<String, String>,
    categoryName: String,
): PoiPointDetails? = PoiSemantics.details(tags = tags, categoryName = categoryName)

internal fun classifyPoiTypeFromRefugesTags(
    tags: Map<String, String>,
    categoryName: String,
): PoiType = PoiSemantics.classifyFromRefugesTags(tags = tags, categoryName = categoryName)

internal fun classifyPoiTypeFromCategory(
    categoryName: String,
): PoiType = PoiSemantics.classifyFromCategory(categoryName)

internal fun parseTagMap(data: String): Map<String, String> = PoiSemantics.parseTags(data)

internal fun parseIntegerTag(raw: String?): Int? = PoiSemantics.integerTag(raw)
