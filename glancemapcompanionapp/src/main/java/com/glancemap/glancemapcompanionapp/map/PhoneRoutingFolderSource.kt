package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDownloadDiagnostics
import java.io.File
import java.io.InputStream

internal enum class PhoneRoutingFolderError {
    PERMISSION_LOST,
    SCAN_FAILED,
    COPY_FAILED,
}

internal data class PhoneRoutingFolderSyncResult(
    val folderName: String? = null,
    val validCount: Int = 0,
    val importedCount: Int = 0,
    val reusedCount: Int = 0,
    val invalidCount: Int = 0,
    val error: PhoneRoutingFolderError? = null,
)

internal data class PhoneRoutingFolderInput(
    val name: String?,
    val isFile: Boolean,
    val openInputStream: () -> InputStream?,
)

/** Imports selected SAF routing packs into the single directory consumed by BRouter. */
internal class PhoneRoutingFolderImporter(
    private val routingDirectory: File,
    private val isUsableRoutingFile: (File) -> Boolean = ::isUsablePhoneRoutingFile,
) {
    @Suppress(
        "CyclomaticComplexMethod",
        "LongMethod",
    ) // One pack is imported through an atomic replacement state machine.
    fun synchronize(
        inputs: List<PhoneRoutingFolderInput>,
        folderName: String? = null,
    ): PhoneRoutingFolderSyncResult {
        var imported = 0
        var reused = 0
        var invalid = 0
        var firstError: PhoneRoutingFolderError? = null
        inputs.forEach { input ->
            val fileName = phoneRoutingFolderFileName(input.name)
            if (!input.isFile || fileName == null) return@forEach

            val target = File(routingDirectory, fileName)
            val existing = existingRoutingFile(fileName)
            if (existing != null && existing.absoluteFile != target.absoluteFile && isUsableRoutingFile(existing)) {
                if (existing.renameTo(target)) {
                    reused += 1
                    PhoneDownloadDiagnostics.log(
                        "RoutingFolder",
                        "reused file=${target.name}",
                    )
                    return@forEach
                }
            }
            if (isUsableRoutingFile(target)) {
                reused += 1
                PhoneDownloadDiagnostics.log(
                    "RoutingFolder",
                    "reused file=${target.name}",
                )
                return@forEach
            }

            val temporary = File(routingDirectory, ".$fileName.import.part")
            val backup = File(routingDirectory, ".$fileName.import.backup")
            var sourceWasInvalid = false
            val installed =
                runCatching {
                    if (!routingDirectory.exists() && !routingDirectory.mkdirs()) return@runCatching false
                    temporary.delete()
                    backup.delete()
                    input.openInputStream()?.use { source ->
                        temporary.outputStream().use { destination -> source.copyTo(destination) }
                    } ?: return@runCatching false
                    if (!isUsableRoutingFile(temporary)) {
                        sourceWasInvalid = true
                        invalid += 1
                        PhoneDownloadDiagnostics.warn(
                            "RoutingFolder",
                            "invalid file=$fileName",
                        )
                        return@runCatching false
                    }

                    // Keep an invalid or old destination recoverable until the new RD5 is ready.
                    if (target.exists() && !target.renameTo(backup)) return@runCatching false
                    if (!temporary.renameTo(target)) {
                        if (backup.exists()) backup.renameTo(target)
                        return@runCatching false
                    }
                    backup.delete()
                    true
                }.getOrElse { false }
            if (installed) {
                imported += 1
                PhoneDownloadDiagnostics.log(
                    "RoutingFolder",
                    "imported file=$fileName",
                )
            } else {
                temporary.delete()
                if (backup.exists() && !target.exists()) backup.renameTo(target)
                if (firstError == null && !sourceWasInvalid) {
                    firstError = PhoneRoutingFolderError.COPY_FAILED
                }
            }
        }
        return PhoneRoutingFolderSyncResult(
            folderName = folderName,
            validCount = imported + reused,
            importedCount = imported,
            reusedCount = reused,
            invalidCount = invalid,
            error = firstError,
        )
    }

    private fun existingRoutingFile(fileName: String): File? =
        routingDirectory
            .takeIf(File::isDirectory)
            ?.listFiles()
            ?.let { files ->
                files.firstOrNull { file -> file.isFile && file.name == fileName }
                    ?: files.firstOrNull { file -> file.isFile && file.name.equals(fileName, ignoreCase = true) }
            }
}

