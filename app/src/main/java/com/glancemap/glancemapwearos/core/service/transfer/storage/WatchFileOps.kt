package com.glancemap.glancemapwearos.core.service.transfer.storage
import android.content.Context
import android.os.Process
import android.util.Log
import com.glancemap.glancemapwearos.GlanceMapWearApp
import com.glancemap.glancemapwearos.core.maps.Dem3CoverageUtils
import com.glancemap.glancemapwearos.core.maps.DemSignatureStore
import com.glancemap.glancemapwearos.core.maps.DemSource
import com.glancemap.glancemapwearos.core.routing.isRoutingSegmentFileName
import com.glancemap.glancemapwearos.core.routing.routingSegmentBounds
import com.glancemap.glancemapwearos.core.routing.routingSegmentPartFile
import com.glancemap.glancemapwearos.core.routing.routingSegmentTargetFile
import com.glancemap.glancemapwearos.core.routing.routingSegmentsDir
import com.glancemap.glancemapwearos.data.repository.internal.AtomicStreamWriter
import kotlinx.coroutines.CancellationException
import org.mapsforge.map.reader.MapFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale

internal data class WatchMapWithBounds(
    val fileName: String,
    val absolutePath: String,
    val bbox: String,
)

internal data class WatchPoiWithBounds(
    val fileName: String,
    val absolutePath: String,
    val bbox: String,
)

internal data class WatchRoutingSegmentWithBounds(
    val fileName: String,
    val absolutePath: String,
    val bbox: String,
)

