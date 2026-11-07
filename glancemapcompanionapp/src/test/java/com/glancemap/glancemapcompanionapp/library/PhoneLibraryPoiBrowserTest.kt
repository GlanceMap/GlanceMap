package com.glancemap.glancemapcompanionapp.library

import androidx.compose.ui.state.ToggleableState
import com.glancemap.glancemapcompanionapp.map.PhoneMapPoi
import com.glancemap.glancemapcompanionapp.map.PhoneMapPoiSource
import com.glancemap.glancemapcompanionapp.map.storedId
import com.glancemap.glancemapcompanionapp.refuges.PoiSqliteCategory
import com.glancemap.glancemapcompanionapp.refuges.withoutSyntheticRoot
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.poi.PoiType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLibraryPoiBrowserTest {
    private val categories =
        listOf(
            PoiSqliteCategory(10, "Huts", null, 186),
            PoiSqliteCategory(11, "Water", null, 112),
            PoiSqliteCategory(12, "Mountain", null, 0),
            PoiSqliteCategory(13, "Peaks", 12, 80),
            PoiSqliteCategory(14, "Passes", 12, 34),
        )
    private val source = PhoneMapPoiSource("alps.poi", true, poiCount = 412, categories = categories)

    @Test
    fun filesSortIntoTheWatchSections() {
        assertEquals(PhoneLibraryPoiSection.MY_CREATION, source.copy(fileName = "my-places.poi").section())
        assertEquals(PhoneLibraryPoiSection.GPX_WAYPOINTS, source.copy(fileName = "tmb__waypoints.poi").section())
        assertEquals(PhoneLibraryPoiSection.FILES, source.section())
    }

    @Test
    fun aSyntheticRootIsDroppedSoItsChildrenBecomeTheTopLevel() {
        val withRoot =
            listOf(PoiSqliteCategory(1, "root", null, 0)) +
                categories.map { if (it.parentId == null) it.copy(parentId = 1) else it }
        assertEquals(categories, withoutSyntheticRoot(withRoot))
        assertEquals(categories, withoutSyntheticRoot(categories))
    }

    @Test
    fun countsAndStatesFollowHiddenCategoriesAndTheFileToggle() {
        assertEquals(PhoneLibraryFolderVisibilityCount(412, 412), source.visibilityCount(null))
        val someHidden = source.copy(disabledCategoryIds = setOf(13))
        assertEquals(PhoneLibraryFolderVisibilityCount(34, 114), someHidden.visibilityCount(12))
        assertEquals(ToggleableState.Indeterminate, someHidden.visibilityState(12))
        assertEquals(ToggleableState.On, someHidden.visibilityState(11))
        assertEquals(ToggleableState.Off, someHidden.visibilityState(13))
        val fileHidden = source.copy(isEnabled = false)
        assertEquals(PhoneLibraryFolderVisibilityCount(0, 412), fileHidden.visibilityCount(null))
        assertEquals(ToggleableState.Off, fileHidden.visibilityState(10))
    }

    @Test
    fun categoryTreesAndBackNavigationFollowTheHierarchy() {
        assertEquals(setOf(12, 13, 14), source.categoryTree(12))
        val inPeaks = PhoneLibraryBrowseState(poiFile = "alps.poi", poiCategoryId = 13)
        assertEquals(12, inPeaks.poiParent(source).poiCategoryId)
        val inMountain = inPeaks.copy(poiCategoryId = 12)
        assertNull(inMountain.poiParent(source).poiCategoryId)
        assertEquals("alps.poi", inMountain.poiParent(source).poiFile)
        assertNull(inMountain.copy(poiCategoryId = null).poiParent(source).poiFile)
    }

    @Test
    fun leafCategoriesAndSingleCategoryFilesListTheirPois() {
        assertFalse(source.listsPois(null))
        assertFalse(source.listsPois(12))
        assertTrue(source.listsPois(13))
        val places = source.copy(categories = listOf(PoiSqliteCategory(2, "Saved places", null, 12)))
        assertTrue(places.listsPois(null))
    }

    @Test
    fun storedIdsComeFromTheMapId() {
        val poi = PhoneMapPoi("my-places.poi#42", "my-places.poi", GeoPoint(45.0, 6.0), PoiType.GENERIC, "Spring", null)
        assertEquals(42L, poi.storedId())
        assertNull(poi.copy(id = "broken").storedId())
    }
}
