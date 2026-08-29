package com.glancemap.glancemapcompanionapp.refuges

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.Locale
import kotlin.math.max

data class PoiSqlitePoint(
    val sourceId: Long? = null,
    val lat: Double,
    val lon: Double,
    val categoryName: String,
    val tags: Map<String, String>,
    val rawData: String = "",
)

internal data class PoiSqliteViewport(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
)

data class PoiSqliteWriteOptions(
    val comment: String,
    val writer: String,
    val extraMetadata: Map<String, String> = emptyMap(),
)

data class PoiSqliteWriteSummary(
    val pointCount: Int,
    val categoryCount: Int,
)

internal object PoiSqliteCodec {
    private const val ROOT_CATEGORY_ID = 1
    private const val ROOT_CATEGORY_NAME = "root"

    fun write(
        file: File,
        points: List<PoiSqlitePoint>,
        options: PoiSqliteWriteOptions,
    ): Int {
        val sanitizedPoints =
            points.mapNotNull { point ->
                val sanitizedTags = sanitizeTags(point.tags)
                if (sanitizedTags.isEmpty()) return@mapNotNull null
                val categoryName = point.categoryName.trim().ifBlank { "Other" }
                point.copy(categoryName = categoryName, tags = sanitizedTags)
            }
        if (sanitizedPoints.isEmpty()) {
            throw IllegalStateException("No POI points to write.")
        }

        val byCategoryKey = linkedMapOf<String, String>()
        sanitizedPoints.forEach { point ->
            byCategoryKey.putIfAbsent(point.categoryName.lowercase(Locale.ROOT), point.categoryName)
        }

        val sortedCategories =
            byCategoryKey.entries
                .sortedBy { it.value.lowercase(Locale.ROOT) }
        val categoryIds = mutableMapOf<String, Int>()
        var nextCategoryId = 100
        sortedCategories.forEach { entry ->
            categoryIds[entry.key] = nextCategoryId++
        }

        val assigned = assignStableIds(sanitizedPoints, categoryIds)
        val bounds = computeBounds(assigned)

        if (file.exists()) {
            file.delete()
        }

        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.beginTransaction()
        try {
            db.execSQL("CREATE TABLE poi_categories (id INTEGER, name TEXT, parent INTEGER, PRIMARY KEY (id));")
            db.execSQL("CREATE TABLE poi_data (id INTEGER, data TEXT, PRIMARY KEY (id));")
            db.execSQL("CREATE TABLE poi_category_map (id INTEGER, category INTEGER, PRIMARY KEY (id, category));")
            db.execSQL("CREATE TABLE poi_index (id INTEGER, lat REAL, lon REAL, PRIMARY KEY (id));")
            db.execSQL("CREATE TABLE metadata (name TEXT, value TEXT);")
            db.execSQL("CREATE INDEX poi_index_idx_lat ON poi_index (lat);")
            db.execSQL("CREATE INDEX poi_index_idx_lon ON poi_index (lon);")

            val insertCategory =
                db.compileStatement(
                    "INSERT INTO poi_categories (id, name, parent) VALUES (?, ?, ?)",
                )
            insertCategory.bindLong(1, ROOT_CATEGORY_ID.toLong())
            insertCategory.bindString(2, ROOT_CATEGORY_NAME)
            insertCategory.bindNull(3)
            insertCategory.executeInsert()
            insertCategory.clearBindings()

            sortedCategories.forEach { entry ->
                val categoryId = categoryIds[entry.key] ?: return@forEach
                insertCategory.bindLong(1, categoryId.toLong())
                insertCategory.bindString(2, entry.value)
                insertCategory.bindLong(3, ROOT_CATEGORY_ID.toLong())
                insertCategory.executeInsert()
                insertCategory.clearBindings()
            }

            val insertIndex =
                db.compileStatement(
                    "INSERT INTO poi_index (id, lat, lon) VALUES (?, ?, ?)",
                )
            val insertData =
                db.compileStatement(
                    "INSERT INTO poi_data (id, data) VALUES (?, ?)",
                )
            val insertMap =
                db.compileStatement(
                    "INSERT INTO poi_category_map (id, category) VALUES (?, ?)",
                )

            assigned.forEach { point ->
                insertIndex.bindLong(1, point.id)
                insertIndex.bindDouble(2, point.lat)
                insertIndex.bindDouble(3, point.lon)
                insertIndex.executeInsert()
                insertIndex.clearBindings()

                insertData.bindLong(1, point.id)
                insertData.bindString(2, buildTagData(point.tags))
                insertData.executeInsert()
                insertData.clearBindings()

                insertMap.bindLong(1, point.id)
                insertMap.bindLong(2, point.categoryId.toLong())
                insertMap.executeInsert()
                insertMap.clearBindings()
            }

            val insertMetadata =
                db.compileStatement(
                    "INSERT INTO metadata (name, value) VALUES (?, ?)",
                )
            val metadata =
                linkedMapOf(
                    "bounds" to "${bounds.minLat},${bounds.minLon},${bounds.maxLat},${bounds.maxLon}",
                    "comment" to options.comment,
                    "date" to System.currentTimeMillis().toString(),
                    "language" to "",
                    "version" to "3",
                    "ways" to "true",
                    "writer" to options.writer,
                )
            metadata.putAll(options.extraMetadata)
            metadata.forEach { (key, value) ->
                insertMetadata.bindString(1, key)
                insertMetadata.bindString(2, value)
                insertMetadata.executeInsert()
                insertMetadata.clearBindings()
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }

        return byCategoryKey.size
    }

    fun openStreamingWriter(
        file: File,
        options: PoiSqliteWriteOptions,
    ): PoiSqliteStreamingWriter =
        PoiSqliteStreamingWriter(
            file = file,
            options = options,
            rootCategoryId = ROOT_CATEGORY_ID,
            rootCategoryName = ROOT_CATEGORY_NAME,
        )

    fun read(file: File): List<PoiSqlitePoint> {
        if (!file.exists() || !file.isFile) return emptyList()
        return readPoiSqlitePoints(file = file, sql = ALL_POI_SQL, selectionArgs = emptyArray())
    }

    private fun assignStableIds(
        points: List<PoiSqlitePoint>,
        categoryIds: Map<String, Int>,
    ): List<AssignedPoiPoint> {
        val used = hashSetOf<Long>()
        var nextId = 1L
        return points.map { point ->
            val categoryKey = point.categoryName.lowercase(Locale.ROOT)
            val categoryId =
                categoryIds[categoryKey]
                    ?: throw IllegalStateException("Missing POI category mapping.")

            var resolvedId = point.sourceId ?: nextId
            if (resolvedId <= 0L) resolvedId = nextId
            while (!used.add(resolvedId)) {
                resolvedId += 1L
            }
            nextId = max(nextId, resolvedId + 1L)

            AssignedPoiPoint(
                id = resolvedId,
                lat = point.lat,
                lon = point.lon,
                categoryId = categoryId,
                tags = point.tags,
            )
        }
    }

    private fun computeBounds(points: List<AssignedPoiPoint>): Bounds {
        val minLat = points.minOf { it.lat }
        val maxLat = points.maxOf { it.lat }
        val minLon = points.minOf { it.lon }
        val maxLon = points.maxOf { it.lon }
        return Bounds(
            minLat = minLat,
            minLon = minLon,
            maxLat = maxLat,
            maxLon = maxLon,
        )
    }

    internal fun sanitizeTags(tags: Map<String, String>): Map<String, String> {
        if (tags.isEmpty()) return emptyMap()
        val out = linkedMapOf<String, String>()
        tags.forEach { (rawKey, rawValue) ->
            val key = sanitizeTagKey(rawKey)
            val value = sanitizeTagValue(rawValue)
            if (key.isNotBlank() && value.isNotBlank()) {
                out[key] = value
            }
        }
        return out
    }

    internal fun buildTagData(tags: Map<String, String>): String =
        tags.entries.joinToString("\r") { (key, value) ->
            "${sanitizeTagKey(key)}=${sanitizeTagValue(value)}"
        }

    private fun sanitizeTagKey(key: String): String =
        key
            .trim()
            .replace(Regex("[^A-Za-z0-9:_-]"), "_")
            .ifBlank { "tag" }

    private fun sanitizeTagValue(value: String): String =
        value
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('\u0000', ' ')
            .trim()

    private data class AssignedPoiPoint(
        val id: Long,
        val lat: Double,
        val lon: Double,
        val categoryId: Int,
        val tags: Map<String, String>,
    )

    private data class Bounds(
        val minLat: Double,
        val minLon: Double,
        val maxLat: Double,
        val maxLon: Double,
    )
}

/** Reads only the indexed map viewport so phone map refreshes do not scan whole POI files. */
internal fun readPoiSqliteViewport(
    file: File,
    viewport: PoiSqliteViewport,
    limit: Int,
): List<PoiSqlitePoint> {
    if (!file.isReadablePoiFile() || limit <= 0 || !viewport.hasOrderedBounds()) {
        return emptyList()
    }
    return readPoiSqlitePoints(
        file = file,
        sql = VIEWPORT_POI_SQL,
        selectionArgs =
            arrayOf(
                viewport.minLat.toString(),
                viewport.maxLat.toString(),
                viewport.minLon.toString(),
                viewport.maxLon.toString(),
                limit.toString(),
            ),
    )
}

/** Checks the Mapsforge POI tables used by the phone viewport reader before installation. */
internal fun isReadablePoiSqliteFile(file: File): Boolean {
    if (!file.isReadablePoiFile()) return false
    return runCatching {
        val database = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            database
                .rawQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN " +
                        "('poi_categories', 'poi_data', 'poi_category_map', 'poi_index')",
                    null,
                ).use { cursor ->
                    val names =
                        buildSet {
                            while (cursor.moveToNext()) {
                                add(cursor.getString(0))
                            }
                        }
                    REQUIRED_POI_TABLES.all(names::contains)
                }
        } finally {
            database.close()
        }
    }.getOrDefault(false)
}

