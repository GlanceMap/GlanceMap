@file:Suppress("TooManyFunctions") // Import, bundle install, discovery, and folder matching share one storage boundary.

package com.glancemap.glancemapcompanionapp.map

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.glancemap.trailcore.map.MapMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.mapsforge.map.reader.MapFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID

/** A locally copied Mapsforge source owned by the companion phone application. */
internal data class PhoneOfflineMap(
    val file: File,
    val contentRevision: String = phoneOfflineMapContentRevision(file),
) {
    val displayName: String
        get() = file.name

    val selectionIdentity: String
        get() = file.absolutePath

    /** Capture the installed file revision once so discovery can replace stale renderer inputs. */
    val rendererIdentity: String
        get() = "$selectionIdentity:$contentRevision"
}

private fun phoneOfflineMapContentRevision(file: File): String =
    try {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        // The file key detects atomic replacement even when length and modification time match.
        "${attributes.fileKey()}:${attributes.size()}:${attributes.lastModifiedTime()}"
    } catch (_: IOException) {
        "unavailable"
    }

/** The active phone renderer source; Mapsforge and file details remain in the companion. */
internal sealed interface PhoneMapSource {
    val mode: MapMode

    data object Online : PhoneMapSource {
        override val mode: MapMode = MapMode.ONLINE
    }

    data class Offline(
        val map: PhoneOfflineMap,
    ) : PhoneMapSource {
        override val mode: MapMode = MapMode.OFFLINE
    }
}

/** A small renderer-neutral camera bridge for temporary online/offline switching. */
internal data class PhoneMapCameraSnapshot(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
    val bearingDegrees: Float = 0f,
    val groundResolutionMetersPerPixel: Double? = null,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(zoom.isFinite() && zoom >= 0.0)
        require(bearingDegrees.isFinite())
        require(
            groundResolutionMetersPerPixel == null ||
                (
                    groundResolutionMetersPerPixel.isFinite() &&
                        groundResolutionMetersPerPixel > 0.0
                ),
        )
    }
}

internal enum class PhoneOfflineMapError {
    MISSING,
    INVALID,
    FILE_NOT_READABLE,
    FILE_NOT_MAP,
    FOLDER_PERMISSION_LOST,
    FOLDER_SCAN_FAILED,
    COPY_FAILED,
}

internal data class PhoneOfflineMapValidation(
    val error: PhoneOfflineMapError? = null,
    val mapFileOpened: Boolean? = null,
    val metadata: PhoneOfflineMapsforgeMetadata? = null,
    val exception: Throwable? = null,
)

internal sealed interface PhoneOfflineMapImportResult {
    data class Success(
        val map: PhoneOfflineMap,
        val reusedExisting: Boolean = false,
    ) : PhoneOfflineMapImportResult

    data class Failure(
        val error: PhoneOfflineMapError,
    ) : PhoneOfflineMapImportResult
}

/** Result of a bundle-owned map installation into the canonical phone map directory. */
internal sealed interface PhoneOfflineMapBundleInstallResult {
    data class Success(
        val map: PhoneOfflineMap,
        val reusedExisting: Boolean,
    ) : PhoneOfflineMapBundleInstallResult

    data class Failure(
        val error: PhoneOfflineMapError,
    ) : PhoneOfflineMapBundleInstallResult
}

internal data class PhoneOfflineMapFolderSyncResult(
    val importedCount: Int = 0,
    val skippedCount: Int = 0,
    val error: PhoneOfflineMapError? = null,
)

/**
 * The canonical companion-owned storage for phone-rendered Mapsforge maps.
 *
 * The transfer flow deliberately remains URI-based and watch-only; importing here is solely for
 * the phone map surface.
 */
