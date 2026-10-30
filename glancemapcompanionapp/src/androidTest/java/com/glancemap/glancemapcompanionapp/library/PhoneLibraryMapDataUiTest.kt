package com.glancemap.glancemapcompanionapp.library

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.map.PhoneOfflineMap
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mapsforge.core.model.BoundingBox
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhoneLibraryMapDataUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun selectingElevationWithoutRoutingStartsOnlyThatDownload() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mapState = mutableStateOf(libraryTestMapState(context))
        val map = PhoneOfflineMap(File("/maps/alps.map"))
        val item = PhoneLibraryItem.OfflineMap(map, 1, PhoneLibraryMapCoverage(BoundingBox(45.0, 6.0, 46.0, 7.0), 2, 0, 2, 0))
        var selected: Pair<Boolean, Boolean>? = null
        val actions =
            libraryTestMapActions(mapState).maps.copy(onDownloadMapData = { requested, elevation, routing ->
                assertEquals(map, requested)
                selected = elevation to routing
                true
            })
        compose.setContent { libraryMapDataButton(item, true, mapState.value.maps, actions) }
        compose.onNodeWithTag("library-map-data-${item.key}").performClick()
        compose.onNodeWithTag("library-map-data-routing").performClick()
        compose.onNodeWithTag("library-map-data-start").performClick()
        compose.runOnIdle { assertEquals(true to false, selected) }
    }

    @Test
    fun unknownMapBoundsCannotStartADataDownload() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mapState = mutableStateOf(libraryTestMapState(context))
        val item = PhoneLibraryItem.OfflineMap(PhoneOfflineMap(File("/maps/missing.map")), null)
        val actions = libraryTestMapActions(mapState).maps
        compose.setContent { libraryMapDataButton(item, true, mapState.value.maps, actions) }
        compose.onNodeWithTag("library-map-data-${item.key}").performClick()
        compose.onNodeWithTag("library-map-data-elevation").performClick()
        compose.onNodeWithTag("library-map-data-start").assertIsNotEnabled()
    }
}
