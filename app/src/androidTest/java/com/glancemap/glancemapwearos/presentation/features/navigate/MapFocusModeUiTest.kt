package com.glancemap.glancemapwearos.presentation.features.navigate

import android.app.Application
import android.view.MotionEvent
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapwearos.data.repository.MapFocusControl
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.design.theme.GlanceMapTheme
import com.glancemap.glancemapwearos.presentation.features.routetools.RouteShortcutTray
import com.glancemap.glancemapwearos.test.LargeRoundWatch
import com.glancemap.glancemapwearos.test.MediumSquareWatch
import com.glancemap.glancemapwearos.test.SmallRoundWatch
import com.glancemap.glancemapwearos.test.SmallRoundWatchLargeText
import com.glancemap.glancemapwearos.test.SmallRoundWatchMaxText
import com.glancemap.glancemapwearos.test.WearDeviceTestConfig
import com.glancemap.glancemapwearos.test.withWearDeviceConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Rotation
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.view.MapView

@RunWith(AndroidJUnit4::class)
class MapFocusModeUiTest {
    @get:Rule val composeRule = createComposeRule()
    private val focus = NavigateMapFocusState()
    private var expanded by mutableStateOf(true)
    private var dashboardFullscreen by mutableStateOf(false)
    private var routeToolsOpen by mutableStateOf(false)
    private var compatBack by mutableStateOf(false)
    private lateinit var backDispatcher: OnBackPressedDispatcher
    private var menuCalls = 0
    private var closeAppCalls = 0
    private var closeAppBack by mutableStateOf(false)
    private var routeDismissCalls = 0
    private lateinit var mapView: MapView
    private var mapCreations = 0
    private var mapTouches = 0
    private var gpsState by mutableStateOf(GpsFixIndicatorState.GOOD)
    private var gpsDegraded by mutableStateOf(false)
    private var visibleControls by mutableStateOf(emptySet<MapFocusControl>())
    private var toolsAvailable by mutableStateOf(true)

    @Test
    fun fullActionClosesTrayAndExitRestoresNormalControls() {
        launch()
        enterFocus()
        assertTrue(focus.mapFocusMode)
        assertFalse(expanded)
        composeRule.onNodeWithContentDescription("Open shortcuts").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Close shortcuts").assertDoesNotExist()
        composeRule.onNodeWithText("Full").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Exit full screen").assertIsDisplayed().performClick()
        assertFalse(focus.mapFocusMode)
        composeRule.onNodeWithContentDescription("Open shortcuts").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Exit full screen").assertDoesNotExist()
    }

    @Test
    fun smallRoundToolsKeepExitInTheFullActionSlot() = checkToolsVisibleLayout(SmallRoundWatchMaxText)

    @Test
    fun squareToolsKeepExitInTheFullActionSlot() = checkToolsVisibleLayout(MediumSquareWatch)

    @Test
    fun standaloneExitReturnsWhenToolsAreUnavailableOrDisabled() {
        visibleControls = setOf(MapFocusControl.TOOLS_BUTTON)
        launch()
        enterFocus()
        composeRule.onNodeWithContentDescription("Exit full screen").assertDoesNotExist()
        composeRule.runOnIdle { toolsAvailable = false }
        composeRule.onNodeWithContentDescription("Open shortcuts").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Exit full screen").assertIsDisplayed()
        composeRule.runOnIdle { toolsAvailable = true }
        composeRule.onNodeWithContentDescription("Open shortcuts").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Exit full screen").assertDoesNotExist()
        composeRule.runOnIdle { visibleControls = emptySet() }
        composeRule.onNodeWithContentDescription("Open shortcuts").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Exit full screen").assertIsDisplayed().performClick()
        assertFalse(focus.mapFocusMode)
    }

    @Test
    fun entranceHighlightSettlesAndReturnsOnTheNextEntry() {
        launch()
        enterFocus()
        composeRule.onNodeWithContentDescription("Exit full screen", useUnmergedTree = true).assertWidthIsEqualTo(22.dp)
        composeRule.waitUntil(timeoutMillis = 3_000) { !focus.exitHighlightVisible }
        composeRule.waitForIdle()
        assertTrue(focus.mapFocusMode)
        composeRule.onNodeWithContentDescription("Exit full screen", useUnmergedTree = true).assertWidthIsEqualTo(18.dp)
        composeRule.onNodeWithContentDescription("Exit full screen").assertWidthIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithContentDescription("Open shortcuts").performClick()
        enterFocus()
        composeRule.onNodeWithContentDescription("Exit full screen", useUnmergedTree = true).assertWidthIsEqualTo(22.dp)
    }