@Suppress("TooManyFunctions") // Import, bundle install, discovery, and folder matching share one storage boundary.
internal class PhoneOfflineMapStore(
    private val directoryProvider: () -> File,
    private val mapInspector: (File) -> PhoneOfflineMapValidation = ::inspectMapsforgeMapFile,
) {
    constructor(directory: File, mapInspector: (File) -> PhoneOfflineMapValidation = ::inspectMapsforgeMapFile) :
        this({ directory }, mapInspector)

    constructor(context: Context) : this({ PhoneOfflineStorage(context).mapsDirectory() })

    private val directory: File
        get() = directoryProvider()

    fun discover(): List<PhoneOfflineMap> =
        directory
            .listFiles()
            ?.asSequence()
            ?.filter(::isPhoneOfflineMapCandidate)
            ?.map(::PhoneOfflineMap)
            ?.sortedBy { map -> map.displayName.lowercase() }
            ?.toList()
            .orEmpty()

    /** Opens only the Mapsforge header; callers run it away from the Compose UI thread. */
    fun validate(map: PhoneOfflineMap): PhoneOfflineMapError? {
        if (!isPhoneOfflineMapCandidate(map.file)) return PhoneOfflineMapError.MISSING
        return mapInspector(map.file).error
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught") // Content providers may throw framework-specific exceptions.
    fun import(
        contentResolver: ContentResolver,
        uri: Uri,
    ): PhoneOfflineMapImportResult {
        val trace = PhoneOfflineMapImportTrace(directory)
        trace.stage = PhoneOfflineMapImportStage.METADATA_READ
        val document = readDocumentMetadata(contentResolver, uri)
        trace.sourceSizeBytes = document.sourceSizeBytes
        val fileName = document.displayName.orEmpty()
        if (!isPhoneOfflineMapFileName(fileName)) {
            return trace.record(PhoneOfflineMapImportResult.Failure(PhoneOfflineMapError.FILE_NOT_MAP))
        }

        trace.stage = PhoneOfflineMapImportStage.STREAM_OPEN
        trace.streamOpened = false
        val input =
            try {
                contentResolver.openInputStream(uri)
            } catch (error: Exception) {
                trace.exception = error
                null
            }
        if (input == null) {
            return trace.record(PhoneOfflineMapImportResult.Failure(PhoneOfflineMapError.FILE_NOT_READABLE))
        }
        trace.streamOpened = true
        val result =
            try {
                input.use { importFromInput(fileName, it, trace) }
            } catch (error: Exception) {
                trace.exception = error
                PhoneOfflineMapImportResult.Failure(PhoneOfflineMapError.FILE_NOT_READABLE)
            }
        return trace.record(result)
    }

    /** Copies a map source into companion-owned storage after validating its Mapsforge structure. */
    internal fun import(
        fileName: String,
        input: InputStream,
    ): PhoneOfflineMapImportResult {
        val trace = PhoneOfflineMapImportTrace(directory)
        trace.streamOpened = true
        return importFromInput(fileName, input, trace).let(trace::record)
    }

    private fun importFromInput(
        fileName: String,
        input: InputStream,
        trace: PhoneOfflineMapImportTrace,
    ): PhoneOfflineMapImportResult =
        when {
            !isPhoneOfflineMapFileName(fileName) ->
                PhoneOfflineMapImportResult.Failure(PhoneOfflineMapError.FILE_NOT_MAP)
            !directory.exists() && !directory.mkdirs() -> {
                trace.stage = PhoneOfflineMapImportStage.COPY
                PhoneOfflineMapImportResult.Failure(PhoneOfflineMapError.COPY_FAILED)
            }
            else -> installMap(fileName, input, trace)
        }

    /**
     * Installs an extracted OAM map only after the temporary file validates. Existing valid files
     * are reused unless replacement is requested. Promotion is atomic within the map directory;
     * a failed promotion leaves the existing map intact.
     */
    @Suppress("CyclomaticComplexMethod", "ReturnCount", "TooGenericExceptionCaught")
    suspend fun installBundleMap(
        fileName: String,
        input: InputStream,
        replaceExisting: Boolean = false,
        onBytesCopied: (Long) -> Unit,
    ): PhoneOfflineMapBundleInstallResult {
        if (!isPhoneOfflineMapFileName(fileName)) {
            return PhoneOfflineMapBundleInstallResult.Failure(PhoneOfflineMapError.FILE_NOT_MAP)
        }
        if (!directory.exists() && !directory.mkdirs()) {
            return PhoneOfflineMapBundleInstallResult.Failure(PhoneOfflineMapError.COPY_FAILED)
        }

        val destination = File(directory, File(fileName).name)
        val temporary = File(destination.parentFile, ".${destination.name}.bundle.part")
        var completed = false
        return try {
            temporary.outputStream().use { output ->
                input.copyCancellableTo(output, onBytesCopied)
            }
            mapInspector(temporary).error?.let(PhoneOfflineMapBundleInstallResult::Failure)
                ?: when {
                    destination.exists() && mapInspector(destination).error == null && !replaceExisting ->
                        PhoneOfflineMapBundleInstallResult.Success(
                            map = PhoneOfflineMap(destination),
                            reusedExisting = true,
                        )
                    else -> {
                        Files.move(
                            temporary.toPath(),
                            destination.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                        PhoneOfflineMapBundleInstallResult.Success(
                            map = PhoneOfflineMap(destination),
                            reusedExisting = false,
                        )
                    }
                }.also { completed = true }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            PhoneOfflineMapBundleInstallResult.Failure(PhoneOfflineMapError.COPY_FAILED)
        } finally {
            if (completed && temporary.exists()) temporary.delete()
        }
    }

    // File I/O has one cleanup path and preserves the exact stage.
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun installMap(
        fileName: String,
        input: InputStream,
        trace: PhoneOfflineMapImportTrace,
    ): PhoneOfflineMapImportResult {
        var temporary: File? = null
        return try {
            trace.stage = PhoneOfflineMapImportStage.COPY
            val candidate = File.createTempFile("phone-map-import-", ".part", directory)
            temporary = candidate
            val digest = MessageDigest.getInstance("SHA-256")
            candidate.outputStream().use { output -> trace.copyFrom(input, DigestOutputStream(output, digest)) }
            trace.candidateSizeBytes = candidate.length()
            trace.stage = PhoneOfflineMapImportStage.VALIDATION
            trace.candidateValid = candidate.isNonEmptyFile()
            if (trace.candidateValid != true) {
                return PhoneOfflineMapImportResult.Failure(PhoneOfflineMapError.FILE_NOT_READABLE)
            }

            trace.stage = PhoneOfflineMapImportStage.MAPFILE_OPEN
            val validation = mapInspector(candidate)
            trace.mapFileOpened = validation.mapFileOpened
            trace.metadata = validation.metadata
            trace.exception = validation.exception
            validation.error?.let { error ->
                return PhoneOfflineMapImportResult.Failure(error)
            }
            if (validation.mapFileOpened == true && validation.metadata == null) {
                trace.stage = PhoneOfflineMapImportStage.MAP_METADATA
            }

            promoteMapImport(fileName, candidate, digest.digest(), trace)
        } catch (error: Exception) {
            trace.exception = error
            PhoneOfflineMapImportResult.Failure(PhoneOfflineMapError.COPY_FAILED)
        } finally {
            temporary?.let { candidate ->
                if (candidate.exists()) trace.candidateSizeBytes = candidate.length()
                candidate.delete()
            }
        }
    }

    private fun promoteMapImport(
        fileName: String,
        candidate: File,
        contentDigest: ByteArray,
        trace: PhoneOfflineMapImportTrace,
    ): PhoneOfflineMapImportResult =
        synchronized(importPromotionLock) {
            trace.stage = PhoneOfflineMapImportStage.PROMOTION
            val existing =
                discover().firstOrNull { map ->
                    map.file.length() == candidate.length() && map.file.hasMapContentDigest(contentDigest)
                }
            val destination = existing?.file ?: nextAvailableMapFile(fileName)
            if (existing != null || candidate.renameTo(destination)) {
                trace.destinationSizeBytes = destination.length()
                trace.stage = PhoneOfflineMapImportStage.COMPLETE
                PhoneOfflineMapImportResult.Success(PhoneOfflineMap(destination), reusedExisting = existing != null)
            } else {
                PhoneOfflineMapImportResult.Failure(PhoneOfflineMapError.COPY_FAILED)
            }
        }

    /** Folder copies are reused only when their content matches, even if source metadata is unchanged. */
    internal fun synchronizeFolderMap(
        sourceFileName: String,
        openInputStream: () -> InputStream?,
    ): PhoneOfflineMapFolderSyncResult {
        if (!isPhoneOfflineMapFileName(sourceFileName)) {
            return PhoneOfflineMapFolderSyncResult(error = PhoneOfflineMapError.FILE_NOT_MAP)
        }
        return runCatching {
            val candidates = discover().filter { map -> map.file.name.isImportedNameFor(sourceFileName) }
            val sourceDigest =
                if (candidates.isEmpty()) {
                    null
                } else {
                    openInputStream()?.use { input -> input.mapContentDigest() }
                }
            when {
                candidates.isNotEmpty() && sourceDigest == null ->
                    PhoneOfflineMapFolderSyncResult(error = PhoneOfflineMapError.FILE_NOT_READABLE)
                candidates.any { map -> map.file.hasMapContentDigest(sourceDigest) } ->
                    PhoneOfflineMapFolderSyncResult(skippedCount = 1)
                else -> {
                    val result =
                        openInputStream()?.use { input -> import(sourceFileName, input) }
                            ?: PhoneOfflineMapImportResult.Failure(PhoneOfflineMapError.FILE_NOT_READABLE)
                    when (result) {
                        is PhoneOfflineMapImportResult.Success ->
                            if (result.reusedExisting) {
                                PhoneOfflineMapFolderSyncResult(skippedCount = 1)
                            } else {
                                PhoneOfflineMapFolderSyncResult(importedCount = 1)
                            }

                        is PhoneOfflineMapImportResult.Failure -> PhoneOfflineMapFolderSyncResult(error = result.error)
                    }
                }
            }
        }.getOrElse { error ->
            if (error is SecurityException) throw error
            PhoneOfflineMapFolderSyncResult(error = PhoneOfflineMapError.COPY_FAILED)
        }
    }

    fun findValidBundleMap(fileName: String): PhoneOfflineMap? {
        val map = PhoneOfflineMap(File(directory, File(fileName).name))
        return map.takeIf { validate(it) == null }
    }

    /** Renames one app-owned map while retaining the Mapsforge file extension. */
    fun rename(
        map: PhoneOfflineMap,
        newName: String,
    ): PhoneOfflineMap {
        val source = map.file
        require(isManagedMapFile(source)) { "The map is outside the app storage." }
        require(source.isFile) { "The map could not be found." }
        val target = File(directory, phoneOfflineMapFileName(newName))
        if (source == target) return map
        require(!target.exists()) { "A map with that name already exists." }
        require(source.renameTo(target)) { "The map could not be renamed." }
        val sourcePartial = File(directory, ".${source.name}.part")
        if (sourcePartial.exists()) {
            val targetPartial = File(directory, ".${target.name}.part")
            if (!sourcePartial.renameTo(targetPartial)) {
                sourcePartial.copyTo(targetPartial, overwrite = true)
                sourcePartial.delete()
            }
        }
        return PhoneOfflineMap(target)
    }

    /** Deletes one app-owned map. Callers confirm the destructive action before invoking this. */
    fun delete(map: PhoneOfflineMap) {
        val file = map.file
        require(isManagedMapFile(file)) { "The map is outside the app storage." }
        require(!file.exists() || file.delete()) { "The map could not be deleted." }
        File(directory, ".${file.name}.part").delete()
    }

    private fun readDocumentMetadata(
        contentResolver: ContentResolver,
        uri: Uri,
    ): PhoneOfflineMapDocumentMetadata {
        val document =
            runCatching {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    PhoneOfflineMapDocumentMetadata(
                        displayName =
                            cursor
                                .getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                .takeIf { it >= 0 }
                                ?.let(cursor::getString),
                        sourceSizeBytes =
                            cursor
                                .getColumnIndex(OpenableColumns.SIZE)
                                .takeIf { it >= 0 && !cursor.isNull(it) }
                                ?.let(cursor::getLong),
                    )
                }
            }.getOrNull()
        val base = document ?: PhoneOfflineMapDocumentMetadata()
        val displayName =
            base.displayName
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment
                    ?.substringAfterLast('/')
                    ?.trim()
                    .orEmpty()
        return base.copy(
            displayName = safeMapDisplayName(displayName),
            mimeType = runCatching { contentResolver.getType(uri) }.getOrNull(),
        )
    }

    private fun nextAvailableMapFile(fileName: String): File {
        val safeFileName = File(fileName).name
        val baseName = safeFileName.substringBeforeLast('.', missingDelimiterValue = safeFileName)
        var index = 0
        while (true) {
            val suffix = if (index == 0) "" else " ($index)"
            val candidate = File(directory, "$baseName$suffix$MAP_EXTENSION")
            if (!candidate.exists()) return candidate
            index += 1
        }
    }

    private fun isManagedMapFile(file: File): Boolean =
        runCatching {
            file.canonicalFile.parentFile?.canonicalFile == directory.canonicalFile
        }.getOrDefault(false)

    private companion object {
        // Shared across store instances recreated while an earlier import is still copying.
        val importPromotionLock = Any()
        const val DIRECTORY_NAME = "maps"
        const val MAP_EXTENSION = ".map"
    }
}

private data class PhoneOfflineMapDocumentMetadata(
    val displayName: String? = null,
    val mimeType: String? = null,
    val sourceSizeBytes: Long? = null,
)

/** Mutable only while a single import is running; the persisted report receives an immutable copy. */
private class PhoneOfflineMapImportTrace(
    private val destinationDirectory: File,
) {
    private val attemptId = UUID.randomUUID().toString()
    private val startedAtEpochMs = System.currentTimeMillis()
    private val startedAtNanos = System.nanoTime()
    private val captureSessionId = PhoneOfflineMapImportDiagnostics.activeCaptureSessionId()
    private val destinationSpaceBytesAtStart = availableDestinationSpaceBytes()
    private var currentStage = PhoneOfflineMapImportStage.DOCUMENT_SELECTED
    private var stageStartedAtNanos = startedAtNanos
    private val stageDurationsMillis = mutableMapOf<PhoneOfflineMapImportStage, Long>()

    init {
        PhoneOfflineMapImportDiagnostics.importStarted(
            attemptId,
            startedAtEpochMs,
            captureSessionId,
            destinationSpaceBytesAtStart,
        )
    }

    var stage: PhoneOfflineMapImportStage
        get() = currentStage
        set(value) {
            if (value != currentStage) {
                closeCurrentStage(System.nanoTime())
                currentStage = value
            }
        }

    var sourceSizeBytes: Long? = null
    var streamOpened: Boolean? = null
    var bytesCopied: Long? = null
    var destinationSizeBytes: Long? = null
    var candidateSizeBytes: Long? = null
    var candidateValid: Boolean? = null
    var mapFileOpened: Boolean? = null
    var metadata: PhoneOfflineMapsforgeMetadata? = null
    var exception: Throwable? = null

    fun record(result: PhoneOfflineMapImportResult): PhoneOfflineMapImportResult {
        val failure = result as? PhoneOfflineMapImportResult.Failure
        val endedAtNanos = System.nanoTime()
        closeCurrentStage(endedAtNanos)
        val endedAtEpochMs = System.currentTimeMillis()
        PhoneOfflineMapImportDiagnostics.record(
            PhoneOfflineMapImportAttempt(
                attemptId = attemptId,
                captureSessionId = captureSessionId,
                startedAtEpochMs = startedAtEpochMs,
                endedAtEpochMs = endedAtEpochMs,
                durationMillis = ((endedAtNanos - startedAtNanos) / 1_000_000L).coerceAtLeast(0L),
                stageDurationsMillis = stageDurationsMillis.toMap(),
                outcome =
                    if (failure == null) {
                        PhoneOfflineMapImportOutcome.SUCCESS
                    } else {
                        PhoneOfflineMapImportOutcome.FAILED
                    },
                failureStage = stage.takeIf { failure != null },
                sourceSizeBytes = sourceSizeBytes,
                streamOpened = streamOpened,
                bytesCopied = bytesCopied,
                candidateSizeBytes = candidateSizeBytes,
                destinationSizeBytes = destinationSizeBytes,
                destinationSpaceBytesAtStart = destinationSpaceBytesAtStart,
                destinationSpaceBytesAtEnd = availableDestinationSpaceBytes(),
                candidateValid = candidateValid,
                mapFileOpened = mapFileOpened,
                metadata = metadata,
                finalError = failure?.error,
                failureCategory = failure?.let { phoneOfflineMapFailureCategory(it.error, exception) },
            ),
        )
        return result
    }

    private fun closeCurrentStage(nowNanos: Long) {
        val elapsedMillis = ((nowNanos - stageStartedAtNanos) / 1_000_000L).coerceAtLeast(0L)
        stageDurationsMillis[currentStage] = (stageDurationsMillis[currentStage] ?: 0L) + elapsedMillis
        stageStartedAtNanos = nowNanos
    }

    private fun availableDestinationSpaceBytes(): Long? =
        runCatching {
            generateSequence(destinationDirectory) { it.parentFile }
                .firstOrNull { it.exists() }
                ?.usableSpace
        }.getOrNull()

    fun copyFrom(
        input: InputStream,
        output: java.io.OutputStream,
    ) {
        var copied = 0L
        bytesCopied = copied
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            output.write(buffer, 0, count)
            copied += count
            bytesCopied = copied
        }
    }
}

