package com.glancemap.glancemapcompanionapp.discovery

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.glancemap.glancemapcompanionapp.filepicker.CompanionHomeArea
import com.glancemap.glancemapcompanionapp.filepicker.CompanionMenuActions
import com.glancemap.glancemapcompanionapp.filepicker.CompanionMenuState
import com.glancemap.glancemapcompanionapp.filepicker.companionNavigationDrawer
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.profile.TrailPoint
import org.junit.Assert.assertEquals
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
            .onNodeWithContentDescription(TrailProvider.OPEN_STREET_MAP.label)
            .assertIsOn()
            .performClick()
        compose.runOnIdle { assertEquals(emptySet<TrailProvider>(), settings.value.enabled) }
        compose.onNodeWithContentDescription(TrailProvider.OPEN_STREET_MAP.label).assertIsOff()
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
    fun providerLabelOpensDetailsAndDownloadsGeometry() {
        var saved = 0
        compose.setContent {
            var selected by remember { mutableStateOf<PublishedTrail?>(null) }
            MaterialTheme {
                selected?.let { trail ->
                    PublishedTrailDetails(trail, DiscoveredTrailSaveState(), { selected = null }) { saved++ }
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
                    {},
                    { downloads++ },
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
    fun searchSourceChipsChangeTheSelectedProviders() {
        val enabled = mutableStateOf(setOf(TrailProvider.OPEN_STREET_MAP))
        compose.setContent {
            MaterialTheme {
                TrailSourceFilters(enabled.value) { provider, selected ->
                    enabled.value = if (selected) enabled.value + provider else enabled.value - provider
                }
            }
        }
        compose.onNodeWithContentDescription("Search OpenStreetMap").assertIsSelected().performClick()
        compose.onNodeWithContentDescription("Search Geotrek").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(setOf(TrailProvider.GEOTREK), enabled.value) }
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
    fun discoveryIsAnActionInTheLeftDrawer() {
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
        compose.onNodeWithText("Discover routes").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(CompanionHomeArea.DISCOVER_ROUTES, destination) }
    }

    @Test
    fun downloadedDetailsCannotAccidentallyImportTheRouteAgain() {
        compose.setContent {
            MaterialTheme {
                PublishedTrailDetails(
                    trail,
                    DiscoveredTrailSaveState(savedTrailIds = setOf(trail.id)),
                    {},
                    {},
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
