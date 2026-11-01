package com.glancemap.glancemapcompanionapp.library

private const val POI_FOLDER_COPY_SUFFIX = "-pois"

private fun String.isPoiEntry() = startsWith("poi/")

private fun String.isRouteEntry() = startsWith("gpx/") || startsWith("export/") || startsWith("received/")

/** Gives each folder saved before per-tab folders a tab, without losing any route or POI placement. */
internal fun PhoneLibraryOrganization.withFolderCategories(): PhoneLibraryOrganization =
    folders
        .filter { it.parentId == null && it.category == null }
        .fold(this) { organization, root -> organization.withLegacyFolderCategory(root.id) }

private fun PhoneLibraryOrganization.withLegacyFolderCategory(rootId: String): PhoneLibraryOrganization {
    val tree = folderTree(rootId)
    val held = entries.filterValues { it.folderId in tree }.keys
    val holdsPois = held.any(String::isPoiEntry)
    val holdsRoutes = held.any(String::isRouteEntry)
    val category = if (holdsPois && !holdsRoutes) PhoneLibraryFilter.POIS else PhoneLibraryFilter.GPX
    val split = holdsPois && holdsRoutes
    val poiCopies =
        folders.filter { split && it.id in tree }.map { folder ->
            folder.copy(
                id = folder.id + POI_FOLDER_COPY_SUFFIX,
                parentId = folder.parentId?.plus(POI_FOLDER_COPY_SUFFIX),
                category = PhoneLibraryFilter.POIS,
            )
        }
    return copy(
        folders = folders.map { if (it.id in tree) it.copy(category = category) else it } + poiCopies,
        entries =
            entries.mapValues { (key, entry) ->
                when {
                    entry.folderId !in tree || key.isRouteEntry() -> entry
                    !key.isPoiEntry() -> entry.copy(folderId = null)
                    split -> entry.copy(folderId = entry.folderId + POI_FOLDER_COPY_SUFFIX)
                    else -> entry
                }
            },
    )
}