private fun readPoiSqlitePoints(
    file: File,
    sql: String,
    selectionArgs: Array<String>,
): List<PoiSqlitePoint> {
    val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    try {
        return db.rawQuery(sql, selectionArgs).use { cursor ->
            val indices = PoiSqliteColumnIndices(cursor)
            buildList {
                while (cursor.moveToNext()) {
                    indices.pointOrNull(cursor)?.let(::add)
                }
            }
        }
    } finally {
        db.close()
    }
}

private data class PoiSqliteColumnIndices(
    val id: Int,
    val lat: Int,
    val lon: Int,
    val data: Int,
    val categoryName: Int,
) {
    constructor(cursor: Cursor) : this(
        id = cursor.getColumnIndex("id"),
        lat = cursor.getColumnIndex("lat"),
        lon = cursor.getColumnIndex("lon"),
        data = cursor.getColumnIndex("data"),
        categoryName = cursor.getColumnIndex("category_name"),
    )

    fun pointOrNull(cursor: Cursor): PoiSqlitePoint? {
        if (id < 0 || lat < 0 || lon < 0) return null
        val rawData =
            if (data >= 0 && !cursor.isNull(data)) {
                cursor.getString(data).orEmpty()
            } else {
                ""
            }
        return PoiSqlitePoint(
            sourceId = cursor.getLong(id),
            lat = cursor.getDouble(lat),
            lon = cursor.getDouble(lon),
            categoryName = cursor.stringOrDefault(categoryName),
            tags = parseTagMap(rawData),
            rawData = rawData,
        )
    }
}

