package com.glancemap.glancemapcompanionapp.library

import androidx.compose.ui.state.ToggleableState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

internal data class PhoneLibraryFolderRowState(
    val folder: PhoneLibraryFolder,
    val items: List<PhoneLibraryItem>,
    val regionPoiNames: Set<String>,
    val deleteEnabled: Boolean,
)

internal data class PhoneLibraryFolderDeletion(
    val deleted: List<PhoneLibraryItem>,
    val keptRegionPois: Int,
) {
    val deviceFiles = deleted.count { it is PhoneLibraryItem.FolderGpx }
}

internal fun PhoneLibraryOrganization.renameFolder(
    id: String,
    name: String,
): PhoneLibraryOrganization {
    val current = requireNotNull(folder(id))
    val trimmed = name.trim()
    copy(folders = folders.filterNot { it.id == id }).requireFolderName(trimmed, current.parentId, current.category)
    return copy(folders = folders.map { if (it.id == id) it.copy(name = trimmed) else it })
}

/** Entries must leave the folder too, because saved entries may only reference existing folders. */
internal fun PhoneLibraryOrganization.deleteFolder(id: String): PhoneLibraryOrganization {
    val removed = folderTree(id)
    return copy(
        folders = folders.filterNot { it.id in removed },
        entries =
            entries.mapValues { (_, entry) ->
                if (entry.folderId in removed) entry.copy(folderId = null) else entry
            },
    )
}

internal fun PhoneLibraryOrganization.folderItems(
    id: String,
    items: List<PhoneLibraryItem>,
): List<PhoneLibraryItem> {
    val tree = folderTree(id)
    return items.filter { it.filter == folder(id)?.category && entry(it).folderId in tree }
}

/** Region POIs belong to their region, so a folder delete only moves them back to the Library root. */
internal fun phoneLibraryFolderDeletion(
    items: List<PhoneLibraryItem>,
    regionPoiNames: Set<String>,
): PhoneLibraryFolderDeletion {
    val (regionPois, deleted) =
        items.partition { it is PhoneLibraryItem.Pois && it.source.fileName in regionPoiNames }
    return PhoneLibraryFolderDeletion(deleted, regionPois.size)
}

internal fun phoneLibraryRegionPoiNames(items: List<PhoneLibraryItem>): Set<String> =
    items
        .filterIsInstance<PhoneLibraryItem.Bundle>()
        .map { it.bundle.poiFileName }
        .toSet()

/** Removes items one at a time and returns those that failed, so the folder is only dropped once it is empty. */
internal suspend fun deletePhoneLibraryItems(
    items: List<PhoneLibraryItem>,
    delete: suspend (PhoneLibraryItem) -> Unit,
): List<PhoneLibraryItem> =
    items.filter { item ->
        try {
            delete(item)
            false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            true
        }
    }

internal suspend fun deletePhoneLibraryReceivedGpx(
    item: PhoneLibraryItem.ReceivedGpx,
    onDeleted: () -> Unit,
) {
    withContext(Dispatchers.IO) {
        if (!item.file.delete() && item.file.exists()) throw IOException("Could not delete received GPX")
    }
    onDeleted()
}

internal fun phoneLibraryFolderToggleState(visible: List<Boolean>): ToggleableState? =
    when {
        visible.isEmpty() -> null
        visible.all { it } -> ToggleableState.On
        visible.none { it } -> ToggleableState.Off
        else -> ToggleableState.Indeterminate
    }
