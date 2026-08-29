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
)

internal data class PhoneGpxFolderScanResult(
    val files: List<PhoneGpxFolderFile> = emptyList(),
    val folderName: String? = null,
    val error: PhoneGpxFolderError? = null,
)

/** Persists one read-only SAF tree and exposes only its direct GPX children. */
internal class PhoneGpxFolderSource(
    private val context: Context,
) {
    fun hasSelectedFolder(): Boolean = selectedFolderUri() != null

    fun selectFolder(uri: Uri): PhoneGpxFolderError? =
        runCatching {
            val previousUri = selectedFolderUri()
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
            onFailure = { PhoneGpxFolderError.PERMISSION_LOST },
        )

    fun clearSelectedFolder() {
        selectedFolderUri()?.let { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
): PhoneGpxFolderFile? =
    name
        ?.takeIf { isFile && isPhoneGpxFolderFileName(it) }
        ?.let { displayName ->
            PhoneGpxFolderFile(
                id = phoneGpxFolderSourceId(documentUri),
                displayName = displayName,
                documentUri = documentUri,
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

internal fun phoneGpxFolderSourceId(documentUri: String): String = "folder:$documentUri"

internal fun List<PhoneGpxFolderFile>.normalizedPhoneGpxFolderFiles(): List<PhoneGpxFolderFile> {
    val distinctFiles = distinctBy(PhoneGpxFolderFile::id)
    return distinctFiles.sortedBy { file -> file.displayName.lowercase() }
}
