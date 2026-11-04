package com.glancemap.glancemapcompanionapp.library

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneLibraryPoiCheckpointsUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun checkpointsChipSwitchesBetweenPoisAndCheckpoints() {
        val mapState = mutableStateOf(libraryTestMapState(composeRule.activity))
        composeRule.setContent {
            MaterialTheme {
                libraryPanelContent(
                    PhoneLibraryListState(
                        listOf(
                            PhoneLibraryItem.Pois(
                                mapState.value.poi.sources
                                    .single(),
                            ),
                        ),
                        PhoneLibraryFilter.POIS,
                    ),
                    mapState.value,
                    libraryTestMapActions(mapState),
                    libraryTestActions(),
                ) { Text("Checkpoint list") }
            }
        }
        composeRule.onNodeWithTag("library-poi-checkpoints").assertIsNotSelected().performClick()
        composeRule.onNodeWithText("Checkpoint list").assertExists()
        composeRule.onNodeWithTag("library-poi-checkpoints").assertIsSelected().performClick()
        composeRule.onNodeWithText("Checkpoint list").assertDoesNotExist()
        composeRule.onNodeWithText("spring.poi").assertExists()
    }
}