internal fun isPhoneOfflineMapCandidate(file: File): Boolean = file.isReadableMapFile()

/** Shared storage reconciliation must validate Mapsforge content, not only a .map file name. */
internal fun isUsablePhoneOfflineMapFile(file: File): Boolean =
    isPhoneOfflineMapCandidate(file) &&
        (
            file.parentFile?.let { directory ->
                PhoneOfflineMapStore(directory).validate(PhoneOfflineMap(file)) == null
            } == true
        )

internal fun isPhoneOfflineMapDocumentCandidate(
    name: String?,
    isFile: Boolean,
): Boolean = isFile && name.isMapFileName()

private fun isPhoneOfflineMapFileName(fileName: String?): Boolean = fileName.isMapFileName()

private fun File.isReadableMapFile(): Boolean = isNonEmptyFile() && name.isMapFileName()

private fun File.isNonEmptyFile(): Boolean = isFile && canRead() && length() > 0L

private fun String?.isMapFileName(): Boolean = this?.endsWith(".map", ignoreCase = true) == true

@Suppress("ReturnCount", "TooGenericExceptionCaught") // MapFile distinguishes open from metadata failures.
private fun inspectMapsforgeMapFile(file: File): PhoneOfflineMapValidation {
    if (!file.isNonEmptyFile()) return PhoneOfflineMapValidation(error = PhoneOfflineMapError.FILE_NOT_READABLE)
    val mapFile =
        try {
            MapFile(file)
        } catch (error: Exception) {
            return PhoneOfflineMapValidation(
                error = PhoneOfflineMapError.INVALID,
                mapFileOpened = false,
                exception = error,
            )
        }
    return try {
        val info = mapFile.mapFileInfo
        PhoneOfflineMapValidation(
            mapFileOpened = true,
            metadata =
                PhoneOfflineMapsforgeMetadata(
                    boundingBoxAvailable = runCatching(mapFile::boundingBox).isSuccess,
                    minZoom = info.zoomLevelMin.toInt(),
                    maxZoom = info.zoomLevelMax.toInt(),
                    startPositionAvailable = runCatching(mapFile::startPosition).getOrNull() != null,
                ),
        )
    } catch (error: Exception) {
        PhoneOfflineMapValidation(
            mapFileOpened = true,
            exception = error,
        )
    } finally {
        runCatching(mapFile::close)
    }
}

