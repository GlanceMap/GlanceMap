package com.glancemap.glancemapcompanionapp.refuges

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.util.Locale

data class GpxWaypointPoiImportOutcome(
    val poiResult: RefugesImportResult?,
    val hasTrackOrRoutePoints: Boolean,
    val waypointCount: Int,
)

class GpxWaypointPoiImporter(
    private val context: Context,
) {
    private companion object {
        private val NAV_ARROW_REGEX = Regex("[↰↱↲↳←→↑↓⇦⇨]")
        private val NAV_INSTRUCTION_START_REGEX =
            Regex(
                pattern = "^(turn|continue|keep|bear|head|take|follow|arrive|destination|u[- ]?turn|roundabout|at the roundabout|tournez|continuer|prenez|suivre|arrivee|abbiegen|weiter|nehmen|ziel|svolta|continua|prendi|arrivo|gire|tome|llegada)\\b",
                option = RegexOption.IGNORE_CASE,
            )
        private val NAV_ACTION_KEYWORDS =
            setOf(
                "turn",
                "continue",
                "keep",
                "bear",
                "head",
                "take",
                "follow",
                "arrive",
                "destination",
                "u-turn",
                "uturn",
                "roundabout",
                "tournez",
                "continuer",
                "prenez",
                "suivre",
                "arrivee",
                "abbiegen",
                "geradeaus",
                "kreisverkehr",
                "ausfahrt",
                "svolta",
                "continua",
                "rotatoria",
                "uscita",
                "gire",
                "continuez",
                "rotonda",
                "salida",
            )
        private val NAV_DIRECTION_KEYWORDS =
            setOf(
                "left",
                "right",
                "straight",
                "slight left",
                "slight right",
                "sharp left",
                "sharp right",
                "gauche",
                "droite",
                "tout droit",
                "links",
                "rechts",
                "geradeaus",
                "sinistra",
                "destra",
                "dritto",
                "izquierda",
                "derecha",
                "recto",
            )
        private val NAV_CONTEXT_KEYWORDS =
            setOf(
                "onto",
                "towards",
                "toward",
                "exit",
                "junction",
                "fork",
                "merge",
                "rond-point",
                "sortie",
                "track",
                "road",
                "street",
                "trail",
                "ausfahrt",
                "kreisverkehr",
                "strasse",
                "weg",
                "rotonda",
                "salida",
                "rotatoria",
                "uscita",
            )
    }

    suspend fun importWaypointsFromGpxText(
        gpxText: String,
        fileNameInput: String,
        categoryNameInput: String,
        linkedGpxFileName: String,
    ): GpxWaypointPoiImportOutcome =
        withContext(Dispatchers.IO) {
            val parsed =
                parseGpxWaypoints(
                    gpxText = gpxText,
                    categoryName = normalizeCategoryName(categoryNameInput),
                )
            if (parsed.points.isEmpty()) {
                return@withContext GpxWaypointPoiImportOutcome(
                    poiResult = null,
                    hasTrackOrRoutePoints = parsed.hasTrackOrRoutePoints,
                    waypointCount = 0,
                )
            }

            val outputDir = PhoneOfflineStorage(context).poiDirectory().apply { mkdirs() }
            val outputFile = File(outputDir, normalizeFileName(fileNameInput))
            val categoryCount =
                PoiSqliteCodec.write(
                    file = outputFile,
                    points = parsed.points,
                    options =
                        PoiSqliteWriteOptions(
                            comment = "Data source: GPX waypoints",
                            writer = "glancemap-gpx-waypoint-importer-1",
                            extraMetadata =
                                linkedMapOf(
                                    "gpx_waypoints_import" to "true",
                                    "linked_gpx_file_name" to File(linkedGpxFileName).name,
                                ),
                        ),
                )
            val uri: Uri =
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    outputFile,
                )

            GpxWaypointPoiImportOutcome(
                poiResult =
                    RefugesImportResult(
                        poiUri = uri,
                        fileName = outputFile.name,
                        pointCount = parsed.points.size,
                        categoryCount = categoryCount,
                        bbox = "",
                    ),
                hasTrackOrRoutePoints = parsed.hasTrackOrRoutePoints,
                waypointCount = parsed.points.size,
            )
        }

    private fun parseGpxWaypoints(
        gpxText: String,
        categoryName: String,
    ): ParsedGpxWaypoints {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(gpxText))

        var event = parser.eventType
        var hasTrackOrRoutePoints = false
        var inWaypoint = false
        var currentLat: Double? = null
        var currentLon: Double? = null
        var currentName: String? = null
        var currentDescription: String? = null
        var currentType: String? = null
        var currentSource: String? = null
        var currentWebsite: String? = null
        var currentElevation: String? = null

        val rawWaypoints = mutableListOf<RawGpxWaypoint>()

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.lowercase(Locale.ROOT)) {
                        "trkpt", "rtept" -> hasTrackOrRoutePoints = true
                        "wpt" -> {
                            inWaypoint = true
                            currentLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                            currentLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                            currentName = null
                            currentDescription = null
                            currentType = null
                            currentSource = null
                            currentWebsite = null
                            currentElevation = null
                        }
                        "name" -> if (inWaypoint) currentName = parser.nextText().trim().ifBlank { null }
                        "desc" -> if (inWaypoint) currentDescription = parser.nextText().trim().ifBlank { null }
                        "type" -> if (inWaypoint) currentType = parser.nextText().trim().ifBlank { null }
                        "src" -> if (inWaypoint) currentSource = parser.nextText().trim().ifBlank { null }
                        "ele" -> if (inWaypoint) currentElevation = parser.nextText().trim().ifBlank { null }
                        "link" ->
                            if (inWaypoint) {
                                val href =
                                    parser
                                        .getAttributeValue(null, "href")
                                        ?.trim()
                                        ?.takeIf { it.isNotBlank() }
                                if (href != null) currentWebsite = href
                            }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("wpt", ignoreCase = true)) {
                        inWaypoint = false
                        val lat = currentLat
                        val lon = currentLon
                        if (lat != null && lon != null && lat.isFinite() && lon.isFinite()) {
                            rawWaypoints +=
                                RawGpxWaypoint(
                                    lat = lat,
                                    lon = lon,
                                    name = currentName,
                                    description = currentDescription,
                                    type = currentType,
                                    source = currentSource,
                                    website = currentWebsite,
                                    elevation = currentElevation,
                                )
                        }
                    }
                }
            }
            event = parser.next()
        }

        val dedup = linkedMapOf<String, PoiSqlitePoint>()
        rawWaypoints.forEach { waypoint ->
            if (
                isLikelyNavigationInstruction(
                    name = waypoint.name,
                    description = waypoint.description,
                    type = waypoint.type,
                )
            ) {
                return@forEach
            }
            val point =
                buildPoiPoint(
                    lat = waypoint.lat,
                    lon = waypoint.lon,
                    categoryName = categoryName,
                    name = waypoint.name,
                    description = waypoint.description,
                    type = waypoint.type,
                    source = waypoint.source,
                    website = waypoint.website,
                    elevation = waypoint.elevation,
                )
            val key = dedupKey(point)
            dedup.putIfAbsent(key, point)
        }

        return ParsedGpxWaypoints(
            points = dedup.values.toList(),
            hasTrackOrRoutePoints = hasTrackOrRoutePoints,
        )
    }

    private fun buildPoiPoint(
        lat: Double,
        lon: Double,
        categoryName: String,
        name: String?,
        description: String?,
        type: String?,
        source: String?,
        website: String?,
        elevation: String?,
    ): PoiSqlitePoint {
        val cleanName = name?.trim().takeUnless { it.isNullOrBlank() } ?: "Waypoint"
        val cleanType = type?.trim().orEmpty()
        val cleanDescription = sanitizeDescription(description)
        val cleanSource = source?.trim().takeUnless { it.isNullOrBlank() } ?: "gpx_waypoint"
        val cleanWebsite = website?.trim().takeUnless { it.isNullOrBlank() }
        val cleanElevation =
            elevation
                ?.trim()
                ?.toDoubleOrNull()
                ?.toInt()
                ?.takeIf { it > 0 }
        val (poiKey, poiValue) =
            inferPoiTag(
                type = cleanType,
                name = cleanName,
                description = cleanDescription.orEmpty(),
            )

        val tags = linkedMapOf<String, String>()
        tags["name"] = cleanName
        tags["source"] = cleanSource
        tags[poiKey] = poiValue
        if (cleanType.isNotBlank()) {
            tags["gpx:type"] = cleanType
            tags["refuges_info:type"] = cleanType
        }
        if (!cleanDescription.isNullOrBlank()) {
            tags["description"] = cleanDescription
            tags["refuges_info:description"] = cleanDescription
        }
        if (cleanWebsite != null) {
            tags["website"] = cleanWebsite
        }
        if (cleanElevation != null) {
            tags["ele"] = cleanElevation.toString()
        }

        return PoiSqlitePoint(
            lat = lat,
            lon = lon,
            categoryName = categoryName,
            tags = tags,
        )
    }

    private fun inferPoiTag(
        type: String,
        name: String,
        description: String,
    ): Pair<String, String> {
        val haystack = "$type $name $description".lowercase(Locale.ROOT)
        return when {
            "peak" in haystack || "summit" in haystack || "sommet" in haystack -> "natural" to "peak"
            "water" in haystack || "spring" in haystack || "source" in haystack || "point d'eau" in haystack ->
                "amenity" to "drinking_water"
            "camp" in haystack || "bivouac" in haystack -> "tourism" to "camp_site"
            "hut" in haystack || "refuge" in haystack || "cabane" in haystack || "abri" in haystack || "gite" in haystack ->
                "tourism" to "alpine_hut"
            "viewpoint" in haystack || "belved" in haystack || "panorama" in haystack ->
                "tourism" to "viewpoint"
            "toilet" in haystack || "wc" in haystack -> "amenity" to "toilets"
            "parking" in haystack -> "amenity" to "parking"
            "restaurant" in haystack || "food" in haystack || "cafe" in haystack || "bar" in haystack ->
                "amenity" to "restaurant"
            else -> "tourism" to "information"
        }
    }

    private fun isLikelyNavigationInstruction(
        name: String?,
        description: String?,
        type: String?,
    ): Boolean {
        val text = normalizeInstructionText(type = type, name = name, description = description)
        if (text.isBlank()) return false
        if (NAV_ARROW_REGEX.containsMatchIn(text)) return true
        if (NAV_INSTRUCTION_START_REGEX.containsMatchIn(text)) return true

        val hasAction = NAV_ACTION_KEYWORDS.any { keyword -> text.contains(keyword) }
        val hasDirection = NAV_DIRECTION_KEYWORDS.any { keyword -> text.contains(keyword) }
        val hasContext = NAV_CONTEXT_KEYWORDS.any { keyword -> text.contains(keyword) }

        return (hasAction && (hasDirection || hasContext)) ||
            (hasDirection && hasContext && text.length <= 180)
    }

    private fun normalizeInstructionText(
        type: String?,
        name: String?,
        description: String?,
    ): String =
        listOf(type, name, description)
            .mapNotNull { value ->
                value?.trim()?.takeIf { it.isNotBlank() }
            }.joinToString(separator = " ")
            .lowercase(Locale.ROOT)
            .replace('’', '\'')
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun sanitizeDescription(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw
            .replace('\r', '\n')
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(420)
            .ifBlank { null }
    }

    private fun dedupKey(point: PoiSqlitePoint): String {
        val lat = String.format(Locale.US, "%.5f", point.lat)
        val lon = String.format(Locale.US, "%.5f", point.lon)
        val name =
            point.tags["name"]
                ?.trim()
                ?.lowercase(Locale.ROOT)
                .orEmpty()
        val primaryType =
            listOf("tourism", "amenity", "natural")
                .firstNotNullOfOrNull { key ->
                    point.tags[key]
                        ?.trim()
                        ?.lowercase(Locale.ROOT)
                        ?.let { "$key=$it" }
                }.orEmpty()
        return "$lat,$lon|$name|$primaryType"
    }

    private fun normalizeFileName(input: String): String {
        val base =
            input
                .trim()
                .ifBlank { "gpx-waypoints.poi" }
                .replace("\\", "_")
                .replace("/", "_")
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .trim('_')
                .ifBlank { "gpx-waypoints.poi" }
        return if (base.lowercase(Locale.ROOT).endsWith(".poi")) base else "$base.poi"
    }

    private fun normalizeCategoryName(input: String): String {
        val normalized =
            input
                .trim()
                .replace(Regex("\\s+"), " ")
                .ifBlank { "Waypoints" }
        return normalized.take(80)
    }

    private data class ParsedGpxWaypoints(
        val points: List<PoiSqlitePoint>,
        val hasTrackOrRoutePoints: Boolean,
    )

    private data class RawGpxWaypoint(
        val lat: Double,
        val lon: Double,
        val name: String?,
        val description: String?,
        val type: String?,
        val source: String?,
        val website: String?,
        val elevation: String?,
    )
}
