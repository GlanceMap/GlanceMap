package com.glancemap.glancemapcompanionapp.map

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MapSourceDropdownTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun onlineSourcesStayCollapsedUntilOpenedAndUnavailableProvidersAreHidden() {
        val selected = mutableStateOf(PhoneOnlineMapSource.OPEN_TOPO)
        val sources = PhoneMapRendererCatalog.availableOnlineSources()
        val next = sources.first { it != selected.value && PhoneMapRendererCatalog.isOnlineSourceAvailable(it) }
        val nextLabel = PhoneMapRendererCatalog.onlineSourceLabel(next)
        composeRule.setContent {
            MaterialTheme {
                mapToolsOnlineSourcePicker(sources, selected.value) { selected.value = it }
            }
        }
        composeRule.onNodeWithText(nextLabel).assertDoesNotExist()
        val online = composeRule.activity.getString(R.string.map_source_online)
        composeRule.onNodeWithText("$online: ${PhoneMapRendererCatalog.onlineSourceLabel(selected.value)}").performClick()
        PhoneMapRendererCatalog
            .supportedOnlineSources()
            .filterNot(PhoneMapRendererCatalog::isOnlineSourceAvailable)
            .forEach { source ->
                composeRule.onNodeWithText(PhoneMapRendererCatalog.onlineSourceLabel(source)).assertDoesNotExist()
            }
        composeRule.onNodeWithText(nextLabel).assertIsDisplayed().performClick()
        composeRule.onNodeWithText("$online: $nextLabel").assertIsDisplayed()
        composeRule.onNodeWithText(nextLabel).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(next, selected.value) }
    }

    @Test
    fun offlineDropdownSelectsTheMapAndManagementActionsFollowThatSelection() {
        val first = PhoneOfflineMap(File(composeRule.activity.cacheDir, "first.map"), contentRevision = "fixture")
        val second = PhoneOfflineMap(File(composeRule.activity.cacheDir, "second.map"), contentRevision = "fixture")
        val selected = mutableStateOf(first)
        var renamed: PhoneOfflineMap? = null
        var deleted: PhoneOfflineMap? = null
        var elevationArea: String? = null
        val actions =
            MapToolsMapsActions(
                onSelectOnline = {},
                onSwitchToOnline = {},
                onSelectOffline = { selected.value = it },
                onRenameOfflineMap = { renamed = it },
                onDeleteOfflineMap = { deleted = it },
                onDownloadElevationForBundleArea = { elevationArea = it },
                onImportMap = {},
                onImportElevation = {},
                onSelectElevationFolder = {},
                onRescanElevationFolder = {},
                onClearElevationFolder = {},
                onSelectRoutingFolder = {},
                onRescanRoutingFolder = {},
                onClearRoutingFolder = {},
                onSelectFolder = {},
                onRescanFolder = {},
                onClearFolder = {},
                onOpenTheme = {},
                onOpenSettingsSection = {},
                onSettingsChanged = {},
                onCompassSettingsChanged = {},
                onCalibrateCompass = {},
            )
        composeRule.setContent {
            MaterialTheme {
                mapToolsMapsPanel(
                    state =
                        MapToolsMapsState(
                            source = PhoneMapSource.Offline(selected.value),
                            onlineSources = PhoneMapRendererCatalog.supportedOnlineSources(),
                            selectedOnlineSource = PhoneOnlineMapSource.OPEN_TOPO,
                            offlineMaps = listOf(first, second),
                            offlineMapAvailability = mapOf(second.displayName to PhoneOfflineMapAvailability(bundleAreaId = "fixture-region")),
                            hasSelectedFolder = false,
                            themeConfig = PhoneOfflineThemeCatalog.defaultConfig,
                        ),
                    actions = actions,
                )
            }
        }
        val offline = composeRule.activity.getString(R.string.map_source_offline_label)
        composeRule.onNodeWithText(second.displayName).assertDoesNotExist()
        composeRule.onNodeWithText("$offline: ${first.displayName}").performClick()
        composeRule.onNodeWithText(second.displayName).assertIsDisplayed().performClick()
        composeRule.onNodeWithText("$offline: ${second.displayName}").assertIsDisplayed()
        composeRule.onNodeWithText(first.displayName).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.map_content_action_rename)).performClick()
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.map_content_action_delete)).performClick()
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.map_source_action_download_elevation)).performClick()
        composeRule.runOnIdle {
            assertEquals(second, renamed)
            assertEquals(second, deleted)
            assertEquals("fixture-region", elevationArea)
        }
    }
}
