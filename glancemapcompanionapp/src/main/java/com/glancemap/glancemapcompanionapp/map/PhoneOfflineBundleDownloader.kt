package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Collections
import java.util.zip.ZipInputStream

/** Phone-only OAM map+POI downloader; background-service behavior is intentionally deferred. */
internal class PhoneOfflineBundleDownloader(
    context: Context,
    private val mapStore: PhoneOfflineMapStore = PhoneOfflineMapStore(context),
    private val bundleStore: PhoneOfflineBundleStore = PhoneOfflineBundleStore(context),
) {
    private val applicationContext = context.applicationContext
    private val activeConnections = Collections.synchronizedSet(mutableSetOf<HttpURLConnection>())

    suspend fun download(
        selection: PhoneOfflineBundleSelection,
        onProgress: (PhoneOfflineBundleProgress) -> Unit,
    ): PhoneOfflineBundleOutcome =
        withContext(Dispatchers.IO) {
            try {
                downloadSelection(selection, onProgress)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: PhoneOfflineBundleDownloadException) {
                PhoneOfflineBundleOutcome.Failure(exception.reason)
            } catch (_: IOException) {
                if (!currentCoroutineContext().isActive) throw CancellationException()
                PhoneOfflineBundleOutcome.Failure(PhoneOfflineBundleFailure.NETWORK)
            } catch (_: Exception) {
                PhoneOfflineBundleOutcome.Failure(PhoneOfflineBundleFailure.STORAGE)
            }
        }

    fun cancelActiveDownloads() {
        val connections = synchronized(activeConnections) { activeConnections.toList() }
        connections.forEach { connection -> runCatching(connection::disconnect) }
    }

    private suspend fun downloadSelection(
        selection: PhoneOfflineBundleSelection,
        onProgress: (PhoneOfflineBundleProgress) -> Unit,
    ): PhoneOfflineBundleOutcome.Success {
        val area = selection.area
        val existing = bundleStore.find(area.id)
        val existingMap = existing?.mapFileName?.let(mapStore::findValidBundleMap)
        val existingPoi =
            existing
                ?.poiFileName
                ?.let { fileName -> File(phoneMapPoiStorageDirectory(applicationContext), File(fileName).name) }
                ?.takeIf(::isPhoneMapPoiFileValid)

        if (existing != null && existingMap != null && existingPoi != null) {
            return PhoneOfflineBundleOutcome.Success(existing, reusedMap = true, reusedPoi = true)
        }

        val mapResult =
            existingMap?.let { InstalledMap(it.displayName, reusedExisting = true) }
                ?: downloadAndInstallMap(area.mapZipUrl, area.id, onProgress)
        val poiResult =
            existingPoi?.let { InstalledPoi(it.name, reusedExisting = true) }
                ?: downloadAndInstallPoi(area.poiZipUrl, area.id, onProgress)

        val bundle =
            PhoneInstalledBundle(
                areaId = area.id,
                areaLabel = area.region,
                mapFileName = mapResult.fileName,
                poiFileName = poiResult.fileName,
                installedAtMillis = System.currentTimeMillis(),
            )
        bundleStore.upsert(bundle)
        return PhoneOfflineBundleOutcome.Success(
            bundle = bundle,
            reusedMap = mapResult.reusedExisting,
            reusedPoi = poiResult.reusedExisting,
        )
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
                throw PhoneOfflineBundleDownloadException(PhoneOfflineBundleFailure.HTTP)
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
