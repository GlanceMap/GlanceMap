@file:Suppress(
    "CyclomaticComplexMethod",
    "LoopWithTooManyJumpStatements",
    "LongMethod",
    "MaxLineLength",
    "ReturnCount",
    "TooManyFunctions",
    "UseCheckOrError",
    "UseRequire",
)

package com.glancemap.glancemapwearos.presentation.features.download

import android.content.Context
import android.content.res.Resources
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.data.repository.PoiRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.mapsforge.map.reader.MapFile
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.cert.CertPathValidatorException
import java.util.Locale
import javax.net.ssl.SSLHandshakeException
import kotlin.coroutines.coroutineContext
import kotlin.math.max

internal class RefugesInfoPoiImporter(
    private val context: Context,
    private val poiRepository: PoiRepository,
    private val activeConnections: MutableSet<HttpURLConnection>,
) {
    suspend fun importForMap(
        mapFile: File,
        fileName: String,
        areaLabel: String,
        onProgress: (OamDownloadProgress) -> Unit,
    ): String =
        withContext(Dispatchers.IO) {
            val bbox =
                readMapBbox(mapFile)
                    ?: throw IllegalArgumentException(context.resources.getString(R.string.download_cannot_read_map_bounds_for_refuges_info))
            validateRefugesBbox(bbox, context.resources)
            val safeFileName = normalizeRefugesPoiFileName(fileName)
            val tempFile = File(context.cacheDir, ".$safeFileName.tmp")
            tempFile.parentFile?.mkdirs()
            tempFile.delete()

            try {
                onProgress(
                    OamDownloadProgress(
                        phase = "DOWNLOADING",
                        detail = "Refuges.info",
                    ),
                )
                val geoJson = fetchGeoJson(bbox)
                coroutineContext.ensureActive()

                onProgress(
                    OamDownloadProgress(
                        phase = "PROCESSING",
                        detail = context.resources.getString(R.string.download_refuges_info_poi),
                    ),
                )
                val points = parsePoints(geoJson)
                if (points.isEmpty()) {
                    throw IllegalStateException(context.resources.getString(R.string.download_no_refuges_info_points_found_for, areaLabel))
                }

                onProgress(
                    OamDownloadProgress(
                        phase = "SAVING",
                        detail = context.resources.getString(R.string.download_refuges_info_poi),
                    ),
                )
                writePoiFile(
                    file = tempFile,
                    bbox = bbox,
                    points = points,
                )
                FileInputStream(tempFile).use { input ->
                    poiRepository.savePoiFileAtomic(
                        fileName = safeFileName,
                        inputStream = input,
                        expectedSize = tempFile.length().takeIf { it > 0L },
                        resumeOffset = 0L,
                        onProgress = { bytes ->
                            onProgress(
                                OamDownloadProgress(
                                    phase = "SAVING",
                                    detail = context.resources.getString(R.string.download_refuges_info_poi),
                                    bytesDone = bytes,
                                    totalBytes = tempFile.length().takeIf { it > 0L },
                                ),
                            )
                        },
                    )
                }
                DebugTelemetry.log(
                    OAM_DOWNLOAD_TELEMETRY_TAG,
                    "event=refuges_info_import_complete area=${areaLabel.telemetryValue()} " +
                        "file=$safeFileName points=${points.size}",
                )
                safeFileName
            } finally {
                tempFile.delete()
            }
        }

    private fun readMapBbox(mapFile: File): RefugesBbox? =
        runCatching {
            val map = MapFile(mapFile)
            val bbox =
                try {
                    map.boundingBox()
                } finally {
                    runCatching { map.close() }
                }
            RefugesBbox(
                minLon = bbox.minLongitude,
                minLat = bbox.minLatitude,
                maxLon = bbox.maxLongitude,
                maxLat = bbox.maxLatitude,
            )
        }.getOrNull()

    private fun fetchGeoJson(bbox: RefugesBbox): JSONObject {
        val query =
            listOf(
                "bbox" to bbox.asQueryParam(),
                "type_points" to REFUGES_API_TYPE_POINTS_ALL,
                "detail" to REFUGES_API_DETAIL,
                "format" to REFUGES_API_FORMAT,
            ).joinToString("&") { (key, value) ->
                "${encode(key)}=${encode(value)}"
            }
        val httpsUrl = "$REFUGES_API_HTTPS_ENDPOINT?$query"
        return try {
            fetchGeoJsonFromUrl(httpsUrl)
        } catch (error: SSLHandshakeException) {
            if (!error.isChainValidationFailure()) throw error
            DebugTelemetry.log(
                OAM_DOWNLOAD_TELEMETRY_TAG,
                "event=refuges_info_tls_chain_failed retry=http",
            )
            fetchGeoJsonFromUrl("$REFUGES_API_HTTP_ENDPOINT?$query")
        }
    }

    private fun fetchGeoJsonFromUrl(url: String): JSONObject {
        val connection =
            (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = REFUGES_CONNECT_TIMEOUT_MS
                readTimeout = REFUGES_READ_TIMEOUT_MS
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("Accept", "application/geo+json,application/json")
                setRequestProperty("User-Agent", REFUGES_USER_AGENT)
            }
        activeConnections += connection
        try {
            val code = connection.responseCode
            val body =
                if (code in 200..299) {
                    readResponseText(connection.inputStream, REFUGES_MAX_RESPONSE_BYTES, context.resources)
                } else {
                    readResponseText(connection.errorStream, REFUGES_MAX_ERROR_RESPONSE_BYTES, context.resources)
                }
            if (code !in 200..299) {
                val detail = body.take(220).replace('\n', ' ').trim()
                throw IllegalStateException(
                    context.resources.getString(R.string.download_refuges_info_failed, code, detail.ifBlank { context.getString(R.string.download_please_try_again) }),
                )
            }
            return JSONObject(body)
        } finally {
            activeConnections -= connection
            connection.disconnect()
        }
    }

    private fun parsePoints(root: JSONObject): List<RefugesParsedPoint> {
        val features = root.optJSONArray("features") ?: return emptyList()
        val points = ArrayList<RefugesParsedPoint>(features.length())
        for (index in 0 until features.length()) {
            val feature = features.optJSONObject(index) ?: continue
            val geometry = feature.optJSONObject("geometry") ?: continue
            if (!geometry.optString("type", "").equals("Point", ignoreCase = true)) continue
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
                ).ifBlank { context.resources.getString(R.string.download_refuges_info_point) }
            if (name.equals("Cliquer pour zoomer", ignoreCase = true)) continue

            val type = parseTypeDescriptor(props.opt("type"), props)
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

            points +=
                RefugesParsedPoint(
                    sourceId = sourceId,
                    lat = lat,
                    lon = lon,
                    name = name,
                    typeId = type.id,
                    typeLabel = type.label.ifBlank { "Other" },
                    typeSym = type.sym,
                    icon = icon,
                    website = website.takeIf { it.isNotBlank() },
                    elevation =
                        parseLongValue(
                            props.opt("alt"),
                            props.opt("altitude"),
                            props.opt("ele"),
                            parseObjectLongField(props.opt("coord"), "alt"),
                        )?.toInt(),
                    sleepingPlaces =
                        parseLongValue(
                            props.opt("places"),
                            parseObjectLongField(props.opt("places"), "valeur"),
                            props.opt("capacity"),
                        )?.toInt(),
                    state =
                        parseStringValue(
                            props.opt("etat"),
                            parseObjectStringField(props.opt("etat"), "valeur"),
                            props.opt("state"),
                            props.opt("status"),
                        ),
                    shortDescription = parseShortDescription(props),
                    poiKey = poiTag.first,
                    poiValue = poiTag.second,
                )
        }
        return points
    }

    private fun writePoiFile(
        file: File,
        bbox: RefugesBbox,
        points: List<RefugesParsedPoint>,
    ) {
        val byCategoryKey = linkedMapOf<String, String>()
        points.forEach { point ->
            val categoryName =
                point.typeLabel.ifBlank {
                    defaultCategoryName(point.poiKey, point.poiValue)
                }
            val key = "${point.typeId ?: -1}|${categoryName.lowercase(Locale.ROOT)}"
            byCategoryKey.putIfAbsent(key, categoryName)
        }
        val sortedCategories = byCategoryKey.entries.sortedBy { it.value.lowercase(Locale.ROOT) }
        val categoryIds = mutableMapOf<String, Int>()
        var nextCategoryId = 100
        sortedCategories.forEach { entry ->
            categoryIds[entry.key] = nextCategoryId++
        }
        val assigned = assignStableIds(points, categoryIds)
        val computedBounds = computeBounds(assigned) ?: bbox

        if (file.exists()) file.delete()
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

            db.compileStatement("INSERT INTO poi_categories (id, name, parent) VALUES (?, ?, ?)").useStatement {
                it.bindLong(1, REFUGES_ROOT_CATEGORY_ID.toLong())
                it.bindString(2, REFUGES_ROOT_CATEGORY_NAME)
                it.bindNull(3)
                it.executeInsert()
                it.clearBindings()

                sortedCategories.forEach { entry ->
                    val id = categoryIds[entry.key] ?: return@forEach
                    it.bindLong(1, id.toLong())
                    it.bindString(2, entry.value)
                    it.bindLong(3, REFUGES_ROOT_CATEGORY_ID.toLong())
                    it.executeInsert()
                    it.clearBindings()
                }
            }

            val insertIndex = db.compileStatement("INSERT INTO poi_index (id, lat, lon) VALUES (?, ?, ?)")
            val insertData = db.compileStatement("INSERT INTO poi_data (id, data) VALUES (?, ?)")
            val insertMap = db.compileStatement("INSERT INTO poi_category_map (id, category) VALUES (?, ?)")
            try {
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
            } finally {
                insertIndex.close()
                insertData.close()
                insertMap.close()
            }

            db.compileStatement("INSERT INTO metadata (name, value) VALUES (?, ?)").useStatement { statement ->
                linkedMapOf(
                    "bounds" to "${computedBounds.minLat},${computedBounds.minLon},${computedBounds.maxLat},${computedBounds.maxLon}",
                    "comment" to "Data source: refuges.info",
                    "date" to System.currentTimeMillis().toString(),
                    "language" to "",
                    "version" to "3",
                    "ways" to "true",
                    "writer" to "glancemap-watch-refuges-importer-1",
                    "refuges_bbox_query" to bbox.asQueryParam(),
                    "refuges_type_points" to REFUGES_API_TYPE_POINTS_ALL,
                ).forEach { (key, value) ->
                    statement.bindString(1, key)
                    statement.bindString(2, value)
                    statement.executeInsert()
                    statement.clearBindings()
                }
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    private fun assignStableIds(
        points: List<RefugesParsedPoint>,
        categoryIds: Map<String, Int>,
    ): List<RefugesAssignedPoint> {
        val used = hashSetOf<Long>()
        var nextId = 1L
        return points.map { point ->
            val categoryName = point.typeLabel.ifBlank { defaultCategoryName(point.poiKey, point.poiValue) }
            val categoryKey = "${point.typeId ?: -1}|${categoryName.lowercase(Locale.ROOT)}"
            val categoryId = categoryIds[categoryKey] ?: throw IllegalStateException("Missing POI category mapping.")
            var resolvedId = point.sourceId ?: nextId
            if (resolvedId <= 0L) resolvedId = nextId
            while (!used.add(resolvedId)) {
                resolvedId += 1L
            }
            nextId = max(nextId, resolvedId + 1L)

            RefugesAssignedPoint(
                id = resolvedId,
                lat = point.lat,
                lon = point.lon,
                categoryId = categoryId,
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
                sourceId = point.sourceId,
            )
        }
    }
}

private fun validateRefugesBbox(
    bbox: RefugesBbox,
    resources: Resources,
) {
    if (bbox.minLon >= bbox.maxLon || bbox.minLat >= bbox.maxLat) {
        throw IllegalArgumentException(resources.getString(R.string.download_invalid_map_bounds_for_refuges_info))
    }
}

private fun normalizeRefugesPoiFileName(input: String): String {
    val base =
        input
            .trim()
            .ifBlank { "refuges-info.poi" }
            .replace("\\", "_")
            .replace("/", "_")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
            .ifBlank { "refuges-info.poi" }
    return if (base.lowercase(Locale.ROOT).endsWith(".poi")) base else "$base.poi"
}

private fun parseTypeDescriptor(
    rawType: Any?,
    props: JSONObject,
): RefugesTypeDescriptor =
    when (rawType) {
        is JSONObject ->
            RefugesTypeDescriptor(
                id = rawType.optInt("id", -1).takeIf { it > 0 },
                label =
                    firstNonBlank(
                        rawType.optString("valeur", ""),
                        rawType.optString("name", ""),
                        rawType.optString("label", ""),
                    ),
                sym = rawType.optString("sym", ""),
            )
        is Number ->
            RefugesTypeDescriptor(
                id = rawType.toInt().takeIf { it > 0 },
                label =
                    firstNonBlank(
                        props.optString("type_nom", ""),
                        props.optString("type_name", ""),
                        props.optString("categorie", ""),
                    ),
                sym = "",
            )
        is String ->
            RefugesTypeDescriptor(
                id = parseLongValue(props.opt("type_id"))?.toInt(),
                label = rawType.trim(),
                sym = "",
            )
        else ->
            RefugesTypeDescriptor(
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

private fun inferPoiTag(
    typeLabel: String,
    sym: String,
    icon: String,
): Pair<String, String> {
    val haystack = listOf(typeLabel, sym, icon).joinToString(" ").lowercase(Locale.ROOT)
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
            haystack.contains("batiment en montagne") ->
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

private fun defaultCategoryName(
    key: String,
    value: String,
): String =
    when ("$key=$value") {
        "tourism=alpine_hut" -> "Alpine Huts"
        "tourism=camp_site" -> "Camping"
        "natural=peak" -> "Peaks"
        "amenity=drinking_water" -> "Water"
        "tourism=viewpoint" -> "Viewpoints"
        "amenity=toilets" -> "Toilets"
        "amenity=parking" -> "Parking"
        "amenity=restaurant" -> "Food"
        else -> "Other"
    }

private fun buildTagData(point: RefugesAssignedPoint): String {
    val tags = linkedMapOf<String, String>()
    tags["name"] = point.name
    tags[point.poiKey] = point.poiValue
    tags["source"] = "refuges.info"
    point.sourceId?.let { tags["refuges_info:id"] = it.toString() }
    if (point.typeLabel.isNotBlank()) tags["refuges_info:type"] = point.typeLabel
    if (point.typeSym.isNotBlank()) tags["refuges_info:sym"] = point.typeSym
    if (point.icon.isNotBlank()) tags["refuges_info:icon"] = point.icon
    point.website?.takeIf { it.isNotBlank() }?.let { tags["website"] = it }
    point.elevation?.takeIf { it > 0 }?.let { tags["ele"] = it.toString() }
    point.sleepingPlaces?.takeIf { it >= 0 }?.let {
        tags["capacity"] = it.toString()
        tags["refuges_info:places"] = it.toString()
    }
    point.state?.takeIf { it.isNotBlank() }?.let { tags["refuges_info:state"] = it }
    point.shortDescription?.takeIf { it.isNotBlank() }?.let { tags["refuges_info:description"] = it }
    return tags.entries.joinToString("\r") { (key, value) ->
        "${sanitizeTagKey(key)}=${sanitizeTagValue(value)}"
    }
}

private fun parseShortDescription(props: JSONObject): String? {
    val raw =
        parseStringValue(
            props.opt("description_courte"),
            props.opt("short_description"),
            props.opt("description_short"),
            props.opt("resume"),
            props.opt("description"),
            props.opt("desc"),
            props.opt("remarque"),
            props.opt("commentaire"),
            props.opt("comment"),
            props.opt("acces"),
            props.opt("infos_comp"),
            parseObjectStringField(props.opt("description"), "texte"),
            parseObjectStringField(props.opt("description"), "text"),
            parseObjectStringField(props.opt("description"), "description"),
            parseObjectStringField(props.opt("description"), "resume"),
            parseObjectStringField(props.opt("resume"), "texte"),
            parseObjectStringField(props.opt("resume"), "text"),
            parseObjectStringField(props.opt("acces"), "texte"),
            parseObjectStringField(props.opt("remarque"), "texte"),
            parseObjectStringField(props.opt("infos_comp"), "texte"),
        ) ?: return null
    val cleaned =
        raw
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p>"), "\n")
            .replace(Regex("(?i)<p[^>]*>"), "")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(REFUGES_MAX_SHORT_DESCRIPTION_CHARS)
    return cleaned.takeIf { it.isNotBlank() }
}

private fun parseObjectLongField(
    raw: Any?,
    key: String,
): Long? = (raw as? JSONObject)?.let { parseLongValue(it.opt(key)) }

private fun parseObjectStringField(
    raw: Any?,
    key: String,
): String? = (raw as? JSONObject)?.let { parseStringValue(it.opt(key)) }

private fun parseStringValue(vararg values: Any?): String? {
    values.forEach { value ->
        when (value) {
            is String -> value.trim().takeIf { it.isNotBlank() }?.let { return it }
            is Number -> return value.toString()
            is JSONObject -> {
                val nested =
                    firstNonBlank(
                        value.optString("valeur", ""),
                        value.optString("value", ""),
                        value.optString("name", ""),
                        value.optString("label", ""),
                    )
                if (nested.isNotBlank()) return nested
            }
        }
    }
    return null
}

private fun parseLongValue(vararg values: Any?): Long? {
    values.forEach { value ->
        when (value) {
            is Number -> return value.toLong()
            is String -> {
                val cleaned = value.trim()
                cleaned.toLongOrNull()?.let { return it }
                cleaned.toDoubleOrNull()?.let { return it.toLong() }
                REFUGES_INTEGER_REGEX
                    .find(cleaned)
                    ?.value
                    ?.toLongOrNull()
                    ?.let { return it }
            }
        }
    }
    return null
}

private fun readResponseText(
    stream: InputStream?,
    maxBytes: Int,
    resources: Resources,
): String {
    if (stream == null) return ""
    stream.use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val out = ByteArray(maxBytes.coerceAtLeast(1))
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (total + read > maxBytes) {
                throw IllegalStateException(resources.getString(R.string.download_refuges_info_response_is_too_large_choose_a_smaller_oam_area))
            }
            System.arraycopy(buffer, 0, out, total, read)
            total += read
        }
        return String(out, 0, total, Charsets.UTF_8)
    }
}

private fun computeBounds(points: List<RefugesAssignedPoint>): RefugesBbox? {
    if (points.isEmpty()) return null
    return RefugesBbox(
        minLon = points.minOf { it.lon },
        minLat = points.minOf { it.lat },
        maxLon = points.maxOf { it.lon },
        maxLat = points.maxOf { it.lat },
    )
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

private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

private fun firstNonBlank(vararg values: String): String = values.firstOrNull { it.isNotBlank() }?.trim().orEmpty()

private fun Throwable.isChainValidationFailure(): Boolean =
    generateSequence(this as Throwable?) { it.cause }.any { cause ->
        cause is CertPathValidatorException ||
            cause.message?.contains("chain validation failed", ignoreCase = true) == true
    }

private inline fun <R> SQLiteStatement.useStatement(block: (SQLiteStatement) -> R): R =
    try {
        block(this)
    } finally {
        close()
    }

private fun String.telemetryValue(): String = replace(' ', '_')

private data class RefugesTypeDescriptor(
    val id: Int?,
    val label: String,
    val sym: String,
)

private data class RefugesParsedPoint(
    val sourceId: Long?,
    val lat: Double,
    val lon: Double,
    val name: String,
    val typeId: Int?,
    val typeLabel: String,
    val typeSym: String,
    val icon: String,
    val website: String?,
    val elevation: Int?,
    val sleepingPlaces: Int?,
    val state: String?,
    val shortDescription: String?,
    val poiKey: String,
    val poiValue: String,
)

private data class RefugesAssignedPoint(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val categoryId: Int,
    val name: String,
    val typeLabel: String,
    val typeSym: String,
    val icon: String,
    val website: String?,
    val elevation: Int?,
    val sleepingPlaces: Int?,
    val state: String?,
    val shortDescription: String?,
    val poiKey: String,
    val poiValue: String,
    val sourceId: Long?,
)

private data class RefugesBbox(
    val minLon: Double,
    val minLat: Double,
    val maxLon: Double,
    val maxLat: Double,
) {
    fun asQueryParam(): String = "$minLon,$minLat,$maxLon,$maxLat"
}

private val REFUGES_INTEGER_REGEX = Regex("-?\\d+")
private const val REFUGES_API_HTTPS_ENDPOINT = "https://www.refuges.info/api/bbox"
private const val REFUGES_API_HTTP_ENDPOINT = "http://www.refuges.info/api/bbox"
private const val REFUGES_API_TYPE_POINTS_ALL = "all"
private const val REFUGES_API_DETAIL = "complet"
private const val REFUGES_API_FORMAT = "geojson"
private const val REFUGES_MAX_RESPONSE_BYTES = 5_000_000
private const val REFUGES_MAX_ERROR_RESPONSE_BYTES = 120_000
private const val REFUGES_CONNECT_TIMEOUT_MS = 20_000
private const val REFUGES_READ_TIMEOUT_MS = 45_000
private const val REFUGES_USER_AGENT = "GlanceMap-WearOS-RefugesInfo/1.0 https://www.refuges.info/"
private const val REFUGES_MAX_SHORT_DESCRIPTION_CHARS = 420
private const val REFUGES_ROOT_CATEGORY_ID = 1
private const val REFUGES_ROOT_CATEGORY_NAME = "root"
private const val OAM_DOWNLOAD_TELEMETRY_TAG = "OamDownload"
