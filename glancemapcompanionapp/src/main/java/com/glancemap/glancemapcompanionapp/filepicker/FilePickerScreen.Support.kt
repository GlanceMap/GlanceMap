package com.glancemap.glancemapcompanionapp.filepicker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.glancemap.glancemapcompanionapp.FileTransferUiState
import com.glancemap.glancemapcompanionapp.FileTransferViewModel
import com.glancemap.glancemapcompanionapp.GeneratedPhoneFile
import com.glancemap.glancemapcompanionapp.PoiImportSource
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineStorage
import java.io.File

internal data class ExternalDownloadSource(
    val category: String,
    val label: String,
    val url: String,
    val guidance: String? = null,
)

internal data class ThemeLegendLink(
    val label: String,
    val url: String,
)

internal data class ThemeLegendSource(
    val label: String,
    val links: List<ThemeLegendLink>,
)

internal data class PhoneStoredFilesGroup(
    val fileCount: Int,
    val totalBytes: Long,
    val fileNames: Set<String>,
)

internal data class PhoneStoredFilesSummary(
    val poi: PhoneStoredFilesGroup,
    val routing: PhoneStoredFilesGroup,
)

internal enum class PoiAreaMethod {
    WATCH_MAP,
    REFUGES_PRESET,
    MAP_PICKER,
    MANUAL_BBOX,
}

internal enum class RoutingAreaMethod {
    WATCH_MAP,
    TILE_PICKER,
    MANUAL_BBOX,
}

internal data class ClearPhoneStoredFilesResult(
    val message: String,
    val removedFileNames: Set<String>,
)

internal fun suggestPoiImportFileName(
    mapFileName: String?,
    source: PoiImportSource,
    enrichWithOsm: Boolean,
): String {
    val base = sanitizeMapBaseForPoiFileName(mapFileName)
    val prefix =
        when (source) {
            PoiImportSource.REFUGES -> {
                if (enrichWithOsm) "refuges-info-enriched" else "refuges-info"
            }
            PoiImportSource.OSM -> "osm-mountain"
        }
    return if (base == null) "$prefix.poi" else "$prefix-$base.poi"
}