private fun PoiSqliteViewport.hasOrderedBounds(): Boolean = minLat <= maxLat && minLon <= maxLon

private fun File.isReadablePoiFile(): Boolean = exists() && isFile

private val REQUIRED_POI_TABLES =
    setOf(
        "poi_categories",
        "poi_data",
        "poi_category_map",
        "poi_index",
    )

private fun Cursor.stringOrDefault(index: Int): String =
    if (index >= 0 && !isNull(index)) {
        getString(index).orEmpty().ifBlank { "Other" }
    } else {
        "Other"
    }

private fun parseTagMap(data: String): Map<String, String> {
    if (data.isBlank()) return emptyMap()
    return data
        .split('\r', '\n')
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && '=' in it }
        .mapNotNull { token ->
            val index = token.indexOf('=')
            if (index <= 0 || index >= token.lastIndex) {
                null
            } else {
                val key = token.substring(0, index).trim()
                val value = token.substring(index + 1).trim()
                if (key.isBlank() || value.isBlank()) null else key to value
            }
        }.toMap()
}

private val ALL_POI_SQL =
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
    ORDER BY pi.id
    """.trimIndent()

private val VIEWPORT_POI_SQL =
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
    WHERE pi.lat BETWEEN ? AND ?
      AND pi.lon BETWEEN ? AND ?
    ORDER BY pi.id
    LIMIT ?
    """.trimIndent()