    @Test
    fun backExitsFocusBeforeWorkflowsOrMenuForBothCompatibilitySettings() {
        launch()
        listOf(false, true).forEach { compat ->
            composeRule.runOnIdle {
                compatBack = compat
                routeToolsOpen = false
                expanded = true
            }
            enterFocus()
            composeRule.runOnIdle {
                routeToolsOpen = true
                backDispatcher.onBackPressed()
            }
            composeRule.runOnIdle {
                assertFalse(focus.mapFocusMode)
                assertTrue(routeToolsOpen)
                assertEquals(0, menuCalls)
                backDispatcher.onBackPressed()
            }
            composeRule.runOnIdle {
                assertFalse(routeToolsOpen)
                backDispatcher.onBackPressed()
            }
            composeRule.runOnIdle { assertEquals(if (compat) 1 else 0, menuCalls) }
        }
        assertEquals(2, routeDismissCalls)
    }

    @Test
    fun closeAppBackExitsFocusAndClosesWorkflowsBeforeClosingApp() {
        launch()
        composeRule.runOnIdle { closeAppBack = true }
        enterFocus()
        composeRule.runOnIdle {
            routeToolsOpen = true
            backDispatcher.onBackPressed()
        }
        composeRule.runOnIdle {
            assertFalse(focus.mapFocusMode)
            assertTrue(routeToolsOpen)
            assertEquals(0, closeAppCalls)
            backDispatcher.onBackPressed()
        }
        composeRule.runOnIdle {
            assertFalse(routeToolsOpen)
            assertEquals(0, closeAppCalls)
            backDispatcher.onBackPressed()
        }
        composeRule.runOnIdle {
            assertEquals(1, closeAppCalls)
            assertEquals(0, menuCalls)
        }
    }

    @Test
    fun smallRoundLargeTextKeepsAllFiveActionsAndExitTouchTargetUsable() = checkSmallLayout(SmallRoundWatchLargeText)

    @Test
    fun smallRoundMaximumTextKeepsAllFiveActionsAndExitTouchTargetUsable() = checkSmallLayout(SmallRoundWatchMaxText)

    @Test
    fun mediumSquareKeepsAllFiveActionsAndExitTouchTargetUsable() = checkSmallLayout(MediumSquareWatch)

