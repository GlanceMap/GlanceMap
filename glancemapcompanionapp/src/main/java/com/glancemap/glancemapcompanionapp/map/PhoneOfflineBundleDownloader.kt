package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import com.glancemap.glancemapcompanionapp.refuges.RefugesGeoJsonPoiImporter
import com.glancemap.glancemapcompanionapp.routing.BRouterTileDownloader
import com.glancemap.glancemapcompanionapp.routing.BRouterTileMath
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
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Collections
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.math.floor

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
    private val activeConnections = Collections.synchronizedSet(mutableSetOf<HttpURLConnection>())
    private val refugesImporter = RefugesGeoJsonPoiImporter(applicationContext)

    @Volatile
    private var activeRecovery: PhoneOfflineBundleRecovery? = null

    suspend fun download(
        selection: PhoneOfflineBundleSelection,
        onProgress: (PhoneOfflineBundleProgress) -> Unit,
    ): PhoneOfflineBundleOutcome =
        withContext(Dispatchers.IO) {
            try {
                downloadSelection(selection, onProgress)
            } catch (exception: CancellationException) {
                saveRecoveryFailure(PhoneOfflineBundleFailure.CANCELLED)
                throw exception
            } catch (exception: PhoneOfflineBundleDownloadException) {
                saveRecoveryFailure(exception.reason)
                PhoneOfflineBundleOutcome.Failure(exception.reason)
            } catch (_: IOException) {
                if (!currentCoroutineContext().isActive) {
                    saveRecoveryFailure(PhoneOfflineBundleFailure.CANCELLED)
                    throw CancellationException()
                }
                saveRecoveryFailure(PhoneOfflineBundleFailure.NETWORK)
                PhoneOfflineBundleOutcome.Failure(PhoneOfflineBundleFailure.NETWORK)
            } catch (_: Exception) {
                saveRecoveryFailure(PhoneOfflineBundleFailure.STORAGE)
                PhoneOfflineBundleOutcome.Failure(PhoneOfflineBundleFailure.STORAGE)
            } finally {
                activeRecovery = null
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
        "ThrowsCount",
        "TooGenericExceptionCaught",
    ) // Coordinates optional map, routing, DEM, and Refuges stages.
    private suspend fun downloadSelection(
        selection: PhoneOfflineBundleSelection,
        onProgress: (PhoneOfflineBundleProgress) -> Unit,
    ): PhoneOfflineBundleOutcome.Success {
        val area = selection.area
        val existing = bundleStore.find(area.id)
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
            )

        fun checkpoint(update: PhoneOfflineBundleRecovery) {
            recoveryState = update.copy(updatedAtMillis = System.currentTimeMillis(), failure = null)
            activeRecovery = recoveryState
            bundleStore.saveRecovery(recoveryState)
        }
        checkpoint(recoveryState)

        val existingMap = existing?.mapFileName?.let(mapStore::findValidBundleMap)
        val existingPoi =
            existing
                ?.poiFileName
                ?.let { fileName -> File(phoneMapPoiStorageDirectory(applicationContext), File(fileName).name) }
                ?.takeIf(::isPhoneMapPoiFileValid)

        val mapResult =
            existingMap?.let { InstalledMap(it.displayName, reusedExisting = true) }
                ?: if (selection.includeMap) {
                    downloadAndInstallMap(area.mapZipUrl, area.id, onProgress)
                } else {
                    null
                }
        val mapFileName = mapResult?.fileName ?: existing?.mapFileName
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
            existingPoi?.let { InstalledPoi(it.name, reusedExisting = true) }
                ?: if (selection.includePoi) {
                    downloadAndInstallPoi(area.poiZipUrl, area.id, onProgress)
                } else {
                    null
                }
        val poiFileName = poiResult?.fileName ?: existing?.poiFileName
        checkpoint(
            recoveryState.copy(
                phase = PhoneOfflineBundlePhase.DOWNLOADING_ROUTING,
                mapFileName = mapFileName,
                poiFileName = poiFileName,
            ),
        )
        val storedMapFileName =
            checkNotNull(mapResult?.fileName ?: existingMap?.displayName) {
                "A map is required for a phone bundle."
            }
        val storedPoiFileName =
            checkNotNull(poiResult?.fileName ?: existingPoi?.name) {
                "POI must be installed for a phone bundle."
            }

        val mapBounds = mapBounds(storedMapFileName)
        val routingExpected =
            if (selection.includeRouting) {
                val bounds = checkNotNull(mapBounds) { "Cannot read map bounds for routing." }
                routingFileNamesForBounds(bounds)
            } else {
                existing?.routingFileNames.orEmpty()
            }
        val demExpected =
            if (selection.includeDem) {
                val bounds = checkNotNull(mapBounds) { "Cannot read map bounds for elevation." }
                demTileIdsForBounds(bounds)
            } else {
                existing?.demTileIds.orEmpty()
            }
        val demSource =
            if (selection.includeDem) selection.demSource else existing?.demSource ?: selection.demSource
        checkpoint(
            recoveryState.copy(
                phase =
                    if (selection.includeRouting) {
                        PhoneOfflineBundlePhase.DOWNLOADING_ROUTING
                    } else {
                        PhoneOfflineBundlePhase.DOWNLOADING_DEM
                    },
                routingFileNames = routingExpected,
                downloadedRoutingFileNames = existing?.downloadedRoutingFileNames.orEmpty(),
                demTileIds = demExpected,
                downloadedDemTileIds = existing?.downloadedDemTileIds.orEmpty(),
                demSource = demSource,
            ),
        )
        val routingFileNames =
            if (selection.includeRouting) {
                val bounds = checkNotNull(mapBounds) { "Cannot read map bounds for routing." }
                try {
                    downloadRouting(bounds, onProgress).also { downloaded ->
                        check(downloaded.containsAll(routingExpected)) { "Routing bundle is incomplete." }
                        checkpoint(
                            recoveryState.copy(
                                phase = PhoneOfflineBundlePhase.DOWNLOADING_DEM,
                                downloadedRoutingFileNames = downloaded,
                            ),
                        )
                    }
                } catch (error: Throwable) {
                    checkpoint(
                        recoveryState.copy(
                            phase = PhoneOfflineBundlePhase.DOWNLOADING_ROUTING,
                            downloadedRoutingFileNames = availableRoutingFiles(routingExpected),
                            detail = error.message.orEmpty(),
                        ),
                    )
                    throw error
                }
            } else {
                existing?.routingFileNames.orEmpty()
            }
        val downloadedRoutingFileNames =
            if (selection.includeRouting) routingFileNames else existing?.downloadedRoutingFileNames.orEmpty()
        val demTileIds =
            if (selection.includeDem) {
                val bounds = checkNotNull(mapBounds) { "Cannot read map bounds for elevation." }
                try {
                    downloadDem(bounds, demSource, onProgress).also { downloaded ->
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
                existing?.demTileIds.orEmpty()
            }
        val downloadedDemTileIds =
            if (selection.includeDem) demTileIds else existing?.downloadedDemTileIds.orEmpty()

        val existingRefugesInfo =
            existing
                ?.refugesInfoFileName
                ?.let { fileName -> File(phoneMapPoiStorageDirectory(applicationContext), File(fileName).name) }
                ?.takeIf(::isPhoneMapPoiFileValid)
        val refugesInfoFileName =
            if (selection.includeRefugesInfo) {
                val bounds = checkNotNull(mapBounds) { "Cannot read map bounds for Refuges.info." }
                checkpoint(
                    recoveryState.copy(
                        phase = PhoneOfflineBundlePhase.DOWNLOADING_REFUGES,
                        downloadedRoutingFileNames = downloadedRoutingFileNames,
                        downloadedDemTileIds = downloadedDemTileIds,
                    ),
                )
                try {
                    existingRefugesInfo?.name
                        ?: refugesImporter
                            .importFromBbox(
                                bboxInput = bounds.asPhoneBbox(),
                                fileNameInput = "${area.id}.refuges-info.poi",
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
            )
        val completedBundle = bundle.copy(integrity = phoneOfflineBundleIntegrity(applicationContext, bundle))
        bundleStore.upsert(completedBundle)
        bundleStore.clearRecovery(area.id)
        return PhoneOfflineBundleOutcome.Success(
            bundle = completedBundle,
            reusedMap = mapResult?.reusedExisting == true,
            reusedPoi = poiResult?.reusedExisting == true,
        )
    }

    private fun saveRecoveryFailure(reason: PhoneOfflineBundleFailure) {
        activeRecovery?.let { recovery ->
            bundleStore.saveRecovery(
                recovery.copy(
                    failure = reason,
                    detail = recovery.detail.ifBlank { reason.name },
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun routingFileNamesForBounds(bounds: BoundingBox): List<String> {
        val bbox = bounds.asPhoneBbox()
        return BRouterTileMath.tileFileNamesForBbox(bbox)
    }

    private fun demTileIdsForBounds(bounds: BoundingBox): List<String> =
        buildList {
            for (latitude in demTileRange(bounds.minLatitude, bounds.maxLatitude, -90, 89)) {
                for (longitude in demTileRange(bounds.minLongitude, bounds.maxLongitude, -180, 179)) {
                    add(phoneDemTileId(latitude, longitude))
                }
            }
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

    private suspend fun downloadAndInstallMap(
        url: String,
        areaId: String,
        onProgress: (PhoneOfflineBundleProgress) -> Unit,
    ): InstalledMap {
        val archive = downloadArchive(url, "$areaId-map.zip", PhoneOfflineBundlePhase.DOWNLOADING_MAP, onProgress)
        return try {
            extractExpectedEntry(
                archive = archive,
                extension = MAP_EXTENSION,
                phase = PhoneOfflineBundlePhase.INSTALLING_MAP,
                onProgress = onProgress,
            ) { fileName, input, entryProgress ->
                when (val result = mapStore.installBundleMap(fileName, input, entryProgress)) {
                    is PhoneOfflineMapBundleInstallResult.Success ->
                        InstalledMap(result.map.displayName, result.reusedExisting)
                    is PhoneOfflineMapBundleInstallResult.Failure ->
                        throw PhoneOfflineBundleDownloadException(result.error.toMapFailure())
                }
            }
        } finally {
            archive.delete()
        }
    }

    private suspend fun downloadAndInstallPoi(
        url: String,
        areaId: String,
        onProgress: (PhoneOfflineBundleProgress) -> Unit,
    ): InstalledPoi {
        val archive = downloadArchive(url, "$areaId-poi.zip", PhoneOfflineBundlePhase.DOWNLOADING_POI, onProgress)
        return try {
            extractExpectedEntry(
                archive = archive,
                extension = POI_EXTENSION,
                phase = PhoneOfflineBundlePhase.INSTALLING_POI,
                onProgress = onProgress,
            ) { fileName, input, entryProgress ->
                installPoi(fileName, input, entryProgress)
            }
        } finally {
            archive.delete()
        }
    }

    private suspend fun downloadArchive(
        url: String,
        fileName: String,
        phase: PhoneOfflineBundlePhase,
        onProgress: (PhoneOfflineBundleProgress) -> Unit,
    ): File {
        val archive = File(applicationContext.cacheDir, File(fileName).name)
        val temporary = File(applicationContext.cacheDir, ".${archive.name}.part")
        archive.delete()
        temporary.delete()

        val connection = openConnection(url)
        activeConnections += connection
        var completed = false
        try {
            val responseCode = connection.responseCode
            if (responseCode !in HttpURLConnection.HTTP_OK..HttpURLConnection.HTTP_PARTIAL) {
                downloadFailure(PhoneOfflineBundleFailure.HTTP)
            }
            val totalBytes = connection.contentLengthLong.takeIf { it > 0L }
            onProgress(PhoneOfflineBundleProgress(phase = phase, totalBytes = totalBytes))
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    input.copyWithProgress(output, totalBytes) { bytes ->
                        onProgress(
                            PhoneOfflineBundleProgress(
                                phase = phase,
                                bytesDownloaded = bytes,
                                totalBytes = totalBytes,
                            ),
                        )
                    }
                }
            }
            if (!temporary.renameTo(archive)) {
                throw PhoneOfflineBundleDownloadException(PhoneOfflineBundleFailure.STORAGE)
            }
            completed = true
            return archive
        } finally {
            activeConnections -= connection
            connection.disconnect()
            if (!completed) temporary.delete()
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
    ): InstalledPoi {
        val directory = phoneMapPoiStorageDirectory(applicationContext)
        if (!directory.exists() && !directory.mkdirs()) {
            downloadFailure(PhoneOfflineBundleFailure.STORAGE)
        }
        val destination = File(directory, File(fileName).name)
        val temporary = File(directory, ".${destination.name}.bundle.part")
        return try {
            temporary.outputStream().use { output ->
                input.copyWithProgress(output, totalBytes = null, onBytesCopied)
            }
            if (!isPhoneMapPoiFileValid(temporary)) {
                downloadFailure(PhoneOfflineBundleFailure.INVALID_POI)
            }
            when {
                isPhoneMapPoiFileValid(destination) -> InstalledPoi(destination.name, reusedExisting = true)
                destination.exists() && !destination.delete() ->
                    downloadFailure(PhoneOfflineBundleFailure.STORAGE)
                !temporary.renameTo(destination) ->
                    downloadFailure(PhoneOfflineBundleFailure.STORAGE)
                else -> InstalledPoi(destination.name, reusedExisting = false)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
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
        onProgress: (PhoneOfflineBundleProgress) -> Unit,
    ): List<String> {
        val bbox = bounds.asPhoneBbox()
        val expectedFileNames = routingFileNamesForBounds(bounds)
        val forceRefresh =
            expectedFileNames.any { fileName ->
                val target = File(storage.routingDirectory(), File(fileName).name)
                target.isFile && !isUsablePhoneRoutingFile(target)
            }
        val result =
            routingDownloader.downloadForBbox(
                bboxInput = bbox,
                forceRefresh = forceRefresh,
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
        onProgress: (PhoneOfflineBundleProgress) -> Unit,
    ): List<String> {
        val tileIds = demTileIdsForBounds(bounds)
        tileIds.forEachIndexed { index, tileId ->
            val target = phoneOfflineDemFile(storage.elevationDirectory(), source, tileId)
            onProgress(
                PhoneOfflineBundleProgress(
                    phase = PhoneOfflineBundlePhase.DOWNLOADING_DEM,
                    detail = "$tileId (${index + 1}/${tileIds.size})",
                ),
            )
            if (!isUsablePhoneDemFile(target)) {
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

    private fun demTileRange(
        minimum: Double,
        maximum: Double,
        minimumTile: Int,
        maximumTile: Int,
    ): IntRange {
        val adjustedMaximum = if (maximum <= minimum) minimum + 1e-9 else maximum
        val firstTile = floor(minimum).toInt().coerceIn(minimumTile, maximumTile)
        val lastTile = floor(Math.nextDown(adjustedMaximum)).toInt().coerceIn(minimumTile, maximumTile)
        return firstTile..lastTile
    }

    private suspend fun downloadFileToTarget(
        url: String,
        target: File,
        phase: PhoneOfflineBundlePhase,
        onProgress: (PhoneOfflineBundleProgress) -> Unit,
    ) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.part")
        temporary.delete()
        val connection = openConnection(url)
        activeConnections += connection
        try {
            val responseCode = connection.responseCode
            if (responseCode !in HttpURLConnection.HTTP_OK..HttpURLConnection.HTTP_PARTIAL) {
                throw PhoneOfflineBundleDownloadException(PhoneOfflineBundleFailure.HTTP)
            }
            val totalBytes = connection.contentLengthLong.takeIf { it > 0L }
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    input.copyWithProgress(output, totalBytes) { bytes ->
                        onProgress(
                            PhoneOfflineBundleProgress(
                                phase = phase,
                                bytesDownloaded = bytes,
                                totalBytes = totalBytes,
                                detail = target.name,
                            ),
                        )
                    }
                }
            }
            if (!isUsablePhoneDemFile(temporary)) downloadFailure(PhoneOfflineBundleFailure.STORAGE)
            target.delete()
            if (!temporary.renameTo(target)) {
                downloadFailure(PhoneOfflineBundleFailure.STORAGE)
            }
        } finally {
            activeConnections -= connection
            connection.disconnect()
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", USER_AGENT)
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
