package com.glancemap.glancemapcompanionapp.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.glancemap.glancemapcompanionapp.map.PhoneElevationFolderImporter
import com.glancemap.glancemapcompanionapp.map.PhoneElevationFolderInput
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineStorage
import com.glancemap.glancemapcompanionapp.map.PhoneRoutingFolderImporter
import com.glancemap.glancemapcompanionapp.map.PhoneRoutingFolderInput
import com.glancemap.glancemapcompanionapp.map.phoneElevationFolderFileName
import com.glancemap.glancemapcompanionapp.map.phoneRoutingFolderFileName

internal data class PhoneLibraryOfflineImportResult(
    val added: Int,
    val reused: Int,
    val invalid: Int,
    val hasError: Boolean,
)

internal fun importPhoneLibraryOfflineFiles(
    context: Context,
    uris: List<Uri>,
    section: PhoneLibraryOfflineSection,
): PhoneLibraryOfflineImportResult {
    val storage = PhoneOfflineStorage(context)
    val resolver = context.contentResolver
    val named =
        uris.map { uri ->
            val name =
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: uri.lastPathSegment?.substringAfterLast('/')
            uri to name
        }
    return if (section == PhoneLibraryOfflineSection.ELEVATION) {
        val accepted = named.filter { phoneElevationFolderFileName(it.second) != null }
        val result =
            PhoneElevationFolderImporter(storage.elevationDirectory()).synchronize(
                accepted.map { (uri, name) -> PhoneElevationFolderInput(name, true) { resolver.openInputStream(uri) } },
            )
        PhoneLibraryOfflineImportResult(
            result.importedCount,
            result.reusedCount,
            result.invalidCount + uris.size - accepted.size,
            result.error != null || result.importedCount + result.reusedCount + result.invalidCount < accepted.size,
        )
    } else {
        require(section == PhoneLibraryOfflineSection.ROUTING)
        val accepted = named.filter { phoneRoutingFolderFileName(it.second) != null }
        val result =
            PhoneRoutingFolderImporter(storage.routingDirectory()).synchronize(
                accepted.map { (uri, name) -> PhoneRoutingFolderInput(name, true) { resolver.openInputStream(uri) } },
            )
        PhoneLibraryOfflineImportResult(
            result.importedCount,
            result.reusedCount,
            result.invalidCount + uris.size - accepted.size,
            result.error != null || result.importedCount + result.reusedCount + result.invalidCount < accepted.size,
        )
    }
}
