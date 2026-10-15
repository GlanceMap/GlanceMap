package com.glancemap.glancemapcompanionapp.library

import androidx.annotation.StringRes
import com.glancemap.glancemapcompanionapp.R
import com.google.gson.annotations.SerializedName

internal enum class PhoneLibraryGpxKind(
    @StringRes val label: Int,
) {
    ROUTE(R.string.library_routes_label),
    RECORDING(R.string.library_filter_recorded),
}

internal enum class PhoneLibraryActivity(
    @StringRes val label: Int,
) {
    @SerializedName(value = "hike", alternate = ["HIKE"])
    HIKE(R.string.library_activity_hike),

    @SerializedName(value = "bike", alternate = ["BIKE"])
    BIKE(R.string.library_activity_bike),

    @SerializedName(value = "unspecified", alternate = ["UNSPECIFIED"])
    UNSPECIFIED(R.string.library_activity_unspecified),
}

internal data class PhoneLibraryFolder(
    @field:SerializedName("id")
    val id: String,
    @field:SerializedName("name")
    val name: String,
    @field:SerializedName("parentId")
    val parentId: String? = null,
)

internal data class PhoneLibraryEntryOrganization(
    @field:SerializedName("folderId")
    val folderId: String? = null,
    @field:SerializedName("activity")
    val activity: PhoneLibraryActivity? = null,
)

internal data class PhoneLibraryOrganization(
    @field:SerializedName("folders")
    val folders: List<PhoneLibraryFolder> = emptyList(),
    @field:SerializedName("entries")
    val entries: Map<String, PhoneLibraryEntryOrganization> = emptyMap(),
) {
    fun folder(id: String?): PhoneLibraryFolder? = folders.firstOrNull { it.id == id }

    fun entry(item: PhoneLibraryItem): PhoneLibraryEntryOrganization {
        val key = item.organizationKey()
        return entries[key] ?: PhoneLibraryEntryOrganization()
    }

    fun folderPath(id: String?): String {
        val names = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        var current = folder(id)
        while (current != null && seen.add(current.id)) {
            names += current.name
            current = folder(current.parentId)
        }
        return names.asReversed().joinToString(" / ")
    }

    fun createFolder(
        id: String,
        name: String,
        parentId: String?,
    ): PhoneLibraryOrganization {
        val trimmed = name.trim()
        require(id.isNotBlank() && folders.none { it.id == id })
        require(parentId == null || folder(parentId) != null)
        require(trimmed.isNotBlank() && trimmed.length <= 80)
        require(folders.none { it.parentId == parentId && it.name.equals(trimmed, ignoreCase = true) })
        return copy(folders = folders + PhoneLibraryFolder(id, trimmed, parentId))
    }

    fun move(
        item: PhoneLibraryItem,
        folderId: String?,
    ): PhoneLibraryOrganization {
        require(folderId == null || folder(folderId) != null)
        return copy(entries = entries + (item.organizationKey() to entry(item).copy(folderId = folderId)))
    }

    fun classify(
        item: PhoneLibraryItem,
        activity: PhoneLibraryActivity,
    ): PhoneLibraryOrganization {
        val updated = entry(item).copy(activity = activity)
        return copy(entries = entries + (item.organizationKey() to updated))
    }

    fun containsFolder(
        ancestorId: String?,
        descendantId: String?,
    ): Boolean {
        if (ancestorId == null) return true
        val seen = mutableSetOf<String>()
        var current = descendantId
        while (current != null && current != ancestorId && seen.add(current)) {
            current = folder(current)?.parentId
        }
        return current == ancestorId
    }
}

internal data class PhoneLibraryBrowseState(
    val folderId: String? = null,
    val query: String = "",
    val kind: PhoneLibraryGpxKind? = null,
    val activity: PhoneLibraryActivity? = null,
    val offlineSection: PhoneLibraryOfflineSection = PhoneLibraryOfflineSection.AREAS,
)