private fun safeMapDisplayName(value: String): String = File(value).name.takeIf(String::isNotBlank).orEmpty()

internal fun phoneOfflineMapFileName(value: String): String {
    val trimmed = value.trim().replace(Regex("\\s+"), " ")
    require(trimmed.isNotBlank()) { "Enter a map name first." }
    val safeName = File(trimmed).name
    require(safeName == trimmed) { "Enter a map name without a folder path." }
    val baseName = safeName.removePhoneFileExtension(".map").trim()
    require(baseName.isNotBlank()) { "Enter a map name first." }
    return "$baseName.map"
}

private fun InputStream.mapContentDigest(): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = read(buffer)
        if (count < 0) return digest.digest()
        digest.update(buffer, 0, count)
    }
}

private fun File.hasMapContentDigest(expected: ByteArray?): Boolean =
    expected != null &&
        runCatching {
            inputStream().use { input -> input.mapContentDigest().contentEquals(expected) }
        }.getOrDefault(false)

private fun String.isImportedNameFor(sourceFileName: String): Boolean {
    if (equals(sourceFileName, ignoreCase = true)) return true
    val sourceBaseName = sourceFileName.substringBeforeLast('.', sourceFileName)
    return startsWith("$sourceBaseName (") && endsWith(".map", ignoreCase = true)
}

