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
import java.io.InputStream

/** A locally copied Mapsforge source owned by the companion phone application. */
internal data class PhoneOfflineMap(
    val file: File,
) {
    val displayName: String
        get() = file.name

    /** The Mapsforge renderer owns one concrete file and must be recreated only when it changes. */
    val rendererIdentity: String
        get() = file.absolutePath
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
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(zoom.isFinite() && zoom >= 0.0)
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
    private val directory: File,
    private val mapInspector: (File) -> PhoneOfflineMapValidation = ::inspectMapsforgeMapFile,
) {
    constructor(context: Context) : this(File(context.filesDir, DIRECTORY_NAME))

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
        val trace = PhoneOfflineMapImportTrace()
        trace.stage = PhoneOfflineMapImportStage.METADATA_READ
        val document = readDocumentMetadata(contentResolver, uri)
        trace.displayName = document.displayName
        trace.mimeType = document.mimeType
        trace.sourceSizeBytes = document.sourceSizeBytes
        trace.stage = PhoneOfflineMapImportStage.DOCUMENT_SELECTED
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
        val trace = PhoneOfflineMapImportTrace(displayName = safeMapDisplayName(fileName))
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
     * are reused; an invalid target is replaced only after its replacement is ready. Atomic
     * promotion needs explicit safety branches, including generic I/O exceptions.
     */
    @Suppress("CyclomaticComplexMethod", "ReturnCount", "TooGenericExceptionCaught")
    suspend fun installBundleMap(
        fileName: String,
        input: InputStream,
        onBytesCopied: (Long) -> Unit,
    ): PhoneOfflineMapBundleInstallResult {
        if (!isPhoneOfflineMapFileName(fileName)) {
            return PhoneOfflineMapBundleInstallResult.Failure(PhoneOfflineMapError.FILE_NOT_MAP)
        }
        if (!directory.exists() && !directory.mkdirs()) {
            return PhoneOfflineMapBundleInstallResult.Failure(PhoneOfflineMapError.COPY_FAILED)
        }

        val destination = File(directory, File(fileName).name)
        val temporary = File(directory, ".${destination.name}.bundle.part")
        return try {
            temporary.outputStream().use { output ->
                input.copyCancellableTo(output, onBytesCopied)
            }
            mapInspector(temporary).error?.let(PhoneOfflineMapBundleInstallResult::Failure)
                ?: when {
                    destination.exists() && mapInspector(destination).error == null ->
                        PhoneOfflineMapBundleInstallResult.Success(
                            map = PhoneOfflineMap(destination),
                            reusedExisting = true,
                        )
                    destination.exists() && !destination.delete() ->
                        PhoneOfflineMapBundleInstallResult.Failure(PhoneOfflineMapError.COPY_FAILED)
                    temporary.renameTo(destination) ->
                        PhoneOfflineMapBundleInstallResult.Success(
                            map = PhoneOfflineMap(destination),
                            reusedExisting = false,
                        )
                    else -> PhoneOfflineMapBundleInstallResult.Failure(PhoneOfflineMapError.COPY_FAILED)
                }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            PhoneOfflineMapBundleInstallResult.Failure(PhoneOfflineMapError.COPY_FAILED)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    // File I/O has one cleanup path and preserves the exact stage.
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun installMap(
        fileName: String,
        input: InputStream,
        trace: PhoneOfflineMapImportTrace,
    ): PhoneOfflineMapImportResult {
        val destination = nextAvailableMapFile(fileName)
        val temporary = File(directory, "${destination.name}.part")
        return try {
            trace.stage = PhoneOfflineMapImportStage.COPY
            temporary.outputStream().use { output -> trace.copyFrom(input, output) }
            trace.destinationSizeBytes = temporary.length()
            trace.stage = PhoneOfflineMapImportStage.VALIDATION
            trace.candidateValid = temporary.isNonEmptyFile()
            if (trace.candidateValid != true) {
                return PhoneOfflineMapImportResult.Failure(PhoneOfflineMapError.FILE_NOT_READABLE)
            }

            trace.stage = PhoneOfflineMapImportStage.MAPFILE_OPEN
            val validation = mapInspector(temporary)
            trace.mapFileOpened = validation.mapFileOpened
            trace.metadata = validation.metadata
            trace.exception = validation.exception
            validation.error?.let { error ->
                return PhoneOfflineMapImportResult.Failure(error)
            }
            if (validation.mapFileOpened == true && validation.metadata == null) {
                trace.stage = PhoneOfflineMapImportStage.MAP_METADATA
            }

            trace.stage = PhoneOfflineMapImportStage.PROMOTION
            if (temporary.renameTo(destination)) {
                trace.destinationSizeBytes = destination.length()
                trace.stage = PhoneOfflineMapImportStage.COMPLETE
                PhoneOfflineMapImportResult.Success(PhoneOfflineMap(destination))
            } else {
                PhoneOfflineMapImportResult.Failure(PhoneOfflineMapError.COPY_FAILED)
            }
        } catch (error: Exception) {
            trace.exception = error
            PhoneOfflineMapImportResult.Failure(PhoneOfflineMapError.COPY_FAILED)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    /** A direct source folder may retain copies; matching name and size avoids needless re-imports. */
    fun findSynchronizedMap(
        sourceFileName: String,
        sourceSize: Long,
    ): PhoneOfflineMap? {
        if (!isPhoneOfflineMapFileName(sourceFileName) || sourceSize <= 0L) return null
        return discover().firstOrNull { map ->
            map.file.length() == sourceSize && map.file.name.isImportedNameFor(sourceFileName)
        }
    }

    fun findValidBundleMap(fileName: String): PhoneOfflineMap? {
        val map = PhoneOfflineMap(File(directory, File(fileName).name))
        return map.takeIf { validate(it) == null }
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

    private companion object {
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
    var displayName: String? = null,
) {
    var stage: PhoneOfflineMapImportStage = PhoneOfflineMapImportStage.DOCUMENT_SELECTED
    var mimeType: String? = null
    var sourceSizeBytes: Long? = null
    var streamOpened: Boolean? = null
    var bytesCopied: Long? = null
    var destinationSizeBytes: Long? = null
    var candidateValid: Boolean? = null
    var mapFileOpened: Boolean? = null
    var metadata: PhoneOfflineMapsforgeMetadata? = null
    var exception: Throwable? = null

    fun record(result: PhoneOfflineMapImportResult): PhoneOfflineMapImportResult {
        val failure = result as? PhoneOfflineMapImportResult.Failure
        val safeException = exception?.toPhoneOfflineMapImportException()
        PhoneOfflineMapImportDiagnostics.record(
            PhoneOfflineMapImportAttempt(
                outcome =
                    if (failure == null) {
                        PhoneOfflineMapImportOutcome.SUCCESS
                    } else {
                        PhoneOfflineMapImportOutcome.FAILED
                    },
                failureStage = stage.takeIf { failure != null },
                displayName = displayName,
                mimeType = mimeType,
                sourceSizeBytes = sourceSizeBytes,
                streamOpened = streamOpened,
                bytesCopied = bytesCopied,
                destinationSizeBytes = destinationSizeBytes,
                candidateValid = candidateValid,
                mapFileOpened = mapFileOpened,
                metadata = metadata,
                finalError = failure?.error,
                exceptionClass = safeException?.className,
                exceptionMessage = safeException?.message,
            ),
        )
        return result
    }

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

private fun String.isImportedNameFor(sourceFileName: String): Boolean {
    if (equals(sourceFileName, ignoreCase = true)) return true
    val sourceBaseName = sourceFileName.substringBeforeLast('.', sourceFileName)
    return startsWith("$sourceBaseName (") && endsWith(".map", ignoreCase = true)
}

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
        val folder = runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull()
        if (folder == null || !folder.canRead()) {
            forgetSelectedFolder()
            return PhoneOfflineMapFolderSyncResult(error = PhoneOfflineMapError.FOLDER_PERMISSION_LOST)
        }
        val documents =
            try {
                folder.listFiles()
            } catch (_: SecurityException) {
                forgetSelectedFolder()
                return PhoneOfflineMapFolderSyncResult(error = PhoneOfflineMapError.FOLDER_PERMISSION_LOST)
            } catch (_: Exception) {
                return PhoneOfflineMapFolderSyncResult(error = PhoneOfflineMapError.FOLDER_SCAN_FAILED)
            }

        return try {
            var importedCount = 0
            var skippedCount = 0
            var firstError: PhoneOfflineMapError? = null
            documents
                .filter { document ->
                    isPhoneOfflineMapDocumentCandidate(
                        name = document.name,
                        isFile = document.isFile,
                    )
                }.forEach { document ->
                    val name = document.name ?: return@forEach
                    if (mapStore.findSynchronizedMap(name, document.length()) != null) {
                        skippedCount += 1
                        return@forEach
                    }
                    val result =
                        runCatching {
                            context.contentResolver.openInputStream(document.uri)?.use { input ->
                                mapStore.import(name, input)
                            } ?: PhoneOfflineMapImportResult.Failure(PhoneOfflineMapError.FILE_NOT_READABLE)
                        }.getOrElse {
                            PhoneOfflineMapImportResult.Failure(PhoneOfflineMapError.COPY_FAILED)
                        }
                    when (result) {
                        is PhoneOfflineMapImportResult.Success -> importedCount += 1
                        is PhoneOfflineMapImportResult.Failure -> firstError = firstError ?: result.error
                    }
                }
            PhoneOfflineMapFolderSyncResult(
                importedCount = importedCount,
                skippedCount = skippedCount,
                error = firstError,
            )
        } catch (_: SecurityException) {
            forgetSelectedFolder()
            PhoneOfflineMapFolderSyncResult(error = PhoneOfflineMapError.FOLDER_PERMISSION_LOST)
        } catch (_: Exception) {
            PhoneOfflineMapFolderSyncResult(error = PhoneOfflineMapError.FOLDER_SCAN_FAILED)
        }
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