internal fun sanitizeMapBaseForPoiFileName(mapFileName: String?): String? {
    val raw = mapFileName?.trim().orEmpty()
    if (raw.isBlank()) return null
    val base =
        raw
            .replace(Regex("\\.map$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
    return base.ifBlank { null }
}

internal fun isAutoPoiFileName(fileName: String): Boolean {
    val normalized = fileName.trim().lowercase()
    if (!normalized.endsWith(".poi")) return false
    return normalized.startsWith("refuges-info") ||
        normalized.startsWith("osm-mountain") ||
        normalized.startsWith("refuges-")
}

internal fun formatSelectedFilesSummary(fileNames: List<String>): String {
    if (fileNames.isEmpty()) return "No file selected"

    val grouped =
        fileNames
            .map { name ->
                val trimmed = name.trim()
                val ext = trimmed.substringAfterLast('.', "").lowercase()
                if (ext.isBlank() || ext == trimmed.lowercase()) "other" else ext
            }.groupingBy { it }
            .eachCount()

    val preferredOrder = listOf("gpx", "poi", "map", "other")
    val orderedKeys =
        grouped.keys.sortedWith(
            compareBy<String> { key ->
                val idx = preferredOrder.indexOf(key)
                if (idx >= 0) idx else Int.MAX_VALUE
            }.thenBy { it },
        )

    return orderedKeys.joinToString(", ") { key ->
        val count = grouped[key] ?: 0
        val label = if (key == "other") "other" else ".$key"
        if (count == 1) "$count $label file" else "$count $label files"
    }
}

internal fun shouldAutoOpenHelpOnFirstLaunch(context: Context): Boolean {
    val prefs = context.getSharedPreferences(COMPANION_UI_PREFS, Context.MODE_PRIVATE)
    return prefs.getBoolean(KEY_AUTO_OPEN_HELP_ON_FIRST_LAUNCH, true)
}

internal fun markHelpShown(context: Context) {
    context
        .getSharedPreferences(COMPANION_UI_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_AUTO_OPEN_HELP_ON_FIRST_LAUNCH, false)
        .apply()
}

internal fun shouldAutoOpenSendToWatchGuide(context: Context): Boolean =
    shouldAutoOpenOneShotGuide(
        context = context,
        key = KEY_AUTO_OPEN_SEND_TO_WATCH_GUIDE,
    )

internal fun markSendToWatchGuideShown(context: Context) {
    markOneShotGuideShown(
        context = context,
        key = KEY_AUTO_OPEN_SEND_TO_WATCH_GUIDE,
    )
}

internal fun shouldAutoOpenLiveTrackingGuide(context: Context): Boolean =
    shouldAutoOpenOneShotGuide(
        context = context,
        key = KEY_AUTO_OPEN_LIVE_TRACKING_GUIDE,
    )

internal fun markLiveTrackingGuideShown(context: Context) {
    markOneShotGuideShown(
        context = context,
        key = KEY_AUTO_OPEN_LIVE_TRACKING_GUIDE,
    )
}

private fun shouldAutoOpenOneShotGuide(
    context: Context,
    key: String,
): Boolean {
    val prefs = context.getSharedPreferences(COMPANION_UI_PREFS, Context.MODE_PRIVATE)
    return prefs.getBoolean(key, true)
}

private fun markOneShotGuideShown(
    context: Context,
    key: String,
) {
    context
        .getSharedPreferences(COMPANION_UI_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(key, false)
        .apply()
}

internal fun hasNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 33) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
}

internal fun saveGeneratedFileToUri(
    context: Context,
    source: GeneratedPhoneFile,
    destinationUri: Uri,
): String =
    runCatching {
        context.contentResolver.openInputStream(source.uri)?.use { input ->
            context.contentResolver.openOutputStream(destinationUri, "w")?.use { output ->
                input.copyTo(output)
            } ?: error("Unable to open destination file.")
        } ?: error("Unable to open source file.")
        "${source.fileName} saved on phone."
    }.getOrElse {
        it.localizedMessage?.takeIf { message -> message.isNotBlank() }
            ?: "Failed to save ${source.fileName}."
    }

internal fun saveGeneratedFilesToTree(
    context: Context,
    files: List<GeneratedPhoneFile>,
    treeUri: Uri,
): String {
    val folder =
        DocumentFile.fromTreeUri(context, treeUri)
            ?: return "Failed to open selected folder."

    var savedCount = 0
    var firstFailure: String? = null

    files.forEach { file ->
        runCatching {
            folder.findFile(file.fileName)?.delete()
            val destination =
                folder.createFile(
                    guessGeneratedFileMimeType(file.fileName),
                    file.fileName,
                ) ?: error("Unable to create ${file.fileName}.")

            context.contentResolver.openInputStream(file.uri)?.use { input ->
                context.contentResolver.openOutputStream(destination.uri, "w")?.use { output ->
                    input.copyTo(output)
                } ?: error("Unable to open ${file.fileName} for writing.")
            } ?: error("Unable to open ${file.fileName}.")
            savedCount += 1
        }.onFailure {
            if (firstFailure == null) {
                firstFailure = it.localizedMessage?.takeIf { message -> message.isNotBlank() }
            }
        }
    }

    return when {
        savedCount == files.size -> "${files.size} file(s) saved on phone."
        savedCount > 0 -> "$savedCount/${files.size} file(s) saved on phone. ${firstFailure.orEmpty()}".trim()
        else -> firstFailure ?: "Failed to save files on phone."
    }
}

internal fun guessGeneratedFileMimeType(fileName: String): String =
    when {
        fileName.endsWith(".gpx", ignoreCase = true) -> "application/gpx+xml"
        fileName.endsWith(".poi", ignoreCase = true) -> "application/octet-stream"
        fileName.endsWith(".rd5", ignoreCase = true) -> "application/octet-stream"
        else -> "application/octet-stream"
    }

internal fun emptyPhoneStoredFilesSummary(): PhoneStoredFilesSummary =
    PhoneStoredFilesSummary(
        poi = PhoneStoredFilesGroup(fileCount = 0, totalBytes = 0L, fileNames = emptySet()),
        routing = PhoneStoredFilesGroup(fileCount = 0, totalBytes = 0L, fileNames = emptySet()),
    )

internal fun loadPhoneStoredFilesSummary(context: Context): PhoneStoredFilesSummary =
    PhoneOfflineStorage(context).let { storage ->
        PhoneStoredFilesSummary(
            poi =
                summarizeGeneratedFiles(
                    directory = storage.poiDirectory(),
                    extension = ".poi",
                ),
            routing =
                summarizeGeneratedFiles(
                    directory = storage.routingDirectory(),
                    extension = ".rd5",
                ),
        )
    }

internal fun summarizeGeneratedFiles(
    directory: File,
    extension: String,
): PhoneStoredFilesGroup {
    val files =
        directory
            .listFiles()
            ?.filter { it.isFile && it.name.endsWith(extension, ignoreCase = true) }
            .orEmpty()
    return PhoneStoredFilesGroup(
        fileCount = files.size,
        totalBytes = files.sumOf { it.length().coerceAtLeast(0L) },
        fileNames = files.map { it.name }.toSet(),
    )
}

internal fun clearPhoneStoredFiles(
    context: Context,
    clearPoi: Boolean,
    clearRouting: Boolean,
): ClearPhoneStoredFilesResult {
    val removedFileNames = linkedSetOf<String>()
    var removedCount = 0

    val storage = PhoneOfflineStorage(context)
    if (clearPoi) {
        val dir = storage.poiDirectory()
        dir
            .listFiles()
            ?.filter { it.isFile && it.name.endsWith(".poi", ignoreCase = true) }
            .orEmpty()
            .forEach { file ->
                if (file.delete()) {
                    removedCount += 1
                    removedFileNames += file.name
                }
            }
    }

    if (clearRouting) {
        val dir = storage.routingDirectory()
        dir
            .listFiles()
            ?.filter { it.isFile && it.name.endsWith(".rd5", ignoreCase = true) }
            .orEmpty()
            .forEach { file ->
                if (file.delete()) {
                    removedCount += 1
                    removedFileNames += file.name
                }
            }
    }

    val message =
        when {
            removedCount == 0 -> "No phone files were removed."
            clearPoi && clearRouting -> "$removedCount phone file(s) cleared."
            clearPoi -> "$removedCount POI file(s) cleared."
            else -> "$removedCount routing file(s) cleared."
        }

    return ClearPhoneStoredFilesResult(
        message = message,
        removedFileNames = removedFileNames,
    )
}

internal fun removeClearedGeneratedFilesFromSelection(
    context: Context,
    viewModel: FileTransferViewModel,
    uiState: FileTransferUiState,
    removedFileNames: Set<String>,
) {
    if (removedFileNames.isEmpty() || uiState.selectedFileUris.isEmpty()) return

    val remainingUris =
        uiState.selectedFileUris
            .zip(uiState.selectedFileDisplayNames)
            .filter { (_, displayName) -> displayName !in removedFileNames }
            .map { (uri, _) -> uri }

    if (remainingUris.size == uiState.selectedFileUris.size) return

    viewModel.clearSelectedFiles()
    if (remainingUris.isNotEmpty()) {
        viewModel.loadFilesFromUris(context, remainingUris)
    }
}

internal fun formatPhoneStoredFilesSummary(
    context: Context,
    group: PhoneStoredFilesGroup,
): String =
    if (group.fileCount > 0) {
        "${group.fileCount} file(s) · ${Formatter.formatShortFileSize(context, group.totalBytes)}"
    } else {
        "No files"
    }

private const val COMPANION_UI_PREFS = "companion_ui_prefs"
private const val KEY_AUTO_OPEN_HELP_ON_FIRST_LAUNCH = "auto_open_help_on_first_launch"
private const val KEY_AUTO_OPEN_SEND_TO_WATCH_GUIDE = "auto_open_send_to_watch_guide"
private const val KEY_AUTO_OPEN_LIVE_TRACKING_GUIDE = "auto_open_live_tracking_guide"
