package com.glancemap.glancemapwearos.data.repository

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File

internal data class PoiCategoryMetadata(
    val categories: List<PoiCategory>,
    val aliases: Map<Int, Set<Int>>,
)

internal fun readPoiCategoryMetadata(poiFile: File): PoiCategoryMetadata {
    if (!poiFile.exists() || !poiFile.isFile) return PoiCategoryMetadata(emptyList(), emptyMap())

    val (rawCategories, directPointCountsByCategoryId) =
        SQLiteDatabase.openDatabase(poiFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            readRawPoiCategories(db) to readDirectPoiCategoryCounts(db)
        }
    val filteredRawCategories = keepCategoriesWithPoiData(rawCategories, directPointCountsByCategoryId.keys)
    val (collapsedRawCategories, mergedAliases) = collapseDuplicateRetainedCategories(filteredRawCategories)
    val (groupedRawCategories, syntheticGroupAliases) = applySyntheticTopLevelGrouping(collapsedRawCategories)
    val combinedAliases = mergedAliases.toMutableMap()
    syntheticGroupAliases.forEach { (key, value) ->
        combinedAliases[key] = (combinedAliases[key].orEmpty() + value).toSet()
    }
    val sortWeights =
        buildCategorySortWeights(
            categories = groupedRawCategories,
            directPointCountsByCategoryId = directPointCountsByCategoryId,
            aliasMap = combinedAliases,
        )
    return PoiCategoryMetadata(buildCategoryTree(groupedRawCategories, sortWeights), combinedAliases)
}

private fun readRawPoiCategories(db: SQLiteDatabase): List<RawPoiCategory> {
    val rawCategories = mutableListOf<RawPoiCategory>()
    db.rawQuery("SELECT id, name, parent FROM poi_categories", emptyArray()).use { cursor ->
        val idIdx = cursor.getColumnIndex("id")
        val nameIdx = cursor.getColumnIndex("name")
        val parentIdx = cursor.getColumnIndex("parent")
        while (cursor.moveToNext()) {
            if (idIdx < 0 || nameIdx < 0) continue
            rawCategories +=
                RawPoiCategory(
                    id = cursor.getInt(idIdx),
                    name = cursor.getString(nameIdx).orEmpty(),
                    parent = if (parentIdx >= 0 && !cursor.isNull(parentIdx)) cursor.getInt(parentIdx) else null,
                )
        }
    }
    return rawCategories
}

private fun readDirectPoiCategoryCounts(db: SQLiteDatabase): Map<Int, Int> {
    val directPointCountsByCategoryId = mutableMapOf<Int, Int>()
    db
        .rawQuery(
            "SELECT category, COUNT(DISTINCT id) AS point_count FROM poi_category_map GROUP BY category",
            emptyArray(),
        ).use { cursor ->
            val categoryIdx = cursor.getColumnIndex("category")
            val countIdx = cursor.getColumnIndex("point_count")
            while (cursor.moveToNext()) {
                if (categoryIdx < 0 || cursor.isNull(categoryIdx)) continue
                val categoryId = cursor.getInt(categoryIdx)
                val pointCount = if (countIdx >= 0 && !cursor.isNull(countIdx)) cursor.getInt(countIdx) else 0
                directPointCountsByCategoryId[categoryId] = pointCount
            }
        }
    return directPointCountsByCategoryId
}

internal fun readPoiPointCount(
    poiFile: File,
    categoryIds: Set<Int>,
): Int {
    if (!poiFile.exists() || !poiFile.isFile || categoryIds.isEmpty()) return 0
    val placeholders = categoryIds.joinToString(separator = ",") { "?" }
    val sql =
        """
        SELECT COUNT(DISTINCT pcm.id) AS poi_count
        FROM poi_category_map pcm
        WHERE pcm.category IN ($placeholders)
        """.trimIndent()
    val args = categoryIds.map { it.toString() }.toTypedArray()
    return SQLiteDatabase.openDatabase(poiFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery(sql, args).use(::readPoiCount)
    }
}

private fun readPoiCount(cursor: Cursor): Int {
    val countIdx = cursor.getColumnIndex("poi_count")
    return if (cursor.moveToFirst()) {
        if (countIdx >= 0) cursor.getInt(countIdx) else cursor.getInt(0)
    } else {
        0
    }
}
