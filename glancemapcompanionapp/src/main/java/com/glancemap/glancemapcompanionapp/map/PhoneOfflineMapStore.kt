package com.glancemap.glancemapcompanionapp.map

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.glancemap.trailcore.map.MapMode
import org.mapsforge.map.reader.MapFile
import java.io.File
import java.io.InputStream

/** A locally copied Mapsforge source owned by the companion phone application. */
internal data class PhoneOfflineMap(
    val file: File,
) {
    val displayName: String
        get() = file.name
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

internal sealed interface PhoneOfflineMapImportResult {
    data class Success(
        val map: PhoneOfflineMap,
    ) : PhoneOfflineMapImportResult

    data class Failure(
        val error: PhoneOfflineMapError,
    ) : PhoneOfflineMapImportResult
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
internal class PhoneOfflineMapStore(
    private val directory: File,
    private val structuralValidator: (File) -> PhoneOfflineMapError? = ::validateMapsforgeMapFile,
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
        return structuralValidator(map.file)
    }

    fun import(
        contentResolver: ContentResolver,
        uri: Uri,
    ): PhoneOfflineMapImportResult {
        val fileName = resolveFileName(contentResolver, uri)
        return when {
            !isPhoneOfflineMapFileName(fileName) ->
                PhoneOfflineMapImportResult.Failure(PhoneOfflineMapError.FILE_NOT_MAP)
            else ->
                runCatching {
                    contentResolver.openInputStream(uri)?.use { input -> import(fileName, input) }
                }.getOrNull() ?: PhoneOfflineMapImportResult.Failure(PhoneOfflineMapError.FILE_NOT_READABLE)
        }
    }

    /** Copies a map source into companion-owned storage after validating its Mapsforge structure. */
    internal fun import(
        fileName: String,
        input: InputStream,
    ): PhoneOfflineMapImportResult =
        when {
            !isPhoneOfflineMapFileName(fileName) ->
                PhoneOfflineMapImportResult.Failure(PhoneOfflineMapError.FILE_NOT_MAP)
            !directory.exists() && !directory.mkdirs() ->
                PhoneOfflineMapImportResult.Failure(PhoneOfflineMapError.COPY_FAILED)
            else -> installMap(fileName, input)
        }

    private fun installMap(
        fileName: String,
        input: InputStream,
    ): PhoneOfflineMapImportResult {
        val destination = nextAvailableMapFile(fileName)
        val temporary = File(directory, "${destination.name}.part")
        return try {
            temporary.outputStream().use(input::copyTo)
            structuralValidator(temporary)?.let(PhoneOfflineMapImportResult::Failure)
                ?: if (temporary.renameTo(destination)) {
                    PhoneOfflineMapImportResult.Success(PhoneOfflineMap(destination))
                } else {
                    PhoneOfflineMapImportResult.Failure(PhoneOfflineMapError.COPY_FAILED)
                }
        } catch (_: Exception) {
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

    private fun resolveFileName(
        contentResolver: ContentResolver,
        uri: Uri,
    ): String =
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                cursor
                    .getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    .takeIf { it >= 0 }
                    ?.let(cursor::getString)
            }
        }.getOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.trim()
                .orEmpty()

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

internal fun isPhoneOfflineMapCandidate(file: File): Boolean = file.isReadableMapFile()

internal fun isPhoneOfflineMapDocumentCandidate(
    name: String?,
    isFile: Boolean,
): Boolean = isFile && name.isMapFileName()

private fun isPhoneOfflineMapFileName(fileName: String?): Boolean = fileName.isMapFileName()

private fun File.isReadableMapFile(): Boolean = isNonEmptyFile() && name.isMapFileName()

private fun File.isNonEmptyFile(): Boolean = isFile && canRead() && length() > 0L

private fun String?.isMapFileName(): Boolean = this?.endsWith(".map", ignoreCase = true) == true

private fun validateMapsforgeMapFile(file: File): PhoneOfflineMapError? {
    if (!file.isNonEmptyFile()) return PhoneOfflineMapError.FILE_NOT_READABLE
    return runCatching { MapFile(file).close() }.fold(
        onSuccess = { null },
        onFailure = { PhoneOfflineMapError.INVALID },
    )
}

private fun String.isImportedNameFor(sourceFileName: String): Boolean {
    if (equals(sourceFileName, ignoreCase = true)) return true
    val sourceBaseName = sourceFileName.substringBeforeLast('.', sourceFileName)
    return startsWith("$sourceBaseName (") && endsWith(".map", ignoreCase = true)
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

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
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
