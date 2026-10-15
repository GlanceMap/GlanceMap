package com.glancemap.glancemapcompanionapp.map

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.library.PhoneLibraryEntryOrganization
import com.glancemap.glancemapcompanionapp.library.PhoneLibraryFilter
import com.glancemap.glancemapcompanionapp.library.PhoneLibraryFolder
import com.glancemap.glancemapcompanionapp.library.PhoneLibraryItem
import com.glancemap.glancemapcompanionapp.library.PhoneLibraryListState
import com.glancemap.glancemapcompanionapp.library.PhoneLibraryOrganization
import com.glancemap.glancemapcompanionapp.library.libraryPanelContent
import com.glancemap.glancemapcompanionapp.library.libraryTestActions
import com.glancemap.glancemapcompanionapp.library.libraryTestMapActions
import com.glancemap.glancemapcompanionapp.library.libraryTestMapState
import com.glancemap.glancemapcompanionapp.library.libraryTestRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneGpxAppearanceUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun generalChangesWaitForScopeAndAllOnlyClearsChangedProperties() {
        val state = mutableStateOf(libraryTestMapState(composeRule.activity))
        val original =
            PhoneGpxCustomizationState(
                fileStyles = mapOf("gpx/route" to PhoneGpxAppearanceOverride(trackWidth = 11f, trackColorArgb = 5)),
                folderStyles = mapOf("parent" to PhoneGpxAppearanceOverride(trackWidth = 9f)),
            )
        state.value = state.value.copy(gpx = state.value.gpx.copy(customization = original))
        val actions =
            libraryTestMapActions(state).copy(
                onGpxSettingsChanged = { settings -> state.value = state.value.copy(gpx = state.value.gpx.copy(settings = settings)) },
                onGpxAppearanceAppliedToAll = { settings ->
                    val current = state.value.gpx
                    state.value =
                        state.value.copy(
                            gpx =
                                current.copy(
                                    settings = settings,
                                    customization = current.customization.applyGeneralToAll(current.settings, settings),
                                ),
                        )
                },
            )
        composeRule.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    phoneGpxGeneralAppearanceSettings(state.value.gpx, actions)
                }
            }
        }
        setWidth(10f)
        composeRule.runOnIdle { assertEquals(8f, state.value.gpx.settings.trackWidth) }
        clickApply()
        composeRule.onNodeWithText(text(R.string.map_poi_cancel)).performClick()
        composeRule.runOnIdle { assertEquals(8f, state.value.gpx.settings.trackWidth) }
        clickApply()
        composeRule.onNodeWithText(text(R.string.gpx_appearance_apply_inherited)).performClick()
        composeRule.runOnIdle {
            assertEquals(10f, state.value.gpx.settings.trackWidth)
            assertEquals(original, state.value.gpx.customization)
        }
        setWidth(12f)
        clickApply()
        composeRule.onNodeWithText(text(R.string.gpx_appearance_apply_all)).performClick()
        composeRule.runOnIdle {
            assertEquals(12f, state.value.gpx.settings.trackWidth)
            assertNull(
                state.value.gpx.customization.fileStyles
                    .getValue("gpx/route")
                    .trackWidth,
            )
            assertNull(
                state.value.gpx.customization.folderStyles
                    .getValue("parent")
                    .trackWidth,
            )
            assertEquals(
                5,
                state.value.gpx.customization.fileStyles
                    .getValue("gpx/route")
                    .trackColorArgb,
            )
        }
    }

    @Test
    fun aSinglePropertyCanReturnToItsParentWithoutErasingOtherCustomizations() {
        var saved: PhoneGpxAppearanceOverride? = null
        composeRule.setContent {
            MaterialTheme {
                phoneGpxAppearanceOverrideDialog(
                    "Forest loop",
                    PhoneGpxAppearanceEditState(
                        PhoneMapGpxSettings(trackWidth = 9f),
                        PhoneGpxAppearanceOverride(trackWidth = 11f, trackColorArgb = 5),
                    ),
                    onSave = { saved = it },
                    onDismiss = {},
                )
            }
        }
        setWidth(12f)
        composeRule.onNodeWithText(text(R.string.gpx_inherit_width)).performScrollTo().performClick()
        composeRule.onNodeWithText(text(R.string.map_poi_save)).performClick()
        composeRule.runOnIdle {
            assertNull(saved?.trackWidth)
            assertEquals(5, saved?.trackColorArgb)
            assertEquals(9f, saved?.applyTo(PhoneMapGpxSettings(trackWidth = 9f))?.trackWidth)
        }
    }

    @Test
    fun libraryFolderAndFileEditorsSaveToTheirSeparateStableKeys() {
        val state = mutableStateOf(libraryTestMapState(composeRule.activity))
        val route = PhoneLibraryItem.Gpx(libraryTestRoute())
        val organization =
            PhoneLibraryOrganization(
                folders = listOf(PhoneLibraryFolder("parent", "Parent")),
                entries = mapOf(route.key to PhoneLibraryEntryOrganization("parent")),
            )
        val actions =
            libraryTestMapActions(state).copy(
                onGpxCustomizationChanged = { state.value = state.value.copy(gpx = state.value.gpx.copy(customization = it)) },
            )
        composeRule.setContent {
            MaterialTheme {
                libraryPanelContent(
                    PhoneLibraryListState(listOf(route), PhoneLibraryFilter.GPX, organization = organization),
                    state.value,
                    actions,
                    libraryTestActions(),
                )
            }
        }
        composeRule.onNodeWithText(text(R.string.gpx_appearance_customize)).performClick()
        setWidth(9f)
        composeRule.onNodeWithText(text(R.string.map_poi_save)).performClick()
        composeRule.onNodeWithTag("library-folder-parent").performClick()
        composeRule.onNodeWithTag("library-item-${route.key}").performClick()
        composeRule.onNodeWithText(text(R.string.gpx_appearance_customize)).performClick()
        setWidth(11f)
        composeRule.onNodeWithText(text(R.string.map_poi_save)).performClick()
        composeRule.runOnIdle {
            assertEquals(
                9f,
                state.value.gpx.customization.folderStyles
                    .getValue("parent")
                    .trackWidth,
            )
            assertEquals(
                11f,
                state.value.gpx.customization.fileStyles
                    .getValue(route.key)
                    .trackWidth,
            )
        }
    }

    private fun setWidth(value: Float) {
        composeRule
            .onNodeWithContentDescription(text(R.string.gpx_appearance_width))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
    }

    private fun clickApply() {
        composeRule.onNodeWithText(text(R.string.gpx_appearance_apply)).performScrollTo().performClick()
    }

    private fun text(id: Int) = composeRule.activity.getString(id)
}
