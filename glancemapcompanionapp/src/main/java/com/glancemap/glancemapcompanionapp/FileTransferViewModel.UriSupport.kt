package com.glancemap.glancemapcompanionapp

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import com.glancemap.glancemapcompanionapp.refuges.GpxWaypointPoiImporter
import com.glancemap.glancemapcompanionapp.refuges.RefugesGeoJsonPoiImporter
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.Locale

internal data class PreparedUriSelection(
    val uris: List<Uri>,
    val convertedGeoJsonCount: Int,
    val extractedPoiFromGpxCount: Int,
    val extractedPoiFromMixedGpxCount: Int,
)

internal suspend fun prepareSelectedUrisForTransfer(
    context: Context,
    uris: List<Uri>,
    refugesImporter: RefugesGeoJsonPoiImporter,
    gpxWaypointPoiImporter: GpxWaypointPoiImporter,
    maxGeoJsonImportBytes: Long,
    maxGpxWaypointImportBytes: Long,
): PreparedUriSelection {
    if (uris.isEmpty()) {
        return PreparedUriSelection(
            uris = emptyList(),
            convertedGeoJsonCount = 0,
            extractedPoiFromGpxCount = 0,
            extractedPoiFromMixedGpxCount = 0,
        )
    }

    val prepared = ArrayList<Uri>(uris.size)
    var convertedGeoJsonCount = 0
    var extractedPoiFromGpxCount = 0
    var extractedPoiFromMixedGpxCount = 0

    uris.forEach { uri ->
        if (isGeoJsonUri(context, uri)) {
            val geoJsonSize = queryUriSize(context, uri)
            if (geoJsonSize != null && geoJsonSize > maxGeoJsonImportBytes) {
                throw IllegalArgumentException(
                    "GeoJSON is too large (${geoJsonSize / (1024 * 1024)}MB). Please use a smaller file.",
                )
            }
            val geoJsonText = readGeoJsonTextWithLimit(context, uri, maxGeoJsonImportBytes)
            val poiFileName = suggestPoiFileName(context, uri)
            val result =
                refugesImporter.importFromGeoJsonText(
                    geoJsonText = geoJsonText,
                    fileNameInput = poiFileName,
                )
            prepared += result.poiUri
            convertedGeoJsonCount += 1
        } else if (isGpxUri(context, uri)) {
            val gpxTextForName =
                runCatching {
                    val gpxSize = queryUriSize(context, uri)
                    if (gpxSize != null && gpxSize > maxGpxWaypointImportBytes) {
                        return@runCatching null
                    }
                    readGpxTextWithLimit(context, uri, maxGpxWaypointImportBytes)
                }.getOrElse {
                    Log.w("FileTransferVM", "Failed to read GPX waypoints for $uri", it)
                    null
                }
            val gpxTransferFileName =
                chooseGpxTransferFileName(
                    displayName = resolveUriDisplayName(context, uri),
                    uriCandidates = uriNameCandidates(uri),
                    gpxText = gpxTextForName,
                    preferFallbackName = uri.authority.orEmpty().contains("whatsapp", ignoreCase = true),
                )
            val gpxOutcome =
                gpxTextForName?.let { gpxText ->
                    runCatching {
                        gpxWaypointPoiImporter.importWaypointsFromGpxText(
                            gpxText = gpxText,
                            fileNameInput = suggestPoiFileNameForGpxWaypoints(gpxTransferFileName),
                            categoryNameInput = suggestPoiCategoryNameForGpx(gpxTransferFileName),
                            linkedGpxFileName = gpxTransferFileName,
                        )
                    }.getOrElse {
                        Log.w("FileTransferVM", "Failed GPX waypoint extraction for $uri", it)
                        null
                    }
                }
            val gpxTransferUri =
                prepareGpxUriForTransferName(
                    context = context,
                    uri = uri,
                    preferredName = gpxTransferFileName,
                )
            val poiResult = gpxOutcome?.poiResult
            if (poiResult == null) {
                prepared += gpxTransferUri
            } else {
                prepared += gpxTransferUri
                if (gpxOutcome.hasTrackOrRoutePoints) {
                    extractedPoiFromMixedGpxCount += 1
                }
                prepared += poiResult.poiUri
                extractedPoiFromGpxCount += 1
            }
        } else {
            prepared += uri
        }
    }

    return PreparedUriSelection(
        uris = prepared.distinctBy { it.toString() },
        convertedGeoJsonCount = convertedGeoJsonCount,
        extractedPoiFromGpxCount = extractedPoiFromGpxCount,
        extractedPoiFromMixedGpxCount = extractedPoiFromMixedGpxCount,
    )
}