private fun String.removePhoneFileExtension(extension: String): String =
    takeIf { endsWith(extension, ignoreCase = true) }
        ?.dropLast(extension.length)
        ?: this

private suspend fun InputStream.copyCancellableTo(
    output: java.io.OutputStream,
    onBytesCopied: (Long) -> Unit,
) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var copied = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = read(buffer)
        if (count < 0) return
        output.write(buffer, 0, count)
        copied += count
        onBytesCopied(copied)
    }
}

/**
 * Remembers one optional SAF tree as an import source. Mapsforge itself continues to read private
 * files from [PhoneOfflineMapStore], never arbitrary content URIs.
 */
internal class PhoneOfflineMapFolderSource(
    private val context: Context,
    private val mapStore: PhoneOfflineMapStore = PhoneOfflineMapStore(context),
) {
    fun hasSelectedFolder(): Boolean = selectedFolderUri() != null

    fun selectFolder(uri: Uri): PhoneOfflineMapError? =
        runCatching {
            val previousUri = selectedFolderUri()
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            preferences().edit().putString(KEY_FOLDER_URI, uri.toString()).apply()
            if (previousUri != null && previousUri != uri) {
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        previousUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
        }.fold(
            onSuccess = { null },
            onFailure = { PhoneOfflineMapError.FOLDER_PERMISSION_LOST },
        )

    fun clearSelectedFolder() {
        selectedFolderUri()?.let { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        forgetSelectedFolder()
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount", "TooGenericExceptionCaught")
    fun syncSelectedFolder(): PhoneOfflineMapFolderSyncResult {
        val treeUri = selectedFolderUri() ?: return PhoneOfflineMapFolderSyncResult()
        val scan = PhoneOfflineMapImportDiagnostics.beginFolderScan()
        val folder = runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull()
        val canRead = canReadForScan(folder, scan)
        if (folder == null || !canRead) {
            forgetSelectedFolder()
            return scan.fail(PhoneOfflineMapError.FOLDER_PERMISSION_LOST)
        }
        val documents =
            try {
                folder.listFiles()
            } catch (_: SecurityException) {
                forgetSelectedFolder()
                return scan.fail(PhoneOfflineMapError.FOLDER_PERMISSION_LOST)
            } catch (_: Exception) {
                return scan.fail(PhoneOfflineMapError.FOLDER_SCAN_FAILED)
            }

        return try {
            var importedCount = 0
            var skippedCount = 0
            var firstError: PhoneOfflineMapError? = null
            val candidates =
                documents.filter { document ->
                    isPhoneOfflineMapDocumentCandidate(
                        name = document.name,
                        isFile = document.isFile,
                    )
                }
            scan.candidatesDiscovered(candidates.size)
            candidates.forEach { document ->
                val name = document.name ?: return@forEach
                scan.candidateStarted()
                val result =
                    mapStore.synchronizeFolderMap(name) {
                        context.contentResolver.openInputStream(document.uri)
                    }
                scan.candidateCompleted(result)
                importedCount += result.importedCount
                skippedCount += result.skippedCount
                firstError = firstError ?: result.error
            }
            scan.finish(
                PhoneOfflineMapFolderSyncResult(
                    importedCount = importedCount,
                    skippedCount = skippedCount,
                    error = firstError,
                ),
            )
        } catch (_: SecurityException) {
            forgetSelectedFolder()
            scan.fail(PhoneOfflineMapError.FOLDER_PERMISSION_LOST)
        } catch (_: Exception) {
            scan.fail(PhoneOfflineMapError.FOLDER_SCAN_FAILED)
        }
    }

    private fun canReadForScan(
        folder: DocumentFile?,
        scan: PhoneOfflineMapFolderScanTrace,
    ): Boolean =
        runCatching {
            folder?.canRead() == true
        }.getOrElse { error ->
            scan.fail(
                if (error is SecurityException) {
                    PhoneOfflineMapError.FOLDER_PERMISSION_LOST
                } else {
                    PhoneOfflineMapError.FOLDER_SCAN_FAILED
                },
            )
            throw error
        }

    private fun selectedFolderUri(): Uri? = preferences().getString(KEY_FOLDER_URI, null)?.let(Uri::parse)

    private fun forgetSelectedFolder() {
        preferences().edit().remove(KEY_FOLDER_URI).apply()
    }

    private fun preferences() = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private companion object {
        const val PREFERENCES_NAME = "phone_offline_map_folder"
        const val KEY_FOLDER_URI = "selected_folder_uri"
    }
}