/** Persists the SAF source; route planning and bundle downloads read only canonical storage. */
internal class PhoneRoutingFolderSource(
    private val context: Context,
    private val storage: PhoneOfflineStorage = PhoneOfflineStorage(context),
) {
    fun hasSelectedFolder(): Boolean = selectedFolderUri() != null

    fun selectFolder(uri: Uri): PhoneRoutingFolderError? =
        runCatching {
            val previousUri = selectedFolderUri()
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            preferences().edit().putString(KEY_FOLDER_URI, uri.toString()).apply()
            if (previousUri != null && previousUri != uri) releasePermission(previousUri)
            PhoneDownloadDiagnostics.log("RoutingFolder", "folder selected")
        }.fold(
            onSuccess = { null },
            onFailure = {
                PhoneDownloadDiagnostics.warn("RoutingFolder", "folder selection permission failed")
                PhoneRoutingFolderError.PERMISSION_LOST
            },
        )

    fun clearSelectedFolder() {
        selectedFolderUri()?.let(::releasePermission)
        preferences().edit().remove(KEY_FOLDER_URI).apply()
        PhoneDownloadDiagnostics.log("RoutingFolder", "folder cleared")
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught") // SAF permission and provider failures stay user-recoverable.
    fun syncSelectedFolder(): PhoneRoutingFolderSyncResult {
        val treeUri = selectedFolderUri() ?: return PhoneRoutingFolderSyncResult()
        PhoneDownloadDiagnostics.log("RoutingFolder", "scan started")
        val folder = runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull()
        if (folder == null || !folder.canRead()) {
            PhoneDownloadDiagnostics.warn("RoutingFolder", "permission unavailable")
            return PhoneRoutingFolderSyncResult(error = PhoneRoutingFolderError.PERMISSION_LOST)
        }
        return try {
            val result =
                PhoneRoutingFolderImporter(storage.routingDirectory()).synchronize(
                    inputs =
                        folder.listFiles().map { document ->
                            PhoneRoutingFolderInput(
                                name = document.name,
                                isFile = document.isFile,
                                openInputStream = { context.contentResolver.openInputStream(document.uri) },
                            )
                        },
                    folderName = folder.name?.takeIf(String::isNotBlank),
                )
            PhoneDownloadDiagnostics.log(
                "RoutingFolder",
                "scan completed folder=${result.folderName ?: "unknown"} " +
                    "valid=${result.validCount} imported=${result.importedCount} " +
                    "reused=${result.reusedCount} invalid=${result.invalidCount}",
            )
            result
        } catch (_: SecurityException) {
            PhoneDownloadDiagnostics.warn("RoutingFolder", "permission lost while scanning")
            PhoneRoutingFolderSyncResult(error = PhoneRoutingFolderError.PERMISSION_LOST)
        } catch (error: Exception) {
            PhoneDownloadDiagnostics.error("RoutingFolder", "scan failed", error)
            PhoneRoutingFolderSyncResult(error = PhoneRoutingFolderError.SCAN_FAILED)
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
        const val PREFERENCES_NAME = "phone_routing_folder"
        const val KEY_FOLDER_URI = "selected_folder_uri"
    }
}

/** Accept only a basename with a final .rd5 suffix; download partials are never import candidates. */
@Suppress("ReturnCount") // Each rejected filename condition is an explicit safety boundary.
internal fun phoneRoutingFolderFileName(name: String?): String? {
    val trimmed = name?.trim()?.takeIf(String::isNotBlank) ?: return null
    if (trimmed != File(trimmed).name || trimmed.contains('\\')) return null
    if (!trimmed.endsWith(".rd5", ignoreCase = true)) return null
    return trimmed.dropLast(".rd5".length) + ".rd5"
}
