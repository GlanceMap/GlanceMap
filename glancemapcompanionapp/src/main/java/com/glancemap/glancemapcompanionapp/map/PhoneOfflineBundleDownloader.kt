package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDownloadDiagnostics
import com.glancemap.glancemapcompanionapp.refuges.RefugesGeoJsonPoiImporter
import com.glancemap.glancemapcompanionapp.routing.BRouterTileDownloadException
import com.glancemap.glancemapcompanionapp.routing.BRouterTileDownloader
import com.glancemap.glancemapcompanionapp.routing.BRouterTileMath
import com.glancemap.trailcore.oam.OamDownloadArea
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.map.reader.MapFile
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Collections
import java.util.Locale
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

private const val BROUTER_SEGMENTS_BASE_URL = "https://brouter.de/brouter/segments4"

internal data class PhoneOfflineRemoteFileRequest(
    val url: String,
    val fileName: String,
)

internal fun buildPhoneOfflineRemoteFileRequestsForBundle(
    area: OamDownloadArea,
    bundle: PhoneInstalledBundle,
): List<PhoneOfflineRemoteFileRequest> =
    buildList {
        if (bundle.mapFileName.isNotBlank()) {
            add(PhoneOfflineRemoteFileRequest(area.mapZipUrl, phoneOfflineRemoteFileName(area.mapZipUrl)))
        }
        if (bundle.poiFileName.isNotBlank()) {
            add(PhoneOfflineRemoteFileRequest(area.poiZipUrl, phoneOfflineRemoteFileName(area.poiZipUrl)))
        }
        bundle.routingFileNames.forEach { fileName ->
            val safeName = File(fileName).name
            add(PhoneOfflineRemoteFileRequest("$BROUTER_SEGMENTS_BASE_URL/$safeName", safeName))
        }
        bundle.demTileIds.forEach { tileId ->
            add(
                PhoneOfflineRemoteFileRequest(
                    url = bundle.demSource.remoteUrl(tileId),
                    fileName = bundle.demSource.remoteFileName(tileId),
                ),
            )
        }
    }

