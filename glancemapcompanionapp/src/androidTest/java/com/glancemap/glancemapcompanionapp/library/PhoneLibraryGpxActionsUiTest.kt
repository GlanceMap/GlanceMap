package com.glancemap.glancemapcompanionapp.library

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneLibraryGpxActionsUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun gpxOverflowMenuOffersDetailsRenameAndConfirmedDelete() {
        val item = PhoneLibraryItem.Gpx(libraryTestRoute())
        val mapState = mutableStateOf(libraryTestMapState(composeRule.activity))
        var renamedRoute: Pair<String, String>? = null
        var deletedRoute: String? = null
        var detailsOpened = 0
        val row =
            PhoneLibraryRowState(
                item = item,
                enabled = true,
                onRenameRoute = { routeId, name -> renamedRoute = routeId to name },
                onDeleteRoute = { routeId -> deletedRoute = routeId },
            )

        composeRule.setContent {
            MaterialTheme {
                Column {
                    libraryItemRow(row, mapState.value, libraryTestMapActions(mapState)) { detailsOpened++ }
                }
            }
        }

        composeRule.onNodeWithTag("library-gpx-overflow-${item.key}").performClick()
        composeRule.onNodeWithTag("library-gpx-details").assertIsDisplayed().performClick()
        assertEquals(1, detailsOpened)

        composeRule.onNodeWithTag("library-gpx-overflow-${item.key}").performClick()
        composeRule.onNodeWithTag("library-gpx-rename").performClick()
        val renameField = composeRule.onNodeWithTag("library-gpx-rename-name")
        renameField.performTextClearance()
        renameField.performTextInput("Renamed loop")
        composeRule.onNodeWithTag("library-gpx-rename-confirm").performClick()
        composeRule.waitForIdle()
        assertEquals("route" to "Renamed loop", renamedRoute)

        composeRule.onNodeWithTag("library-gpx-overflow-${item.key}").performClick()
        composeRule.onNodeWithTag("library-gpx-delete").performClick()
        composeRule.onNodeWithTag("library-gpx-delete-confirm").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals("route", deletedRoute)
    }

    @Test
    fun longPressingGpxRowOpensRouteInNewHikeTab() {
        val item = PhoneLibraryItem.Gpx(libraryTestRoute())
        val mapState = mutableStateOf(libraryTestMapState(composeRule.activity))
        var openedItem: PhoneLibraryItem? = null
        val row =
            PhoneLibraryRowState(
                item = item,
                enabled = true,
                onOpenGpxInNewHikeTab = { openedItem = it },
            )

        composeRule.setContent {
            MaterialTheme {
                Column {
                    libraryItemRow(row, mapState.value, libraryTestMapActions(mapState)) {}
                }
            }
        }

        composeRule.onNodeWithTag("library-item-${item.key}").performTouchInput { longClick() }
        assertEquals(item, openedItem)
    }
}
