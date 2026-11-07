package com.glancemap.glancemapcompanionapp.refuges

import android.database.sqlite.SQLiteDatabase
import java.io.File

/** One row of a Mapsforge POI file's category tree with the POIs filed directly under it. */
internal data class PoiSqliteCategory(
    val id: Int,
    val name: String,
    val parentId: Int?,
    val directCount: Int,
)

/**
 * Reads the category tree. A single unnamed-looking `root` category, as GlanceMap and Mapsforge writers add,
 * is dropped so its children become the top level.
 */
internal fun readPoiSqliteCategories(file: File): List<PoiSqliteCategory> {
    val database = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    val categories =
        try {
            val counts = mutableMapOf<Int, Int>()
            database.rawQuery("SELECT category, COUNT(*) FROM poi_category_map GROUP BY category", null).use { cursor ->
                while (cursor.moveToNext()) counts[cursor.getInt(0)] = cursor.getInt(1)
            }
            database.rawQuery("SELECT id, name, parent FROM poi_categories ORDER BY id", null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.getInt(0)
                        add(
                            PoiSqliteCategory(
                                id = id,
                                name = cursor.getString(1).orEmpty().ifBlank { "Other" },
                                parentId = if (cursor.isNull(2)) null else cursor.getInt(2),
                                directCount = counts[id] ?: 0,
                            ),
                        )
                    }
                }
            }
        } finally {
            database.close()
        }
    return withoutSyntheticRoot(categories)
}

internal fun withoutSyntheticRoot(categories: List<PoiSqliteCategory>): List<PoiSqliteCategory> {
    val roots = categories.filter { it.parentId == null }
    val root = roots.singleOrNull()?.takeIf { it.name.equals("root", ignoreCase = true) } ?: return categories
    return categories
        .filterNot { it.id == root.id }
        .map { if (it.parentId == root.id) it.copy(parentId = null) else it }
}

/** POIs filed under any of the given categories, for the Library's category list. */
internal fun readPoiSqliteCategoryPoints(
    file: File,
    categoryIds: Set<Int>,
    limit: Int,
): List<PoiSqlitePoint> {
    if (categoryIds.isEmpty() || limit <= 0 || !file.isFile) return emptyList()
    val ids = categoryIds.sorted()
    val placeholders = List(ids.size) { "?" }.joinToString(", ")
    val sql =
        """
        SELECT
            pi.id,
            pi.lat,
            pi.lon,
            pd.data,
            (
                SELECT pc.name
                FROM poi_category_map pcm2
                JOIN poi_categories pc ON pc.id = pcm2.category
                WHERE pcm2.id = pi.id
                ORDER BY pc.id
                LIMIT 1
            ) AS category_name
        FROM poi_index pi
        JOIN poi_data pd ON pd.id = pi.id
        WHERE EXISTS (SELECT 1 FROM poi_category_map m WHERE m.id = pi.id AND m.category IN ($placeholders))
        ORDER BY pi.id
        LIMIT ?
        """.trimIndent()
    return readPoiSqlitePoints(file, sql, (ids.map(Int::toString) + limit.toString()).toTypedArray())
}
