package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream

internal enum class PhoneGpxFolderError {
    PERMISSION_LOST,
    SCAN_FAILED,
}

/** A direct child of the configured SAF tree; the URI stays internal to the companion source. */
internal data class PhoneGpxFolderFile(
    val id: String,
    val displayName: String,
    val documentUri: String,
    val isWritable: Boolean = false,
)

internal data class PhoneGpxFolderScanResult(
    val files: List<PhoneGpxFolderFile> = emptyList(),
    val folderName: String? = null,
    val error: PhoneGpxFolderError? = null,
)

/** Persists one SAF tree and exposes only its direct GPX children. */
@Suppress("TooManyFunctions") // Selection, permission, scan, and file operations share one SAF boundary.
internal class PhoneGpxFolderSource(
    private val context: Context,
) {
    fun hasSelectedFolder(): Boolean = selectedFolderUri() != null

    fun selectFolder(uri: Uri): PhoneGpxFolderError? =
        runCatching {
            val previousUri = selectedFolderUri()
            takePersistedFolderPermission(uri)
            preferences().edit().putString(KEY_FOLDER_URI, uri.toString()).apply()
            if (previousUri != null && previousUri != uri) {
                runCatching {
                    releasePersistedFolderPermission(previousUri)
                }
            }
        }.fold(
            onSuccess = { null },
            onFailure = { PhoneGpxFolderError.PERMISSION_LOST },
        )

    fun clearSelectedFolder() {
        selectedFolderUri()?.let { uri ->
            runCatching {
                releasePersistedFolderPermission(uri)
            }
        }
        preferences().edit().remove(KEY_FOLDER_URI).apply()
    }

    fun scanSelectedFolder(): PhoneGpxFolderScanResult {
        val treeUri = selectedFolderUri() ?: return PhoneGpxFolderScanResult()
        val folder = runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull()
        return if (folder == null || !folder.canRead()) {
            PhoneGpxFolderScanResult(error = PhoneGpxFolderError.PERMISSION_LOST)
        } else {
            scanFolder(folder)
        }
    }

    private fun scanFolder(folder: DocumentFile): PhoneGpxFolderScanResult =
        try {
            PhoneGpxFolderScanResult(
                files =
                    folder
                        .listFiles()
                        .mapNotNull { document ->
                            phoneGpxFolderFile(
                                name = document.name,
                                isFile = document.isFile,
                                documentUri = document.uri.toString(),
                                isWritable = document.canWrite(),
                            )
                        }.normalizedPhoneGpxFolderFiles(),
                folderName = folder.name?.takeIf(String::isNotBlank),
            )
        } catch (_: SecurityException) {
            PhoneGpxFolderScanResult(error = PhoneGpxFolderError.PERMISSION_LOST)
        } catch (_: Exception) {
            PhoneGpxFolderScanResult(error = PhoneGpxFolderError.SCAN_FAILED)
        }

    fun openInputStream(file: PhoneGpxFolderFile): InputStream? {
        val documentUri = Uri.parse(file.documentUri)
        return context.contentResolver.openInputStream(documentUri)
    }

    fun rename(
        file: PhoneGpxFolderFile,
        newName: String,
    ) {
        val document = mutableDocument(file)
        require(document.renameTo(phoneGpxFolderFileName(newName))) { "The GPX file could not be renamed." }
    }

    fun delete(file: PhoneGpxFolderFile) {
        val document = mutableDocument(file)
        require(document.delete()) { "The GPX file could not be deleted." }
    }

    private fun mutableDocument(file: PhoneGpxFolderFile): DocumentFile {
        require(file.isWritable) { "Android did not grant write access to this GPX folder." }
        val document = DocumentFile.fromSingleUri(context, Uri.parse(file.documentUri))
        require(document != null && document.canWrite()) { "Android did not grant write access to this GPX file." }
        return document
    }

    private fun takePersistedFolderPermission(uri: Uri) {
        val resolver = context.contentResolver
        val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.takePersistableUriPermission(uri, readWrite) }
            .recoverCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .getOrThrow()
    }

    private fun releasePersistedFolderPermission(uri: Uri) {
        val resolver = context.contentResolver
        val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.releasePersistableUriPermission(uri, readWrite) }
            .recoverCatching { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .getOrThrow()
    }

    private fun selectedFolderUri(): Uri? = preferences().getString(KEY_FOLDER_URI, null)?.let(Uri::parse)

    private fun preferences() = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private companion object {
        const val PREFERENCES_NAME = "phone_gpx_folder"
        const val KEY_FOLDER_URI = "selected_folder_uri"
    }
}

internal fun phoneGpxFolderFile(
    name: String?,
    isFile: Boolean,
    documentUri: String,
    isWritable: Boolean = false,
): PhoneGpxFolderFile? =
    name
        ?.takeIf { isFile && isPhoneGpxFolderFileName(it) }
        ?.let { displayName ->
            PhoneGpxFolderFile(
                id = phoneGpxFolderSourceId(documentUri),
                displayName = displayName,
                documentUri = documentUri,
                isWritable = isWritable,
            )
        }

internal fun isPhoneGpxFolderFileName(name: String): Boolean {
    val baseName = name.dropLastWhile(Char::isWhitespace)
    if (!baseName.endsWith(".gpx", ignoreCase = true)) return false
    val stem = baseName.dropLast(4)
    return stem.isNotBlank() &&
        !stem.startsWith('~') &&
        !stem.endsWith(".part", ignoreCase = true) &&
        !stem.endsWith(".tmp", ignoreCase = true)
}

internal fun phoneGpxFolderFileName(value: String): String {
    val trimmed = value.trim().replace(Regex("\\s+"), " ")
    require(trimmed.isNotBlank()) { "Enter a GPX name first." }
    val safeName = java.io.File(trimmed).name
    require(safeName == trimmed) { "Enter a GPX name without a folder path." }
    val baseName = safeName.removePhoneGpxFileExtension(".gpx").trim()
    require(baseName.isNotBlank()) { "Enter a GPX name first." }
    return "$baseName.gpx"
}

internal fun phoneGpxFolderSourceId(documentUri: String): String = "folder:$documentUri"

private fun String.removePhoneGpxFileExtension(extension: String): String =
    takeIf { endsWith(extension, ignoreCase = true) }
        ?.dropLast(extension.length)
        ?: this

internal fun List<PhoneGpxFolderFile>.normalizedPhoneGpxFolderFiles(): List<PhoneGpxFolderFile> {
    val distinctFiles = distinctBy(PhoneGpxFolderFile::id)
    return distinctFiles.sortedBy { file -> phoneGpxDisplayNameFromFileName(file.displayName).lowercase() }
}
