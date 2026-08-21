@file:Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
    "LongParameterList",
    "LoopWithTooManyJumpStatements",
    "LargeClass",
    "MaxLineLength",
    "TooManyFunctions",
)

package com.glancemap.glancemapwearos.presentation.features.download

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.sqlite.SQLiteDatabase
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import com.glancemap.glancemapwearos.core.maps.Dem3CoverageUtils
import com.glancemap.glancemapwearos.core.maps.DemSignatureStore
import com.glancemap.glancemapwearos.core.maps.DemSource
import com.glancemap.glancemapwearos.core.routing.RoutingCoverageUtils
import com.glancemap.glancemapwearos.core.routing.routingSegmentPartFile
import com.glancemap.glancemapwearos.core.routing.routingSegmentTargetFile
import com.glancemap.glancemapwearos.core.routing.routingSegmentsDir
import com.glancemap.glancemapwearos.core.service.diagnostics.DebugTelemetry
import com.glancemap.glancemapwearos.data.repository.MapRepositoryImpl
import com.glancemap.glancemapwearos.data.repository.PoiRepository
import com.glancemap.glancemapwearos.data.repository.internal.AtomicStreamWriter
import com.glancemap.glancemapwearos.presentation.features.maps.theme.createMissingDemMarker
import com.glancemap.glancemapwearos.presentation.features.maps.theme.validateDemTileFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.mapsforge.map.reader.MapFile
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.Collections
import java.util.Locale
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

private const val OAM_REMOTE_BROUTER_SEGMENTS_BASE_URL = "https://brouter.de/brouter/segments4"

data class OamDownloadProgress(
    val phase: String,
    val detail: String,
    val bytesDone: Long = 0L,
    val totalBytes: Long? = null,
)

private data class RoutingSegmentDownloadResult(
    val fileName: String,
    val downloaded: Boolean,
    val available: Boolean = true,
)

private data class DemTileDownloadResult(
    val tileId: String,
    val stored: Boolean,
    val downloaded: Boolean = false,
    val available: Boolean = true,
    val bytesStored: Long = 0L,
)

internal data class RemoteFileRequest(
    val url: String,
    val fileName: String,
)

internal fun buildRemoteFileRequestsForBundle(
    area: OamDownloadArea,
    bundle: OamInstalledBundle,
    demRemoteFileRequest: (String) -> RemoteFileRequest,
    shouldSkipKnownMissingDemTile: (String) -> Boolean = { false },
): List<RemoteFileRequest> =
    buildList {
        if (bundle.mapFileName != null) {
            add(
                RemoteFileRequest(
                    url = area.mapZipUrl,
                    fileName = oamRemoteFileName(area.mapZipUrl),
                ),
            )
        }
        if (bundle.poiFileName != null) {
            add(
                RemoteFileRequest(
                    url = area.poiZipUrl,
                    fileName = oamRemoteFileName(area.poiZipUrl),
                ),
            )
        }
        bundle.routingFileNames.forEach { fileName ->
            val safeName = File(fileName).name
            add(
                RemoteFileRequest(
                    url = "$OAM_REMOTE_BROUTER_SEGMENTS_BASE_URL/$safeName",
                    fileName = safeName,
                ),
            )
        }
        bundle.demTileIds
            .filterNot(shouldSkipKnownMissingDemTile)
            .forEach { tileId ->
                add(demRemoteFileRequest(tileId))
            }
    }

