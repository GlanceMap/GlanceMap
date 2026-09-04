package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream

private const val ELEVATION_FOLDER_TARGET_SCAN_MAX_DEPTH = 3

internal enum class PhoneElevationFolderError {
    PERMISSION_LOST,
    SCAN_FAILED,
    COPY_FAILED,
}

internal data class PhoneElevationFolderSyncResult(
    val folderName: String? = null,
    val validCount: Int = 0,
    val importedCount: Int = 0,
    val reusedCount: Int = 0,
    val invalidCount: Int = 0,
    val error: PhoneElevationFolderError? = null,
)

internal data class PhoneElevationFolderInput(
    val name: String?,
    val isFile: Boolean,
    val openInputStream: () -> InputStream?,
)

/** Imports selected SAF elevation content into the canonical private elevation tree. */
internal class PhoneElevationFolderImporter(
    private val elevationDirectory: File,
    private val isUsableDemFile: (File) -> Boolean = ::isUsablePhoneDemFile,
) {
    // Promotion keeps a valid canonical DEM intact until its replacement validates.
    @Suppress("CyclomaticComplexMethod")
    fun synchronize(
        inputs: List<PhoneElevationFolderInput>,
        folderName: String? = null,
    ): PhoneElevationFolderSyncResult {
        var imported = 0
        var reused = 0
        var invalid = 0
        var firstError: PhoneElevationFolderError? = null
        inputs.forEach { input ->
            val fileName = phoneElevationFolderFileName(input.name)
            if (!input.isFile || fileName == null) return@forEach
            val target = existingElevationFile(fileName) ?: File(elevationDirectory, fileName)
            if (isUsableDemFile(target)) {
                reused += 1
                return@forEach
            }
            val temporary = File(elevationDirectory, "$fileName.import.part")
            val installed =
                runCatching {
                    if (!elevationDirectory.exists() && !elevationDirectory.mkdirs()) return@runCatching false
                    temporary.delete()
                    input.openInputStream()?.use { source ->
                        temporary.outputStream().use { destination -> source.copyTo(destination) }
                    } ?: return@runCatching false
                    if (!isUsableDemFile(temporary)) {
                        invalid += 1
                        return@runCatching false
                    }
                    if (target.exists() && !target.delete()) return@runCatching false
                    temporary.renameTo(target)
                }.getOrElse { false }
            if (installed) {
                imported += 1
            } else {
                temporary.delete()
                if (firstError == null && invalid == 0) {
                    firstError = PhoneElevationFolderError.COPY_FAILED
                }
            }
        }
        return PhoneElevationFolderSyncResult(
            folderName = folderName,
            validCount = imported + reused,
            importedCount = imported,
            reusedCount = reused,
            invalidCount = invalid,
            error = firstError,
        )
    }

    private fun existingElevationFile(fileName: String): File? =
        elevationDirectory
            .takeIf(File::isDirectory)
            ?.walkTopDown()
            ?.maxDepth(ELEVATION_FOLDER_TARGET_SCAN_MAX_DEPTH)
            ?.firstOrNull { file -> file.isFile && file.name == fileName }
}

/** Persists a direct SAF import source; Mapsforge continues to read only [PhoneElevationStore]. */
internal class PhoneElevationFolderSource(
    private val context: Context,
    private val elevationStore: PhoneElevationStore = PhoneElevationStore(context),
) {
    fun hasSelectedFolder(): Boolean = selectedFolderUri() != null

    fun selectFolder(uri: Uri): PhoneElevationFolderError? =
        runCatching {
            val previousUri = selectedFolderUri()
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            preferences().edit().putString(KEY_FOLDER_URI, uri.toString()).apply()
            if (previousUri != null && previousUri != uri) releasePermission(previousUri)
        }.fold(
            onSuccess = { null },
            onFailure = { PhoneElevationFolderError.PERMISSION_LOST },
        )

    fun clearSelectedFolder() {
        selectedFolderUri()?.let(::releasePermission)
        preferences().edit().remove(KEY_FOLDER_URI).apply()
    }

    @Suppress("ReturnCount") // Permission loss is a distinct recoverable state for persisted SAF access.
    fun syncSelectedFolder(): PhoneElevationFolderSyncResult {
        val treeUri = selectedFolderUri() ?: return PhoneElevationFolderSyncResult()
        val folder = runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull()
        if (folder == null || !folder.canRead()) {
            return PhoneElevationFolderSyncResult(error = PhoneElevationFolderError.PERMISSION_LOST)
        }
        return try {
            PhoneElevationFolderImporter(elevationStore.directory)
                .synchronize(
                    inputs =
                        folder.listFiles().map { document ->
                            PhoneElevationFolderInput(
                                name = document.name,
                                isFile = document.isFile,
                                openInputStream = { context.contentResolver.openInputStream(document.uri) },
                            )
                        },
                    folderName = folder.name?.takeIf(String::isNotBlank),
                )
        } catch (_: SecurityException) {
            PhoneElevationFolderSyncResult(error = PhoneElevationFolderError.PERMISSION_LOST)
        } catch (_: Exception) {
            PhoneElevationFolderSyncResult(error = PhoneElevationFolderError.SCAN_FAILED)
        }
    }

    private fun selectedFolderUri(): Uri? = preferences().getString(KEY_FOLDER_URI, null)?.let(Uri::parse)

    private fun releasePermission(uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun preferences() = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private companion object {
        const val PREFERENCES_NAME = "phone_elevation_folder"
        const val KEY_FOLDER_URI = "selected_folder_uri"
    }
}

internal fun phoneElevationFolderFileName(name: String?): String? =
    name
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.takeIf { value -> value == File(value).name && !value.contains('\\') }
        ?.takeIf(String::isPhoneDemFileName)
