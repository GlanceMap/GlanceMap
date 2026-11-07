package com.glancemap.glancemapcompanionapp.library

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneLibrarySearchUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun openingSearchFromAScrolledListShowsAndFocusesTheField() {
        val searchOpen = mutableStateOf(false)
        val mapState = mutableStateOf(libraryTestMapState(composeRule.activity))
        val items =
            List(30) { index ->
                PhoneLibraryItem.Gpx(libraryTestRoute().copy(id = "route-$index", displayName = "Route $index"))
            }
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.height(320.dp)) {
                    libraryPanelContent(
                        PhoneLibraryListState(items, PhoneLibraryFilter.GPX, searchOpen = searchOpen.value),
                        mapState.value,
                        libraryTestMapActions(mapState),
                        libraryTestActions(),
                    )
                }
            }
        }
        composeRule.onNodeWithTag("library-list").performScrollToIndex(items.size)
        composeRule.onNodeWithTag("library-search").assertDoesNotExist()

        composeRule.runOnIdle { searchOpen.value = true }

        composeRule.onNodeWithTag("library-search").assertIsDisplayed().assertIsFocused()
    }

    @Test
    fun searchRowIndexFollowsTheRowsAboveIt() {
        assertEquals(0, librarySearchItemIndex(PhoneLibraryFilter.GPX))
        assertEquals(0, librarySearchItemIndex(PhoneLibraryFilter.POIS))
        assertEquals(0, librarySearchItemIndex(PhoneLibraryFilter.MAPS))
        assertEquals(2, librarySearchItemIndex(PhoneLibraryFilter.BUNDLES))
    }
}