class OamBundleDownloader(
    private val context: Context,
    private val mapRepository: MapRepositoryImpl,
    private val poiRepository: PoiRepository,
    private val bundleStore: OamBundleStore = OamBundleStore(context),
) {
    private val downloadDir: File by lazy { context.getDir("oam_downloads", Context.MODE_PRIVATE) }
    private val activeConnections = Collections.synchronizedSet(mutableSetOf<HttpURLConnection>())
    private val refugesInfoImporter by lazy {
        RefugesInfoPoiImporter(
            context = context,
            poiRepository = poiRepository,
            activeConnections = activeConnections,
        )
    }

    suspend fun installedBundles(): List<OamInstalledBundle> = bundleStore.listInstalledBundles()

    suspend fun checkInstalledBundleHealth(
        bundle: OamInstalledBundle,
        thorough: Boolean = false,
    ): OamBundleLocalHealth =
        withContext(Dispatchers.IO) {
            val area = OamDownloadCatalog.areas.firstOrNull { it.id == bundle.areaId }
            val repairFileNames = mutableListOf<String>()
            val mapFiles = mapRepository.listMapFiles()
            val poiFiles = poiRepository.listPoiFiles()

            bundle.mapFileName?.let { fileName ->
                val file = mapFiles.firstOrNull { it.name.equals(fileName, ignoreCase = true) }
                val healthy =
                    file.isHealthyLocalFile() &&
                        (!thorough || runCatching { validateMapFile(file!!) }.isSuccess)
                if (!healthy) {
                    repairFileNames += area?.let { oamRemoteFileName(it.mapZipUrl) } ?: File(fileName).name
                }
            }
            bundle.poiFileName?.let { fileName ->
                val file = poiFiles.firstOrNull { it.name.equals(fileName, ignoreCase = true) }
                val healthy =
                    file.isHealthyLocalFile() &&
                        (!thorough || runCatching { validatePoiFile(file!!) }.isSuccess)
                if (!healthy) {
                    repairFileNames += area?.let { oamRemoteFileName(it.poiZipUrl) } ?: File(fileName).name
                }
            }
            bundle.refugesInfoFileName?.let { fileName ->
                val file = poiFiles.firstOrNull { it.name.equals(fileName, ignoreCase = true) }
                val healthy =
                    file.isHealthyLocalFile() &&
                        (!thorough || runCatching { validatePoiFile(file!!) }.isSuccess)
                if (!healthy) repairFileNames += File(fileName).name
            }
            bundle.routingFileNames.forEach { fileName ->
                val file = routingSegmentTargetFile(context, fileName)
                val healthy =
                    file.isHealthyLocalFile() &&
                        (!thorough || runCatching { validateRoutingSegmentIndex(file) }.isSuccess)
                if (!healthy) repairFileNames += File(fileName).name
            }
            bundle.demTileIds.forEach { tileId ->
                if (isKnownMissingDemTile(tileId, bundle.demSource)) return@forEach
                val file = demTileTargetFile(tileId, bundle.demSource)
                val healthy =
                    file.isHealthyLocalFile() &&
                        (
                            !thorough ||
                                runCatching {
                                    if (file.name.endsWith(".gz", ignoreCase = true)) {
                                        validateGzipDemStructure(file)
                                    } else {
                                        validateDemTileFile(file)
                                    }
                                }.isSuccess
                        )
                if (!healthy) repairFileNames += bundle.demSource.remoteFileName(tileId)
            }

            OamBundleLocalHealth(repairFileNames.distinct()).also { health ->
                if (health.needsRepair) {
                    DebugTelemetry.log(
                        OAM_DOWNLOAD_TELEMETRY_TAG,
                        "event=local_integrity_failed area=${bundle.areaId} thorough=$thorough " +
                            "files=${health.repairFileNames.joinToString(limit = 5)}",
                    )
                }
            }
        }

    suspend fun checkBundleUpdates(bundle: OamInstalledBundle): OamBundleUpdateCheck =
        withContext(Dispatchers.IO) {
            val localHealth = checkInstalledBundleHealth(bundle = bundle, thorough = true)
            val area =
                OamDownloadCatalog.areas.firstOrNull { it.id == bundle.areaId }
                    ?: return@withContext OamBundleUpdateCheck(
                        bundle = bundle,
                        status = OamBundleUpdateStatus.UNKNOWN,
                        checkedFileCount = 0,
                        unknownFileNames = listOf(bundle.areaLabel),
                    )
            val requests = remoteFileRequestsForBundle(area = area, bundle = bundle)
            val previousByUrl = bundle.remoteFiles.associateBy { it.url }
            val changedFileNames = mutableListOf<String>()
            val unknownFileNames = mutableListOf<String>()
            var checkedFileCount = 0

            requests.forEach { request ->
                coroutineContext.ensureActive()
                val previous = previousByUrl[request.url]
                if (previous == null || !previous.isComparable()) {
                    unknownFileNames += request.fileName
                    return@forEach
                }
                val current =
                    runCatching { fetchRemoteMetadata(request) }
                        .getOrNull()
                if (current == null || !current.isComparable()) {
                    unknownFileNames += request.fileName
                    return@forEach
                }
                checkedFileCount += 1
                when (previous.compareWith(current)) {
                    RemoteMetadataComparison.CHANGED -> {
                        changedFileNames += request.fileName
                        DebugTelemetry.log(
                            OAM_DOWNLOAD_TELEMETRY_TAG,
                            "event=remote_metadata_changed file=${request.fileName} " +
                                "previous=${previous.telemetrySummary()} current=${current.telemetrySummary()}",
                        )
                    }
                    RemoteMetadataComparison.UNKNOWN -> unknownFileNames += request.fileName
                    RemoteMetadataComparison.SAME -> Unit
                }
            }

            val distinctChangedFileNames = changedFileNames.distinct()
            val distinctUnknownFileNames = unknownFileNames.distinct()
            val status =
                when {
                    localHealth.needsRepair -> OamBundleUpdateStatus.REPAIR_NEEDED
                    distinctChangedFileNames.isNotEmpty() -> OamBundleUpdateStatus.UPDATE_AVAILABLE
                    requests.isEmpty() -> OamBundleUpdateStatus.UP_TO_DATE
                    distinctUnknownFileNames.isNotEmpty() || checkedFileCount == 0 -> OamBundleUpdateStatus.UNKNOWN
                    else -> OamBundleUpdateStatus.UP_TO_DATE
                }
            DebugTelemetry.log(
                OAM_DOWNLOAD_TELEMETRY_TAG,
                "event=update_check_result area=${bundle.areaId} status=$status checked=$checkedFileCount " +
                    "repair=${localHealth.repairFileNames.joinToString(limit = 5)} " +
                    "changed=${distinctChangedFileNames.joinToString(limit = 5)} " +
                    "unknown=${distinctUnknownFileNames.joinToString(limit = 5)}",
            )
            OamBundleUpdateCheck(
                bundle = bundle,
                status = status,
                checkedFileCount = checkedFileCount,
                changedFileNames = distinctChangedFileNames,
                repairFileNames = localHealth.repairFileNames,
                unknownFileNames = distinctUnknownFileNames,
            )
        }

    fun abortActiveDownloads(reason: String = "manual") {
        val connections = synchronized(activeConnections) { activeConnections.toList() }
        DebugTelemetry.log(
            OAM_DOWNLOAD_TELEMETRY_TAG,
            "event=abort_active_downloads reason=$reason activeConnections=${connections.size}",
        )
        connections.forEach { connection ->
            runCatching { connection.disconnect() }
        }
    }

    suspend fun deletePartialDownloads(
        areas: List<OamDownloadArea>,
        selection: OamDownloadSelection,
    ) {
        withContext(Dispatchers.IO) {
            val installedBundles = bundleStore.listInstalledBundles()
            areas.forEach { area ->
                if (selection.includeMap) {
                    deleteZipAndPartial("${area.id}.map.zip")
                }
                if (selection.includePoi) {
                    deleteZipAndPartial("${area.id}.poi.zip")
                }
                if (selection.includeRefugesInfo) {
                    val fileName = refugesInfoFileNameForArea(area)
                    File(context.cacheDir, ".$fileName.tmp").delete()
                    File(context.cacheDir, fileName).delete()
                }
                if (selection.includeRouting) {
                    routingSegmentNamesForArea(
                        area = area,
                        installedBundles = installedBundles,
                    ).forEach { fileName ->
                        routingSegmentPartFile(context, fileName).delete()
                    }
                }
                if (selection.includeDem) {
                    runCatching {
                        demTileIdsForArea(
                            area = area,
                            installedBundles = installedBundles,
                        )
                    }.getOrDefault(emptySet())
                        .forEach { tileId -> deleteDemPartial(tileId, selection.demSource) }
                }
            }
        }
    }

    suspend fun downloadBundle(
        area: OamDownloadArea,
        selection: OamDownloadSelection,
        forceMapAndPoi: Boolean = false,
        forceMap: Boolean = forceMapAndPoi,
        forcePoi: Boolean = forceMapAndPoi,
        forceRefugesInfo: Boolean = forceMap,
        forceRoutingSegments: Boolean = false,
        forceRoutingFileNames: Set<String> = emptySet(),
        forceDemTiles: Boolean = false,
        forceDemTileIds: Set<String> = emptySet(),
        extractionKeepAliveState: () -> OamDownloadKeepAliveState = { OamDownloadKeepAliveState() },
        onProgress: (OamDownloadProgress) -> Unit,
    ): OamInstalledBundle =
        coroutineScope {
            require(selection.canDownload) { "Select at least one download item." }

            val bundleStartedAtMs = System.currentTimeMillis()
            DebugTelemetry.log(
                OAM_DOWNLOAD_TELEMETRY_TAG,
                "event=bundle_start area=${area.id} map=${selection.includeMap} poi=${selection.includePoi} " +
                    "routing=${selection.includeRouting} dem=${selection.includeDem} " +
                    "demSource=${selection.demSource.id} refuges=${selection.includeRefugesInfo}",
            )
            val progressArbiter = OamBundleProgressArbiter(onProgress)
            val extractionLane = Semaphore(1)
            val existingBundle = bundleStore.listInstalledBundles().firstOrNull { it.areaId == area.id }
            val remoteFilesByUrl =
                existingBundle
                    ?.remoteFiles
                    .orEmpty()
                    .associateBy { it.url }
                    .toMutableMap()
            var mapFileName: String? = existingBundle?.mapFileName
            var mapArchive: File? = null
            var mapExtraction: kotlinx.coroutines.Deferred<String>? = null
            if (selection.includeMap) {
                if (forceMap) deleteZipAndPartial("${area.id}.map.zip")
                val existingMapFile =
                    if (forceMap) {
                        null
                    } else {
                        existingMapFileForArea(area = area, knownFileName = existingBundle?.mapFileName)
                    }
                val archiveName = "${area.id}.map.zip"
                val completedArchiveAvailable = File(downloadDir, archiveName).isHealthyLocalFile()
                val mapMetadata =
                    if (
                        shouldFetchRemoteMetadataBeforeDownload(
                            localFileAvailable = existingMapFile != null,
                            completedArchiveAvailable = completedArchiveAvailable,
                            forceDownload = forceMap,
                        )
                    ) {
                        fetchRemoteMetadataOrNull(
                            RemoteFileRequest(
                                url = area.mapZipUrl,
                                fileName = remoteFileName(area.mapZipUrl),
                            ),
                        )
                    } else {
                        null
                    }
                mapMetadata?.let { remoteFilesByUrl[it.url] = it }
                if (existingMapFile != null) {
                    mapFileName = existingMapFile.name
                    reportExistingFile(
                        label = "Map",
                        file = existingMapFile,
                        onProgress = progressArbiter::emitForeground,
                    )
                    deleteZipAndPartial("${area.id}.map.zip")
                } else {
                    mapArchive =
                        reusableBundleArchiveOrNull(
                            directory = downloadDir,
                            fileName = archiveName,
                            entryExtension = ".map",
                            expectedSize = mapMetadata?.contentLengthBytes,
                        )?.also { archive ->
                            reportReusableArchive("Map", archive, progressArbiter::emitForeground)
                        } ?: progressArbiter.runNetwork { networkProgress ->
                            downloadFile(
                                url = area.mapZipUrl,
                                dir = downloadDir,
                                fileName = archiveName,
                                label = "Map",
                                progressDetail = "Map zip",
                                bufferSize = OAM_ZIP_DOWNLOAD_BUFFER_SIZE,
                                progressStepBytes = 2L * 1024 * 1024,
                                fsync = false,
                                onResponseMetadata = { metadata -> remoteFilesByUrl[metadata.url] = metadata },
                                onProgress = networkProgress,
                            )
                        }
                    val archive = checkNotNull(mapArchive)
                    mapExtraction =
                        async(Dispatchers.IO) {
                            try {
                                extractionLane.withPermit {
                                    progressArbiter.runExtraction { extractionProgress ->
                                        extractFirstEntry(
                                            zipFile = archive,
                                            extension = ".map",
                                            label = "Map",
                                            extractionKeepAliveState = extractionKeepAliveState,
                                            onProgress = extractionProgress,
                                        ) { fileName, input, expectedSize, progress ->
                                            mapRepository.saveMapFileAtomic(
                                                fileName = fileName,
                                                inputStream = input,
                                                expectedSize = expectedSize,
                                                resumeOffset = 0L,
                                                computeSha256 = false,
                                                onProgress = progress,
                                            )
                                        }
                                    }
                                }
                            } catch (error: ZipException) {
                                archive.delete()
                                throw error
                            }
                        }
                }
            }

            var poiFileName: String? = existingBundle?.poiFileName
            var poiArchive: File? = null
            var poiExtraction: kotlinx.coroutines.Deferred<String>? = null
            if (selection.includePoi) {
                if (forcePoi) deleteZipAndPartial("${area.id}.poi.zip")
                val existingPoiFile =
                    if (forcePoi) {
                        null
                    } else {
                        existingPoiFileForArea(area = area, knownFileName = existingBundle?.poiFileName)
                    }
                val archiveName = "${area.id}.poi.zip"
                val completedArchiveAvailable = File(downloadDir, archiveName).isHealthyLocalFile()
                val poiMetadata =
                    if (
                        shouldFetchRemoteMetadataBeforeDownload(
                            localFileAvailable = existingPoiFile != null,
                            completedArchiveAvailable = completedArchiveAvailable,
                            forceDownload = forcePoi,
                        )
                    ) {
                        fetchRemoteMetadataOrNull(
                            RemoteFileRequest(
                                url = area.poiZipUrl,
                                fileName = remoteFileName(area.poiZipUrl),
                            ),
                        )
                    } else {
                        null
                    }
                poiMetadata?.let { remoteFilesByUrl[it.url] = it }
                if (existingPoiFile != null) {
                    poiFileName = existingPoiFile.name
                    reportExistingFile(
                        label = "POI",
                        file = existingPoiFile,
                        onProgress = progressArbiter::emitForeground,
                    )
                    deleteZipAndPartial("${area.id}.poi.zip")
                } else {
                    poiArchive =
                        reusableBundleArchiveOrNull(
                            directory = downloadDir,
                            fileName = archiveName,
                            entryExtension = ".poi",
                            expectedSize = poiMetadata?.contentLengthBytes,
                        )?.also { archive ->
                            reportReusableArchive("POI", archive, progressArbiter::emitForeground)
                        } ?: progressArbiter.runNetwork { networkProgress ->
                            downloadFile(
                                url = area.poiZipUrl,
                                dir = downloadDir,
                                fileName = archiveName,
                                label = "POI",
                                progressDetail = "POI zip",
                                bufferSize = OAM_ZIP_DOWNLOAD_BUFFER_SIZE,
                                progressStepBytes = 2L * 1024 * 1024,
                                fsync = false,
                                onResponseMetadata = { metadata -> remoteFilesByUrl[metadata.url] = metadata },
                                onProgress = networkProgress,
                            )
                        }
                    val archive = checkNotNull(poiArchive)
                    poiExtraction =
                        async(Dispatchers.IO) {
                            try {
                                extractionLane.withPermit {
                                    progressArbiter.runExtraction { extractionProgress ->
                                        extractFirstEntry(
                                            zipFile = archive,
                                            extension = ".poi",
                                            label = "POI",
                                            extractionKeepAliveState = extractionKeepAliveState,
                                            onProgress = extractionProgress,
                                        ) { fileName, input, expectedSize, progress ->
                                            poiRepository.savePoiFileAtomic(
                                                fileName = fileName,
                                                inputStream = input,
                                                expectedSize = expectedSize,
                                                resumeOffset = 0L,
                                                onProgress = progress,
                                            )
                                        }
                                    }
                                }
                            } catch (error: ZipException) {
                                archive.delete()
                                throw error
                            }
                        }
                }
            }

            mapExtraction?.let { extraction ->
                mapFileName = extraction.await()
                mapArchive?.delete()
            }
            if (selection.includeMap) {
                upsertPartialBundle(
                    area = area,
                    selection = selection,
                    existingBundle = existingBundle,
                    mapFileName = mapFileName,
                    poiFileName = existingBundle?.poiFileName,
                    refugesInfoFileName = existingBundle?.refugesInfoFileName,
                    remoteFiles = remoteFilesByUrl.values,
                )
            }

            var downloadedRoutingFileNames = existingBundle?.downloadedRoutingFileNames.orEmpty()
            val routingFileNames =
                if (selection.includeRouting) {
                    val requiredSegments = routingSegmentNamesForArea(area = area, mapFileName = mapFileName)
                    val segmentResults =
                        requiredSegments.map { fileName ->
                            val segmentUrl = "$BROUTER_SEGMENTS_BASE_URL/$fileName"
                            val safeName = File(fileName).name
                            val forceDownload = forceRoutingSegments || safeName in forceRoutingFileNames
                            val localFileAvailable =
                                routingSegmentTargetFile(context, safeName).isHealthyLocalFile()
                            if (
                                shouldFetchRemoteMetadataBeforeDownload(
                                    localFileAvailable = localFileAvailable,
                                    forceDownload = forceDownload,
                                )
                            ) {
                                fetchRemoteMetadataOrNull(
                                    RemoteFileRequest(
                                        url = segmentUrl,
                                        fileName = safeName,
                                    ),
                                )?.let { remoteFilesByUrl[it.url] = it }
                            }
                            progressArbiter.runNetwork { networkProgress ->
                                downloadRoutingSegment(
                                    fileName = safeName,
                                    forceDownload = forceDownload,
                                    onResponseMetadata = { metadata -> remoteFilesByUrl[metadata.url] = metadata },
                                    onProgress = networkProgress,
                                )
                            }
                        }
                    val resultFileNames = segmentResults.filter { it.available }.map { it.fileName }
                    downloadedRoutingFileNames =
                        (downloadedRoutingFileNames + segmentResults.filter { it.downloaded }.map { it.fileName })
                            .distinct()
                            .filter { it in resultFileNames }
                    resultFileNames
                } else {
                    existingBundle?.routingFileNames.orEmpty()
                }

            var downloadedDemTileIds = existingBundle?.downloadedDemTileIds.orEmpty()
            val demTileIds =
                if (selection.includeDem) {
                    val requiredTiles = demTileIdsForArea(area = area, mapFileName = mapFileName)
                    val isLargeDetailedDownload =
                        selection.demSource == DemSource.MAPZEN_SKADI_1S &&
                            requiredTiles.size >= LARGE_DETAILED_DEM_TILE_THRESHOLD
                    val demStartedAtMs = System.currentTimeMillis()
                    DebugTelemetry.log(
                        OAM_DOWNLOAD_TELEMETRY_TAG,
                        "event=dem_plan area=${area.id} source=${selection.demSource.id} " +
                            "tiles=${requiredTiles.size} large=$isLargeDetailedDownload",
                    )
                    progressArbiter.emitForeground(
                        OamDownloadProgress(
                            phase = "PREPARING",
                            detail =
                                if (isLargeDetailedDownload) {
                                    "Large detailed DEM · ${requiredTiles.size} tiles"
                                } else {
                                    "${selection.demSource.shortLabel} DEM · ${requiredTiles.size} tiles"
                                },
                        ),
                    )
                    val tileResults =
                        requiredTiles.mapIndexed { tileIndex, tileId ->
                            val safeTileId = tileId.uppercase(Locale.ROOT)
                            val forceDownload = forceDemTiles || safeTileId in forceDemTileIds
                            val targetFile = demTileTargetFile(safeTileId, selection.demSource)
                            val localFileAvailable = isDemTileStored(safeTileId, targetFile)
                            val tileRequest = demRemoteFileRequest(tileId, selection.demSource)
                            if (
                                shouldFetchRemoteMetadataBeforeDownload(
                                    localFileAvailable = localFileAvailable,
                                    forceDownload = forceDownload,
                                )
                            ) {
                                fetchRemoteMetadataOrNull(tileRequest)?.let { remoteFilesByUrl[it.url] = it }
                            }
                            progressArbiter.runNetwork { networkProgress ->
                                downloadDemTile(
                                    tileId = safeTileId,
                                    source = selection.demSource,
                                    forceDownload = forceDownload,
                                    onResponseMetadata = { metadata -> remoteFilesByUrl[metadata.url] = metadata },
                                    onProgress = { progress ->
                                        networkProgress(
                                            progress.withDemBatchContext(
                                                tileIndex = tileIndex,
                                                tileCount = requiredTiles.size,
                                                tileId = safeTileId,
                                                sourceLabel = selection.demSource.shortLabel,
                                                isLargeDetailedDownload = isLargeDetailedDownload,
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    DebugTelemetry.log(
                        OAM_DOWNLOAD_TELEMETRY_TAG,
                        "event=dem_complete area=${area.id} source=${selection.demSource.id} " +
                            "tiles=${requiredTiles.size} downloaded=${tileResults.count { it.downloaded }} " +
                            "ready=${tileResults.count { it.available }} " +
                            "unavailable=${tileResults.count { !it.available }} " +
                            "bytes=${tileResults.sumOf { it.bytesStored }} " +
                            "durationMs=${System.currentTimeMillis() - demStartedAtMs}",
                    )
                    downloadedDemTileIds =
                        (downloadedDemTileIds + tileResults.filter { it.stored }.map { it.tileId })
                            .distinct()
                            .filter { it in requiredTiles }
                    requiredTiles
                } else {
                    existingBundle?.demTileIds.orEmpty()
                }

            poiExtraction?.let { extraction ->
                poiFileName = extraction.await()
                poiArchive?.delete()
            }

            var refugesInfoFileName: String? = existingBundle?.refugesInfoFileName
            if (selection.includeRefugesInfo) {
                val existingRefugesInfoFile =
                    if (forceRefugesInfo) {
                        null
                    } else {
                        existingRefugesInfoFileForArea(
                            area = area,
                            knownFileName = existingBundle?.refugesInfoFileName,
                        )
                    }
                if (existingRefugesInfoFile != null) {
                    refugesInfoFileName = existingRefugesInfoFile.name
                    reportExistingFile(
                        label = "Refuges.info",
                        file = existingRefugesInfoFile,
                        onProgress = progressArbiter::emitForeground,
                    )
                } else {
                    val mapFile =
                        mapFileForArea(area = area, mapFileName = mapFileName)
                            ?: throw IOException(
                                "Refuges.info needs the map in this bundle or an installed map for ${area.region}.",
                            )
                    refugesInfoFileName =
                        refugesInfoImporter.importForMap(
                            mapFile = mapFile,
                            fileName = refugesInfoFileNameForArea(area),
                            areaLabel = area.region,
                            onProgress = progressArbiter::emitForeground,
                        )
                }
            }

            val installed =
                OamInstalledBundle(
                    areaId = area.id,
                    areaLabel = area.region,
                    bundleChoice = selection.toBundleChoice(),
                    mapFileName = mapFileName,
                    poiFileName = poiFileName,
                    refugesInfoFileName = refugesInfoFileName,
                    routingFileNames = routingFileNames,
                    downloadedRoutingFileNames = downloadedRoutingFileNames,
                    demSource =
                        if (selection.includeDem) {
                            selection.demSource
                        } else {
                            existingBundle?.demSource ?: selection.demSource
                        },
                    demTileIds = demTileIds,
                    downloadedDemTileIds = downloadedDemTileIds,
                    installedAtMillis = System.currentTimeMillis(),
                    remoteFiles = remoteFilesByUrl.values.sortedBy { it.url },
                )
            bundleStore.upsert(installed)
            if (selection.includeRouting) {
                RoutingCoverageUtils.clearCaches()
            }
            if (selection.includeDem) {
                DemSignatureStore.markDirty(context)
                Dem3CoverageUtils.clearCaches()
            }
            DebugTelemetry.log(
                OAM_DOWNLOAD_TELEMETRY_TAG,
                "event=bundle_complete area=${area.id} durationMs=${System.currentTimeMillis() - bundleStartedAtMs}",
            )
            installed
        }

    suspend fun deleteBundle(bundle: OamInstalledBundle) {
        withContext(Dispatchers.IO) {
            val routingFilesUsedByOtherBundles =
                bundleStore
                    .listInstalledBundles()
                    .asSequence()
                    .filterNot { it.areaId == bundle.areaId }
                    .flatMap { it.routingFileNames.asSequence() }
                    .toSet()
            val demTilesUsedByOtherBundles =
                bundleStore
                    .listInstalledBundles()
                    .asSequence()
                    .filterNot { it.areaId == bundle.areaId }
                    .filter { it.demSource == bundle.demSource }
                    .flatMap { it.demTileIds.asSequence() }
                    .toSet()
            bundle.mapFileName?.let { fileName ->
                mapRepository
                    .listMapFiles()
                    .firstOrNull { it.name == fileName }
                    ?.let { mapRepository.deleteMapFile(it.absolutePath) }
            }
            bundle.poiFileName?.let { fileName ->
                poiRepository
                    .listPoiFiles()
                    .firstOrNull { it.name == fileName }
                    ?.let { poiRepository.deletePoiFile(it.absolutePath) }
            }
            bundle.refugesInfoFileName?.let { fileName ->
                poiRepository
                    .listPoiFiles()
                    .firstOrNull { it.name == fileName }
                    ?.let { poiRepository.deletePoiFile(it.absolutePath) }
            }
            deleteZipAndPartial("${bundle.areaId}.map.zip")
            deleteZipAndPartial("${bundle.areaId}.poi.zip")
            bundle.downloadedRoutingFileNames
                .filterNot { it in routingFilesUsedByOtherBundles }
                .forEach { fileName ->
                    routingSegmentTargetFile(context, fileName).delete()
                    routingSegmentPartFile(context, fileName).delete()
                }
            RoutingCoverageUtils.clearCaches()
            val remainingMapDemTiles =
                mapRepository
                    .listMapFiles()
                    .flatMap { file -> Dem3CoverageUtils.requiredTileIdsForMap(file).orEmpty() }
                    .toSet()
            Dem3CoverageUtils.deleteTiles(
                context = context,
                tileIds =
                    bundle.downloadedDemTileIds
                        .filterNot { it in demTilesUsedByOtherBundles || it in remainingMapDemTiles }
                        .toSet(),
                sources = listOf(bundle.demSource),
            )
            Dem3CoverageUtils.clearCaches()
        }
        bundleStore.remove(bundle.areaId)
    }

    private fun deleteZipAndPartial(fileName: String) {
        val safeName = File(fileName).name
        File(downloadDir, safeName).delete()
        File(downloadDir, ".$safeName.part").delete()
    }

    private suspend fun routingSegmentNamesForArea(
        area: OamDownloadArea,
        installedBundles: List<OamInstalledBundle>,
    ): Set<String> =
        routingSegmentNamesForArea(
            area = area,
            mapFileName =
                installedBundles
                    .firstOrNull { it.areaId == area.id }
                    ?.mapFileName,
        ).toSet()

    private suspend fun routingSegmentNamesForArea(
        area: OamDownloadArea,
        mapFileName: String?,
    ): List<String> {
        val mapFile =
            mapFileForArea(area = area, mapFileName = mapFileName)
                ?: throw IOException("Routing needs the map in this bundle or an installed map for ${area.region}.")
        return RoutingCoverageUtils
            .requiredSegmentNamesForMapFile(mapFile)
            ?.sorted()
            ?: throw IOException("Cannot read map bounds for routing.")
    }

    private suspend fun demTileIdsForArea(
        area: OamDownloadArea,
        installedBundles: List<OamInstalledBundle>,
    ): Set<String> =
        demTileIdsForArea(
            area = area,
            mapFileName =
                installedBundles
                    .firstOrNull { it.areaId == area.id }
                    ?.mapFileName,
        ).toSet()

    private suspend fun demTileIdsForArea(
        area: OamDownloadArea,
        mapFileName: String?,
    ): List<String> {
        val mapFile =
            mapFileForArea(area = area, mapFileName = mapFileName)
                ?: throw IOException("DEM needs the map in this bundle or an installed map for ${area.region}.")
        return Dem3CoverageUtils
            .requiredTileIdsForMap(mapFile)
            ?.sorted()
            ?: throw IOException("Cannot read map bounds for DEM.")
    }

    private suspend fun mapFileForArea(
        area: OamDownloadArea,
        mapFileName: String?,
    ): File? {
        val candidateNames =
            listOfNotNull(
                mapFileName,
                "${area.region}.map",
            )
        return mapRepository
            .listMapFiles()
            .firstMatchingFileName(candidateNames)
    }

    private suspend fun existingMapFileForArea(
        area: OamDownloadArea,
        knownFileName: String?,
    ): File? =
        mapRepository
            .listMapFiles()
            .firstMatchingFileName(
                listOf(
                    knownFileName,
                    "${area.region}.map",
                ),
            )

    private suspend fun existingPoiFileForArea(
        area: OamDownloadArea,
        knownFileName: String?,
    ): File? =
        poiRepository
            .listPoiFiles()
            .firstMatchingFileName(
                listOf(
                    knownFileName,
                    "${area.region}.poi",
                ),
            )

    private suspend fun existingRefugesInfoFileForArea(
        area: OamDownloadArea,
        knownFileName: String?,
    ): File? =
        poiRepository
            .listPoiFiles()
            .firstMatchingFileName(
                listOf(
                    knownFileName,
                    refugesInfoFileNameForArea(area),
                ),
            )

    private fun refugesInfoFileNameForArea(area: OamDownloadArea): String = "${area.region}.refuges-info.poi"

    private fun List<File>.firstMatchingFileName(candidateNames: Iterable<String?>): File? =
        firstOrNull { file ->
            candidateNames
                .filterNotNull()
                .any { candidate -> candidate.matchesOamFileName(file.name) }
        }

    private fun String.matchesOamFileName(fileName: String): Boolean =
        equals(fileName, ignoreCase = true) ||
            normalizedOamFileStem() == fileName.normalizedOamFileStem()

    private fun String.normalizedOamFileStem(): String =
        substringBeforeLast('.')
            .filter(Char::isLetterOrDigit)
            .lowercase(Locale.ROOT)

    private suspend fun upsertPartialBundle(
        area: OamDownloadArea,
        selection: OamDownloadSelection,
        existingBundle: OamInstalledBundle?,
        mapFileName: String?,
        poiFileName: String?,
        refugesInfoFileName: String?,
        remoteFiles: Collection<OamRemoteFileMetadata>,
    ) {
        bundleStore.upsert(
            OamInstalledBundle(
                areaId = area.id,
                areaLabel = area.region,
                bundleChoice = selection.toBundleChoice(),
                mapFileName = mapFileName,
                poiFileName = poiFileName,
                refugesInfoFileName = refugesInfoFileName,
                routingFileNames = existingBundle?.routingFileNames.orEmpty(),
                downloadedRoutingFileNames = existingBundle?.downloadedRoutingFileNames.orEmpty(),
                demSource = existingBundle?.demSource ?: selection.demSource,
                demTileIds = existingBundle?.demTileIds.orEmpty(),
                downloadedDemTileIds = existingBundle?.downloadedDemTileIds.orEmpty(),
                installedAtMillis =
                    existingBundle?.installedAtMillis?.takeIf { it > 0L }
                        ?: System.currentTimeMillis(),
                remoteFiles = remoteFiles.sortedBy { it.url },
            ),
        )
    }

    private fun reportExistingFile(
        label: String,
        file: File,
        onProgress: (OamDownloadProgress) -> Unit,
    ) {
        DebugTelemetry.log(
            OAM_DOWNLOAD_TELEMETRY_TAG,
            "event=reuse_existing_file label=$label file=${file.name} bytes=${file.length().coerceAtLeast(0L)}",
        )
        onProgress(
            OamDownloadProgress(
                phase = "READY",
                detail = "${file.name} already on watch",
                bytesDone = file.length().coerceAtLeast(0L),
                totalBytes = file.length().takeIf { it > 0L },
            ),
        )
    }

    private fun reportReusableArchive(
        label: String,
        archive: File,
        onProgress: (OamDownloadProgress) -> Unit,
    ) {
        DebugTelemetry.log(
            OAM_DOWNLOAD_TELEMETRY_TAG,
            "event=reuse_completed_archive label=$label file=${archive.name} bytes=${archive.length().coerceAtLeast(0L)}",
        )
        onProgress(
            OamDownloadProgress(
                phase = "READY",
                detail = "$label archive ready",
                bytesDone = archive.length().coerceAtLeast(0L),
                totalBytes = archive.length().takeIf { it > 0L },
            ),
        )
    }

    private suspend fun downloadRoutingSegment(
        fileName: String,
        forceDownload: Boolean,
        onResponseMetadata: (OamRemoteFileMetadata) -> Unit,
        onProgress: (OamDownloadProgress) -> Unit,
    ): RoutingSegmentDownloadResult {
        val safeName = File(fileName).name
        val targetFile = routingSegmentTargetFile(context, safeName)
        if (forceDownload) {
            routingSegmentPartFile(context, safeName).delete()
        }
        if (!forceDownload && targetFile.exists() && targetFile.length() > 0L) {
            onProgress(
                OamDownloadProgress(
                    phase = "READY",
                    detail = safeName,
                    bytesDone = targetFile.length(),
                    totalBytes = targetFile.length(),
                ),
            )
            return RoutingSegmentDownloadResult(fileName = safeName, downloaded = false)
        }
        val url = "$BROUTER_SEGMENTS_BASE_URL/$safeName"
        val result =
            runCatching {
                downloadFile(
                    url = url,
                    dir = routingSegmentsDir(context),
                    fileName = safeName,
                    label = "Routing",
                    progressDetail = safeName,
                    bufferSize = 512 * 1024,
                    progressStepBytes = 1L * 1024 * 1024,
                    fsync = true,
                    onResponseMetadata = onResponseMetadata,
                    onProgress = onProgress,
                ).let { downloadedFile ->
                    runCatching { validateRoutingSegmentIndex(downloadedFile) }
                        .onFailure { error ->
                            DebugTelemetry.log(
                                OAM_DOWNLOAD_TELEMETRY_TAG,
                                "event=routing_integrity_failed file=$safeName " +
                                    "error=${error.message.orEmpty().replace(' ', '_')}",
                            )
                            downloadedFile.delete()
                            routingSegmentPartFile(context, safeName).delete()
                        }.getOrThrow()
                }
                RoutingSegmentDownloadResult(fileName = safeName, downloaded = true)
            }.getOrElse { error ->
                if (error.isHttpNotFound()) {
                    DebugTelemetry.log(
                        OAM_DOWNLOAD_TELEMETRY_TAG,
                        "event=skip_missing_routing_segment file=$safeName url=$url",
                    )
                    onProgress(
                        OamDownloadProgress(
                            phase = "SKIPPED",
                            detail = "$safeName unavailable",
                        ),
                    )
                    RoutingSegmentDownloadResult(
                        fileName = safeName,
                        downloaded = false,
                        available = false,
                    )
                } else {
                    throw error
                }
            }
        return result
    }

    private suspend fun downloadDemTile(
        tileId: String,
        source: DemSource,
        forceDownload: Boolean,
        onResponseMetadata: (OamRemoteFileMetadata) -> Unit,
        onProgress: (OamDownloadProgress) -> Unit,
    ): DemTileDownloadResult {
        val safeTileId = tileId.uppercase(Locale.ROOT)
        val targetFile = demTileTargetFile(safeTileId, source)
        if (forceDownload) {
            deleteDemPartial(safeTileId, source)
        }
        if (!forceDownload && isDemTileStored(safeTileId, targetFile)) {
            onProgress(
                OamDownloadProgress(
                    phase = "READY",
                    detail = "$safeTileId DEM",
                    bytesDone = targetFile.takeIf { it.exists() }?.length()?.coerceAtLeast(0L) ?: 0L,
                    totalBytes = targetFile.takeIf { it.exists() }?.length()?.takeIf { it > 0L },
                ),
            )
            return DemTileDownloadResult(
                tileId = safeTileId,
                stored = true,
                available = targetFile.isFile,
                bytesStored = targetFile.takeIf { it.isFile }?.length()?.coerceAtLeast(0L) ?: 0L,
            )
        }

        val request = demRemoteFileRequest(safeTileId, source)
        val result =
            runCatching {
                val file =
                    downloadFile(
                        url = request.url,
                        dir = targetFile.parentFile ?: Dem3CoverageUtils.demRootDir(context, source),
                        fileName = targetFile.name,
                        label = "DEM",
                        progressDetail = "$safeTileId DEM",
                        bufferSize = 512 * 1024,
                        progressStepBytes = 512L * 1024,
                        fsync = true,
                        onResponseMetadata = onResponseMetadata,
                        onProgress = onProgress,
                    )
                runCatching { validateDemTileFile(file) }
                    .onFailure {
                        file.delete()
                    }.getOrThrow()
                clearMissingDemMarkers(safeTileId, source)
                DemTileDownloadResult(
                    tileId = safeTileId,
                    stored = true,
                    downloaded = true,
                    bytesStored = file.length().coerceAtLeast(0L),
                )
            }.getOrElse { error ->
                if (error.isHttpNotFound()) {
                    targetFile.delete()
                    File(
                        targetFile.parentFile ?: Dem3CoverageUtils.demRootDir(context, source),
                        ".${targetFile.name}.part",
                    ).delete()
                    createMissingDemMarker(
                        target = targetFile,
                        demRoot = Dem3CoverageUtils.demRootDir(context, source),
                    )
                    DebugTelemetry.log(
                        OAM_DOWNLOAD_TELEMETRY_TAG,
                        "event=skip_missing_dem_tile tile=$safeTileId url=${request.url}",
                    )
                    onProgress(
                        OamDownloadProgress(
                            phase = "SKIPPED",
                            detail = "$safeTileId DEM unavailable",
                        ),
                    )
                    DemTileDownloadResult(
                        tileId = safeTileId,
                        stored = true,
                        available = false,
                    )
                } else {
                    throw error
                }
            }
        return result
    }

    private fun isDemTileStored(
        tileId: String,
        targetFile: File,
    ): Boolean {
        val demRoot = targetFile.parentFile?.parentFile ?: Dem3CoverageUtils.demRootDir(context)
        if (targetFile.exists() && targetFile.isFile && runCatching { validateDemTileFile(targetFile) }.isSuccess) {
            clearMissingDemMarkers(tileId, demRoot)
            return true
        }
        return Dem3CoverageUtils
            .missingTileMarkerCandidates(demRoot = demRoot, tileId = tileId)
            .any { it.exists() && it.isFile }
    }

    private fun deleteDemPartial(
        tileId: String,
        source: DemSource,
    ) {
        val targetFile = demTileTargetFile(tileId, source)
        File(
            targetFile.parentFile ?: Dem3CoverageUtils.demRootDir(context, source),
            ".${targetFile.name}.part",
        ).delete()
    }

    private fun demRemoteFileRequest(
        tileId: String,
        source: DemSource = DemSource.DEFAULT,
    ): RemoteFileRequest {
        val safeTileId = tileId.uppercase(Locale.ROOT)
        val fileName = source.remoteFileName(safeTileId)
        return RemoteFileRequest(
            url = source.remoteUrl(safeTileId),
            fileName = fileName,
        )
    }

    private fun demTileTargetFile(
        tileId: String,
        source: DemSource = DemSource.DEFAULT,
    ): File {
        val safeTileId = tileId.uppercase(Locale.ROOT)
        val folder = source.folderForTile(safeTileId)
        return File(File(Dem3CoverageUtils.demRootDir(context, source), folder), source.localFileName(safeTileId))
    }

    private fun remoteFileRequestsForBundle(
        area: OamDownloadArea,
        bundle: OamInstalledBundle,
    ): List<RemoteFileRequest> =
        buildRemoteFileRequestsForBundle(
            area = area,
            bundle = bundle,
            demRemoteFileRequest = { tileId -> demRemoteFileRequest(tileId, bundle.demSource) },
            shouldSkipKnownMissingDemTile = { tileId -> isKnownMissingDemTile(tileId, bundle.demSource) },
        )

    private fun isKnownMissingDemTile(
        tileId: String,
        source: DemSource,
    ): Boolean =
        Dem3CoverageUtils
            .missingTileMarkerCandidates(demRoot = Dem3CoverageUtils.demRootDir(context, source), tileId = tileId)
            .any { it.exists() && it.isFile }

    private fun clearMissingDemMarkers(
        tileId: String,
        source: DemSource,
    ) {
        clearMissingDemMarkers(
            tileId = tileId,
            demRoot = Dem3CoverageUtils.demRootDir(context, source),
        )
    }

    private fun clearMissingDemMarkers(
        tileId: String,
        demRoot: File,
    ) {
        Dem3CoverageUtils
            .missingTileMarkerCandidates(demRoot = demRoot, tileId = tileId)
            .forEach { it.delete() }
    }

    private suspend fun fetchRemoteMetadataOrNull(
        request: RemoteFileRequest,
    ): OamRemoteFileMetadata? =
        runCatching {
            fetchRemoteMetadata(request)
        }.getOrNull()

    private suspend fun fetchRemoteMetadata(request: RemoteFileRequest): OamRemoteFileMetadata =
        withContext(Dispatchers.IO) {
            val connection =
                (URI(request.url).toURL().openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    useCaches = false
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("User-Agent", USER_AGENT)
                }
            activeConnections += connection
            try {
                val code = connection.responseCode
                if (code !in 200..399) {
                    throw IOException("HTTP $code for ${request.url}")
                }
                OamRemoteFileMetadata(
                    url = request.url,
                    fileName = request.fileName,
                    entityTag = connection.getHeaderField("ETag")?.takeIf { it.isNotBlank() },
                    lastModifiedMillis =
                        connection
                            .getHeaderFieldDate("Last-Modified", -1L)
                            .takeIf { it >= 0L },
                    contentLengthBytes = connection.contentLengthLong.takeIf { it > 0L },
                )
            } finally {
                activeConnections -= connection
                connection.disconnect()
            }
        }

    private suspend fun downloadFile(
        url: String,
        dir: File,
        fileName: String,
        label: String,
        progressDetail: String,
        bufferSize: Int,
        progressStepBytes: Long,
        fsync: Boolean,
        onResponseMetadata: (OamRemoteFileMetadata) -> Unit = {},
        onProgress: (OamDownloadProgress) -> Unit,
    ): File =
        withContext(Dispatchers.IO) {
            if (!dir.exists() && !dir.mkdirs()) {
                throw IOException("Cannot create download directory")
            }
            val safeName = File(fileName).name
            val finalFile = File(dir, safeName)
            val partFile = File(dir, ".$safeName.part")
            var resumeOffset = partFile.takeIf { it.exists() }?.length()?.coerceAtLeast(0L) ?: 0L
            var restartCount = 0
            var ioRetryCount = 0
            val downloadStartedAtMs = System.currentTimeMillis()
            var lastSpeedSampleAtMs = downloadStartedAtMs
            var lastSpeedSampleBytes = resumeOffset

            while (true) {
                coroutineContext.ensureActive()
                val connection =
                    openConnection(
                        url = url,
                        resumeOffset = resumeOffset,
                    )
                activeConnections += connection
                try {
                    val code = connection.responseCode
                    val append =
                        when {
                            resumeOffset > 0L && code == HttpURLConnection.HTTP_PARTIAL -> true
                            resumeOffset > 0L && code == HttpURLConnection.HTTP_OK -> {
                                partFile.delete()
                                resumeOffset = 0L
                                restartCount += 1
                                connection.disconnect()
                                continue
                            }
                            resumeOffset > 0L && code == HTTP_RANGE_NOT_SATISFIABLE -> {
                                partFile.delete()
                                resumeOffset = 0L
                                restartCount += 1
                                connection.disconnect()
                                continue
                            }
                            code == HttpURLConnection.HTTP_OK -> false
                            else -> throw IOException("HTTP $code for $url")
                        }

                    if (restartCount > MAX_RANGE_RESTARTS) {
                        throw IOException("Server rejected resume too many times")
                    }

                    val expectedTotalBytes =
                        connection.contentLengthLong
                            .takeIf { it > 0L }
                            ?.let { contentLength -> (if (append) resumeOffset else 0L) + contentLength }
                    onResponseMetadata(
                        OamRemoteFileMetadata(
                            url = url,
                            fileName = remoteFileName(url),
                            entityTag = connection.getHeaderField("ETag")?.takeIf { it.isNotBlank() },
                            lastModifiedMillis =
                                connection
                                    .getHeaderFieldDate("Last-Modified", -1L)
                                    .takeIf { it >= 0L },
                            contentLengthBytes = expectedTotalBytes,
                        ),
                    )

                    onProgress(
                        OamDownloadProgress(
                            phase = "DOWNLOADING",
                            detail = progressDetail,
                            bytesDone = resumeOffset,
                            totalBytes = expectedTotalBytes,
                        ),
                    )
                    logDownloadSpeed(
                        event = "download_start",
                        label = label,
                        fileName = safeName,
                        bytesDone = resumeOffset,
                        totalBytes = expectedTotalBytes,
                        currentSpeedMbps = null,
                        averageSpeedMbps = null,
                    )

                    var lastProgressAtMs = System.currentTimeMillis()
                    var lastProgressBytes = resumeOffset
                    val stallWatchdog =
                        launch {
                            while (isActive) {
                                delay(STALL_CHECK_INTERVAL_MS)
                                val idleMs = System.currentTimeMillis() - lastProgressAtMs
                                if (idleMs >= STALL_RECONNECT_TIMEOUT_MS) {
                                    DebugTelemetry.log(
                                        OAM_DOWNLOAD_TELEMETRY_TAG,
                                        "event=auto_reconnect_request reason=stall_timeout " +
                                            "file=$safeName idleMs=$idleMs bytes=$lastProgressBytes",
                                    )
                                    logDownloadSpeed(
                                        event = "download_stalled_reconnect",
                                        label = label,
                                        fileName = safeName,
                                        bytesDone = lastProgressBytes,
                                        totalBytes = expectedTotalBytes,
                                        currentSpeedMbps = null,
                                        averageSpeedMbps = null,
                                    )
                                    connection.disconnect()
                                    return@launch
                                }
                            }
                        }
                    try {
                        connection.inputStream.use { input ->
                            AtomicStreamWriter.writeAtomic(
                                dir = dir,
                                fileName = safeName,
                                inputStream = input,
                                onProgress = { bytes ->
                                    val nowMs = System.currentTimeMillis()
                                    val elapsedSinceLastMs = nowMs - lastSpeedSampleAtMs
                                    lastProgressAtMs = nowMs
                                    lastProgressBytes = bytes
                                    onProgress(
                                        OamDownloadProgress(
                                            phase = "DOWNLOADING",
                                            detail = progressDetail,
                                            bytesDone = bytes,
                                            totalBytes = expectedTotalBytes,
                                        ),
                                    )
                                    if (elapsedSinceLastMs >= DOWNLOAD_PROGRESS_TELEMETRY_INTERVAL_MS) {
                                        val bytesSinceLast = bytes - lastSpeedSampleBytes
                                        val elapsedSinceStartMs = nowMs - downloadStartedAtMs
                                        val bytesSinceStart = bytes - resumeOffset
                                        logDownloadSpeed(
                                            event = "download_progress",
                                            label = label,
                                            fileName = safeName,
                                            bytesDone = bytes,
                                            totalBytes = expectedTotalBytes,
                                            currentSpeedMbps = bytesPerMsToMbps(bytesSinceLast, elapsedSinceLastMs),
                                            averageSpeedMbps = bytesPerMsToMbps(bytesSinceStart, elapsedSinceStartMs),
                                        )
                                        lastSpeedSampleAtMs = nowMs
                                        lastSpeedSampleBytes = bytes
                                    }
                                },
                                options =
                                    AtomicStreamWriter.Options(
                                        bufferSize = bufferSize,
                                        progressStepBytes = progressStepBytes,
                                        fsync = fsync,
                                        expectedSize = expectedTotalBytes,
                                        requireExactSize = expectedTotalBytes != null,
                                        resumeOffset = if (append) resumeOffset else 0L,
                                        keepPartialOnCancel = true,
                                        keepPartialOnFailure = true,
                                        computeSha256 = false,
                                    ),
                            )
                        }
                    } finally {
                        stallWatchdog.cancel()
                    }
                    val completedAtMs = System.currentTimeMillis()
                    logDownloadSpeed(
                        event = "download_complete",
                        label = label,
                        fileName = safeName,
                        bytesDone = finalFile.length().coerceAtLeast(0L),
                        totalBytes = expectedTotalBytes,
                        currentSpeedMbps = null,
                        averageSpeedMbps =
                            bytesPerMsToMbps(
                                bytes = finalFile.length().coerceAtLeast(0L) - resumeOffset,
                                elapsedMs = completedAtMs - downloadStartedAtMs,
                            ),
                    )
                    return@withContext finalFile
                } catch (error: IOException) {
                    resumeOffset = partFile.takeIf { it.exists() }?.length()?.coerceAtLeast(0L) ?: 0L
                    if (error.isHttpResponseError() || ioRetryCount >= MAX_IO_RETRIES) {
                        throw error
                    }
                    ioRetryCount += 1
                    onProgress(
                        OamDownloadProgress(
                            phase = "PAUSED",
                            detail = "$progressDetail interrupted",
                            bytesDone = resumeOffset,
                        ),
                    )
                    logDownloadSpeed(
                        event = "download_interrupted",
                        label = label,
                        fileName = safeName,
                        bytesDone = resumeOffset,
                        totalBytes = null,
                        currentSpeedMbps = null,
                        averageSpeedMbps =
                            bytesPerMsToMbps(
                                bytes = resumeOffset - lastSpeedSampleBytes,
                                elapsedMs = System.currentTimeMillis() - lastSpeedSampleAtMs,
                            ),
                    )
                    delay(IO_RETRY_DELAY_MS * ioRetryCount)
                    continue
                } finally {
                    activeConnections -= connection
                    connection.disconnect()
                }
            }

            finalFile
        }

    private fun openConnection(
        url: String,
        resumeOffset: Long,
    ): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", USER_AGENT)
            if (resumeOffset > 0L) {
                setRequestProperty("Range", "bytes=$resumeOffset-")
            }
        }

    private fun remoteFileName(url: String): String =
        runCatching { File(URI(url).path).name }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: url.substringAfterLast('/').ifBlank { "download" }

    private fun logDownloadSpeed(
        event: String,
        label: String,
        fileName: String,
        bytesDone: Long,
        totalBytes: Long?,
        currentSpeedMbps: Double?,
        averageSpeedMbps: Double?,
    ) {
        DebugTelemetry.log(
            OAM_DOWNLOAD_TELEMETRY_TAG,
            buildString {
                append("event=").append(event)
                append(" label=").append(label)
                append(" file=").append(fileName)
                append(" bytes=").append(bytesDone.coerceAtLeast(0L))
                append(" total=").append(totalBytes ?: "unknown")
                append(" currentMbps=").append(currentSpeedMbps?.formatSpeed() ?: "na")
                append(" averageMbps=").append(averageSpeedMbps?.formatSpeed() ?: "na")
            },
        )
    }

    private fun bytesPerMsToMbps(
        bytes: Long,
        elapsedMs: Long,
    ): Double? =
        if (bytes > 0L && elapsedMs > 0L) {
            (bytes * 8.0) / elapsedMs / 1000.0
        } else {
            null
        }

    private fun Double.formatSpeed(): String = java.lang.String.format(java.util.Locale.US, "%.2f", this)

    private suspend fun extractFirstEntry(
        zipFile: File,
        extension: String,
        label: String,
        extractionKeepAliveState: () -> OamDownloadKeepAliveState,
        onProgress: (OamDownloadProgress) -> Unit,
        saveEntry: suspend (
            fileName: String,
            input: ZipInputStream,
            expectedSize: Long?,
            onEntryProgress: (Long) -> Unit,
        ) -> Unit,
    ): String =
        withContext(Dispatchers.IO) {
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile), ZIP_READ_BUFFER_SIZE)).use { zip ->
                while (true) {
                    coroutineContext.ensureActive()
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || !entry.name.endsWith(extension, ignoreCase = true)) {
                        zip.closeEntry()
                        continue
                    }
                    val entryFileName = File(entry.name).name
                    val expectedSize = entry.size.takeIf { it > 0L }
                    val extractStartedAtMs = System.currentTimeMillis()
                    var extractedBytes = 0L
                    val extractionTelemetry =
                        if (DebugTelemetry.isEnabled()) {
                            OamExtractionTelemetryReporter(
                                label = label,
                                entryFileName = entryFileName,
                                totalBytes = expectedSize,
                                wallNowMs = SystemClock::elapsedRealtime,
                                uptimeNowMs = SystemClock::uptimeMillis,
                                processCpuMs = Process::getElapsedCpuTime,
                                runtimeSnapshot = {
                                    captureExtractionRuntimeSnapshot(extractionKeepAliveState())
                                },
                                emit = { message -> DebugTelemetry.log(OAM_DOWNLOAD_TELEMETRY_TAG, message) },
                            )
                        } else {
                            null
                        }
                    logExtraction(
                        event = "extract_start",
                        label = label,
                        zipFileName = zipFile.name,
                        entryFileName = entryFileName,
                        bytesDone = 0L,
                        totalBytes = expectedSize,
                        durationMs = null,
                    )
                    onProgress(
                        OamDownloadProgress(
                            phase = "EXTRACTING",
                            detail = entryFileName,
                            totalBytes = expectedSize,
                        ),
                    )
                    val heartbeatJob =
                        extractionTelemetry?.let { telemetry ->
                            launch {
                                while (isActive) {
                                    delay(EXTRACTION_STALL_HEARTBEAT_INTERVAL_MS)
                                    telemetry.emitStallHeartbeatIfNeeded()
                                }
                            }
                        }
                    try {
                        saveEntry(
                            entryFileName,
                            zip,
                            expectedSize,
                        ) { bytes ->
                            extractedBytes = bytes
                            extractionTelemetry?.onBytesWritten(bytes)
                            onProgress(
                                OamDownloadProgress(
                                    phase = "EXTRACTING",
                                    detail = entryFileName,
                                    bytesDone = bytes,
                                    totalBytes = expectedSize,
                                ),
                            )
                        }
                    } finally {
                        heartbeatJob?.cancel()
                        extractionTelemetry?.complete(extractedBytes)
                    }
                    logExtraction(
                        event = "extract_complete",
                        label = label,
                        zipFileName = zipFile.name,
                        entryFileName = entryFileName,
                        bytesDone = extractedBytes,
                        totalBytes = expectedSize,
                        durationMs = System.currentTimeMillis() - extractStartedAtMs,
                    )
                    zip.closeEntry()
                    return@withContext entryFileName
                }
            }
            throw IOException("$label ZIP did not contain a $extension file")
        }

    private fun logExtraction(
        event: String,
        label: String,
        zipFileName: String,
        entryFileName: String,
        bytesDone: Long,
        totalBytes: Long?,
        durationMs: Long?,
    ) {
        DebugTelemetry.log(
            OAM_DOWNLOAD_TELEMETRY_TAG,
            buildString {
                append("event=").append(event)
                append(" label=").append(label)
                append(" zip=").append(zipFileName)
                append(" entry=").append(entryFileName)
                append(" bytes=").append(bytesDone.coerceAtLeast(0L))
                append(" total=").append(totalBytes ?: "unknown")
                append(" durationMs=").append(durationMs ?: "na")
            },
        )
    }

    private fun captureExtractionRuntimeSnapshot(
        keepAliveState: OamDownloadKeepAliveState,
    ): OamExtractionRuntimeSnapshot {
        val powerManager = context.getSystemService(PowerManager::class.java)
        val batteryIntent =
            runCatching {
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }.getOrNull()
        val batteryStatus = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPercent =
            if (level >= 0 && scale > 0) {
                ((level * 100f) / scale).toInt().toString()
            } else {
                "na"
            }
        val pluggedLabel =
            when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                else -> "battery"
            }
        return OamExtractionRuntimeSnapshot(
            interactive = powerManager?.isInteractive == true,
            charging =
                batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
                    batteryStatus == BatteryManager.BATTERY_STATUS_FULL,
            plugged = pluggedLabel,
            batteryPercent = batteryPercent,
            thermalStatus =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
                    powerManager.currentThermalStatus.toString()
                } else {
                    "na"
                },
            wakeLockHeld = keepAliveState.wakeLockHeld,
            wifiLockHeld = keepAliveState.wifiLockHeld,
            wakeLockType = keepAliveState.wakeLockType,
            wakeLockAcquireAgeMs = keepAliveState.wakeLockAcquireAgeMs,
            wakeLockTimeoutMs = keepAliveState.wakeLockTimeoutMs,
            wakeLockGeneration = keepAliveState.wakeLockGeneration,
        )
    }

    private companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val STALL_CHECK_INTERVAL_MS = 15_000L
        private const val STALL_RECONNECT_TIMEOUT_MS = 75_000L
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val MAX_RANGE_RESTARTS = 1
        private const val MAX_IO_RETRIES = 3
        private const val IO_RETRY_DELAY_MS = 2_000L
        private const val OAM_ZIP_DOWNLOAD_BUFFER_SIZE = 2 * 1024 * 1024
        private const val ZIP_READ_BUFFER_SIZE = 1024 * 1024
        private const val DOWNLOAD_PROGRESS_TELEMETRY_INTERVAL_MS = 5_000L
        private const val EXTRACTION_STALL_HEARTBEAT_INTERVAL_MS = 30_000L
        private const val LARGE_DETAILED_DEM_TILE_THRESHOLD = 100
        private const val BROUTER_SEGMENTS_BASE_URL = "https://brouter.de/brouter/segments4"
        private const val USER_AGENT = "GlanceMap-WearOS-OAM-Downloader/1.0 https://www.openandromaps.org"
        private const val OAM_DOWNLOAD_TELEMETRY_TAG = "OamDownload"
    }
}