internal fun buildOsmEnrichTempFileName(basePoiFileName: String): String {
    val base =
        basePoiFileName
            .removeSuffix(".poi")
            .ifBlank { "refuges-info" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
            .ifBlank { "refuges-info" }
    return "${base}__osm_enrich.poi"
}

internal fun suggestPoiFileName(
    context: Context,
    uri: Uri,
): String {
    val raw = resolveUriDisplayName(context, uri)

    val safeBase =
        raw
            .ifBlank { "refuges-local" }
            .replace("\\", "_")
            .replace("/", "_")
            .replace(Regex("\\.[A-Za-z0-9]{1,8}$"), "")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
            .ifBlank { "refuges-local" }

    return "$safeBase.poi"
}

internal fun isGeoJsonUri(
    context: Context,
    uri: Uri,
): Boolean {
    val name = resolveUriDisplayName(context, uri)
    if (isGeoJsonFileName(name)) return true

    val mimeType =
        context.contentResolver
            .getType(uri)
            .orEmpty()
            .lowercase()
    return mimeType == "application/geo+json" ||
        mimeType == "application/vnd.geo+json" ||
        (mimeType == "application/json" && name.lowercase().endsWith(".json"))
}

internal fun isGeoJsonFileName(fileName: String): Boolean {
    val lower = fileName.lowercase()
    return lower.endsWith(".geojson") || lower.endsWith(".geo.json")
}

internal fun isGpxUri(
    context: Context,
    uri: Uri,
): Boolean {
    val name = resolveUriDisplayName(context, uri)
    val mimeType =
        context.contentResolver
            .getType(uri)
            .orEmpty()
            .lowercase()
    return name.lowercase().endsWith(".gpx") ||
        isGpxMimeType(mimeType) ||
        isLikelyGpxContent(context, uri)
}

internal fun isGpxMimeType(mimeType: String?): Boolean =
    when (mimeType?.trim()?.lowercase(Locale.ROOT)) {
        "application/gpx+xml",
        "application/x-gpx+xml",
        "application/vnd.gpx+xml",
        "application/gpx",
        "application/x-gpx",
        -> true
        else -> false
    }

private fun isLikelyGpxContent(
    context: Context,
    uri: Uri,
): Boolean =
    runCatching {
        val input = context.contentResolver.openInputStream(uri) ?: return@runCatching false
        input.use { stream ->
            val buffer = ByteArray(GPX_SNIFF_MAX_BYTES)
            val read = stream.read(buffer)
            if (read <= 0) return@use false
            isLikelyGpxTextPrefix(String(buffer, 0, read, Charsets.UTF_8))
        }
    }.getOrDefault(false)

internal fun isLikelyGpxTextPrefix(text: String): Boolean {
    val normalized =
        text
            .removePrefix("\uFEFF")
            .trimStart()
            .replace(Regex("""\A<\?xml[^>]*>\s*""", RegexOption.IGNORE_CASE), "")
            .trimStart()
    return Regex("""\A<\s*gpx(?:\s|>)""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
}

internal fun suggestPoiFileNameForGpxWaypoints(
    gpxFileName: String,
): String {
    val base =
        gpxFileName
            .trim()
            .replace("\\", "_")
            .replace("/", "_")
            .replace(Regex("\\.gpx$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
            .ifBlank { "gpx-waypoints" }
    return "${base}__waypoints.poi"
}

internal fun suggestPoiCategoryNameForGpx(
    gpxFileName: String,
): String =
    gpxFileName
        .trim()
        .replace(Regex("\\.gpx$", RegexOption.IGNORE_CASE), "")
        .replace('_', ' ')
        .replace('-', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { "Waypoints" }

internal fun resolveUriDisplayName(
    context: Context,
    uri: Uri,
): String {
    val displayName =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx < 0) null else cursor.getString(idx)
            }
        }.getOrNull()

    return displayName
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.trim()
            .orEmpty()
}

internal fun chooseGpxTransferFileName(
    displayName: String,
    uriCandidates: List<String>,
    gpxText: String?,
    preferFallbackName: Boolean,
): String {
    val displayFileName = displayName.toSafeFileName()
    // The document id often preserves the original file name even when a sharing provider
    // supplies a route/waypoint label as DISPLAY_NAME. It may not include the .gpx extension.
    val candidateName = uriCandidates.firstNotNullOfOrNull { it.extractGpxSourceFileName() }
    val metadataName = gpxText?.extractGpxDisplayName()?.toGpxFileName()

    val chosen =
        when {
            candidateName != null -> candidateName
            displayFileName.isNotBlank() && !displayFileName.isGenericSharedGpxName() ->
                displayFileName.ensureGpxExtension()

            preferFallbackName && metadataName != null -> metadataName
            metadataName != null -> metadataName
            displayFileName.isNotBlank() -> displayFileName.ensureGpxExtension()
            else -> "shared-route.gpx"
        }

    return chosen.toSafeFileName().ensureGpxExtension()
}

private fun prepareGpxUriForTransferName(
    context: Context,
    uri: Uri,
    preferredName: String,
): Uri {
    val currentName = resolveUriDisplayName(context, uri)
    if (!shouldCopyGpxToPreserveTransferName(currentName, preferredName)) return uri

    return runCatching {
        copyUriToTransferCache(
            context = context,
            uri = uri,
            fileName = preferredName,
        )
    }.getOrElse {
        Log.w("FileTransferVM", "Failed to preserve GPX display name for $uri", it)
        uri
    }
}

internal fun shouldCopyGpxToPreserveTransferName(
    sourceDisplayName: String,
    preferredName: String,
): Boolean = !preferredName.equals(sourceDisplayName.toSafeFileName(), ignoreCase = false)

private fun copyUriToTransferCache(
    context: Context,
    uri: Uri,
    fileName: String,
): Uri {
    val outputDir = File(context.cacheDir, "incoming-transfer").apply { mkdirs() }
    val outputFile = File(outputDir, fileName.toSafeFileName().ensureGpxExtension())
    context.contentResolver.openInputStream(uri)?.use { input ->
        outputFile.outputStream().use { output ->
            input.copyTo(output)
        }
    } ?: error("Cannot open GPX input stream.")

    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        outputFile,
    )
}

private fun uriNameCandidates(uri: Uri): List<String> {
    val candidates = ArrayList<String>()
    runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()?.let(candidates::add)
    uri.lastPathSegment?.let(candidates::add)
    candidates += uri.pathSegments.asReversed()
    return candidates
}

private fun String.extractGpxSourceFileName(): String? {
    val decoded = decodeUriNameCandidate(this).replace('\\', '/')
    val match =
        Regex("""([^/?:#]+\.gpx)(?:$|[?#])""", RegexOption.IGNORE_CASE)
            .findAll(decoded)
            .lastOrNull()
    if (match != null) return match.groupValues[1].toSafeFileName()

    // Document ids commonly look like "primary:Download/Route name". Preserve the last
    // component only for a path-like id. A bare provider id must not become the visible title.
    if ('/' !in decoded) return null
    val lastComponent =
        decoded
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('/')
            .substringAfterLast(':')
            .toSafeFileName()
    return lastComponent
        .takeIf { it.isNotBlank() && !it.isGenericSharedGpxName() && !it.isOpaqueDocumentId() }
}

private fun decodeUriNameCandidate(value: String): String =
    runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }
        .getOrDefault(value)

private fun String.extractGpxDisplayName(): String? {
    val containerRegex =
        Regex(
            pattern = """<(metadata|trk|rte)\b[^>]*>([\s\S]*?)</\1\s*>""",
            option = RegexOption.IGNORE_CASE,
        )
    val nameRegex =
        Regex(
            pattern = """<name\b[^>]*>([\s\S]*?)</name\s*>""",
            option = RegexOption.IGNORE_CASE,
        )
    val nestedPointOrSegmentRegex =
        Regex(
            pattern = """<(?:wpt|rtept|trkseg|extensions)\b""",
            option = RegexOption.IGNORE_CASE,
        )

    // A GPX metadata block can be present without a name. Do not let a loose search escape that
    // block and promote the first waypoint name (for example, "Guidepost") to the route title.
    val titleByContainer =
        containerRegex
            .findAll(this)
            .mapNotNull { container ->
                val kind = container.groupValues[1].lowercase(Locale.ROOT)
                val body = container.groupValues[2]
                val directContent =
                    body.substring(
                        0,
                        nestedPointOrSegmentRegex.find(body)?.range?.first ?: body.length,
                    )
                nameRegex.find(directContent)?.groupValues?.getOrNull(1)?.let { title ->
                    kind to title.replace(Regex("<[^>]+>"), "").trim().takeIf { it.isNotBlank() }
                }
            }.toList()

    return listOf("metadata", "trk", "rte")
        .firstNotNullOfOrNull { kind -> titleByContainer.firstOrNull { it.first == kind }?.second }
}

private fun String.toGpxFileName(): String? =
    trim()
        .replace(Regex("\\s+"), " ")
        .replace(Regex("[^A-Za-z0-9._ -]"), "_")
        .trim(' ', '.', '_', '-')
        .takeIf { it.isNotBlank() }
        ?.ensureGpxExtension()

private fun String.toSafeFileName(): String =
    trim()
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("[\\p{Cntrl}]"), "")
        .replace(Regex("[/:*?\"<>|]"), "_")
        .trim()

@Suppress("MaxLineLength")
private fun String.ensureGpxExtension(): String = if (endsWith(".gpx", ignoreCase = true)) this else "${ifBlank { "shared-route" }}.gpx"

private fun String.isGenericSharedGpxName(): Boolean {
    val base = substringBeforeLast('.').lowercase(Locale.ROOT).replace('_', '-').trim()
    return base in GENERIC_SHARED_GPX_BASENAMES || base.startsWith("document-")
}

private fun String.isOpaqueDocumentId(): Boolean =
    matches(Regex("""\d+""")) ||
        matches(Regex("""[0-9a-f]{16,}""", RegexOption.IGNORE_CASE)) ||
        matches(
            Regex(
                """[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""",
                RegexOption.IGNORE_CASE,
            ),
        )

private val GENERIC_SHARED_GPX_BASENAMES =
    setOf(
        "attachment",
        "document",
        "file",
        "gpx",
        "route",
        "shared-route",
        "track",
        "unknown",
    )

private const val GPX_SNIFF_MAX_BYTES = 8192

internal fun queryUriSize(
    context: Context,
    uri: Uri,
): Long? {
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (idx < 0 || cursor.isNull(idx)) null else cursor.getLong(idx)
        }
    }.getOrNull()
}

internal fun readGeoJsonTextWithLimit(
    context: Context,
    uri: Uri,
    maxBytes: Long,
): String =
    readTextWithLimit(
        context = context,
        uri = uri,
        maxBytes = maxBytes,
        kindLabel = "GeoJSON",
    )

internal fun readGpxTextWithLimit(
    context: Context,
    uri: Uri,
    maxBytes: Long,
): String =
    readTextWithLimit(
        context = context,
        uri = uri,
        maxBytes = maxBytes,
        kindLabel = "GPX",
    )

internal fun readTextWithLimit(
    context: Context,
    uri: Uri,
    maxBytes: Long,
    kindLabel: String,
): String {
    val safeMax = maxBytes.coerceAtLeast(1L)
    val input =
        context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot read selected $kindLabel file.")
    input.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read.toLong()
            if (total > safeMax) {
                throw IllegalArgumentException(
                    "$kindLabel is too large (${total / (1024 * 1024)}MB). Please use a smaller file.",
                )
            }
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
