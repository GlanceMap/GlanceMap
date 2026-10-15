package com.glancemap.glancemapcompanionapp.library

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PhoneLibraryFoldersUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val organization = mutableStateOf(PhoneLibraryOrganization())
    private var folderSequence = 0

    @Test
    fun createsNestedFoldersMovesAnEntryAndBackGoesToTheParent() {
        setContent()
        createFolder("Alps")
        composeRule.onNodeWithTag("library-folder-folder-1").performClick()
        createFolder("Day 1")
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("library-list").performScrollToNode(hasText("Forest loop"))
        composeRule.onNodeWithText("Forest loop").performClick()
        composeRule.onNodeWithText(label(R.string.library_move_to_folder)).performScrollTo().performClick()
        composeRule.onNodeWithText("Alps / Day 1").performClick()
        assertEquals("folder-2", organization.value.entry(PhoneLibraryItem.Gpx(libraryTestRoute())).folderId)
        composeRule.onNodeWithTag("library-list").performScrollToNode(hasTestTag("library-folder-folder-1"))
        composeRule.onNodeWithTag("library-folder-folder-1").performClick()
        composeRule.onNodeWithTag("library-folder-folder-2").performClick()
        composeRule.onNodeWithTag("library-list").performScrollToNode(hasText("Forest loop"))
        composeRule.onNodeWithText("Forest loop").assertIsDisplayed()
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("library-folder-folder-2").assertIsDisplayed()
    }

    @Test
    fun hikeBikeAndRecordingFiltersKeepTheirSelectionsDistinct() {
        val route = PhoneLibraryItem.Gpx(libraryTestRoute())
        val recording =
            PhoneLibraryItem.Gpx(
                libraryTestRoute().copy(id = "recording", displayName = "Coast ride", isRecording = true),
            )
        organization.value =
            organization.value.classify(route, PhoneLibraryActivity.HIKE).classify(recording, PhoneLibraryActivity.BIKE)
        setContent(listOf(route, recording))
        composeRule.onNodeWithTag("library-list").performScrollToNode(hasTestTag("library-kind-RECORDING"))
        composeRule.onNodeWithTag("library-kind-RECORDING").performClick()
        composeRule.onNodeWithTag("library-list").performScrollToNode(hasText("Coast ride"))
        composeRule.onNodeWithText("Coast ride").assertIsDisplayed()
        composeRule.onNodeWithText("Forest loop").assertDoesNotExist()
        composeRule.onNodeWithTag("library-list").performScrollToNode(hasTestTag("library-activity-HIKE"))
        composeRule.onNodeWithTag("library-activity-HIKE").performClick()
        composeRule.onNodeWithText("Coast ride").assertDoesNotExist()
        composeRule.onNodeWithTag("library-activity-BIKE").performClick()
        composeRule.onNodeWithTag("library-list").performScrollToNode(hasText("Coast ride"))
        composeRule.onNodeWithText("Coast ride").assertIsDisplayed()
    }

    @Test
    fun activityChoiceUpdatesTheItemWithoutChangingMapVisibility() {
        setContent()
        composeRule.onNodeWithTag("library-list").performScrollToNode(hasText("Forest loop"))
        composeRule.onNodeWithText("Forest loop").performClick()
        composeRule.onNodeWithTag("library-classify-BIKE").performScrollTo().performClick()
        composeRule.onNodeWithText(label(R.string.common_action_close)).performClick()
        assertEquals(
            PhoneLibraryActivity.BIKE,
            organization.value.entry(PhoneLibraryItem.Gpx(libraryTestRoute())).activity,
        )
        composeRule.onNodeWithTag("library-list").performScrollToNode(hasTestTag("library-activity-BIKE"))
        composeRule.onNodeWithTag("library-activity-BIKE").performClick()
        composeRule.onNodeWithTag("library-list").performScrollToNode(hasText("Forest loop"))
        composeRule.onNodeWithText("Forest loop").assertIsDisplayed()
    }

    @Test
    fun savedFoldersRestoreAndDamagedMetadataIsNotOverwrittenOnLoad() {
        val preferenceName = "library-organization-test-" + UUID.randomUUID()
        val context =
            object : ContextWrapper(composeRule.activity) {
                override fun getSharedPreferences(
                    name: String,
                    mode: Int,
                ) = super.getSharedPreferences(preferenceName, mode)
            }
        try {
            val item = PhoneLibraryItem.Gpx(libraryTestRoute())
            val stored =
                PhoneLibraryOrganization()
                    .createFolder("trip", "Trip", null)
                    .move(item, "trip")
                    .classify(item, PhoneLibraryActivity.HIKE)
            PhoneLibraryOrganizationStore(context).save(stored)
            assertEquals(stored, PhoneLibraryOrganizationStore(context).load())
            val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
            preferences.edit().putString("organization", "damaged json").commit()
            assertThrows(IOException::class.java) { PhoneLibraryOrganizationStore(context).load() }
            assertEquals("damaged json", preferences.getString("organization", null))
        } finally {
            composeRule.activity.deleteSharedPreferences(preferenceName)
        }
    }

    private fun createFolder(name: String) {
        val description = label(R.string.library_create_folder)
        composeRule.onNodeWithTag("library-list").performScrollToNode(hasContentDescription(description))
        composeRule.onNodeWithContentDescription(description).performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput(name)
        composeRule.onNodeWithText(label(R.string.library_folder_create)).performClick()
    }

    private fun label(resource: Int) = composeRule.activity.getString(resource)

    private fun setContent(items: List<PhoneLibraryItem> = listOf(PhoneLibraryItem.Gpx(libraryTestRoute()))) {
        val mapState = mutableStateOf(libraryTestMapState(composeRule.activity))
        composeRule.setContent {
            MaterialTheme {
                libraryPanelContent(
                    PhoneLibraryListState(items, PhoneLibraryFilter.GPX, organization = organization.value),
                    mapState.value,
                    libraryTestMapActions(mapState),
                    libraryTestActions().copy(
                        organization =
                            PhoneLibraryOrganizationActions(
                                onCreateFolder = { parent, name ->
                                    organization.value =
                                        organization.value.createFolder("folder-${++folderSequence}", name, parent)
                                },
                                onMoveItem = { item, folder ->
                                    organization.value = organization.value.move(item, folder)
                                },
                                onActivity = { item, activity ->
                                    organization.value = organization.value.classify(item, activity)
                                },
                            ),
                    ),
                )
            }
        }
    }
}