private fun File?.isHealthyLocalFile(): Boolean = this != null && exists() && isFile && length() > 0L

private fun validateMapFile(file: File) {
    val map = MapFile(file)
    try {
        map.boundingBox()
    } finally {
        map.close()
    }
}

private fun validatePoiFile(file: File) {
    SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { database ->
        database.rawQuery("PRAGMA quick_check(1)", emptyArray()).use { cursor ->
            check(cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)) {
                "POI database integrity check failed."
            }
        }
    }
}

internal enum class RemoteMetadataComparison {
    SAME,
    CHANGED,
    UNKNOWN,
}

private val OamRemoteFileMetadata.metadataValues: List<Any?>
    get() = listOf(entityTag, lastModifiedMillis, contentLengthBytes)

private fun OamRemoteFileMetadata.isComparable(): Boolean = metadataValues.any { it != null }

internal fun OamRemoteFileMetadata.compareWith(other: OamRemoteFileMetadata): RemoteMetadataComparison =
    when {
        url != other.url -> RemoteMetadataComparison.CHANGED
        contentLengthBytes != null &&
            other.contentLengthBytes != null &&
            contentLengthBytes == other.contentLengthBytes -> RemoteMetadataComparison.SAME
        contentLengthBytes != null &&
            other.contentLengthBytes != null -> RemoteMetadataComparison.CHANGED
        lastModifiedMillis != null && other.lastModifiedMillis != null ->
            compareNullableValues(lastModifiedMillis, other.lastModifiedMillis)
        entityTag != null && other.entityTag != null && entityTag == other.entityTag -> RemoteMetadataComparison.SAME
        entityTag != null && other.entityTag != null -> RemoteMetadataComparison.CHANGED
        else -> RemoteMetadataComparison.UNKNOWN
    }

private fun Throwable.isHttpNotFound(): Boolean = message?.contains("HTTP 404", ignoreCase = true) == true

private fun Throwable.isHttpResponseError(): Boolean = message?.startsWith("HTTP ", ignoreCase = true) == true

private fun OamRemoteFileMetadata.telemetrySummary(): String = "etag=${entityTag ?: "na"},modified=${lastModifiedMillis ?: "na"},bytes=${contentLengthBytes ?: "na"}"

private fun <T> compareNullableValues(
    previous: T,
    current: T,
): RemoteMetadataComparison =
    if (previous == current) {
        RemoteMetadataComparison.SAME
    } else {
        RemoteMetadataComparison.CHANGED
    }
