package com.glancemap.glancemapcompanionapp.library

import androidx.annotation.StringRes
import androidx.compose.ui.state.ToggleableState
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.MapToolsPanelActions
import com.glancemap.glancemapcompanionapp.map.PhoneMapPoiSource
import com.glancemap.glancemapcompanionapp.map.PhoneMapUserPoiStore
import com.glancemap.glancemapcompanionapp.refuges.PoiSqliteCategory

/** The watch's POI sections: places made by the user, waypoints linked from GPX, then imported files. */
internal enum class PhoneLibraryPoiSection(
    @StringRes val label: Int,
) {
    MY_CREATION(R.string.library_poi_section_my_creation),
    GPX_WAYPOINTS(R.string.library_poi_section_waypoints),
    FILES(R.string.library_poi_section_files),
}

internal fun PhoneMapPoiSource.section(): PhoneLibraryPoiSection =
    when {
        fileName.equals(PhoneMapUserPoiStore.USER_POI_FILE_NAME, ignoreCase = true) ->
            PhoneLibraryPoiSection.MY_CREATION

        fileName.endsWith(GPX_WAYPOINT_POI_SUFFIX, ignoreCase = true) -> PhoneLibraryPoiSection.GPX_WAYPOINTS
        else -> PhoneLibraryPoiSection.FILES
    }

private const val GPX_WAYPOINT_POI_SUFFIX = "__waypoints.poi"

internal val PhoneMapPoiSource.isUserPlaces: Boolean
    get() = section() == PhoneLibraryPoiSection.MY_CREATION

internal fun PhoneMapPoiSource.childCategories(parentId: Int?): List<PoiSqliteCategory> {
    val children = categories.filter { it.parentId == parentId }
    return children.sortedBy { it.name.lowercase() }
}

/** A category and every category below it. */
internal fun PhoneMapPoiSource.categoryTree(id: Int): Set<Int> {
    val tree = linkedSetOf(id)
    var added = true
    while (added) {
        added = tree.addAll(categories.filter { it.parentId in tree }.map(PoiSqliteCategory::id))
    }
    return tree
}

/** POIs shown on the map out of the POIs in the scope; `null` means the whole file. */
internal fun PhoneMapPoiSource.visibilityCount(categoryId: Int?): PhoneLibraryFolderVisibilityCount {
    if (categories.isEmpty()) {
        val total = poiCount ?: 0
        return PhoneLibraryFolderVisibilityCount(if (isEnabled) total else 0, total)
    }
    val scope = categoryId?.let(::categoryTree) ?: categories.map(PoiSqliteCategory::id).toSet()
    val inScope = categories.filter { it.id in scope }
    val total = inScope.sumOf(PoiSqliteCategory::directCount)
    val shown =
        if (isEnabled) inScope.filterNot { it.id in disabledCategoryIds }.sumOf(PoiSqliteCategory::directCount) else 0
    return PhoneLibraryFolderVisibilityCount(shown, total)
}

/** All, some or no categories of the scope are shown; a hidden file shows none. */
internal fun PhoneMapPoiSource.visibilityState(categoryId: Int?): ToggleableState {
    val scope = categoryId?.let(::categoryTree) ?: categories.map(PoiSqliteCategory::id).toSet()
    val hidden = scope.count { it in disabledCategoryIds }
    return when {
        !isEnabled || (scope.isNotEmpty() && hidden == scope.size) -> ToggleableState.Off
        hidden == 0 -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
}

/** Showing a category also shows its file, so switching one on always puts it on the map. */
internal fun setPoiScopeVisible(
    source: PhoneMapPoiSource,
    categoryId: Int?,
    visible: Boolean,
    actions: PhoneLibraryActions,
    mapActions: MapToolsPanelActions,
) {
    val scope = categoryId?.let(source::categoryTree) ?: source.categories.map(PoiSqliteCategory::id).toSet()
    if (categoryId == null) mapActions.onPoiSourceVisibilityChanged(source.fileName, visible)
    if (visible && !source.isEnabled) mapActions.onPoiSourceVisibilityChanged(source.fileName, true)
    if (scope.isNotEmpty() && (categoryId != null || visible)) {
        actions.onPoiCategoriesVisible(source.fileName, scope, visible)
    }
}

internal fun PhoneLibraryBrowseState.poiParent(source: PhoneMapPoiSource?): PhoneLibraryBrowseState {
    val categoryId = poiCategoryId ?: return copy(poiFile = null, poiCategoryId = null)
    val parent = source?.categories?.firstOrNull { it.id == categoryId }?.parentId
    return copy(poiCategoryId = parent)
}

/** A scope with no sub-categories lists its POIs; a file with one category goes straight to them. */
internal fun PhoneMapPoiSource.listsPois(categoryId: Int?): Boolean {
    val children = childCategories(categoryId)
    return children.isEmpty() || (categoryId == null && children.size == 1 && childCategories(children[0].id).isEmpty())
}
