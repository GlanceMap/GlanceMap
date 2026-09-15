package com.glancemap.glancemapwearos.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.text.Collator
import java.util.Locale

class PoiRepositoryImpl(
    private val context: Context,
) : PoiRepository {
    private val poiDir by lazy { context.getDir("poi", Context.MODE_PRIVATE) }
    private val poiFiles by lazy { PoiFileStorage(poiDir) }
    private val links by lazy { PoiGpxWaypointLinks(poiDir) }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("poi_metadata", Context.MODE_PRIVATE)
    }
    private val mergedCategoryAliasesByPath = mutableMapOf<String, Map<Int, Set<Int>>>()

    companion object {
        private const val KEY_FILE_ENABLED_PREFIX = "file_enabled_"
        private const val KEY_ENABLED_CATEGORY_PREFIX = "enabled_categories_"
    }

    override suspend fun listPoiFiles(): List<File> = withContext(Dispatchers.IO) { poiFiles.list() }

    override suspend fun savePoiFileAtomic(
        fileName: String,
        inputStream: InputStream,
        onProgress: (bytesCopied: Long) -> Unit,
        expectedSize: Long?,
        resumeOffset: Long,
        diagnosticContext: String?,
    ): String? =
        withContext(Dispatchers.IO) {
            poiFiles.saveAtomic(fileName, inputStream, onProgress, expectedSize, resumeOffset, diagnosticContext)
        }

    override suspend fun deletePoiFile(path: String): Boolean =
        withContext(Dispatchers.IO) {
            val file = File(path)
            val isInsideDir =
                runCatching {
                    file.canonicalFile.parentFile?.canonicalPath == poiDir.canonicalPath
                }.getOrDefault(false)

            val deleted = if (file.exists() && isInsideDir) file.delete() else false
            File(poiDir, ".${file.name}.part").delete()
            deleteVisibilityState(path)
            synchronized(mergedCategoryAliasesByPath) {
                mergedCategoryAliasesByPath.remove(file.absolutePath)
            }
            deleted
        }

    override suspend fun fileExists(fileName: String): Boolean =
        withContext(Dispatchers.IO) {
            val safeName = File(fileName).name
            File(poiDir, safeName).exists()
        }

    override suspend fun readCategories(path: String): List<PoiCategory> =
        withContext(Dispatchers.IO) {
            val poiFile = File(path)
            if (!poiFile.exists() || !poiFile.isFile) return@withContext emptyList()

            val rawCategories = mutableListOf<RawPoiCategory>()
            val directPointCountsByCategoryId = mutableMapOf<Int, Int>()
            openPoiDatabase(poiFile.absolutePath).use { db ->
                db
                    .rawQuery(
                        "SELECT id, name, parent FROM poi_categories",
                        emptyArray(),
                    ).use { cursor ->
                        val idIdx = cursor.getColumnIndex("id")
                        val nameIdx = cursor.getColumnIndex("name")
                        val parentIdx = cursor.getColumnIndex("parent")
                        while (cursor.moveToNext()) {
                            if (idIdx < 0 || nameIdx < 0) continue
                            rawCategories +=
                                RawPoiCategory(
                                    id = cursor.getInt(idIdx),
                                    name = cursor.getString(nameIdx).orEmpty(),
                                    parent =
                                        if (parentIdx >= 0 && !cursor.isNull(parentIdx)) {
                                            cursor.getInt(parentIdx)
                                        } else {
                                            null
                                        },
                                )
                        }
                    }
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
                            val pointCount =
                                if (countIdx >= 0 && !cursor.isNull(countIdx)) {
                                    cursor.getInt(countIdx)
                                } else {
                                    0
                                }
                            directPointCountsByCategoryId[categoryId] = pointCount
                        }
                    }
            }

            val usedCategoryIds = directPointCountsByCategoryId.keys
            val filteredRawCategories = keepCategoriesWithPoiData(rawCategories, usedCategoryIds)
            val (collapsedRawCategories, mergedAliases) =
                collapseDuplicateRetainedCategories(filteredRawCategories)
            val (groupedRawCategories, syntheticGroupAliases) =
                applySyntheticTopLevelGrouping(collapsedRawCategories)
            val combinedAliases =
                mutableMapOf<Int, Set<Int>>().apply {
                    putAll(mergedAliases)
                    syntheticGroupAliases.forEach { (key, value) ->
                        val current = this[key].orEmpty()
                        this[key] = (current + value).toSet()
                    }
                }
            synchronized(mergedCategoryAliasesByPath) {
                mergedCategoryAliasesByPath[poiFile.absolutePath] = combinedAliases
            }
            val sortWeights =
                buildCategorySortWeights(
                    categories = groupedRawCategories,
                    directPointCountsByCategoryId = directPointCountsByCategoryId,
                    aliasMap = combinedAliases,
                )
            buildCategoryTree(
                raw = groupedRawCategories,
                sortWeightByCategoryId = sortWeights,
            )
        }

    override suspend fun readCoverageBounds(path: String) = withContext(Dispatchers.IO) { readPoiCoverageBounds(path) }

    override suspend fun readLinkedGpxWaypointFileName(path: String): String? = poiIo { links.read(path) }

    override suspend fun findGpxWaypointPoiFiles(gpxFileName: String): List<File> = poiIo { links.find(gpxFileName) }

    override suspend fun updateLinkedGpxWaypointFileName(
        previousGpxFileName: String,
        newGpxFileName: String,
    ): Int =
        withContext(Dispatchers.IO) {
            links.update(previousGpxFileName, newGpxFileName)
        }

    override suspend fun isFileEnabled(path: String): Boolean =
        withContext(Dispatchers.IO) {
            prefs.getBoolean(fileEnabledKey(path), false)
        }

    override suspend fun setFileEnabled(
        path: String,
        enabled: Boolean,
    ) = withContext(Dispatchers.IO) {
        prefs
            .edit()
            .putBoolean(fileEnabledKey(path), enabled)
            .apply()
    }

    override suspend fun getEnabledCategories(
        path: String,
        availableCategoryIds: Set<Int>,
    ): Set<Int> =
        withContext(Dispatchers.IO) {
            if (availableCategoryIds.isEmpty()) return@withContext emptySet()
            val key = enabledCategoriesKey(path)
            if (!prefs.contains(key)) {
                return@withContext emptySet()
            }

            val stored =
                prefs
                    .getStringSet(key, emptySet())
                    .orEmpty()
                    .mapNotNull { it.toIntOrNull() }
                    .toSet()
            val aliasMap =
                synchronized(mergedCategoryAliasesByPath) {
                    mergedCategoryAliasesByPath[File(path).absolutePath]
                }.orEmpty()

            buildSet {
                stored.forEach { storedId ->
                    if (storedId in availableCategoryIds) {
                        add(storedId)
                        return@forEach
                    }
                    val candidates =
                        availableCategoryIds.filter { candidate ->
                            aliasMap[candidate]?.contains(storedId) == true
                        }
                    val preferred =
                        candidates
                            .filter { it >= 0 }
                            .minOrNull()
                            ?: candidates.minOrNull()
                    if (preferred != null) {
                        add(preferred)
                    }
                }
            }
        }

    override suspend fun setEnabledCategories(
        path: String,
        enabledCategoryIds: Set<Int>,
    ) = withContext(Dispatchers.IO) {
        val key = enabledCategoriesKey(path)
        prefs
            .edit()
            .putStringSet(key, enabledCategoryIds.map { it.toString() }.toSet())
            .apply()
    }

    override suspend fun countPoiPoints(
        path: String,
        categoryIds: Set<Int>,
    ): Int =
        withContext(Dispatchers.IO) {
            val poiFile = File(path)
            if (!poiFile.exists() || !poiFile.isFile) return@withContext 0
            if (categoryIds.isEmpty()) return@withContext 0
            val expandedCategoryIds =
                expandCategoryIdsWithMergedAliases(
                    path = poiFile.absolutePath,
                    categoryIds = categoryIds,
                )
            if (expandedCategoryIds.isEmpty()) return@withContext 0

            val placeholders = expandedCategoryIds.joinToString(separator = ",") { "?" }
            val sql =
                """
                SELECT COUNT(DISTINCT pcm.id) AS poi_count
                FROM poi_category_map pcm
                WHERE pcm.category IN ($placeholders)
                """.trimIndent()
            val args = expandedCategoryIds.map { it.toString() }.toTypedArray()

            openPoiDatabase(poiFile.absolutePath).use { db ->
                db.rawQuery(sql, args).use { cursor ->
                    val countIdx = cursor.getColumnIndex("poi_count")
                    if (cursor.moveToFirst()) {
                        if (countIdx >= 0) cursor.getInt(countIdx) else cursor.getInt(0)
                    } else {
                        0
                    }
                }
            }
        }

    override suspend fun queryPoiPointsByCategories(
        path: String,
        categoryIds: Set<Int>,
        limit: Int,
    ): List<PoiPoint> =
        withContext(Dispatchers.IO) {
            val poiFile = File(path)
            if (!poiFile.exists() || !poiFile.isFile) return@withContext emptyList()
            if (categoryIds.isEmpty()) return@withContext emptyList()
            val expandedCategoryIds =
                expandCategoryIdsWithMergedAliases(
                    path = poiFile.absolutePath,
                    categoryIds = categoryIds,
                )
            if (expandedCategoryIds.isEmpty()) return@withContext emptyList()

            val placeholders = expandedCategoryIds.joinToString(separator = ",") { "?" }
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
                          AND pcm2.category IN ($placeholders)
                        ORDER BY pc.id
                        LIMIT 1
                    ) AS category_name
                FROM poi_index pi
                JOIN poi_data pd ON pd.id = pi.id
                WHERE EXISTS (
                        SELECT 1
                        FROM poi_category_map pcm
                        WHERE pcm.id = pi.id
                          AND pcm.category IN ($placeholders)
                )
                """.trimIndent()
            val args =
                buildList {
                    expandedCategoryIds.forEach { add(it.toString()) } // category_name subquery
                    expandedCategoryIds.forEach { add(it.toString()) } // EXISTS subquery
                }.toTypedArray()

            val results = mutableListOf<PoiPoint>()
            openPoiDatabase(poiFile.absolutePath).use { db ->
                db.rawQuery(sql, args).use { cursor ->
                    val idIdx = cursor.getColumnIndex("id")
                    val latIdx = cursor.getColumnIndex("lat")
                    val lonIdx = cursor.getColumnIndex("lon")
                    val dataIdx = cursor.getColumnIndex("data")
                    val categoryIdx = cursor.getColumnIndex("category_name")
                    while (cursor.moveToNext()) {
                        if (idIdx < 0 || latIdx < 0 || lonIdx < 0) continue
                        val data =
                            if (dataIdx >= 0 && !cursor.isNull(dataIdx)) {
                                cursor.getString(dataIdx).orEmpty()
                            } else {
                                ""
                            }
                        val tags = parseTagMap(data)
                        val name = parseDisplayName(tags)
                        val categoryName =
                            if (categoryIdx >= 0 && !cursor.isNull(categoryIdx)) {
                                cursor.getString(categoryIdx).orEmpty()
                            } else {
                                ""
                            }
                        results +=
                            PoiPoint(
                                id = cursor.getLong(idIdx),
                                lat = cursor.getDouble(latIdx),
                                lon = cursor.getDouble(lonIdx),
                                name = name,
                                type = classifyPoiType(tags = tags, categoryName = categoryName, rawData = data),
                                details = buildPoiPointDetails(tags = tags, categoryName = categoryName),
                            )
                    }
                }
            }
            val safeLimit = limit.coerceAtLeast(1)
            val collator =
                Collator.getInstance(Locale.getDefault()).apply {
                    strength = Collator.PRIMARY
                }
            results
                .sortedWith(
                    compareBy<PoiPoint>(
                        { it.name.isNullOrBlank() },
                        { it.name?.trim().orEmpty() },
                        { it.id },
                    ).let { base ->
                        Comparator { left, right ->
                            val nullRank = left.name.isNullOrBlank().compareTo(right.name.isNullOrBlank())
                            if (nullRank != 0) return@Comparator nullRank
                            val nameCompare =
                                collator.compare(
                                    left.name?.trim().orEmpty(),
                                    right.name?.trim().orEmpty(),
                                )
                            if (nameCompare != 0) return@Comparator nameCompare
                            base.compare(left, right)
                        }
                    },
                ).take(safeLimit)
        }

    override suspend fun queryPoiPoints(
        path: String,
        viewport: PoiViewport,
        enabledCategoryIds: Set<Int>,
        limit: Int,
    ): List<PoiPoint> =
        withContext(Dispatchers.IO) {
            val poiFile = File(path)
            if (!poiFile.exists() || !poiFile.isFile) return@withContext emptyList()
            if (enabledCategoryIds.isEmpty()) return@withContext emptyList()
            val expandedCategoryIds =
                expandCategoryIdsWithMergedAliases(
                    path = poiFile.absolutePath,
                    categoryIds = enabledCategoryIds,
                )
            if (expandedCategoryIds.isEmpty()) return@withContext emptyList()

            val placeholders = expandedCategoryIds.joinToString(separator = ",") { "?" }
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
                          AND pcm2.category IN ($placeholders)
                        ORDER BY pc.id
                        LIMIT 1
                    ) AS category_name
                FROM poi_index pi
                JOIN poi_data pd ON pd.id = pi.id
                WHERE pi.lat BETWEEN ? AND ?
                  AND pi.lon BETWEEN ? AND ?
                  AND EXISTS (
                        SELECT 1
                        FROM poi_category_map pcm
                        WHERE pcm.id = pi.id
                          AND pcm.category IN ($placeholders)
                  )
                ORDER BY pi.id
                LIMIT ?
                """.trimIndent()

            val args =
                buildList {
                    expandedCategoryIds.forEach { add(it.toString()) } // category_name subquery
                    add(viewport.minLat.toString())
                    add(viewport.maxLat.toString())
                    add(viewport.minLon.toString())
                    add(viewport.maxLon.toString())
                    expandedCategoryIds.forEach { add(it.toString()) }
                    add(limit.coerceAtLeast(1).toString())
                }.toTypedArray()

            val results = mutableListOf<PoiPoint>()
            openPoiDatabase(poiFile.absolutePath).use { db ->
                db.rawQuery(sql, args).use { cursor ->
                    val idIdx = cursor.getColumnIndex("id")
                    val latIdx = cursor.getColumnIndex("lat")
                    val lonIdx = cursor.getColumnIndex("lon")
                    val dataIdx = cursor.getColumnIndex("data")
                    val categoryIdx = cursor.getColumnIndex("category_name")
                    while (cursor.moveToNext()) {
                        if (idIdx < 0 || latIdx < 0 || lonIdx < 0) continue
                        val data =
                            if (dataIdx >= 0 && !cursor.isNull(dataIdx)) {
                                cursor.getString(dataIdx).orEmpty()
                            } else {
                                ""
                            }
                        val tags = parseTagMap(data)
                        val name = parseDisplayName(tags)
                        val categoryName =
                            if (categoryIdx >= 0 && !cursor.isNull(categoryIdx)) {
                                cursor.getString(categoryIdx).orEmpty()
                            } else {
                                ""
                            }
                        results +=
                            PoiPoint(
                                id = cursor.getLong(idIdx),
                                lat = cursor.getDouble(latIdx),
                                lon = cursor.getDouble(lonIdx),
                                name = name,
                                type = classifyPoiType(tags = tags, categoryName = categoryName, rawData = data),
                                details = buildPoiPointDetails(tags = tags, categoryName = categoryName),
                            )
                    }
                }
            }

            results
        }

    override suspend fun searchPoiPoints(
        path: String,
        query: String,
        enabledCategoryIds: Set<Int>,
        limit: Int,
    ): List<PoiPoint> =
        withContext(Dispatchers.IO) {
            val poiFile = File(path)
            if (!poiFile.exists() || !poiFile.isFile) return@withContext emptyList()
            if (enabledCategoryIds.isEmpty()) return@withContext emptyList()

            val normalizedQuery = query.trim()
            if (normalizedQuery.length < 2) return@withContext emptyList()

            val expandedCategoryIds =
                expandCategoryIdsWithMergedAliases(
                    path = poiFile.absolutePath,
                    categoryIds = enabledCategoryIds,
                )
            if (expandedCategoryIds.isEmpty()) return@withContext emptyList()

            val placeholders = expandedCategoryIds.joinToString(separator = ",") { "?" }
            val rawLimit = (limit.coerceAtLeast(1) * 8).coerceAtLeast(40)
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
                          AND pcm2.category IN ($placeholders)
                        ORDER BY pc.id
                        LIMIT 1
                    ) AS category_name
                FROM poi_index pi
                JOIN poi_data pd ON pd.id = pi.id
                WHERE lower(pd.data) LIKE ?
                  AND EXISTS (
                        SELECT 1
                        FROM poi_category_map pcm
                        WHERE pcm.id = pi.id
                          AND pcm.category IN ($placeholders)
                  )
                ORDER BY pi.id
                LIMIT ?
                """.trimIndent()

            val args =
                buildList {
                    expandedCategoryIds.forEach { add(it.toString()) }
                    add("%${normalizedQuery.lowercase(Locale.ROOT)}%")
                    expandedCategoryIds.forEach { add(it.toString()) }
                    add(rawLimit.toString())
                }.toTypedArray()

            val results = mutableListOf<PoiPoint>()
            openPoiDatabase(poiFile.absolutePath).use { db ->
                db.rawQuery(sql, args).use { cursor ->
                    val idIdx = cursor.getColumnIndex("id")
                    val latIdx = cursor.getColumnIndex("lat")
                    val lonIdx = cursor.getColumnIndex("lon")
                    val dataIdx = cursor.getColumnIndex("data")
                    val categoryIdx = cursor.getColumnIndex("category_name")
                    while (cursor.moveToNext()) {
                        if (idIdx < 0 || latIdx < 0 || lonIdx < 0) continue
                        val data =
                            if (dataIdx >= 0 && !cursor.isNull(dataIdx)) {
                                cursor.getString(dataIdx).orEmpty()
                            } else {
                                ""
                            }
                        val tags = parseTagMap(data)
                        val name =
                            parseDisplayName(tags)
                                ?.takeIf { it.contains(normalizedQuery, ignoreCase = true) }
                                ?: continue
                        val categoryName =
                            if (categoryIdx >= 0 && !cursor.isNull(categoryIdx)) {
                                cursor.getString(categoryIdx).orEmpty()
                            } else {
                                ""
                            }
                        results +=
                            PoiPoint(
                                id = cursor.getLong(idIdx),
                                lat = cursor.getDouble(latIdx),
                                lon = cursor.getDouble(lonIdx),
                                name = name,
                                type = classifyPoiType(tags = tags, categoryName = categoryName, rawData = data),
                                details = buildPoiPointDetails(tags = tags, categoryName = categoryName),
                            )
                    }
                }
            }

            val safeLimit = limit.coerceAtLeast(1)
            val queryLower = normalizedQuery.lowercase(Locale.ROOT)
            val collator =
                Collator.getInstance(Locale.getDefault()).apply {
                    strength = Collator.PRIMARY
                }
            results
                .distinctBy { it.id }
                .sortedWith(
                    compareBy<PoiPoint>(
                        { poiSearchMatchRank(it.name.orEmpty(), queryLower) },
                        { it.name.isNullOrBlank() },
                        { it.id },
                    ).let { base ->
                        Comparator { left, right ->
                            val leftName = left.name?.trim().orEmpty()
                            val rightName = right.name?.trim().orEmpty()
                            val rankCompare =
                                poiSearchMatchRank(leftName, queryLower)
                                    .compareTo(poiSearchMatchRank(rightName, queryLower))
                            if (rankCompare != 0) return@Comparator rankCompare
                            val nameCompare = collator.compare(leftName, rightName)
                            if (nameCompare != 0) return@Comparator nameCompare
                            base.compare(left, right)
                        }
                    },
                ).take(safeLimit)
        }

    override suspend fun deleteVisibilityState(path: String) =
        withContext(Dispatchers.IO) {
            prefs
                .edit()
                .remove(fileEnabledKey(path))
                .remove(enabledCategoriesKey(path))
                .apply()
            synchronized(mergedCategoryAliasesByPath) {
                mergedCategoryAliasesByPath.remove(File(path).absolutePath)
            }
            Unit
        }

    private fun openPoiDatabase(path: String): SQLiteDatabase = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)

    private fun fileEnabledKey(path: String): String = KEY_FILE_ENABLED_PREFIX + File(path).name.lowercase(Locale.ROOT)

    private fun enabledCategoriesKey(path: String): String = KEY_ENABLED_CATEGORY_PREFIX + File(path).name.lowercase(Locale.ROOT)

    private fun expandCategoryIdsWithMergedAliases(
        path: String,
        categoryIds: Set<Int>,
    ): Set<Int> {
        if (categoryIds.isEmpty()) return emptySet()
        val aliasMap =
            synchronized(mergedCategoryAliasesByPath) {
                mergedCategoryAliasesByPath[path]
            }.orEmpty()
        return expandCategoryIdsWithAliasMap(categoryIds = categoryIds, aliasMap = aliasMap)
    }
}