internal class WatchFileOps(
    private val app: GlanceMapWearApp,
) {
    companion object {
        private const val CHECKSUM_READ_BUFFER_SIZE = 2 * 1024 * 1024
        private const val CHECKSUM_PROGRESS_STEP_BYTES = 8L * 1024L * 1024L
        private val DEM_TILE_ID_REGEX = Regex("^([NS]\\d{2}[EW]\\d{3})$", RegexOption.IGNORE_CASE)
    }

    private val container get() = app.container

    fun isSupportedTransferFileName(fileName: String): Boolean =
        fileName.endsWith(".gpx", ignoreCase = true) ||
            fileName.endsWith(".map", ignoreCase = true) ||
            fileName.endsWith(".poi", ignoreCase = true) ||
            isRoutingSegmentFileName(fileName) ||
            isDemFileName(fileName)

    suspend fun fileExistsOnWatch(fileName: String): Boolean =
        when {
            fileName.endsWith(".gpx", ignoreCase = true) -> container.gpxRepository.fileExists(fileName)
            fileName.endsWith(".map", ignoreCase = true) -> container.mapRepository.fileExists(fileName)
            fileName.endsWith(".poi", ignoreCase = true) -> container.poiRepository.fileExists(fileName)
            isRoutingSegmentFileName(fileName) -> routingSegmentTargetFile(app.applicationContext, fileName).exists()
            isDemFileName(fileName) -> demTargetFileForName(fileName).exists()
            else -> false
        }

    suspend fun fileBlocksIncomingTransfer(name: String): Boolean = fileExistsOnWatch(name) && !isReplaceable(name)

    fun isReplaceableTransferFileName(fileName: String): Boolean = isReplaceable(fileName)

    private fun isReplaceable(fileName: String): Boolean = isRoutingSegmentFileName(fileName)

    suspend fun deleteLocalFile(fileName: String) {
        runCatching {
            when {
                fileName.endsWith(".gpx", ignoreCase = true) -> container.gpxRepository.deleteGpxFile(fileName)
                fileName.endsWith(".map", ignoreCase = true) -> {
                    val safeName = sanitizeFileName(fileName)
                    val mapPath = File(app.getDir("maps", Context.MODE_PRIVATE), safeName).absolutePath
                    cleanupDemForMapPath(mapPath)
                    container.mapRepository.deleteMapFile(mapPath)
                }
                fileName.endsWith(".poi", ignoreCase = true) -> {
                    val safeName = sanitizeFileName(fileName)
                    val poiPath = File(app.getDir("poi", Context.MODE_PRIVATE), safeName).absolutePath
                    container.poiRepository.deletePoiFile(poiPath)
                    container.syncManager.requestPoiSync()
                }
                isRoutingSegmentFileName(fileName) -> {
                    val target = routingSegmentTargetFile(app.applicationContext, fileName)
                    if (target.exists()) target.delete()
                    routingSegmentPartFile(app.applicationContext, fileName).delete()
                    container.syncManager.requestMapSync()
                }
                isDemFileName(fileName) -> {
                    val target = demTargetFileForName(fileName)
                    if (target.exists()) target.delete()
                    demPartFileForName(fileName).delete()
                    DemSignatureStore.markDirty(app.applicationContext)
                }
            }
        }
    }

    suspend fun listMapFilesWithBounds(): List<WatchMapWithBounds> {
        return container.mapRepository
            .listMapFiles()
            .mapNotNull { file ->
                val bbox =
                    runCatching {
                        val mapFile = MapFile(file)
                        try {
                            mapFile.boundingBox()
                        } finally {
                            runCatching { mapFile.close() }
                        }
                    }.getOrNull() ?: return@mapNotNull null

                WatchMapWithBounds(
                    fileName = file.name,
                    absolutePath = file.absolutePath,
                    bbox = "${bbox.minLongitude},${bbox.minLatitude},${bbox.maxLongitude},${bbox.maxLatitude}",
                )
            }.sortedBy { it.fileName.lowercase(Locale.ROOT) }
    }

    suspend fun listPoiFilesWithBounds(): List<WatchPoiWithBounds> {
        return container.poiRepository
            .listPoiFiles()
            .mapNotNull { file ->
                val bounds =
                    runCatching { container.poiRepository.readCoverageBounds(file.absolutePath) }
                        .getOrNull()
                        ?: return@mapNotNull null

                WatchPoiWithBounds(
                    fileName = file.name,
                    absolutePath = file.absolutePath,
                    bbox = "${bounds.minLon},${bounds.minLat},${bounds.maxLon},${bounds.maxLat}",
                )
            }.sortedBy { it.fileName.lowercase(Locale.ROOT) }
    }

    fun listRoutingSegmentsWithBounds(): List<WatchRoutingSegmentWithBounds> {
        return routingSegmentsDir(app.applicationContext)
            .listFiles()
            ?.asSequence()
            ?.filter { it.isFile && isRoutingSegmentFileName(it.name) }
            ?.mapNotNull { file ->
                val bounds = routingSegmentBounds(file.name) ?: return@mapNotNull null
                WatchRoutingSegmentWithBounds(
                    fileName = file.name,
                    absolutePath = file.absolutePath,
                    bbox = "${bounds.minLon},${bounds.minLat},${bounds.maxLon},${bounds.maxLat}",
                )
            }?.sortedBy { it.fileName.lowercase(Locale.ROOT) }
            ?.toList()
            .orEmpty()
    }

    /**
     * Save with expected-size enforcement and stable ".part" resume support.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList", "ThrowsCount")
    suspend fun saveFile(
        fileName: String,
        inputStream: InputStream,
        expectedSize: Long?,
        resumeOffset: Long = 0L,
        keepPartialOnFailure: Boolean = false,
        computeSha256: Boolean = true,
        diagnosticContext: String? = null,
        onProgress: (Long) -> Unit,
    ): String? {
        try {
            return when {
                fileName.endsWith(".gpx", ignoreCase = true) -> {
                    val sha256 =
                        container.gpxRepository.saveGpxFileAtomic(
                            fileName = fileName,
                            inputStream = inputStream,
                            onProgress = onProgress,
                            expectedSize = expectedSize,
                            resumeOffset = resumeOffset,
                            diagnosticContext = diagnosticContext,
                        )
                    container.syncManager.requestGpxSync()
                    sha256
                }

                fileName.endsWith(".map", ignoreCase = true) -> {
                    val sha256 =
                        container.mapRepository.saveMapFileAtomic(
                            fileName = fileName,
                            inputStream = inputStream,
                            onProgress = onProgress,
                            expectedSize = expectedSize,
                            resumeOffset = resumeOffset,
                            computeSha256 = computeSha256,
                            diagnosticContext = diagnosticContext,
                        )
                    container.syncManager.requestMapSync()
                    sha256
                }

                fileName.endsWith(".poi", ignoreCase = true) -> {
                    val sha256 =
                        container.poiRepository.savePoiFileAtomic(
                            fileName = fileName,
                            inputStream = inputStream,
                            onProgress = onProgress,
                            expectedSize = expectedSize,
                            resumeOffset = resumeOffset,
                            diagnosticContext = diagnosticContext,
                        )
                    container.syncManager.requestPoiSync()
                    sha256
                }

                isRoutingSegmentFileName(fileName) -> {
                    val target = routingSegmentTargetFile(app.applicationContext, fileName)
                    val options =
                        AtomicStreamWriter.Options(
                            bufferSize = 2 * 1024 * 1024,
                            progressStepBytes = 8L * 1024 * 1024,
                            fsync = true,
                            failIfExists = false,
                            expectedSize = expectedSize?.takeIf { it > 0L },
                            requireExactSize = expectedSize != null && expectedSize > 0L,
                            resumeOffset = resumeOffset.coerceAtLeast(0L),
                            keepPartialOnCancel = true,
                            keepPartialOnFailure = true,
                            computeSha256 = computeSha256,
                            diagnosticContext = diagnosticContext,
                        )
                    val result =
                        AtomicStreamWriter.writeAtomic(
                            dir = target.parentFile ?: routingSegmentsDir(app.applicationContext),
                            fileName = target.name,
                            inputStream = inputStream,
                            onProgress = onProgress,
                            options = options,
                        )
                    container.syncManager.requestMapSync()
                    result.sha256
                }

                isDemFileName(fileName) -> {
                    val target = demTargetFileForName(fileName)
                    val options =
                        AtomicStreamWriter.Options(
                            bufferSize = 2 * 1024 * 1024,
                            progressStepBytes = 8L * 1024 * 1024,
                            fsync = true,
                            failIfExists = false,
                            expectedSize = expectedSize?.takeIf { it > 0L },
                            requireExactSize = expectedSize != null && expectedSize > 0L,
                            resumeOffset = resumeOffset.coerceAtLeast(0L),
                            keepPartialOnCancel = true,
                            keepPartialOnFailure = true,
                            computeSha256 = computeSha256,
                            diagnosticContext = diagnosticContext,
                        )
                    val result =
                        AtomicStreamWriter.writeAtomic(
                            dir = target.parentFile ?: demRootDir(),
                            fileName = target.name,
                            inputStream = inputStream,
                            onProgress = onProgress,
                            options = options,
                        )
                    // Triggers map layer refresh path; renderer will pick DEM changes from dem3 folder.
                    DemSignatureStore.markDirty(app.applicationContext)
                    container.syncManager.requestMapSync()
                    result.sha256
                }

                else -> throw IllegalArgumentException("Unsupported file type: $fileName")
            }
        } catch (ce: CancellationException) {
            // Keep hidden ".part" for resume support.
            throw ce
        } catch (ioe: IOException) {
            if (keepPartialOnFailure) {
                Log.w("WatchFileOps", "Recoverable IO failure. Keeping partial for resume: $fileName", ioe)
                throw ioe
            }
            Log.e("WatchFileOps", "IO error saving file. Cleaning up partial: $fileName", ioe)
            deleteLocalFile(fileName)
            throw ioe
        } catch (e: Exception) {
            Log.e("WatchFileOps", "Error saving file. Cleaning up partial: $fileName", e)
            deleteLocalFile(fileName)
            throw e
        }
    }

    fun getPartialSize(fileName: String): Long {
        val safeName = sanitizeFileName(fileName)
        val part = partFileForName(safeName) ?: return 0L
        return if (part.exists()) part.length() else 0L
    }

    fun deletePartial(fileName: String): Boolean {
        val safeName = sanitizeFileName(fileName)
        val part = partFileForName(safeName) ?: return false
        return !part.exists() || part.delete()
    }

    fun truncatePartial(
        fileName: String,
        expectedSize: Long,
    ): Boolean {
        if (expectedSize < 0L) return false
        val safeName = sanitizeFileName(fileName)
        val part = partFileForName(safeName) ?: return false
        if (!part.exists()) return false
        return runCatching {
            RandomAccessFile(part, "rw").use { raf ->
                raf.setLength(expectedSize)
            }
            true
        }.getOrDefault(false)
    }

    fun computePartialFileSha256(fileName: String): String? {
        val safeName = sanitizeFileName(fileName)
        val file = partFileForName(safeName) ?: return null
        if (!file.exists() || !file.isFile) return null
        return computeSha256(file)
    }

    suspend fun promotePartialToFinal(fileName: String): Boolean {
        val safeName = sanitizeFileName(fileName)
        val part = partFileForName(safeName) ?: return false
        val target = targetFileForName(safeName) ?: return false
        if (!part.exists()) return false

        val promoted =
            runCatching {
                target.parentFile?.mkdirs()
                if (target.exists() && !target.delete()) return@runCatching false
                if (part.renameTo(target)) return@runCatching true
                part.copyTo(target, overwrite = true)
                part.delete()
            }.getOrDefault(false)

        if (!promoted) return false

        when {
            safeName.endsWith(".gpx", ignoreCase = true) -> container.syncManager.requestGpxSync()
            safeName.endsWith(".map", ignoreCase = true) -> container.syncManager.requestMapSync()
            safeName.endsWith(".poi", ignoreCase = true) -> container.syncManager.requestPoiSync()
            isDemFileName(safeName) -> {
                DemSignatureStore.markDirty(app.applicationContext)
                container.syncManager.requestMapSync()
            }
        }
        return true
    }

    suspend fun deleteByName(fileName: String) {
        val safeName = sanitizeFileName(fileName)
        when {
            safeName.endsWith(".gpx", ignoreCase = true) -> {
                val dir = app.getDir("gpx", Context.MODE_PRIVATE)
                container.gpxRepository.deleteGpxFile(File(dir, safeName).absolutePath)
            }
            safeName.endsWith(".map", ignoreCase = true) -> {
                val dir = app.getDir("maps", Context.MODE_PRIVATE)
                val mapPath = File(dir, safeName).absolutePath
                cleanupDemForMapPath(mapPath)
                container.mapRepository.deleteMapFile(mapPath)
            }
            safeName.endsWith(".poi", ignoreCase = true) -> {
                val dir = app.getDir("poi", Context.MODE_PRIVATE)
                val poiPath = File(dir, safeName).absolutePath
                container.poiRepository.deletePoiFile(poiPath)
                container.syncManager.requestPoiSync()
            }
            isRoutingSegmentFileName(safeName) -> {
                val target = routingSegmentTargetFile(app.applicationContext, safeName)
                if (target.exists()) target.delete()
                routingSegmentPartFile(app.applicationContext, safeName).delete()
            }
            isDemFileName(safeName) -> {
                val target = demTargetFileForName(safeName)
                if (target.exists()) target.delete()
                demPartFileForName(safeName).delete()
                DemSignatureStore.markDirty(app.applicationContext)
            }
        }
    }

    fun computeFinalFileSha256(
        fileName: String,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
    ): String? {
        val safeName = sanitizeFileName(fileName)
        val file = targetFileForName(safeName) ?: return null
        if (!file.exists() || !file.isFile) return null
        return computeSha256(file, onProgress)
    }

    fun sanitizeFileName(name: String): String =
        name
            .replace("\\", "_")
            .replace("/", "_")
            .trim()
            .ifBlank { "file.bin" }

    private suspend fun cleanupDemForMapPath(mapPath: String) {
        runCatching {
            val mapFile = File(mapPath)
            if (!mapFile.exists() || !mapFile.isFile) return

            val remainingMaps =
                container.mapRepository
                    .listMapFiles()
                    .filterNot { it.absolutePath == mapPath }
            val tilesToDelete =
                Dem3CoverageUtils.tilesToDeleteForMap(
                    deletedMapFile = mapFile,
                    remainingMapFiles = remainingMaps,
                )
            Dem3CoverageUtils.deleteTiles(app.applicationContext, tilesToDelete)
        }.onFailure { error ->
            Log.w("WatchFileOps", "Failed DEM cleanup for deleted map: $mapPath", error)
        }
    }

    private fun isDemFileName(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".hgt") || lower.endsWith(".hgt.zip") || lower.endsWith(".hgt.gz")
    }

    private fun targetFileForName(fileName: String): File? =
        when {
            fileName.endsWith(".gpx", ignoreCase = true) -> File(app.getDir("gpx", Context.MODE_PRIVATE), fileName)
            fileName.endsWith(".map", ignoreCase = true) -> File(app.getDir("maps", Context.MODE_PRIVATE), fileName)
            fileName.endsWith(".poi", ignoreCase = true) -> File(app.getDir("poi", Context.MODE_PRIVATE), fileName)
            isRoutingSegmentFileName(fileName) -> routingSegmentTargetFile(app.applicationContext, fileName)
            isDemFileName(fileName) -> demTargetFileForName(fileName)
            else -> null
        }

    private fun partFileForName(fileName: String): File? =
        when {
            fileName.endsWith(".gpx", ignoreCase = true) -> {
                val dir = app.getDir("gpx", Context.MODE_PRIVATE)
                File(dir, ".$fileName.part")
            }
            fileName.endsWith(".map", ignoreCase = true) -> {
                val dir = app.getDir("maps", Context.MODE_PRIVATE)
                File(dir, ".$fileName.part")
            }
            fileName.endsWith(".poi", ignoreCase = true) -> {
                val dir = app.getDir("poi", Context.MODE_PRIVATE)
                File(dir, ".$fileName.part")
            }
            isRoutingSegmentFileName(fileName) -> routingSegmentPartFile(app.applicationContext, fileName)
            isDemFileName(fileName) -> demPartFileForName(fileName)
            else -> null
        }

    private fun computeSha256(
        file: File,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
    ): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        val totalBytes = file.length().coerceAtLeast(0L)
        val buffer = ByteArray(CHECKSUM_READ_BUFFER_SIZE)
        val threadId = Process.myTid()
        val originalPriority = runCatching { Process.getThreadPriority(threadId) }.getOrNull()
        var bytesRead = 0L
        var lastReportedBytes = 0L

        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND) }

        try {
            file.inputStream().buffered(CHECKSUM_READ_BUFFER_SIZE).use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read <= 0) continue

                    digest.update(buffer, 0, read)
                    bytesRead += read

                    if (
                        onProgress != null &&
                        (
                            bytesRead >= totalBytes ||
                                (bytesRead - lastReportedBytes) >= CHECKSUM_PROGRESS_STEP_BYTES
                        )
                    ) {
                        lastReportedBytes = bytesRead
                        onProgress(bytesRead, totalBytes)
                    }
                }
            }
        } finally {
            if (originalPriority != null) {
                runCatching { Process.setThreadPriority(originalPriority) }
            }
        }

        if (onProgress != null && totalBytes > 0L && lastReportedBytes < totalBytes) {
            onProgress(totalBytes, totalBytes)
        }

        return digest.digest().joinToString("") { b -> "%02x".format(b) }
    }

    private fun demRootDir(source: DemSource = DemSource.DEFAULT): File = source.rootDir(app.applicationContext)

    private fun demTargetFileForName(fileName: String): File {
        val safeName = sanitizeFileName(fileName)
        val source = demSourceForFileName(safeName)
        val root = demRootDir(source)
        val tileId = demTileIdFromFileName(safeName)
        val parent = if (tileId != null) File(root, tileId.substring(0, 3)) else root
        return File(parent, safeName)
    }

    private fun demPartFileForName(fileName: String): File {
        val target = demTargetFileForName(fileName)
        return File(target.parentFile ?: demRootDir(demSourceForFileName(fileName)), ".${target.name}.part")
    }

    private fun demSourceForFileName(fileName: String): DemSource =
        if (fileName.endsWith(".hgt.gz", ignoreCase = true)) {
            DemSource.MAPZEN_SKADI_1S
        } else {
            DemSource.MAPSFORGE_DEM3
        }

    private fun demTileIdFromFileName(fileName: String): String? {
        val upper = fileName.uppercase()
        val base =
            when {
                upper.endsWith(".HGT.ZIP") -> upper.removeSuffix(".HGT.ZIP")
                upper.endsWith(".HGT.GZ") -> upper.removeSuffix(".HGT.GZ")
                upper.endsWith(".HGT") -> upper.removeSuffix(".HGT")
                else -> return null
            }
        return if (DEM_TILE_ID_REGEX.matches(base)) base else null
    }
}
