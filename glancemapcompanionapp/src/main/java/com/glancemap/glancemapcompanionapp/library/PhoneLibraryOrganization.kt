package com.glancemap.glancemapcompanionapp.library

import androidx.annotation.StringRes
import com.glancemap.glancemapcompanionapp.R
import com.google.gson.annotations.SerializedName

internal enum class PhoneLibraryGpxKind(
    @StringRes val label: Int,
) {
    ROUTE(R.string.library_filter_planned),
    RECORDING(R.string.library_filter_recorded),
}

internal enum class PhoneLibraryActivity(
    @StringRes val label: Int,
) {
    @SerializedName(value = "hike", alternate = ["HIKE", "unspecified", "UNSPECIFIED"])
    HIKE(R.string.library_activity_hike),

    @SerializedName(value = "bike", alternate = ["BIKE"])
    BIKE(R.string.library_activity_bike),
}

internal data class PhoneLibraryFolder(
    @field:SerializedName("id")
    val id: String,
    @field:SerializedName("name")
    val name: String,
    @field:SerializedName("parentId")
    val parentId: String? = null,
    // Null only on folders saved before each tab had its own folders.
    @field:SerializedName("category")
    val category: PhoneLibraryFilter? = PhoneLibraryFilter.GPX,
)

internal val PhoneLibraryFilter.hasFolders: Boolean
    get() = this == PhoneLibraryFilter.GPX || this == PhoneLibraryFilter.POIS

internal data class PhoneLibraryEntryOrganization(
    @field:SerializedName("folderId")
    val folderId: String? = null,
    // Null means no manual choice: the GPX's own recorded activity, otherwise Hike, applies.
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
        val entry = storedEntry(item)
        return if (entry.activity == null) entry.copy(activity = item.defaultActivity()) else entry
    }

    private fun storedEntry(item: PhoneLibraryItem): PhoneLibraryEntryOrganization {
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
        category: PhoneLibraryFilter? = PhoneLibraryFilter.GPX,
    ): PhoneLibraryOrganization {
        val trimmed = name.trim()
        require(id.isNotBlank() && folders.none { it.id == id })
        require(parentId == null || folder(parentId)?.category == category)
        requireFolderName(trimmed, parentId, category)
        return copy(folders = folders + PhoneLibraryFolder(id, trimmed, parentId, category))
    }

    fun folderTree(id: String): Set<String> {
        val tree = linkedSetOf(id)
        var added = true
        while (added) {
            added = tree.addAll(folders.filter { it.parentId in tree }.map(PhoneLibraryFolder::id))
        }
        return tree
    }

    internal fun requireFolderName(
        name: String,
        parentId: String?,
        category: PhoneLibraryFilter?,
    ) {
        require(name.isNotBlank() && name.length <= 80)
        require(
            folders.none {
                it.parentId == parentId && it.category == category && it.name.equals(name, ignoreCase = true)
            },
        )
    }

    fun move(
        item: PhoneLibraryItem,
        folderId: String?,
    ): PhoneLibraryOrganization {
        require(folderId == null || folder(folderId) != null)
        // Moving keeps an automatic activity automatic instead of freezing the current default.
        return copy(entries = entries + (item.organizationKey() to storedEntry(item).copy(folderId = folderId)))
    }

    fun classify(
        item: PhoneLibraryItem,
        activity: PhoneLibraryActivity,
    ): PhoneLibraryOrganization {
        val updated = storedEntry(item).copy(activity = activity)
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
    /** The POI file and category opened on the POIs tab, like a folder. */
    val poiFile: String? = null,
    val poiCategoryId: Int? = null,
)

internal data class PhoneLibraryOrganizationActions(
    val enabled: Boolean = true,
    val onCreateFolder: (String?, String, PhoneLibraryFilter) -> Unit = { _, _, _ -> },
    val onRenameFolder: (String, String) -> Unit = { _, _ -> },
    val onDeleteFolder: (String) -> Unit = {},
    val onDeleteReceivedGpx: suspend (PhoneLibraryItem.ReceivedGpx) -> Unit = {},
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

/** Recordings carry their profile (`gmap:activityProfile`); everything else defaults to Hike. */
internal fun PhoneLibraryItem.defaultActivity(): PhoneLibraryActivity =
    if (this is PhoneLibraryItem.Gpx && route.activityProfile.equals("BIKE", ignoreCase = true)) {
        PhoneLibraryActivity.BIKE
    } else {
        PhoneLibraryActivity.HIKE
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
            if (!category.hasFolders) {
                true
            } else if (browse.query.isBlank()) {
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
                    entry.activity == browse.activity
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
        ready.forEach { checked = checked.createFolder(it.id, it.name, it.parentId, it.category) }
        remaining.removeAll(ready.toSet())
    }
    entries.forEach { (key, entry) -> validateLibraryEntry(key, entry, checked) }
    return checked.copy(entries = entries).withFolderCategories()
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