internal data class PhoneLibraryOrganizationActions(
    val enabled: Boolean = true,
    val onCreateFolder: (String?, String) -> Unit = { _, _ -> },
    val onMoveItem: (PhoneLibraryItem, String?) -> Unit = { _, _ -> },
    val onActivity: (PhoneLibraryItem, PhoneLibraryActivity) -> Unit = { _, _ -> },
)

// shortcut: POI/map placement follows source identity; migrate it when rename actions move into Library.
internal fun PhoneLibraryItem.organizationKey(): String =
    when (this) {
        is PhoneLibraryItem.Gpx -> route.sourceExportId?.let { "export/$it" } ?: key
        is PhoneLibraryItem.ReceivedGpx -> "export/$exportId"
        else -> key
    }

internal fun PhoneLibraryItem.gpxKind(): PhoneLibraryGpxKind? =
    when (this) {
        is PhoneLibraryItem.Gpx -> if (route.isRecording) PhoneLibraryGpxKind.RECORDING else PhoneLibraryGpxKind.ROUTE
        is PhoneLibraryItem.FolderGpx ->
            if (item.isRecording) PhoneLibraryGpxKind.RECORDING else PhoneLibraryGpxKind.ROUTE
        else -> null
    }

internal fun browsePhoneLibraryItems(
    items: List<PhoneLibraryItem>,
    category: PhoneLibraryFilter,
    browse: PhoneLibraryBrowseState,
    organization: PhoneLibraryOrganization,
): List<PhoneLibraryItem> =
    filterPhoneLibraryItems(items, category, browse.query).filter { item ->
        val entry = organization.entry(item)
        val inFolder =
            if (browse.query.isBlank()) {
                entry.folderId == browse.folderId
            } else {
                organization.containsFolder(browse.folderId, entry.folderId)
            }
        inFolder &&
            (category != PhoneLibraryFilter.BUNDLES || item.offlineSection() == browse.offlineSection) &&
            (category != PhoneLibraryFilter.GPX || browse.kind == null || item.gpxKind() == browse.kind) &&
            (
                category != PhoneLibraryFilter.GPX ||
                    browse.activity == null ||
                    (entry.activity ?: PhoneLibraryActivity.UNSPECIFIED) == browse.activity
            )
    }

internal fun PhoneLibraryItem.offlineSection(): PhoneLibraryOfflineSection =
    when (this) {
        is PhoneLibraryItem.OfflineData -> section
        else -> PhoneLibraryOfflineSection.AREAS
    }

internal fun PhoneLibraryOrganization.parentBrowse(browse: PhoneLibraryBrowseState): PhoneLibraryBrowseState =
    browse.copy(
        folderId = folder(browse.folderId)?.parentId,
        query = "",
    )

internal fun validatePhoneLibraryOrganization(organization: PhoneLibraryOrganization): PhoneLibraryOrganization {
    val folders =
        organization.folders.orEmpty().requireNoNulls().map { folder ->
            folder.copy(id = requiredLibraryText(folder.id), name = requiredLibraryText(folder.name))
        }
    val entries = organization.entries.orEmpty()
    var checked = PhoneLibraryOrganization()
    val remaining = folders.toMutableList()
    while (remaining.isNotEmpty()) {
        val ready = remaining.filter { it.parentId == null || checked.folder(it.parentId) != null }
        require(ready.isNotEmpty())
        ready.forEach { checked = checked.createFolder(it.id, it.name, it.parentId) }
        remaining.removeAll(ready.toSet())
    }
    entries.forEach { (key, entry) -> validateLibraryEntry(key, entry, checked) }
    return checked.copy(entries = entries)
}

private fun requiredLibraryText(value: String?): String = requireNotNull(value).also { require(it.isNotBlank()) }

private fun validateLibraryEntry(
    key: String?,
    entry: PhoneLibraryEntryOrganization?,
    organization: PhoneLibraryOrganization,
) {
    requiredLibraryText(key)
    val existing = requireNotNull(entry)
    require(existing.folderId == null || organization.folder(existing.folderId) != null)
}