    @Test
    fun hintExpiresWithoutLeavingFocusAndDoesNotRepeatOnToggle() {
        launch()
        enterFocus()
        composeRule.onNodeWithText("Full screen").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 4_000) { !focus.hintVisible }
        assertTrue(focus.mapFocusMode)
        composeRule.onNodeWithContentDescription("Exit full screen").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Open shortcuts").performClick()
        enterFocus()
        composeRule.onNodeWithText("Full screen").assertDoesNotExist()
    }

    @Test
    fun existingDashboardTakesOverAndEndsFocusWithoutChangingDashboardState() {
        launch()
        enterFocus()
        composeRule.runOnIdle { dashboardFullscreen = true }
        composeRule.runOnIdle {
            assertFalse(focus.mapFocusMode)
            assertTrue(dashboardFullscreen)
        }
        composeRule.onNodeWithContentDescription("Exit full screen").assertDoesNotExist()
    }

    @Test
    fun gpsChangesNeverBringBackTheNavModeControlWhileFocused() {
        launch(withMap = true)
        composeRule.onNodeWithContentDescription("Nav mode").assertIsDisplayed()
        enterFocus()
        GpsFixIndicatorState.entries.forEach { gps ->
            listOf(false, true).forEach { degraded ->
                composeRule.runOnIdle {
                    gpsState = gps
                    gpsDegraded = degraded
                }
                composeRule.onNodeWithContentDescription("Nav mode").assertDoesNotExist()
                composeRule.onNodeWithContentDescription("Exit full screen").assertIsDisplayed()
                composeRule.runOnIdle { assertTrue(focus.mapFocusMode) }
            }
        }
        composeRule.onNodeWithContentDescription("Exit full screen").performClick()
        composeRule.onNodeWithContentDescription("Nav mode").assertIsDisplayed()
    }

    @Test
    fun centerControlOnlyReturnsWhenExplicitlyEnabledInFullscreenSettings() {
        launch(withMap = true)
        enterFocus()
        composeRule.runOnIdle { gpsState = GpsFixIndicatorState.SEARCHING }
        composeRule.onNodeWithContentDescription("Nav mode").assertDoesNotExist()
        composeRule.runOnIdle { visibleControls = setOf(MapFocusControl.NAV_MODE_BUTTON) }
        composeRule.onNodeWithContentDescription("Nav mode").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Exit full screen").assertIsDisplayed()
        composeRule.runOnIdle { visibleControls = emptySet() }
        composeRule.onNodeWithContentDescription("Nav mode").assertDoesNotExist()
    }

    @Test
    fun togglesRetainMapsforgeViewAndViewportAndAllowMapTouchesOutsideExit() {
        launch(config = LargeRoundWatch, withMap = true)
        lateinit var original: MapView
        lateinit var center: LatLong
        var zoom: Byte = 0
        lateinit var rotation: Rotation
        composeRule.runOnIdle {
            original = mapView
            center = mapView.model.mapViewPosition.center
            zoom = mapView.model.mapViewPosition.zoomLevel
            rotation = mapView.mapRotation
        }
        enterFocus()
        composeRule.runOnIdle {
            assertSame(original, mapView)
            assertEquals(1, mapCreations)
            assertEquals(center, mapView.model.mapViewPosition.center)
            assertEquals(zoom, mapView.model.mapViewPosition.zoomLevel)
            assertEquals(rotation, mapView.mapRotation)
        }
        composeRule.onNodeWithContentDescription("Exit full screen").performClick()
        composeRule.runOnIdle {
            assertSame(original, mapView)
            assertEquals(1, mapCreations)
            assertEquals(center, mapView.model.mapViewPosition.center)
            assertEquals(zoom, mapView.model.mapViewPosition.zoomLevel)
            assertEquals(rotation, mapView.mapRotation)
            expanded = true
        }
        enterFocus()
        composeRule.onNodeWithTag("map_focus_test_surface").performTouchInput {
            swipe(Offset(width * 0.2f, height * 0.5f), Offset(width * 0.45f, height * 0.6f))
        }
        composeRule.runOnIdle { assertTrue(mapTouches > 0) }
    }

    private fun checkToolsVisibleLayout(config: WearDeviceTestConfig) {
        visibleControls = setOf(MapFocusControl.TOOLS_BUTTON)
        launch(config, anchorSize = if (config.isRound) 26.dp else 30.dp)
        val fullCenter = shortcutCenter("Full")
        enterFocus()
        composeRule.onNodeWithContentDescription("Exit full screen").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Open shortcuts").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Full").assertDoesNotExist()
        composeRule.onNodeWithText("Exit full").assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription("Exit full screen")
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        val exitCenter = shortcutCenter("Exit full")
        assertEquals(fullCenter.x, exitCenter.x, 1f)
        assertEquals(fullCenter.y, exitCenter.y, 1f)
        composeRule.onNodeWithContentDescription("Exit full screen").performClick()
        composeRule.runOnIdle {
            assertFalse(focus.mapFocusMode)
            assertFalse(expanded)
        }
        composeRule.onNodeWithContentDescription("Open shortcuts").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Exit full screen").assertDoesNotExist()
    }

    private fun shortcutCenter(label: String): Offset =
        composeRule
            .onNodeWithText(label)
            .fetchSemanticsNode()
            .boundsInRoot.center

    private fun checkSmallLayout(config: WearDeviceTestConfig) {
        launch(config, anchorSize = if (config.isRound) 26.dp else 30.dp)
        val toolsCenter =
            composeRule
                .onNodeWithContentDescription("Close shortcuts", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot.center
        listOf("POI", "Stay", "GPX", "REC", "Full").forEach { label ->
            composeRule
                .onNodeWithText(label)
                .assertIsDisplayed()
                .assertHasClickAction()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
        }
        val poi = shortcutCenter("POI")
        val stay = shortcutCenter("Stay")
        val gpx = shortcutCenter("GPX")
        val rec = shortcutCenter("REC")
        val full = shortcutCenter("Full")
        val surface =
            composeRule
                .onNodeWithTag("map_focus_test_surface")
                .fetchSemanticsNode()
                .boundsInRoot.center
        assertEquals(poi.y, stay.y, 1f)
        assertEquals(gpx.y, rec.y, 1f)
        assertEquals(poi.x, gpx.x, 1f)
        assertEquals(stay.x, rec.x, 1f)
        assertEquals(surface.x, (poi.x + stay.x) / 2f, 1f)
        assertEquals(toolsCenter.y, (poi.y + gpx.y) / 2f, 1f)
        assertEquals(surface.x, full.x, 1f)
        assertTrue(full.y < poi.y)
        enterFocus()
        composeRule
            .onNodeWithContentDescription("Exit full screen")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        val exitCenter =
            composeRule
                .onNodeWithContentDescription("Exit full screen", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot.center
        assertEquals(toolsCenter.x, exitCenter.x, 1f)
        assertEquals(toolsCenter.y, exitCenter.y, 1f)
    }

    private fun enterFocus() {
        composeRule.onNodeWithContentDescription("Enter full screen").performClick()
        composeRule.waitForIdle()
    }

    private fun launch(
        config: WearDeviceTestConfig = SmallRoundWatch,
        withMap: Boolean = false,
        anchorSize: Dp = 26.dp,
    ) {
        composeRule.setContent {
            backDispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            withWearDeviceConfig(config) {
                GlanceMapTheme {
                    TestBackHandler()
                    MapFocusModeEffects(focus, dashboardFullscreen) { focus.exit() }
                    Box(Modifier.fillMaxSize().testTag("map_focus_test_surface")) {
                        val chrome =
                            mapFocusChromePolicy(focus.mapFocusMode, dashboardFullscreen, visibleControls, toolsAvailable)
                        if (withMap) {
                            TestMapSurface()
                            NavModeButtonOverlay(
                                visible = chrome.shows(MapFocusControl.NAV_MODE_BUTTON),
                                mapView = mapView,
                                navMode = NavMode.PANNING,
                                isOfflineMode = false,
                                gpsIndicatorState = gpsState,
                                watchGpsDegradedWarning = gpsDegraded,
                                lastKnownLocation = null,
                                triggerHaptic = {},
                                navButtonBottomPadding = 8.dp,
                                navButtonSize = 26.dp,
                                navButtonIconSize = 16.dp,
                                onRecenter = {},
                                onRecenterRequested = {},
                                autoRecenterEnabled = false,
                                autoRecenterDelay = 5,
                                onToggleAutoRecenter = {},
                                onToggleOrientation = {},
                                navigationMarkerAnchorMode = SettingsRepository.NAVIGATION_MARKER_ANCHOR_CENTER,
                                isScreenInteractive = false,
                            )
                        }
                        if (chrome.showToolsControl) {
                            RouteShortcutTray(
                                expanded = expanded,
                                mapFocusMode = focus.mapFocusMode,
                                keepAppOpen = false,
                                edgePadding = 2.dp,
                                anchorSize = anchorSize,
                                actionHeight = 26.dp,
                                iconSize = 14.dp,
                                recordingActive = false,
                                recordingPaused = false,
                                recordingSaving = false,
                                headingReferenceTestActive = false,
                                onToggleExpanded = { expanded = !expanded },
                                onKeepAppOpenClick = {},
                                onGpxToolsClick = {},
                                onCreatePoiClick = {},
                                onRecordingClick = {},
                                onHeadingReferenceMark = {},
                                onMapFocusClick = {
                                    expanded = false
                                    if (focus.mapFocusMode) focus.exit() else focus.enter {}
                                },
                            )
                        }
                        MapFocusHint(focus.hintVisible && !dashboardFullscreen)
                        if (chrome.showExitControl) {
                            MapFocusExitControl(
                                edgePadding = 2.dp,
                                anchorSize = anchorSize,
                                highlighted = focus.exitHighlightVisible,
                            ) { focus.exit() }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Composable
    private fun TestBackHandler() {
        NavigateBackHandler(
            mapFocusMode = focus.mapFocusMode,
            onExitMapFocus = {
                expanded = false
                focus.exit()
            },
            createdPoiCreateInProgress = false,
            completedRouteToolDraftActive = false,
            routeToolExecutionInProgress = false,
            routeToolSessionActive = false,
            showPoiCreationChoiceDialog = false,
            showPoiCoordinateEntryDialog = false,
            showCreatedPoiRenameDialog = false,
            createdPoiRenameInProgress = false,
            poiCreationSelectionActive = false,
            showRouteToolsPanel = routeToolsOpen,
            shortcutTrayExpanded = expanded,
            backButtonExitsNavigation = compatBack,
            backButtonClosesApp = closeAppBack,
            onCloseApp = { closeAppCalls++ },
            onDismissCompletedRouteToolDraft = {},
            onCancelRouteToolSession = {},
            onDismissPoiCreationChoiceDialog = {},
            onDismissPoiCoordinateEntryDialog = {},
            onDismissCreatedPoiRename = {},
            onCancelPoiCreation = {},
            onDismissRouteToolsPanel = {
                routeToolsOpen = false
                routeDismissCalls++
            },
            onDismissShortcutTray = { expanded = false },
            onMenuClick = { menuCalls++ },
        )
    }

    @Composable
    private fun TestMapSurface() {
        val context = LocalContext.current
        val nativeMap =
            remember {
                AndroidGraphicFactory.createInstance(context.applicationContext as Application)
                MapView(context)
                    .apply {
                        model.mapViewPosition.setCenter(LatLong(60.0, 24.0))
                        model.mapViewPosition.setZoomLevel(12, false)
                        setBuiltInZoomControls(false)
                        rotate(Rotation(33f, 40f, 40f))
                        setOnTouchListener { _, event ->
                            if (event.actionMasked == MotionEvent.ACTION_DOWN) mapTouches++
                            false
                        }
                    }.also {
                        mapView = it
                        mapCreations++
                    }
            }
        AndroidView(factory = { nativeMap }, modifier = Modifier.fillMaxSize())
        DisposableEffect(nativeMap) { onDispose { nativeMap.destroyAll() } }
    }
}