internal class PoiSqliteStreamingWriter(
    file: File,
    private val options: PoiSqliteWriteOptions,
    private val rootCategoryId: Int,
    private val rootCategoryName: String,
) : AutoCloseable {
    private val db: SQLiteDatabase
    private val insertCategory by lazy {
        db.compileStatement(
            "INSERT INTO poi_categories (id, name, parent) VALUES (?, ?, ?)",
        )
    }
    private val insertIndex by lazy {
        db.compileStatement(
            "INSERT INTO poi_index (id, lat, lon) VALUES (?, ?, ?)",
        )
    }
    private val insertData by lazy {
        db.compileStatement(
            "INSERT INTO poi_data (id, data) VALUES (?, ?)",
        )
    }
    private val insertMap by lazy {
        db.compileStatement(
            "INSERT INTO poi_category_map (id, category) VALUES (?, ?)",
        )
    }
    private val insertMetadata by lazy {
        db.compileStatement(
            "INSERT INTO metadata (name, value) VALUES (?, ?)",
        )
    }
    private val insertDedup by lazy {
        db.compileStatement(
            "INSERT OR IGNORE INTO poi_dedup (dedup_key) VALUES (?)",
        )
    }

    private val categoryIds = linkedMapOf<String, Int>()
    private val usedIds = hashSetOf<Long>()
    private var nextCategoryId = 100
    private var nextPointId = 1L
    private var pointCount = 0
    private var minLat = Double.POSITIVE_INFINITY
    private var minLon = Double.POSITIVE_INFINITY
    private var maxLat = Double.NEGATIVE_INFINITY
    private var maxLon = Double.NEGATIVE_INFINITY
    private var finished = false

    init {
        if (file.exists()) {
            file.delete()
        }
        db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.beginTransaction()
        db.execSQL("CREATE TABLE poi_categories (id INTEGER, name TEXT, parent INTEGER, PRIMARY KEY (id));")
        db.execSQL("CREATE TABLE poi_data (id INTEGER, data TEXT, PRIMARY KEY (id));")
        db.execSQL("CREATE TABLE poi_category_map (id INTEGER, category INTEGER, PRIMARY KEY (id, category));")
        db.execSQL("CREATE TABLE poi_index (id INTEGER, lat REAL, lon REAL, PRIMARY KEY (id));")
        db.execSQL("CREATE TABLE metadata (name TEXT, value TEXT);")
        db.execSQL("CREATE INDEX poi_index_idx_lat ON poi_index (lat);")
        db.execSQL("CREATE INDEX poi_index_idx_lon ON poi_index (lon);")
        db.execSQL("CREATE TEMP TABLE poi_dedup (dedup_key TEXT PRIMARY KEY);")

        insertCategory.bindLong(1, rootCategoryId.toLong())
        insertCategory.bindString(2, rootCategoryName)
        insertCategory.bindNull(3)
        insertCategory.executeInsert()
        insertCategory.clearBindings()
    }

    fun append(points: Iterable<PoiSqlitePoint>): Int {
        var inserted = 0
        points.forEach { point ->
            if (append(point)) {
                inserted += 1
            }
        }
        return inserted
    }

    fun append(point: PoiSqlitePoint): Boolean {
        check(!finished) { "POI writer is already finished." }

        val sanitizedTags = PoiSqliteCodec.sanitizeTags(point.tags)
        if (sanitizedTags.isEmpty()) return false
        val categoryName = point.categoryName.trim().ifBlank { "Other" }
        val sanitizedPoint =
            point.copy(
                categoryName = categoryName,
                tags = sanitizedTags,
            )

        insertDedup.bindString(1, buildPoiDedupKey(sanitizedPoint))
        val dedupInserted = insertDedup.executeInsert()
        insertDedup.clearBindings()
        if (dedupInserted == -1L) return false

        val categoryId = resolveCategoryId(categoryName)
        val pointId = resolvePointId(sanitizedPoint.sourceId)
        updateBounds(sanitizedPoint)

        insertIndex.bindLong(1, pointId)
        insertIndex.bindDouble(2, sanitizedPoint.lat)
        insertIndex.bindDouble(3, sanitizedPoint.lon)
        insertIndex.executeInsert()
        insertIndex.clearBindings()

        insertData.bindLong(1, pointId)
        insertData.bindString(2, PoiSqliteCodec.buildTagData(sanitizedPoint.tags))
        insertData.executeInsert()
        insertData.clearBindings()

        insertMap.bindLong(1, pointId)
        insertMap.bindLong(2, categoryId.toLong())
        insertMap.executeInsert()
        insertMap.clearBindings()

        pointCount += 1
        return true
    }

    fun finish(): PoiSqliteWriteSummary {
        check(!finished) { "POI writer is already finished." }
        if (pointCount == 0) {
            throw IllegalStateException("No POI points to write.")
        }

        val metadata =
            linkedMapOf(
                "bounds" to "$minLat,$minLon,$maxLat,$maxLon",
                "comment" to options.comment,
                "date" to System.currentTimeMillis().toString(),
                "language" to "",
                "version" to "3",
                "ways" to "true",
                "writer" to options.writer,
            )
        metadata.putAll(options.extraMetadata)
        metadata.forEach { (key, value) ->
            insertMetadata.bindString(1, key)
            insertMetadata.bindString(2, value)
            insertMetadata.executeInsert()
            insertMetadata.clearBindings()
        }

        db.setTransactionSuccessful()
        finished = true
        return PoiSqliteWriteSummary(
            pointCount = pointCount,
            categoryCount = categoryIds.size,
        )
    }

    override fun close() {
        try {
            db.endTransaction()
        } finally {
            db.close()
        }
    }

    private fun resolveCategoryId(categoryName: String): Int {
        val categoryKey = categoryName.lowercase(Locale.ROOT)
        categoryIds[categoryKey]?.let { return it }

        val categoryId = nextCategoryId++
        categoryIds[categoryKey] = categoryId
        insertCategory.bindLong(1, categoryId.toLong())
        insertCategory.bindString(2, categoryName)
        insertCategory.bindLong(3, rootCategoryId.toLong())
        insertCategory.executeInsert()
        insertCategory.clearBindings()
        return categoryId
    }

    private fun resolvePointId(sourceId: Long?): Long {
        var resolvedId = sourceId ?: nextPointId
        if (resolvedId <= 0L) {
            resolvedId = nextPointId
        }
        while (!usedIds.add(resolvedId)) {
            resolvedId += 1L
        }
        nextPointId = max(nextPointId, resolvedId + 1L)
        return resolvedId
    }

    private fun updateBounds(point: PoiSqlitePoint) {
        minLat = minOf(minLat, point.lat)
        minLon = minOf(minLon, point.lon)
        maxLat = maxOf(maxLat, point.lat)
        maxLon = maxOf(maxLon, point.lon)
    }
}

internal fun buildPoiDedupKey(point: PoiSqlitePoint): String {
    val lat = String.format(Locale.US, "%.5f", point.lat)
    val lon = String.format(Locale.US, "%.5f", point.lon)
    val name =
        point.tags["name"]
            ?.trim()
            ?.lowercase(Locale.ROOT)
            .orEmpty()
    val primaryType =
        listOf("tourism", "amenity", "natural", "highway", "shop")
            .firstNotNullOfOrNull { key ->
                point.tags[key]
                    ?.trim()
                    ?.lowercase(Locale.ROOT)
                    ?.let { "$key=$it" }
            }.orEmpty()
    return "$lat,$lon|$name|$primaryType"
}