/** Phone OAM bundle downloader using the same map, routing, and DEM file conventions as the watch. */
@Suppress(
    "TooManyFunctions",
    "LargeClass",
) // Bundle download, validation, and atomic installation share one workflow.
internal class PhoneOfflineBundleDownloader(
    context: Context,
    private val mapStore: PhoneOfflineMapStore = PhoneOfflineMapStore(context),
    private val bundleStore: PhoneOfflineBundleStore = PhoneOfflineBundleStore(context),
    private val routingDownloader: BRouterTileDownloader = BRouterTileDownloader(context),
) {
    private val applicationContext = context.applicationContext
    private val storage = PhoneOfflineStorage(applicationContext)
    private val downloadDirectory: File by lazy { storage.bundleDownloadDirectory() }
    private val activeConnections = Collections.synchronizedSet(mutableSetOf<HttpURLConnection>())
    private val refugesImporter = RefugesGeoJsonPoiImporter(applicationContext)

    @Volatile
    private var activeRecovery: PhoneOfflineBundleRecovery? = null

    @Volatile
    private var activeFailureContext: PhoneOfflineBundleFailureContext? = null

    suspend fun download(
        selection: PhoneOfflineBundleSelection,
        forces: PhoneOfflineBundleRefreshForces = PhoneOfflineBundleRefreshForces(),
        onProgress: (PhoneOfflineBundleProgress) -> Unit,
    ): PhoneOfflineBundleOutcome =
        withContext(Dispatchers.IO) {
            try {
                downloadSelection(selection, onProgress, forces)
            } catch (exception: CancellationException) {
                PhoneDownloadDiagnostics.warn(
                    "Bundle",
                    "Cancelled ${bundleDiagnosticContext()}",
                )
                saveRecoveryFailure(PhoneOfflineBundleFailure.CANCELLED)
                throw exception
            } catch (exception: PhoneOfflineBundleDownloadException) {
                PhoneDownloadDiagnostics.error(
                    "Bundle",
                    "Failed reason=${exception.reason.name} ${bundleDiagnosticContext()}",
                    exception,
                )
                saveRecoveryFailure(exception.reason)
                PhoneOfflineBundleOutcome.Failure(exception.reason, failureContext(exception))
            } catch (exception: IOException) {
                if (!currentCoroutineContext().isActive) {
                    PhoneDownloadDiagnostics.warn(
                        "Bundle",
                        "Cancelled ${bundleDiagnosticContext()}",
                    )
                    saveRecoveryFailure(PhoneOfflineBundleFailure.CANCELLED)
                    throw CancellationException()
                }
                PhoneDownloadDiagnostics.error(
                    "Bundle",
                    "Failed ${bundleDiagnosticContext()}",
                    exception,
                )
                saveRecoveryFailure(PhoneOfflineBundleFailure.NETWORK)
                PhoneOfflineBundleOutcome.Failure(
                    PhoneOfflineBundleFailure.NETWORK,
                    failureContext(exception),
                )
            } catch (exception: Exception) {
                PhoneDownloadDiagnostics.error(
                    "Bundle",
                    "Failed ${bundleDiagnosticContext()}",
                    exception,
                )
                saveRecoveryFailure(PhoneOfflineBundleFailure.STORAGE)
                PhoneOfflineBundleOutcome.Failure(
                    PhoneOfflineBundleFailure.STORAGE,
                    failureContext(exception),
                )
            } finally {
                activeRecovery = null
                activeFailureContext = null
            }
        }

    fun cancelActiveDownloads() {
        val connections = synchronized(activeConnections) { activeConnections.toList() }
        connections.forEach { connection -> runCatching(connection::disconnect) }
        routingDownloader.cancelActiveDownload()
        refugesImporter.cancelActiveDownload()
    }

    @Suppress(
        "LongMethod",
        "CyclomaticComplexMethod",
    ) // The check intentionally combines local integrity and remote metadata results.
    suspend fun checkBundleUpdates(bundle: PhoneInstalledBundle): PhoneOfflineBundleUpdateCheck =
        withContext(Dispatchers.IO) {
            val area =
                com.glancemap.trailcore.oam.OamDownloadCatalog.areas
                    .firstOrNull { it.id == bundle.areaId }
                    ?: return@withContext PhoneOfflineBundleUpdateCheck(
                        bundle = bundle,
                        status = PhoneOfflineBundleUpdateStatus.UNKNOWN,
                        checkedFileCount = 0,
                        unknownFileNames = listOf(bundle.areaLabel),
                    )
            val localHealth =
                phoneOfflineBundleHealth(
                    context = applicationContext,
                    mapStore = mapStore,
                    bundle = bundle,
                    recovery = bundleStore.findRecovery(bundle.areaId),
                )
            val repairFileNames =
                (localHealth.invalidFileNames + localHealth.missingFileNames)
                    .map { fileName ->
                        when {
                            fileName == "map" || fileName.startsWith("map/") ->
                                phoneOfflineRemoteFileName(area.mapZipUrl)
                            fileName == "poi" || fileName.startsWith("poi/") ->
                                phoneOfflineRemoteFileName(area.poiZipUrl)
                            fileName == "refuges.info" ->
                                bundle.refugesInfoFileName?.let { File(it).name } ?: fileName
                            fileName.uppercase(Locale.ROOT) in bundle.demTileIds.map { it.uppercase(Locale.ROOT) } ->
                                bundle.demSource.remoteFileName(fileName)
                            else -> File(fileName).name
                        }
                    }.distinct()
            val requests = buildPhoneOfflineRemoteFileRequestsForBundle(area, bundle)
            val previousByUrl = bundle.remoteFiles.associateBy { it.url }
            val changedFileNames = mutableListOf<String>()
            val unknownFileNames = mutableListOf<String>()
            var checkedFileCount = 0
            requests.forEach { request ->
                currentCoroutineContext().ensureActive()
                val previous = previousByUrl[request.url]
                if (previous == null || !previous.isComparable()) {
                    unknownFileNames += request.fileName
                    return@forEach
                }
                val current = fetchRemoteMetadataOrNull(request)
                if (current == null || !current.isComparable()) {
                    unknownFileNames += request.fileName
                    return@forEach
                }
                checkedFileCount += 1
                when (previous.compareWith(current)) {
                    PhoneOfflineRemoteMetadataComparison.CHANGED -> changedFileNames += request.fileName
                    PhoneOfflineRemoteMetadataComparison.UNKNOWN -> unknownFileNames += request.fileName
                    PhoneOfflineRemoteMetadataComparison.SAME -> Unit
                }
            }
            val distinctChanged = changedFileNames.distinct()
            val distinctUnknown = unknownFileNames.distinct()
            val status =
                when {
                    repairFileNames.isNotEmpty() -> PhoneOfflineBundleUpdateStatus.REPAIR_NEEDED
                    distinctChanged.isNotEmpty() -> PhoneOfflineBundleUpdateStatus.UPDATE_AVAILABLE
                    requests.isEmpty() -> PhoneOfflineBundleUpdateStatus.UP_TO_DATE
                    distinctUnknown.isNotEmpty() || checkedFileCount == 0 -> PhoneOfflineBundleUpdateStatus.UNKNOWN
                    else -> PhoneOfflineBundleUpdateStatus.UP_TO_DATE
                }
            PhoneDownloadDiagnostics.log(
                "Bundle",
                "Update check area=${bundle.areaId} status=$status checked=$checkedFileCount " +
                    "changed=${distinctChanged.size} repair=${repairFileNames.size} unknown=${distinctUnknown.size}",
            )
            PhoneOfflineBundleUpdateCheck(
                bundle = bundle,
                status = status,
                checkedFileCount = checkedFileCount,
                changedFileNames = distinctChanged,
                repairFileNames = repairFileNames,
                unknownFileNames = distinctUnknown,
            )
        }

    @Suppress(
        "LongMethod",
        "CyclomaticComplexMethod",
        "ThrowsCount",
        "TooGenericExceptionCaught",
        "NestedBlockDepth",
    ) // Coordinates optional map, routing, DEM, and Refuges stages.
    private suspend fun downloadSelection(
        selection: PhoneOfflineBundleSelection,
        onProgress: (PhoneOfflineBundleProgress) -> Unit,
        forces: PhoneOfflineBundleRefreshForces,
    ): PhoneOfflineBundleOutcome.Success {
        val area = selection.area
        activeFailureContext = null
        val existing = bundleStore.find(area.id)
        val recovery = bundleStore.findRecovery(area.id)
        val remoteFilesByUrl =
            existing
                ?.remoteFiles
                .orEmpty()
                .associateBy { it.url }
                .toMutableMap()
        val existingMap =
            if (forces.forceMap) {
                null
            } else {
                resolvePhoneOfflineBundleLocalAsset(
                    candidateFileNames =
                        phoneOfflineBundleMapCandidateFileNames(
                            area = area,
                            completedFileName = existing?.mapFileName,
                            recoveryFileName = recovery?.mapFileName,
                        ),
                    findValidAsset = mapStore::findValidBundleMap,
                )
            }
        val existingPoi =
            if (forces.forcePoi) {
                null
            } else {
                resolvePhoneOfflineBundleLocalAsset(
                    candidateFileNames =
                        phoneOfflineBundlePoiCandidateFileNames(
                            area = area,
                            completedFileName = existing?.poiFileName,
                            recoveryFileName = recovery?.poiFileName,
                        ),
                    findValidAsset = { fileName ->
                        File(phoneMapPoiStorageDirectory(applicationContext), fileName)
                            .takeIf(::isPhoneMapPoiFileValid)
                    },
                )
            }
        var recoveryState =
            PhoneOfflineBundleRecovery(
                areaId = area.id,
                areaLabel = area.region,
                includeMap = selection.includeMap,
                includePoi = selection.includePoi,
                includeRouting = selection.includeRouting,
                includeDem = selection.includeDem,
                includeRefugesInfo = selection.includeRefugesInfo,
                demSource = selection.demSource,
                phase = PhoneOfflineBundlePhase.DOWNLOADING_MAP,
                mapFileName = existingMap?.displayName,
                poiFileName = existingPoi?.name,
            )

        fun checkpoint(update: PhoneOfflineBundleRecovery) {
            recoveryState = update.copy(updatedAtMillis = System.currentTimeMillis(), failure = null)
            activeRecovery = recoveryState
            bundleStore.saveRecovery(recoveryState)
        }
        checkpoint(recoveryState)

        val mapResult =
            existingMap?.let {
                onProgress(
                    PhoneOfflineBundleProgress(
                        phase = PhoneOfflineBundlePhase.DOWNLOADING_MAP,
                        detail = "Map already on phone.",
                    ),
                )
                InstalledMap(it.displayName, reusedExisting = true)
            }
                ?: if (selection.includeMap) {
                    setFailureContext(
                        phase = PhoneOfflineBundlePhase.DOWNLOADING_MAP,
                        component = PhoneOfflineBundleComponent.MAP,
                        fileName = phoneOfflineRemoteFileName(area.mapZipUrl),
                    )
                    downloadAndInstallMap(
                        url = area.mapZipUrl,
                        areaId = area.id,
                        replaceExisting = forces.forceMap,
                        expectedArchiveSize = remoteFilesByUrl[area.mapZipUrl]?.contentLengthBytes,
                        onResponseMetadata = { metadata -> remoteFilesByUrl[metadata.url] = metadata },
                        onProgress = onProgress,
                    )
                } else {
                    null
                }
        val mapFileName = mapResult?.fileName
        checkpoint(
            recoveryState.copy(
                phase = PhoneOfflineBundlePhase.DOWNLOADING_POI,
                mapFileName = mapFileName,
            ),
        )
        if (selection.includeRouting || selection.includeDem || selection.includeRefugesInfo) {
            checkNotNull(mapFileName) { "Maps must be installed before routing, elevation, or Refuges.info." }
        }
        val poiResult =
            existingPoi?.let {
                onProgress(
                    PhoneOfflineBundleProgress(
                        phase = PhoneOfflineBundlePhase.DOWNLOADING_POI,
                        detail = "POI already on phone.",
                    ),
                )
                InstalledPoi(it.name, reusedExisting = true)
            }
                ?: if (selection.includePoi) {
                    setFailureContext(
                        phase = PhoneOfflineBundlePhase.DOWNLOADING_POI,
                        component = PhoneOfflineBundleComponent.POI,
                        fileName = phoneOfflineRemoteFileName(area.poiZipUrl),
                    )
                    downloadAndInstallPoi(
                        url = area.poiZipUrl,
                        areaId = area.id,
                        replaceExisting = forces.forcePoi,
                        expectedArchiveSize = remoteFilesByUrl[area.poiZipUrl]?.contentLengthBytes,
                        onResponseMetadata = { metadata -> remoteFilesByUrl[metadata.url] = metadata },
                        onProgress = onProgress,
                    )
                } else {
                    null
                }
        val poiFileName = poiResult?.fileName
        checkpoint(
            recoveryState.copy(
                phase = PhoneOfflineBundlePhase.DOWNLOADING_ROUTING,
                mapFileName = mapFileName,
                poiFileName = poiFileName,
            ),
        )
        val storedMapFileName =
            checkNotNull(mapResult?.fileName) {
                "A map is required for a phone bundle."
            }
        val storedPoiFileName =
            checkNotNull(poiResult?.fileName) {
                "POI must be installed for a phone bundle."
            }

        val mapBounds = mapBounds(storedMapFileName)
        val routingExpected =
            if (selection.includeRouting) {
                val bounds = checkNotNull(mapBounds) { "Cannot read map bounds for routing." }
                routingFileNamesForBounds(bounds)
            } else {
                existing?.routingFileNames.orEmpty().ifEmpty { recovery?.routingFileNames.orEmpty() }
            }
        val demExpected =
            if (selection.includeDem) {
                val bounds = checkNotNull(mapBounds) { "Cannot read map bounds for elevation." }
                phoneDemTileIdsForBounds(bounds)
            } else {
                existing?.demTileIds.orEmpty().ifEmpty { recovery?.demTileIds.orEmpty() }
            }
        val demSource =
            if (selection.includeDem) {
                selection.demSource
            } else {
                existing?.demSource ?: recovery?.demSource ?: selection.demSource
            }
        val availableRouting = availableRoutingFiles(routingExpected)
        val availableDem = availableDemFiles(demExpected, demSource)
        checkpoint(
            recoveryState.copy(
                phase =
                    if (selection.includeRouting) {
                        PhoneOfflineBundlePhase.DOWNLOADING_ROUTING
                    } else {
                        PhoneOfflineBundlePhase.DOWNLOADING_DEM
                    },
                routingFileNames = routingExpected,
                downloadedRoutingFileNames = availableRouting,
                demTileIds = demExpected,
                downloadedDemTileIds = availableDem,
                demSource = demSource,
            ),
        )

        fun checkpointRoutingFailure(error: Throwable): Nothing {
            checkpoint(
                recoveryState.copy(
                    phase = PhoneOfflineBundlePhase.DOWNLOADING_ROUTING,
                    downloadedRoutingFileNames = availableRoutingFiles(routingExpected),
                    detail = activeFailureContext?.detail.orEmpty().ifBlank { error.message.orEmpty() },
                ),
            )
            throw error
        }
        val routingFileNames =
            if (selection.includeRouting) {
                val bounds = checkNotNull(mapBounds) { "Cannot read map bounds for routing." }
                setFailureContext(
                    phase = PhoneOfflineBundlePhase.DOWNLOADING_ROUTING,
                    component = PhoneOfflineBundleComponent.ROUTING,
                )
                try {
                    downloadRouting(bounds, forces.forceRouting, onProgress).also { downloaded ->
                        check(downloaded.containsAll(routingExpected)) { "Routing bundle is incomplete." }
                        checkpoint(
                            recoveryState.copy(
                                phase = PhoneOfflineBundlePhase.DOWNLOADING_DEM,
                                downloadedRoutingFileNames = downloaded,
                            ),
                        )
                    }
                } catch (error: BRouterTileDownloadException) {
                    setFailureContext(
                        phase = PhoneOfflineBundlePhase.DOWNLOADING_ROUTING,
                        component = PhoneOfflineBundleComponent.ROUTING,
                        fileName = error.tileName,
                        detail = error.userMessage,
                    )
                    checkpointRoutingFailure(error)
                } catch (error: Throwable) {
                    checkpointRoutingFailure(error)
                }
            } else {
                availableRouting
            }
        val downloadedRoutingFileNames =
            if (selection.includeRouting) routingFileNames else availableRouting
        val demTileIds =
            if (selection.includeDem) {
                val bounds = checkNotNull(mapBounds) { "Cannot read map bounds for elevation." }
                setFailureContext(
                    phase = PhoneOfflineBundlePhase.DOWNLOADING_DEM,
                    component = PhoneOfflineBundleComponent.DEM,
                )
                try {
                    downloadDem(bounds, demSource, forces.forceDemTileIds, onProgress).also { downloaded ->
                        checkpoint(
                            recoveryState.copy(
                                phase = PhoneOfflineBundlePhase.DOWNLOADING_REFUGES,
                                downloadedRoutingFileNames = downloadedRoutingFileNames,
                                downloadedDemTileIds = downloaded,
                            ),
                        )
                    }
                } catch (error: Throwable) {
                    checkpoint(
                        recoveryState.copy(
                            phase = PhoneOfflineBundlePhase.DOWNLOADING_DEM,
                            downloadedRoutingFileNames = downloadedRoutingFileNames,
                            downloadedDemTileIds = availableDemFiles(demExpected, demSource),
                            detail = error.message.orEmpty(),
                        ),
                    )
                    throw error
                }
            } else {
                availableDem
            }
        val downloadedDemTileIds =
            if (selection.includeDem) demTileIds else availableDem

        val existingRefugesInfo =
            resolvePhoneOfflineBundleLocalAsset(
                candidateFileNames = listOf(existing?.refugesInfoFileName, recovery?.refugesInfoFileName),
                findValidAsset = { fileName ->
                    File(phoneMapPoiStorageDirectory(applicationContext), fileName)
                        .takeIf(::isPhoneMapPoiFileValid)
                },
            )
        val refugesInfoFileName =
            if (selection.includeRefugesInfo) {
                val bounds = checkNotNull(mapBounds) { "Cannot read map bounds for Refuges.info." }
                setFailureContext(
                    phase = PhoneOfflineBundlePhase.DOWNLOADING_REFUGES,
                    component = PhoneOfflineBundleComponent.REFUGES_INFO,
                    fileName = "${area.id}.refuges-info.poi",
                )
                checkpoint(
                    recoveryState.copy(
                        phase = PhoneOfflineBundlePhase.DOWNLOADING_REFUGES,
                        downloadedRoutingFileNames = downloadedRoutingFileNames,
                        downloadedDemTileIds = downloadedDemTileIds,
                    ),
                )
                try {
                    existingRefugesInfo
                        ?.takeUnless { forces.forceRefugesInfo }
                        ?.name
                        ?: refugesImporter
                            .importFromBbox(
                                bboxInput = bounds.asPhoneBbox(),
                                fileNameInput = "${area.id}.refuges-info.poi",
                                forceRefresh = forces.forceRefugesInfo,
                                reportProgress = { percent, detail ->
                                    onProgress(
                                        PhoneOfflineBundleProgress(
                                            phase = PhoneOfflineBundlePhase.DOWNLOADING_REFUGES,
                                            percent = percent.coerceIn(0, 100),
                                            detail = detail,
                                        ),
                                    )
                                },
                            ).fileName
                            .also { fileName ->
                                val file = File(phoneMapPoiStorageDirectory(applicationContext), fileName)
                                if (!isPhoneMapPoiFileValid(file)) {
                                    downloadFailure(PhoneOfflineBundleFailure.INVALID_REFUGES_INFO)
                                }
                                checkpoint(recoveryState.copy(refugesInfoFileName = fileName))
                            }
                } catch (error: Throwable) {
                    checkpoint(recoveryState.copy(detail = error.message.orEmpty()))
                    throw error
                }
            } else {
                existing?.refugesInfoFileName
            }

        val bundle =
            PhoneInstalledBundle(
                areaId = area.id,
                areaLabel = area.region,
                mapFileName = storedMapFileName,
                poiFileName = storedPoiFileName,
                refugesInfoFileName = refugesInfoFileName,
                routingFileNames = routingFileNames,
                downloadedRoutingFileNames = downloadedRoutingFileNames,
                demSource = demSource,
                demTileIds = demTileIds,
                downloadedDemTileIds = downloadedDemTileIds,
                installedAtMillis = System.currentTimeMillis(),
                remoteFiles = remoteFilesByUrl.values.sortedBy { it.url },
            )
        val completedBundle = bundle.copy(integrity = phoneOfflineBundleIntegrity(applicationContext, bundle))
        bundleStore.upsert(completedBundle)
        bundleStore.clearRecovery(area.id)
        return PhoneOfflineBundleOutcome.Success(
            bundle = completedBundle,
            reusedMap = mapResult.reusedExisting,
            reusedPoi = poiResult.reusedExisting,
        )
    }

    private fun saveRecoveryFailure(reason: PhoneOfflineBundleFailure) {
        activeRecovery?.let { recovery ->
            bundleStore.saveRecovery(
                recovery.copy(
                    failure = reason,
                    detail = recovery.detail.ifBlank { activeFailureContext?.detail.orEmpty().ifBlank { reason.name } },
                    failureComponent = activeFailureContext?.component,
                    failureFileName = activeFailureContext?.fileName,
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun setFailureContext(
        phase: PhoneOfflineBundlePhase,
        component: PhoneOfflineBundleComponent,
        fileName: String? = null,
        detail: String = "",
    ) {
        activeFailureContext = PhoneOfflineBundleFailureContext(phase, component, fileName, detail)
    }

    private fun failureContext(error: Throwable): PhoneOfflineBundleFailureContext? =
        activeFailureContext?.copy(
            detail =
                activeFailureContext
                    ?.detail
                    .orEmpty()
                    .ifBlank { error.toPhoneOfflineBundleFailureDetail() },
        )

    private fun bundleDiagnosticContext(): String {
        val selectedStorage = runCatching { storage.location().name }.getOrDefault("UNKNOWN")
        val targetRoot =
            runCatching { storage.activeRoot().absolutePath }
                .getOrDefault("<unavailable>")
        return "stage=${activeRecovery?.phase?.name ?: "UNKNOWN"} " +
            "storage=$selectedStorage target=$targetRoot"
    }

    private fun routingFileNamesForBounds(bounds: BoundingBox): List<String> {
        val bbox = bounds.asPhoneBbox()
        return BRouterTileMath.tileFileNamesForBbox(bbox)
    }

    private fun availableRoutingFiles(expected: List<String>): List<String> =
        expected.filter { fileName ->
            isUsablePhoneRoutingFile(File(storage.routingDirectory(), File(fileName).name))
        }

    private fun availableDemFiles(
        expected: List<String>,
        source: PhoneOfflineDemSource,
    ): List<String> =
        expected.filter { tileId ->
            isUsablePhoneDemFile(phoneOfflineDemFile(storage.elevationDirectory(), source, tileId))
        }

    @Suppress(
        "LongParameterList",
    ) // Streaming installation needs its source, archive validation, and progress callbacks together.
    private suspend fun downloadAndInstallMap(
        url: String,
        areaId: String,
        replaceExisting: Boolean,
        expectedArchiveSize: Long?,
        onResponseMetadata: (PhoneOfflineRemoteFileMetadata) -> Unit,
        onProgress: (PhoneOfflineBundleProgress) -> Unit,
    ): InstalledMap {
        val archive =
            downloadArchive(
                url = url,
                fileName = "$areaId-map.zip",
                phase = PhoneOfflineBundlePhase.DOWNLOADING_MAP,
                resetPartial = replaceExisting,
                expectedArchiveSize = expectedArchiveSize,
                entryExtension = MAP_EXTENSION,
                reuseLabel = "Map",
                onResponseMetadata = onResponseMetadata,
                onProgress = onProgress,
            )
        var installed = false
        return try {
            extractExpectedEntry(
                archive = archive,
                extension = MAP_EXTENSION,
                phase = PhoneOfflineBundlePhase.INSTALLING_MAP,
                onProgress = onProgress,
            ) { fileName, input, entryProgress ->
                when (val result = mapStore.installBundleMap(fileName, input, replaceExisting, entryProgress)) {
                    is PhoneOfflineMapBundleInstallResult.Success ->
                        InstalledMap(result.map.displayName, result.reusedExisting)
                    is PhoneOfflineMapBundleInstallResult.Failure ->
                        throw PhoneOfflineBundleDownloadException(result.error.toMapFailure())
                }
            }.also { installed = true }
        } catch (error: PhoneOfflineBundleDownloadException) {
            if (error.reason == PhoneOfflineBundleFailure.ARCHIVE) archive.delete()
            throw error
        } catch (error: ZipException) {
            archive.delete()
            throw error
        } finally {
            if (installed) archive.delete()
        }
    }

    @Suppress(
        "LongParameterList",
    ) // Streaming installation needs its source, archive validation, and progress callbacks together.
    private suspend fun downloadAndInstallPoi(
        url: String,
        areaId: String,
        replaceExisting: Boolean,
        expectedArchiveSize: Long?,
        onResponseMetadata: (PhoneOfflineRemoteFileMetadata) -> Unit,
        onProgress: (PhoneOfflineBundleProgress) -> Unit,
    ): InstalledPoi {
        val archive =
            downloadArchive(
                url = url,
                fileName = "$areaId-poi.zip",
                phase = PhoneOfflineBundlePhase.DOWNLOADING_POI,
                resetPartial = replaceExisting,
                expectedArchiveSize = expectedArchiveSize,
                entryExtension = POI_EXTENSION,
                reuseLabel = "POI",
                onResponseMetadata = onResponseMetadata,
                onProgress = onProgress,
            )
        var installed = false
        return try {
            extractExpectedEntry(
                archive = archive,
                extension = POI_EXTENSION,
                phase = PhoneOfflineBundlePhase.INSTALLING_POI,
                onProgress = onProgress,
            ) { fileName, input, entryProgress ->
                installPoi(fileName, input, entryProgress, replaceExisting)
            }.also { installed = true }
        } catch (error: PhoneOfflineBundleDownloadException) {
            if (error.reason == PhoneOfflineBundleFailure.ARCHIVE) archive.delete()
            throw error
        } catch (error: ZipException) {
            archive.delete()
            throw error
        } finally {
            if (installed) archive.delete()
        }
    }

    @Suppress("LongParameterList") // Archive reuse validates the same request that reports network progress.
    private suspend fun downloadArchive(
        url: String,
        fileName: String,
        phase: PhoneOfflineBundlePhase,
        resetPartial: Boolean,
        expectedArchiveSize: Long?,
        entryExtension: String,
        reuseLabel: String,
        onResponseMetadata: (PhoneOfflineRemoteFileMetadata) -> Unit,
        onProgress: (PhoneOfflineBundleProgress) -> Unit,
    ): File {
        val safeFileName = File(fileName).name
        val archive = File(downloadDirectory, safeFileName)
        val temporary = File(downloadDirectory, ".${archive.name}.part")
        migrateLegacyArchiveFile(File(applicationContext.cacheDir, safeFileName), archive)
        migrateLegacyArchiveFile(File(applicationContext.cacheDir, ".${archive.name}.part"), temporary)
        if (resetPartial) {
            archive.delete()
        }
        reusablePhoneBundleArchiveOrNull(
            directory = downloadDirectory,
            fileName = safeFileName,
            entryExtension = entryExtension,
            expectedSize = expectedArchiveSize,
        )?.let { reusableArchive ->
            onProgress(
                PhoneOfflineBundleProgress(
                    phase = phase,
                    detail = "Using saved $reuseLabel archive.",
                ),
            )
            return reusableArchive
        }

        downloadToTemporary(
            url = url,
            temporary = temporary,
            phase = phase,
            // A forced refresh invalidates the completed archive, but a saved .part file is
            // still useful when the previous forced refresh was interrupted.
            resetPartial = false,
            onResponseMetadata = { connection, totalBytes ->
                onResponseMetadata(
                    connection.remoteMetadata(
                        url = url,
                        fileName = phoneOfflineRemoteFileName(url),
                        contentLengthOverride = totalBytes,
                    ),
                )
            },
            onProgress = onProgress,
        )
        if (!temporary.renameTo(archive)) {
            throw PhoneOfflineBundleDownloadException(PhoneOfflineBundleFailure.STORAGE)
        }
        return archive
    }

    private fun migrateLegacyArchiveFile(
        source: File,
        target: File,
    ) {
        if (!source.exists() || target.exists()) return
        runCatching {
            if (!source.renameTo(target)) {
                source.copyTo(target, overwrite = false)
                source.delete()
            }
        }
    }

    private suspend fun <T> extractExpectedEntry(
        archive: File,
        extension: String,
        phase: PhoneOfflineBundlePhase,
        onProgress: (PhoneOfflineBundleProgress) -> Unit,
        install: suspend (String, InputStream, (Long) -> Unit) -> T,
    ): T {
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                currentCoroutineContext().ensureActive()
                val fileName = expectedPhoneBundleArchiveEntryName(entry.name, extension)
                if (!entry.isDirectory && fileName != null) {
                    return installArchiveEntry(zip, entry, fileName, phase, onProgress, install)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        throw PhoneOfflineBundleDownloadException(PhoneOfflineBundleFailure.ARCHIVE)
    }

    @Suppress("LongParameterList") // Streaming extraction needs the archive entry, installer, and progress context.
    private suspend fun <T> installArchiveEntry(
        zip: ZipInputStream,
        entry: java.util.zip.ZipEntry,
        fileName: String,
        phase: PhoneOfflineBundlePhase,
        onProgress: (PhoneOfflineBundleProgress) -> Unit,
        install: suspend (String, InputStream, (Long) -> Unit) -> T,
    ): T {
        val totalBytes = entry.size.takeIf { it > 0L }
        onProgress(PhoneOfflineBundleProgress(phase = phase, totalBytes = totalBytes))
        return try {
            install(fileName, zip) { bytes ->
                onProgress(
                    PhoneOfflineBundleProgress(
                        phase = phase,
                        bytesDownloaded = bytes,
                        totalBytes = totalBytes,
                    ),
                )
            }
        } finally {
            zip.closeEntry()
        }
    }

    private suspend fun installPoi(
        fileName: String,
        input: InputStream,
        onBytesCopied: (Long) -> Unit,
        replaceExisting: Boolean,
    ): InstalledPoi {
        val directory = phoneMapPoiStorageDirectory(applicationContext)
        if (!directory.exists() && !directory.mkdirs()) {
            downloadFailure(PhoneOfflineBundleFailure.STORAGE)
        }
        val destination = File(directory, File(fileName).name)
        val temporary = File(directory, ".${destination.name}.bundle.part")
        var completed = false
        return try {
            temporary.outputStream().use { output ->
                input.copyWithProgress(output, totalBytes = null, onBytesCopied)
            }
            if (!isPhoneMapPoiFileValid(temporary)) {
                downloadFailure(PhoneOfflineBundleFailure.INVALID_POI)
            }
            when {
                isPhoneMapPoiFileValid(destination) && !replaceExisting ->
                    InstalledPoi(destination.name, reusedExisting = true)
                destination.exists() && !destination.delete() ->
                    downloadFailure(PhoneOfflineBundleFailure.STORAGE)
                !temporary.renameTo(destination) ->
                    downloadFailure(PhoneOfflineBundleFailure.STORAGE)
                else -> InstalledPoi(destination.name, reusedExisting = false)
            }.also { completed = true }
        } finally {
            if (completed && temporary.exists()) temporary.delete()
        }
    }

    private fun mapBounds(fileName: String): BoundingBox? {
        val map = mapStore.findValidBundleMap(fileName) ?: return null
        val mapFile = MapFile(map.file)
        return try {
            mapFile.boundingBox()
        } finally {
            runCatching { mapFile.close() }
        }
    }

    private suspend fun downloadRouting(
        bounds: BoundingBox,
        forceRefresh: Boolean,
        onProgress: (PhoneOfflineBundleProgress) -> Unit,
    ): List<String> {
        val bbox = bounds.asPhoneBbox()
        val result =
            routingDownloader.downloadForBbox(
                bboxInput = bbox,
                forceRefresh = forceRefresh,
                includeFileUris = false,
                reportProgress = { percent, status, detail ->
                    onProgress(
                        PhoneOfflineBundleProgress(
                            phase = PhoneOfflineBundlePhase.DOWNLOADING_ROUTING,
                            bytesDownloaded = percent.toLong(),
                            totalBytes = null,
                            detail = listOf(status, detail).filter(String::isNotBlank).joinToString(" · "),
                            percent = percent.coerceIn(0, 100),
                        ),
                    )
                },
            )
        return result.tileNames.filter { fileName ->
            val target = File(storage.routingDirectory(), File(fileName).name)
            isUsablePhoneRoutingFile(target)
        }
    }

    private suspend fun downloadDem(
        bounds: BoundingBox,
        source: PhoneOfflineDemSource,
        forceTileIds: Set<String>,
        onProgress: (PhoneOfflineBundleProgress) -> Unit,
    ): List<String> {
        val tileIds = phoneDemTileIdsForBounds(bounds)
        tileIds.forEachIndexed { index, tileId ->
            val target = phoneOfflineDemFile(storage.elevationDirectory(), source, tileId)
            onProgress(
                PhoneOfflineBundleProgress(
                    phase = PhoneOfflineBundlePhase.DOWNLOADING_DEM,
                    detail = "$tileId (${index + 1}/${tileIds.size})",
                ),
            )
            if (tileId.uppercase(Locale.ROOT) in forceTileIds || !isUsablePhoneDemFile(target)) {
                downloadFileToTarget(
                    url = source.remoteUrl(tileId),
                    target = target,
                    phase = PhoneOfflineBundlePhase.DOWNLOADING_DEM,
                    onProgress = onProgress,
                )
            }
        }
        return tileIds.filter { tileId ->
            isUsablePhoneDemFile(phoneOfflineDemFile(storage.elevationDirectory(), source, tileId))
        }
    }

    private suspend fun downloadFileToTarget(
        url: String,
        target: File,
        phase: PhoneOfflineBundlePhase,
        onProgress: (PhoneOfflineBundleProgress) -> Unit,
    ) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.part")
        downloadToTemporary(
            url = url,
            temporary = temporary,
            phase = phase,
            // Keep a partial DEM tile even when the completed tile is being replaced.
            resetPartial = false,
            onProgress = { progress ->
                onProgress(progress.copy(detail = target.name))
            },
        )
        if (!isUsablePhoneDemFile(temporary)) {
            temporary.delete()
            downloadFailure(PhoneOfflineBundleFailure.STORAGE)
        }
        target.delete()
        if (!temporary.renameTo(target)) {
            throw PhoneOfflineBundleDownloadException(PhoneOfflineBundleFailure.STORAGE)
        }
    }

    @Suppress("CyclomaticComplexMethod") // Handles the small HTTP response matrix needed for safe resume.
    private suspend fun downloadToTemporary(
        url: String,
        temporary: File,
        phase: PhoneOfflineBundlePhase,
        resetPartial: Boolean,
        onProgress: (PhoneOfflineBundleProgress) -> Unit,
        onResponseMetadata: ((HttpURLConnection, Long?) -> Unit)? = null,
    ): Long {
        if (resetPartial) temporary.delete()
        temporary.parentFile?.mkdirs()
        var rangeRestartCount = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val resumeOffset = temporary.length().takeIf { it > 0L } ?: 0L
            val connection = openConnection(url, resumeOffset)
            activeConnections += connection
            try {
                val responseCode = connection.responseCode
                if (resumeOffset > 0L && responseCode == HttpURLConnection.HTTP_OK) {
                    if (!temporary.delete()) {
                        throw PhoneOfflineBundleDownloadException(PhoneOfflineBundleFailure.STORAGE)
                    }
                    rangeRestartCount += 1
                    if (rangeRestartCount > MAX_RANGE_RESTARTS) {
                        throw PhoneOfflineBundleDownloadException(PhoneOfflineBundleFailure.HTTP)
                    }
                    continue
                }
                if (resumeOffset > 0L && responseCode == 416) {
                    if (!temporary.delete()) {
                        throw PhoneOfflineBundleDownloadException(PhoneOfflineBundleFailure.STORAGE)
                    }
                    rangeRestartCount += 1
                    if (rangeRestartCount > MAX_RANGE_RESTARTS) {
                        throw PhoneOfflineBundleDownloadException(PhoneOfflineBundleFailure.HTTP)
                    }
                    continue
                }
                if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                    throw PhoneOfflineBundleDownloadException(PhoneOfflineBundleFailure.HTTP)
                }
                val append = resumeOffset > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL
                val initialBytes = if (append) resumeOffset else 0L
                if (!append && resumeOffset > 0L && !temporary.delete()) {
                    throw PhoneOfflineBundleDownloadException(PhoneOfflineBundleFailure.STORAGE)
                }
                val responseBytes = connection.contentLengthLong.takeIf { it > 0L }
                val totalBytes = responseBytes?.let { it + initialBytes }
                onResponseMetadata?.invoke(connection, totalBytes)
                onProgress(
                    PhoneOfflineBundleProgress(
                        phase = phase,
                        bytesDownloaded = initialBytes,
                        totalBytes = totalBytes,
                    ),
                )
                connection.inputStream.use { input ->
                    FileOutputStream(temporary, append).use { output ->
                        input.copyWithProgress(output, responseBytes) { bytes ->
                            onProgress(
                                PhoneOfflineBundleProgress(
                                    phase = phase,
                                    bytesDownloaded = initialBytes + bytes,
                                    totalBytes = totalBytes,
                                ),
                            )
                        }
                    }
                }
                if (totalBytes != null && temporary.length() != totalBytes) {
                    throw IOException(
                        "Incomplete download for ${temporary.name}: ${temporary.length()}/$totalBytes bytes.",
                    )
                }
                return temporary.length()
            } finally {
                activeConnections -= connection
                connection.disconnect()
            }
        }
    }

    private suspend fun fetchRemoteMetadataOrNull(
        request: PhoneOfflineRemoteFileRequest,
    ): PhoneOfflineRemoteFileMetadata? =
        runCatching {
            withContext(Dispatchers.IO) {
                val connection =
                    (URI(request.url).toURL().openConnection() as HttpURLConnection).apply {
                        requestMethod = "HEAD"
                        connectTimeout = CONNECT_TIMEOUT_MILLIS
                        readTimeout = READ_TIMEOUT_MILLIS
                        instanceFollowRedirects = true
                        useCaches = false
                        setRequestProperty("Accept-Encoding", "identity")
                        setRequestProperty("User-Agent", USER_AGENT)
                    }
                activeConnections += connection
                try {
                    val responseCode = connection.responseCode
                    if (responseCode !in 200..399) throw IOException("HTTP $responseCode for ${request.url}")
                    connection.remoteMetadata(request.url, request.fileName)
                } finally {
                    activeConnections -= connection
                    connection.disconnect()
                }
            }
        }.getOrNull()

    private fun HttpURLConnection.remoteMetadata(
        url: String,
        fileName: String,
        contentLengthOverride: Long? = null,
    ): PhoneOfflineRemoteFileMetadata =
        PhoneOfflineRemoteFileMetadata(
            url = url,
            fileName = fileName,
            entityTag = getHeaderField("ETag")?.takeIf(String::isNotBlank),
            lastModifiedMillis = getHeaderFieldDate("Last-Modified", -1L).takeIf { it >= 0L },
            contentLengthBytes = contentLengthOverride ?: contentLengthLong.takeIf { it > 0L },
        )

    private fun openConnection(
        url: String,
        resumeOffset: Long = 0L,
    ): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", USER_AGENT)
            if (resumeOffset > 0L) setRequestProperty("Range", "bytes=$resumeOffset-")
        }

    private data class InstalledMap(
        val fileName: String,
        val reusedExisting: Boolean,
    )

    private data class InstalledPoi(
        val fileName: String,
        val reusedExisting: Boolean,
    )

    private companion object {
        const val MAP_EXTENSION = ".map"
        const val POI_EXTENSION = ".poi"
        const val CONNECT_TIMEOUT_MILLIS = 20_000
        const val READ_TIMEOUT_MILLIS = 30_000
        const val USER_AGENT = "GlanceMap-Android-OAM-Downloader/1.0 https://www.openandromaps.org"
        const val MAX_RANGE_RESTARTS = 2
    }
}

internal fun expectedPhoneBundleArchiveEntryName(
    entryName: String,
    expectedExtension: String,
): String? {
    val normalized = entryName.replace('\\', '/')
    if (normalized.startsWith('/') || normalized.split('/').any { it == ".." }) return null
    val fileName = normalized.substringAfterLast('/')
    return fileName.takeIf {
        it.isNotBlank() &&
            it.endsWith(expectedExtension, ignoreCase = true) &&
            File(it).name == it
    }
}

private class PhoneOfflineBundleDownloadException(
    val reason: PhoneOfflineBundleFailure,
) : IOException()

private fun Throwable.toPhoneOfflineBundleFailureDetail(): String =
    when (this) {
        is BRouterTileDownloadException -> userMessage
        else -> ""
    }

private fun downloadFailure(
    reason: PhoneOfflineBundleFailure,
): Nothing = throw PhoneOfflineBundleDownloadException(reason)

private fun PhoneOfflineMapError.toMapFailure(): PhoneOfflineBundleFailure =
    when (this) {
        PhoneOfflineMapError.INVALID,
        PhoneOfflineMapError.FILE_NOT_MAP,
        -> PhoneOfflineBundleFailure.INVALID_MAP
        PhoneOfflineMapError.COPY_FAILED,
        PhoneOfflineMapError.FILE_NOT_READABLE,
        -> PhoneOfflineBundleFailure.STORAGE
        else -> PhoneOfflineBundleFailure.INVALID_MAP
    }

private suspend fun InputStream.copyWithProgress(
    output: OutputStream,
    totalBytes: Long?,
    onBytesCopied: (Long) -> Unit,
) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var copied = 0L
    var lastReported = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = read(buffer)
        if (count < 0) break
        output.write(buffer, 0, count)
        copied += count
        if (copied - lastReported >= PROGRESS_STEP_BYTES || copied == totalBytes) {
            lastReported = copied
            onBytesCopied(copied)
        }
    }
    if (copied != lastReported) onBytesCopied(copied)
}

private const val PROGRESS_STEP_BYTES = 256L * 1024L

private fun BoundingBox.asPhoneBbox(): String =
    listOf(minLongitude, minLatitude, maxLongitude, maxLatitude)
        .joinToString(",") { value -> "%.5f".format(Locale.US, value) }
