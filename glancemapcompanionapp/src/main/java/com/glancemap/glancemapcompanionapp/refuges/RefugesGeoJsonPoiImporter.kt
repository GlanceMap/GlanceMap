package com.glancemap.glancemapcompanionapp.refuges

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDownloadDiagnostics
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.max

data class RefugesImportRequest(
    val bbox: String,
    val fileName: String,
    val typePointIds: Set<Int>,
)

data class RefugesPointType(
    val id: Int,
    val label: String,
)

data class RefugesImportResult(
    val poiUri: Uri,
    val fileName: String,
    val pointCount: Int,
    val categoryCount: Int,
    val bbox: String,
)

class RefugesGeoJsonPoiImporter(
    private val context: Context,
) {
    @Volatile
    private var activeConnection: HttpURLConnection? = null

    companion object {
        private const val PREFS_NAME = "refuges_import"
        private const val KEY_LAST_BBOX = "last_bbox"
        private const val KEY_LAST_FILE_NAME = "last_file_name"
        private const val KEY_LAST_TYPE_POINTS = "last_type_points"
        private const val META_REFUGES_BBOX_QUERY = "refuges_bbox_query"
        private const val META_REFUGES_TYPE_POINTS = "refuges_type_points"
        private const val META_ENRICHED_WITH_OSM = "enriched_with_osm"

        private const val API_ENDPOINT = "https://www.refuges.info/api/bbox"
        private const val API_TYPE_POINTS_ALL = "all"
        private const val API_DETAIL = "complet"
        private const val API_FORMAT = "geojson"
        private const val MAX_RESPONSE_BYTES = 5_000_000
        private const val MAX_ERROR_RESPONSE_BYTES = 120_000

        private const val ROOT_CATEGORY_ID = 1
        private const val ROOT_CATEGORY_NAME = "root"

        private val DEFAULT_POINT_TYPES =
            listOf(
                RefugesPointType(id = 7, label = "cabane non gardée"),
                RefugesPointType(id = 10, label = "refuge gardé"),
                RefugesPointType(id = 9, label = "gîte d'étape"),
                RefugesPointType(id = 29, label = "grotte"),
                RefugesPointType(id = 23, label = "point d'eau"),
                RefugesPointType(id = 3, label = "passage délicat"),
                RefugesPointType(id = 28, label = "bâtiment en montagne"),
            )
        private val DEFAULT_POINT_TYPE_IDS =
            DEFAULT_POINT_TYPES
                .map { it.id }
                .toSet()

        fun defaultPointTypes(): List<RefugesPointType> = DEFAULT_POINT_TYPES

        fun defaultPointTypeIds(): Set<Int> = DEFAULT_POINT_TYPE_IDS
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun cancelActiveDownload() {
        activeConnection?.disconnect()
    }

    suspend fun importFromBbox(
        bboxInput: String,
        fileNameInput: String,
        forceRefresh: Boolean = false,
        typePointIds: Set<Int> = defaultPointTypeIds(),
        reportProgress: ((percent: Int, status: String) -> Unit)? = null,
    ): RefugesImportResult =
        withContext(Dispatchers.IO) {
            val bbox = parseBbox(bboxInput)
            val bboxQuery = bbox.asQueryParam()
            val normalizedTypePointIds = normalizeTypePointIds(typePointIds)
            val typePointQuery = toTypePointQueryValue(normalizedTypePointIds)
            val safeFileName = normalizeFileName(fileNameInput)
            val outputDir = PhoneOfflineStorage(context).poiDirectory().apply { mkdirs() }
            val outputFile = File(outputDir, safeFileName)
            val startedAtMs = SystemClock.elapsedRealtime()
            PhoneDownloadDiagnostics.log(
                "Refuges",
                "Start bbox=$bboxQuery file=$safeFileName typePoints=$typePointQuery forceRefresh=$forceRefresh",
            )

            try {
                reportProgress?.invoke(0, "Preparing Refuges.info import…")
                if (!forceRefresh) {
                    val cached =
                        tryReuseExistingImport(
                            file = outputFile,
                            expectedBbox = bboxQuery,
                            expectedTypePoints = typePointQuery,
                        )
                    if (cached != null) {
                        persistLastRequest(
                            bbox = bboxQuery,
                            fileName = safeFileName,
                            typePointIds = normalizedTypePointIds,
                        )
                        PhoneDownloadDiagnostics.log(
                            "Refuges",
                            "Cache hit bbox=$bboxQuery file=${cached.fileName} points=${cached.pointCount} categories=${cached.categoryCount}",
                        )
                        reportProgress?.invoke(94, "Using existing Refuges.info POI…")
                        return@withContext cached
                    }
                    PhoneDownloadDiagnostics.log(
                        "Refuges",
                        "Cache miss bbox=$bboxQuery file=$safeFileName",
                    )
                }

                reportProgress?.invoke(8, "Connecting Refuges.info…")
                val fetchStartedAtMs = SystemClock.elapsedRealtime()
                val geoJson =
                    fetchGeoJson(
                        bbox = bbox,
                        typePointIds = normalizedTypePointIds,
                    )
                val fetchDurationMs = SystemClock.elapsedRealtime() - fetchStartedAtMs
                reportProgress?.invoke(55, "Parsing Refuges.info…")
                val points =
                    parsePoints(
                        root = geoJson,
                        allowedTypeIds = normalizedTypePointIds,
                    )
                PhoneDownloadDiagnostics.log(
                    "Refuges",
                    "Parsed bbox=$bboxQuery points=${points.size} fetchDurationMs=$fetchDurationMs",
                )
                if (points.isEmpty()) {
                    throw IllegalStateException("No Refuges.info points found in this area.")
                }

                reportProgress?.invoke(78, "Saving Refuges.info POI…")
                val writeStartedAtMs = SystemClock.elapsedRealtime()
                val temporary = File(outputDir, ".${outputFile.name}.refuges.part")
                var installed = false
                val categoryCount =
                    try {
                        check(!temporary.exists() || temporary.delete()) { "Could not reset temporary Refuges.info POI." }
                        val categories =
                            writePoiFile(
                                file = temporary,
                                bbox = bbox,
                                points = points,
                                requestedBboxQuery = bboxQuery,
                                requestedTypePoints = typePointQuery,
                            )
                        check(isReadablePoiSqliteFile(temporary)) { "Generated Refuges.info POI is invalid." }
                        check(!outputFile.exists() || outputFile.delete()) { "Could not replace Refuges.info POI." }
                        check(temporary.renameTo(outputFile)) { "Could not install Refuges.info POI." }
                        installed = true
                        categories
                    } finally {
                        if (installed && temporary.exists()) temporary.delete()
                    }
                val writeDurationMs = SystemClock.elapsedRealtime() - writeStartedAtMs
                persistLastRequest(
                    bbox = bboxQuery,
                    fileName = safeFileName,
                    typePointIds = normalizedTypePointIds,
                )
                reportProgress?.invoke(94, "Finalizing Refuges.info POI…")

                val uri =
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        outputFile,
                    )

                val result =
                    RefugesImportResult(
                        poiUri = uri,
                        fileName = outputFile.name,
                        pointCount = points.size,
                        categoryCount = categoryCount,
                        bbox = bboxQuery,
                    )
                val durationMs = SystemClock.elapsedRealtime() - startedAtMs
                PhoneDownloadDiagnostics.log(
                    "Refuges",
                    "Complete bbox=$bboxQuery file=${result.fileName} points=${result.pointCount} categories=${result.categoryCount} writeDurationMs=$writeDurationMs durationMs=$durationMs",
                )
                result
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    PhoneDownloadDiagnostics.warn(
                        "Refuges",
                        "Cancelled bbox=$bboxQuery file=$safeFileName",
                    )
                } else {
                    PhoneDownloadDiagnostics.error(
                        "Refuges",
                        "Failed bbox=$bboxQuery file=$safeFileName",
                        error,
                    )
                }
                throw error
            }
        }

    suspend fun importLast(
        reportProgress: ((percent: Int, status: String) -> Unit)? = null,
    ): RefugesImportResult {
        val last =
            getLastRequest()
                ?: throw IllegalStateException("No previous Refuges import request found.")
        return importFromBbox(
            bboxInput = last.bbox,
            fileNameInput = last.fileName,
            forceRefresh = true,
            typePointIds = last.typePointIds,
            reportProgress = reportProgress,
        )
    }

    suspend fun importFromGeoJsonText(
        geoJsonText: String,
        fileNameInput: String,
    ): RefugesImportResult =
        withContext(Dispatchers.IO) {
            val safeFileName = normalizeFileName(fileNameInput)
            val geoJson =
                runCatching { JSONObject(geoJsonText) }.getOrElse {
                    throw IllegalArgumentException("Invalid GeoJSON content.")
                }
            val points =
                parsePoints(
                    root = geoJson,
                    allowedTypeIds = defaultPointTypeIds(),
                )
            if (points.isEmpty()) {
                throw IllegalStateException("No point features found in GeoJSON.")
            }

            val outputDir = PhoneOfflineStorage(context).poiDirectory().apply { mkdirs() }
            val outputFile = File(outputDir, safeFileName)
            if (outputFile.exists()) {
                outputFile.delete()
            }

            val sourceBounds = computeBoundsFromParsedPoints(points)
            val categoryCount =
                writePoiFile(
                    file = outputFile,
                    bbox = sourceBounds,
                    points = points,
                    requestedBboxQuery = null,
                    requestedTypePoints = API_TYPE_POINTS_ALL,
                )

            val uri =
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    outputFile,
                )
            RefugesImportResult(
                poiUri = uri,
                fileName = outputFile.name,
                pointCount = points.size,
                categoryCount = categoryCount,
                bbox = sourceBounds?.asQueryParam().orEmpty(),
            )
        }

    fun getLastRequest(): RefugesImportRequest? {
        val bbox = prefs.getString(KEY_LAST_BBOX, null)?.trim().orEmpty()
        if (bbox.isBlank()) return null
        val fileName =
            prefs
                .getString(KEY_LAST_FILE_NAME, "refuges-info.poi")
                ?.trim()
                .orEmpty()
                .ifBlank { "refuges-info.poi" }
        val typePointIds =
            parseStoredTypePointIds(
                prefs.getString(KEY_LAST_TYPE_POINTS, null),
            )
        return RefugesImportRequest(
            bbox = bbox,
            fileName = normalizeFileName(fileName),
            typePointIds = typePointIds,
        )
    }

    private fun persistLastRequest(
        bbox: String,
        fileName: String,
        typePointIds: Set<Int>,
    ) {
        prefs
            .edit()
            .putString(KEY_LAST_BBOX, bbox)
            .putString(KEY_LAST_FILE_NAME, fileName)
            .putString(KEY_LAST_TYPE_POINTS, toTypePointQueryValue(typePointIds))
            .apply()
    }

    private fun fetchGeoJson(
        bbox: BBox,
        typePointIds: Set<Int>,
    ): JSONObject {
        val requestedTypePoints =
            if (typePointIds.size == 1) {
                typePointIds.first().toString()
            } else {
                API_TYPE_POINTS_ALL
            }
        val bboxQuery = bbox.asQueryParam()
        val query =
            listOf(
                "bbox" to bboxQuery,
                "type_points" to requestedTypePoints,
                "detail" to API_DETAIL,
                "format" to API_FORMAT,
            ).joinToString("&") { (k, v) ->
                "${encode(k)}=${encode(v)}"
            }
        val url = URL("$API_ENDPOINT?$query")
        val requestStartedAtMs = SystemClock.elapsedRealtime()
        val connection =
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 45_000
                setRequestProperty("Accept", "application/geo+json,application/json")
                setRequestProperty("User-Agent", "GlanceMap-Companion")
            }
        activeConnection = connection

        try {
            val code = connection.responseCode
            val body =
                if (code in 200..299) {
                    readResponseText(connection.inputStream, MAX_RESPONSE_BYTES)
                } else {
                    readResponseText(connection.errorStream, MAX_ERROR_RESPONSE_BYTES)
                }
            val durationMs = SystemClock.elapsedRealtime() - requestStartedAtMs

            if (code !in 200..299) {
                val detail = body.take(220).replace('\n', ' ').trim()
                PhoneDownloadDiagnostics.warn(
                    "Refuges",
                    "HTTP error bbox=$bboxQuery typePoints=$requestedTypePoints code=$code durationMs=$durationMs detail=${detail.ifBlank { "na" }}",
                )
                throw IllegalStateException(
                    "Refuges API failed ($code). ${detail.ifBlank { "Please try again." }}",
                )
            }

            PhoneDownloadDiagnostics.log(
                "Refuges",
                "HTTP complete bbox=$bboxQuery typePoints=$requestedTypePoints code=$code responseChars=${body.length} durationMs=$durationMs",
            )
            return JSONObject(body)
        } finally {
            if (activeConnection === connection) activeConnection = null
            connection.disconnect()
        }
    }

    private fun parsePoints(
        root: JSONObject,
        allowedTypeIds: Set<Int>,
    ): List<ParsedPoint> {
        val features = root.optJSONArray("features") ?: return emptyList()
        val points = ArrayList<ParsedPoint>(features.length())
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val geometry = feature.optJSONObject("geometry") ?: continue
            val geometryType = geometry.optString("type", "")
            if (!geometryType.equals("Point", ignoreCase = true)) continue

            val coordinates = geometry.optJSONArray("coordinates") ?: continue
            val lon = coordinates.optDouble(0, Double.NaN)
            val lat = coordinates.optDouble(1, Double.NaN)
            if (!lon.isFinite() || !lat.isFinite()) continue

            val props = feature.optJSONObject("properties") ?: JSONObject()
            if (props.optJSONArray("features") != null || props.has("cluster")) continue
            val name =
                firstNonBlank(
                    props.optString("nom", ""),
                    props.optString("name", ""),
                    props.optString("titre", ""),
                ).ifBlank { "Refuges.info point" }
            if (name.equals("Cliquer pour zoomer", ignoreCase = true)) continue

            val type = parseTypeDescriptor(props.opt("type"), props)
            if (type.id == null && allowedTypeIds != defaultPointTypeIds()) continue
            if (type.id != null && type.id !in allowedTypeIds) continue
            val icon =
                firstNonBlank(
                    props.optString("icone", ""),
                    props.optString("icon", ""),
                )
            val poiTag = inferPoiTag(type.label, type.sym, icon)

            val sourceId =
                parseLongValue(
                    props.opt("id"),
                    feature.opt("id"),
                )
            val website =
                firstNonBlank(
                    props.optString("lien", ""),
                    props.optString("link", ""),
                    props.optString("url", ""),
                    props.optString("website", ""),
                ).let { raw ->
                    if (raw.startsWith("/")) "https://www.refuges.info$raw" else raw
                }
            val elevation =
                parseLongValue(
                    props.opt("alt"),
                    props.opt("altitude"),
                    props.opt("ele"),
                    parseObjectLongField(props.opt("coord"), "alt"),
                )?.toInt()
            val sleepingPlaces =
                parseLongValue(
                    props.opt("places"),
                    parseObjectLongField(props.opt("places"), "valeur"),
                    props.opt("capacity"),
                )?.toInt()
            val state =
                parseStringValue(
                    props.opt("etat"),
                    parseObjectStringField(props.opt("etat"), "valeur"),
                    props.opt("state"),
                    props.opt("status"),
                )
            val shortDescription = parseShortDescription(props)

            points +=
                ParsedPoint(
                    sourceId = sourceId,
                    lat = lat,
                    lon = lon,
                    name = name,
                    typeId = type.id,
                    typeLabel = type.label.ifBlank { "Other" },
                    typeSym = type.sym,
                    icon = icon,
                    website = website.takeIf { it.isNotBlank() },
                    elevation = elevation,
                    sleepingPlaces = sleepingPlaces,
                    state = state,
                    shortDescription = shortDescription,
                ).copy(
                    poiKey = poiTag.first,
                    poiValue = poiTag.second,
                )
        }
        return points
    }

    private fun parseTypeDescriptor(
        rawType: Any?,
        props: JSONObject,
    ): TypeDescriptor =
        when (rawType) {
            is JSONObject -> {
                TypeDescriptor(
                    id = rawType.optInt("id", -1).takeIf { it > 0 },
                    label =
                        firstNonBlank(
                            rawType.optString("valeur", ""),
                            rawType.optString("name", ""),
                            rawType.optString("label", ""),
                        ),
                    sym = rawType.optString("sym", ""),
                )
            }

            is Number -> {
                TypeDescriptor(
                    id = rawType.toInt().takeIf { it > 0 },
                    label =
                        firstNonBlank(
                            props.optString("type_nom", ""),
                            props.optString("type_name", ""),
                            props.optString("categorie", ""),
                        ),
                    sym = "",
                )
            }

            is String -> {
                TypeDescriptor(
                    id = parseLongValue(props.opt("type_id"))?.toInt(),
                    label = rawType.trim(),
                    sym = "",
                )
            }

            else -> {
                TypeDescriptor(
                    id = parseLongValue(props.opt("type_id"))?.toInt(),
                    label =
                        firstNonBlank(
                            props.optString("type_nom", ""),
                            props.optString("type_name", ""),
                            props.optString("categorie", ""),
                        ),
                    sym = props.optString("sym", ""),
                )
            }
        }

    private fun inferPoiTag(
        typeLabel: String,
        sym: String,
        icon: String,
    ): Pair<String, String> {
        val haystack =
            listOf(typeLabel, sym, icon)
                .joinToString(" ")
                .lowercase(Locale.ROOT)

        return when {
            haystack.contains("sommet") || haystack.contains("summit") || haystack.contains("peak") ->
                "natural" to "peak"

            haystack.contains("water") || haystack.contains("eau") || haystack.contains("source") || haystack.contains("spring") ->
                "amenity" to "drinking_water"

            haystack.contains("camp") || haystack.contains("bivouac") ->
                "tourism" to "camp_site"

            haystack.contains("refuge") ||
                haystack.contains("hut") ||
                haystack.contains("cabane") ||
                haystack.contains("abri") ||
                haystack.contains("lodging") ||
                haystack.contains("gite") ||
                haystack.contains("gîte") ||
                haystack.contains("batiment en montagne") ||
                haystack.contains("bâtiment en montagne") ->
                "tourism" to "alpine_hut"

            haystack.contains("viewpoint") || haystack.contains("belved") || haystack.contains("scenic") ->
                "tourism" to "viewpoint"

            haystack.contains("toilet") || haystack.contains("wc") ->
                "amenity" to "toilets"

            haystack.contains("parking") ->
                "amenity" to "parking"

            haystack.contains("restaurant") || haystack.contains("food") || haystack.contains("cafe") || haystack.contains("bar") ->
                "amenity" to "restaurant"

            else -> "tourism" to "information"
        }
    }

    private fun writePoiFile(
        file: File,
        bbox: BBox?,
        points: List<ParsedPoint>,
        requestedBboxQuery: String?,
        requestedTypePoints: String,
    ): Int {
        val byCategoryKey = linkedMapOf<String, String>()
        points.forEach { point ->
            val categoryName =
                point.typeLabel.ifBlank {
                    defaultCategoryName(point.poiKey, point.poiValue)
                }
            val key = "${point.typeId ?: -1}|${categoryName.lowercase(Locale.ROOT)}"
            byCategoryKey.putIfAbsent(key, categoryName)
        }

        val sortedCategories =
            byCategoryKey.entries
                .sortedBy { it.value.lowercase(Locale.ROOT) }
        val categoryIds = mutableMapOf<String, Int>()
        var nextCategoryId = 100
        sortedCategories.forEach { entry ->
            categoryIds[entry.key] = nextCategoryId++
        }

        val assigned = assignStableIds(points, categoryIds)
        val computedBounds = computeBounds(assigned)

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
                val id = categoryIds[entry.key] ?: return@forEach
                insertCategory.bindLong(1, id.toLong())
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
                insertData.bindString(2, buildTagData(point))
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
            val effectiveBounds = computedBounds ?: bbox
            val metadata =
                linkedMapOf(
                    "bounds" to
                        if (effectiveBounds != null) {
                            "${effectiveBounds.minLat},${effectiveBounds.minLon},${effectiveBounds.maxLat},${effectiveBounds.maxLon}"
                        } else {
                            "0,0,0,0"
                        },
                    "comment" to "Data source: refuges.info",
                    "date" to System.currentTimeMillis().toString(),
                    "language" to "",
                    "version" to "3",
                    "ways" to "true",
                    "writer" to "glancemap-refuges-importer-1",
                )
            if (!requestedBboxQuery.isNullOrBlank()) {
                metadata[META_REFUGES_BBOX_QUERY] = requestedBboxQuery
            }
            metadata[META_REFUGES_TYPE_POINTS] = requestedTypePoints
            metadata.forEach { (k, v) ->
                insertMetadata.bindString(1, k)
                insertMetadata.bindString(2, v)
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

    private fun tryReuseExistingImport(
        file: File,
        expectedBbox: String,
        expectedTypePoints: String,
    ): RefugesImportResult? {
        if (!file.exists() || !file.isFile) return null

        return runCatching {
            val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val pointCount: Int
            val categoryCount: Int
            val cachedBbox: String
            val cachedTypePoints: String
            try {
                cachedBbox = readMetadataValue(db, META_REFUGES_BBOX_QUERY).orEmpty()
                if (cachedBbox.isBlank() || cachedBbox != expectedBbox) {
                    return@runCatching null
                }
                if (metadataFlagTrue(readMetadataValue(db, META_ENRICHED_WITH_OSM))) {
                    return@runCatching null
                }
                cachedTypePoints =
                    readMetadataValue(db, META_REFUGES_TYPE_POINTS)
                        ?.trim()
                        .orEmpty()
                        .ifBlank { API_TYPE_POINTS_ALL }
                if (cachedTypePoints != expectedTypePoints) {
                    return@runCatching null
                }
                pointCount = queryLong(db, "SELECT COUNT(*) FROM poi_index").toInt()
                categoryCount =
                    queryLong(
                        db,
                        "SELECT COUNT(*) FROM poi_categories WHERE id != ?",
                        arrayOf(ROOT_CATEGORY_ID.toString()),
                    ).toInt()
            } finally {
                db.close()
            }

            val uri =
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
            RefugesImportResult(
                poiUri = uri,
                fileName = file.name,
                pointCount = pointCount,
                categoryCount = categoryCount,
                bbox = cachedBbox,
            )
        }.getOrNull()
    }

    private fun readMetadataValue(
        db: SQLiteDatabase,
        key: String,
    ): String? =
        runCatching {
            db
                .rawQuery(
                    "SELECT value FROM metadata WHERE name = ? LIMIT 1",
                    arrayOf(key),
                ).use { cursor ->
                    if (!cursor.moveToFirst() || cursor.isNull(0)) null else cursor.getString(0)
                }
        }.getOrNull()

    private fun metadataFlagTrue(raw: String?): Boolean {
        val normalized = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return normalized == "true" || normalized == "1" || normalized == "yes"
    }

    private fun queryLong(
        db: SQLiteDatabase,
        sql: String,
        args: Array<String> = emptyArray(),
    ): Long =
        db.rawQuery(sql, args).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }

    private fun assignStableIds(
        points: List<ParsedPoint>,
        categoryIds: Map<String, Int>,
    ): List<ResolvedPoint> {
        val used = hashSetOf<Long>()
        var nextId = 1L
        return points.map { point ->
            val categoryName =
                point.typeLabel.ifBlank {
                    defaultCategoryName(point.poiKey, point.poiValue)
                }
            val categoryKey = "${point.typeId ?: -1}|${categoryName.lowercase(Locale.ROOT)}"
            val categoryId =
                categoryIds[categoryKey]
                    ?: throw IllegalStateException("Missing POI category mapping.")

            var resolvedId = point.sourceId ?: nextId
            if (resolvedId <= 0L) resolvedId = nextId
            while (!used.add(resolvedId)) {
                resolvedId += 1L
            }
            nextId = max(nextId, resolvedId + 1L)

            ResolvedPoint(
                id = resolvedId,
                lat = point.lat,
                lon = point.lon,
                name = point.name,
                typeLabel = point.typeLabel,
                typeSym = point.typeSym,
                icon = point.icon,
                website = point.website,
                elevation = point.elevation,
                sleepingPlaces = point.sleepingPlaces,
                state = point.state,
                shortDescription = point.shortDescription,
                poiKey = point.poiKey,
                poiValue = point.poiValue,
                categoryId = categoryId,
                sourceId = point.sourceId,
            )
        }
    }

    private fun computeBounds(points: List<ResolvedPoint>): BBox? {
        if (points.isEmpty()) return null
        val minLat = points.minOf { it.lat }
        val maxLat = points.maxOf { it.lat }
        val minLon = points.minOf { it.lon }
        val maxLon = points.maxOf { it.lon }
        return BBox(
            minLon = minLon,
            minLat = minLat,
            maxLon = maxLon,
            maxLat = maxLat,
        )
    }

    private fun computeBoundsFromParsedPoints(points: List<ParsedPoint>): BBox? {
        if (points.isEmpty()) return null
        val minLat = points.minOf { it.lat }
        val maxLat = points.maxOf { it.lat }
        val minLon = points.minOf { it.lon }
        val maxLon = points.maxOf { it.lon }
        return BBox(
            minLon = minLon,
            minLat = minLat,
            maxLon = maxLon,
            maxLat = maxLat,
        )
    }
}
