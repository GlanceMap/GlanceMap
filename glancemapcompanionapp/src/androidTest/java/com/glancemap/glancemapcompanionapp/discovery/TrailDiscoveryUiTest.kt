package com.glancemap.glancemapcompanionapp.discovery

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.glancemap.glancemapcompanionapp.filepicker.CompanionHomeArea
import com.glancemap.glancemapcompanionapp.filepicker.CompanionMenuActions
import com.glancemap.glancemapcompanionapp.filepicker.CompanionMenuState
import com.glancemap.glancemapcompanionapp.filepicker.companionNavigationDrawer
import com.glancemap.glancemapcompanionapp.library.libraryTestMapActions
import com.glancemap.glancemapcompanionapp.library.libraryTestMapState
import com.glancemap.glancemapcompanionapp.map.MapTool
import com.glancemap.glancemapcompanionapp.map.MapToolContentMode
import com.glancemap.glancemapcompanionapp.map.MapToolFeatureSettingsSection
import com.glancemap.glancemapcompanionapp.map.MapToolPanelContent
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.profile.TrailPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TrailDiscoveryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun sourceSwitchesChangeIndependently() {
        val settings = mutableStateOf(RouteSourceSettings())
        compose.setContent {
            MaterialTheme {
                Column {
                    RouteSourceToggles(settings.value) { source, enabled ->
                        val current = settings.value
                        settings.value =
                            current.copy(
                                enabled = if (enabled) current.enabled + source else current.enabled - source,
                            )
                    }
                }
            }
        }
        compose
            .onNodeWithContentDescription(TrailProvider.WAYMARKED_TRAILS.label)
            .assertIsOn()
            .performClick()
        compose.runOnIdle { assertEquals(emptySet<TrailProvider>(), settings.value.enabled) }
        compose.onNodeWithContentDescription(TrailProvider.WAYMARKED_TRAILS.label).assertIsOff()
        compose
            .onNodeWithContentDescription("Geotrek")
            .assertIsOff()
            .performClick()
            .assertIsOn()
        compose.onNodeWithContentDescription("Wanderer").assertIsOff()
    }

    @Test
    fun loadingEmptyAndFailureStatesAreVisible() {
        val state = mutableStateOf(TrailDiscoveryUiState(loading = true))
        compose.setContent { MaterialTheme { TrailDiscoveryPanel(state.value, actions()) } }
        compose.onNodeWithText("Finding published routes…").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state.value = TrailDiscoveryUiState(searched = true) }
        compose
            .onNodeWithText(
                "No published routes found here. Try another area or source.",
            ).performScrollTo()
            .assertIsDisplayed()
        compose.runOnIdle {
            state.value =
                state.value.copy(
                    reply =
                        TrailDiscoveryReply(
                            statuses =
                                listOf(
                                    TrailSourceStatus(TrailProvider.GEOTREK, "Geotrek", TrailSourceOutcome.FAILED),
                                ),
                        ),
                )
        }
        compose
            .onNodeWithText(
                "Geotrek: Could not load this source. Try searching again.",
            ).performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun failedAndPartiallyEmptySearchesDoNotPromiseResults() {
        val busy =
            TrailSourceStatus(
                TrailProvider.OPEN_STREET_MAP,
                "OpenStreetMap",
                TrailSourceOutcome.FAILED,
                failure = TrailDiscoveryFailure.SERVICE_UNAVAILABLE,
            )
        val state =
            mutableStateOf(
                TrailDiscoveryUiState(
                    searched = true,
                    enabled = setOf(TrailProvider.OPEN_STREET_MAP, TrailProvider.GEOTREK),
                    reply = TrailDiscoveryReply(statuses = listOf(busy)),
                ),
            )
        compose.setContent { MaterialTheme { TrailDiscoveryPanel(state.value, actions()) } }
        compose
            .onNodeWithText("No enabled route source could be searched. Try again later or configure route sources.")
            .performScrollTo()
            .assertIsDisplayed()
        compose
            .onNodeWithText("OpenStreetMap: This source is temporarily unavailable or overloaded. Try again later.")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Some sources are unavailable. Available results are shown below.").assertDoesNotExist()
        compose.runOnIdle {
            state.value = state.value.copy(reply = TrailDiscoveryReply(statuses = listOf(busy, TrailSourceStatus(TrailProvider.GEOTREK, "Park", TrailSourceOutcome.SUCCESS))))
        }
        compose
            .onNodeWithText("Some sources are unavailable. No routes were found in the sources that responded.")
            .performScrollTo()
            .assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(reply = state.value.reply.copy(trails = listOf(trail))) }
        compose
            .onNodeWithText("Some sources are unavailable. Available results are shown below.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun providerLabelOpensDetailsAndDownloadsGeometry() {
        var saved = 0
        compose.setContent {
            var selected by remember { mutableStateOf<PublishedTrail?>(null) }
            MaterialTheme {
                selected?.let { trail ->
                    PublishedTrailDetails(
                        trail,
                        DiscoveredTrailSaveState(),
                        PublishedTrailDetailActions({ selected = null }, { saved++ }),
                    )
                } ?: TrailDiscoveryPanel(
                    TrailDiscoveryUiState(
                        reply = TrailDiscoveryReply(listOf(trail)),
                        enabled = setOf(TrailProvider.GEOTREK),
                    ),
                    actions { selected = it },
                )
            }
        }
        compose.onNodeWithText("Fixture hike").performScrollTo().performClick()
        compose.onNodeWithText("Route preview shown on the map.").assertIsDisplayed()
        compose.onNodeWithText("Download for offline").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, saved) }
    }

    @Test
    fun metadataOnlyDownloadClearlyExplainsThatNoGeometryIsAvailable() {
        var downloads = 0
        compose.setContent {
            MaterialTheme {
                PublishedTrailDetails(
                    trail.copy(geometryComplete = false),
                    DiscoveredTrailSaveState(),
                    PublishedTrailDetailActions({}, { downloads++ }),
                )
            }
        }
        compose
            .onNodeWithText("Downloads details and source credits only. No navigable route geometry is available.")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Download for offline").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, downloads) }
    }

    @Test
    fun lazyDetailLoadingAndFailurePreventSavingAndOfferRetry() {
        val detail = mutableStateOf(OnlineTrailDetailState(loading = true))
        var retries = 0
        compose.setContent {
            MaterialTheme {
                PublishedTrailDetails(
                    trail.copy(geometry = emptyList(), geometryComplete = false),
                    DiscoveredTrailSaveState(),
                    PublishedTrailDetailActions({}, {}, { retries++ }),
                    detail = detail.value,
                )
            }
        }
        compose.onNodeWithText("Loading route geometry…").performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithText("Downloads details and source credits only. No navigable route geometry is available.")
            .assertDoesNotExist()
        compose.onNodeWithText("Download for offline").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { detail.value = OnlineTrailDetailState(failed = true) }
        compose.onNodeWithText("Complete route geometry could not be loaded.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Retry").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun sourcesAreConfiguredInSettingsRatherThanOnSearchScreen() {
        compose.setContent { MaterialTheme { TrailDiscoveryPanel(TrailDiscoveryUiState(), actions()) } }
        compose.onNodeWithContentDescription("Search OpenStreetMap").assertDoesNotExist()
        compose.onNodeWithText("Configure route sources").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun checkingARouteKeepsItAvailableAfterSearchingAnotherArea() {
        val state =
            mutableStateOf(
                TrailDiscoveryUiState(
                    searched = true,
                    enabled = setOf(TrailProvider.GEOTREK),
                    reply = TrailDiscoveryReply(listOf(trail)),
                ),
            )
        val selection = mutableStateOf(TrailMapSelectionState())
        var opened = 0
        compose.setContent {
            MaterialTheme {
                TrailDiscoveryPanel(
                    state.value,
                    actions { opened++ }.copy(
                        onMapSelectionChanged = { selected, visible ->
                            val next =
                                if (visible) {
                                    selection.value.requested + (selected.id to selected)
                                } else {
                                    selection.value.requested - selected.id
                                }
                            selection.value = selection.value.copy(requested = next)
                        },
                    ),
                    mapSelection = selection.value,
                )
            }
        }
        compose
            .onNodeWithContentDescription("Show Fixture hike on map")
            .performScrollTo()
            .assertIsOff()
            .performClick()
        compose.onNodeWithContentDescription("Show Fixture hike on map").assertIsOn()
        compose.runOnIdle {
            assertEquals(0, opened)
            state.value = state.value.copy(reply = TrailDiscoveryReply())
        }
        compose.onNodeWithText("Selected for map").performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithContentDescription("Show Fixture hike on map")
            .performScrollTo()
            .assertIsOn()
            .performClick()
        compose.onNodeWithText("Fixture hike").assertDoesNotExist()
    }

    @Test
    fun checkedRouteCanBeRemovedWhenNewResultHasNoGeometry() {
        val selection = mutableStateOf(TrailMapSelectionState(requested = mapOf(trail.id to trail)))
        compose.setContent {
            MaterialTheme {
                TrailDiscoveryPanel(
                    TrailDiscoveryUiState(
                        enabled = setOf(TrailProvider.GEOTREK),
                        reply =
                            TrailDiscoveryReply(
                                listOf(trail.copy(geometry = emptyList(), geometryComplete = false)),
                            ),
                    ),
                    actions().copy(
                        onMapSelectionChanged = { selected, visible ->
                            if (!visible) {
                                selection.value = selection.value.copy(requested = selection.value.requested - selected.id)
                            }
                        },
                    ),
                    mapSelection = selection.value,
                )
            }
        }
        compose
            .onNodeWithContentDescription("Show Fixture hike on map")
            .performScrollTo()
            .assertIsOn()
            .performClick()
        compose.runOnIdle { assertTrue(selection.value.requested.isEmpty()) }
    }

    @Test
    fun nearMeAndMapSelectionControlsDispatchTheirActions() {
        var nearby = 0
        var centre = 0
        val picking = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                TrailDiscoveryPanel(
                    TrailDiscoveryUiState(),
                    actions().copy(
                        map =
                            TrailDiscoverySearchActions(
                                onNearMe = { nearby++ },
                                onChoosePoint = { picking.value = true },
                                onUseMapCenter = { centre++ },
                                onCancelPoint = { picking.value = false },
                                pickingPoint = picking.value,
                            ),
                    ),
                )
            }
        }
        compose.onNodeWithText("Near me").performClick()
        compose.onNodeWithText("Choose on map").performClick()
        compose.onNodeWithText("Use map centre").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle {
            assertEquals(1, nearby)
            assertEquals(1, centre)
            assertEquals(false, picking.value)
        }
    }

    @Test
    fun downloadedRoutesOpenLocallyAndShowTheirCredits() {
        val record = OfflinePublishedTrail(trail, "gpx-fixture", 1)
        var opened: OfflinePublishedTrail? = null
        compose.setContent {
            MaterialTheme { OfflineTrailsPanel(OfflineTrailsUiState(listOf(record))) { opened = it } }
        }
        compose.onNodeWithText("© Fixture Park").assertIsDisplayed()
        compose.onNodeWithText("Fixture hike").performClick()
        compose.runOnIdle { assertEquals(record, opened) }
    }

    @Test
    fun discoveryIsHiddenFromTheLeftDrawer() {
        var destination: CompanionHomeArea? = null
        compose.setContent {
            MaterialTheme {
                companionNavigationDrawer(
                    DrawerState(DrawerValue.Open),
                    CompanionMenuState(CompanionHomeArea.MAP, false),
                    CompanionMenuActions({ destination = it }, {}, {}, {}),
                ) {}
            }
        }
        compose.onNodeWithText("Discover routes").assertDoesNotExist()
        compose.runOnIdle { assertEquals(null, destination) }
    }

    @Test
    fun discoveryIsHiddenFromTheGpxToolPanel() {
        compose.setContent {
            val context = LocalContext.current
            val mapState = remember(context) { mutableStateOf(libraryTestMapState(context)) }
            MaterialTheme {
                MapToolPanelContent(
                    tool = MapTool.GPX,
                    contentMode = MapToolContentMode.MAIN,
                    featureSettingsSection = MapToolFeatureSettingsSection.ROOT,
                    state = mapState.value,
                    actions = libraryTestMapActions(mapState),
                )
            }
        }

        compose.onNodeWithText("Discover routes").assertDoesNotExist()
    }

    @Test
    fun downloadedDetailsCannotAccidentallyImportTheRouteAgain() {
        compose.setContent {
            MaterialTheme {
                PublishedTrailDetails(
                    trail,
                    DiscoveredTrailSaveState(savedTrailIds = setOf(trail.id)),
                    PublishedTrailDetailActions({}, {}),
                )
            }
        }
        compose.onNodeWithText("Available offline").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun disabledSourceResultsDoNotAppear() {
        compose.setContent {
            MaterialTheme {
                TrailDiscoveryPanel(
                    TrailDiscoveryUiState(enabled = emptySet(), reply = TrailDiscoveryReply(listOf(trail))),
                    actions(),
                )
            }
        }
        compose.onNodeWithText("Fixture hike").assertDoesNotExist()
    }

    private fun actions(open: (PublishedTrail) -> Unit = {}) = TrailDiscoveryActions({}, {}, {}, open)

    private val trail =
        PublishedTrail(
            "fixture",
            "Fixture hike",
            listOf(TrailSource(TrailProvider.GEOTREK, "1", "https://example.com/1", "© Fixture Park", "CC BY 4.0")),
            geometry = listOf(TrailPoint(GeoPoint(46.0, 7.0)), TrailPoint(GeoPoint(46.001, 7.001))),
            geometryComplete = true,
        )
}
