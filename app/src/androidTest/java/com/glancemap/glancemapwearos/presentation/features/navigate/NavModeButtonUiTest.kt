package com.glancemap.glancemapwearos.presentation.features.navigate

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapwearos.data.repository.SettingsRepository
import com.glancemap.glancemapwearos.presentation.design.theme.GlanceMapTheme
import com.glancemap.glancemapwearos.test.SmallRoundWatchMaxText
import com.glancemap.glancemapwearos.test.withWearDeviceConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.view.MapView

@RunWith(AndroidJUnit4::class)
class NavModeButtonUiTest {
    @get:Rule val composeRule = createComposeRule()
    private var autoRecenterEnabled by mutableStateOf(false)
    private var autoRecenterDelay = 5
    private var navMode by mutableStateOf(NavMode.PANNING)
    private var offline = false
    private var hapticCalls = 0
    private var recenterCalls = 0
    private var refreshCalls = 0
    private var orientationCalls = 0

    @Test
    fun longPressTogglesAutoRecenterWithFeedbackAndNeverFiresTheTapAction() {
        launch()
        button().performTouchInput { longClick() }
        composeRule.runOnIdle {
            assertTrue(autoRecenterEnabled)
            assertEquals(1, hapticCalls)
            assertEquals(0, recenterCalls)
            assertEquals(0, refreshCalls)
            assertEquals(0, orientationCalls)
        }
        composeRule.onNodeWithText("Auto recenter on · 5s").assertIsDisplayed()
        button().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Auto recenter on · 5s"))
        button().performTouchInput { longClick() }
        composeRule.runOnIdle {
            assertFalse(autoRecenterEnabled)
            assertEquals(2, hapticCalls)
            assertEquals(0, recenterCalls)
            assertEquals(0, refreshCalls)
        }
        composeRule.onNodeWithText("Auto recenter off").assertIsDisplayed()
        composeRule.onNodeWithText("Auto recenter on · 5s").assertDoesNotExist()
    }

    @Test
    fun normalTapWhilePanningStillRecentersAndRefreshesWithoutEnablingAutoRecenter() {
        launch()
        button().performClick()
        composeRule.runOnIdle {
            assertEquals(1, hapticCalls)
            assertEquals(1, recenterCalls)
            assertEquals(1, refreshCalls)
            assertEquals(0, orientationCalls)
            assertFalse(autoRecenterEnabled)
        }
        composeRule.onNodeWithText("Auto recenter off").assertDoesNotExist()
    }

    @Test
    fun normalTapWhileFollowingStillChangesOrientationAndLongPressDoesNot() {
        navMode = NavMode.COMPASS_FOLLOW
        launch()
        button().performClick()
        button().performTouchInput { longClick() }
        composeRule.runOnIdle {
            assertEquals(1, orientationCalls)
            assertEquals(0, recenterCalls)
            assertEquals(0, refreshCalls)
            assertEquals(2, hapticCalls)
            assertTrue(autoRecenterEnabled)
        }
    }

    @Test
    fun configuredDelayAndExternalSettingChangesAreReflectedInTheButton() {
        autoRecenterEnabled = true
        autoRecenterDelay = 12
        launch()
        button().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Auto recenter on · 12s"))
        composeRule.onNodeWithText("Auto recenter on · 12s").assertDoesNotExist()
        composeRule.runOnIdle { autoRecenterEnabled = false }
        button().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Auto recenter off"))
        button().performTouchInput { longClick() }
        composeRule.onNodeWithText("Auto recenter on · 12s").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(autoRecenterEnabled) }
    }

    @Test
    fun popupExpiresWithoutTurningAutoRecenterOff() {
        launch()
        button().performTouchInput { longClick() }
        composeRule.onNodeWithText("Auto recenter on · 5s").assertIsDisplayed()
        composeRule.mainClock.advanceTimeBy(2_100L)
        composeRule.onNodeWithText("Auto recenter on · 5s").assertDoesNotExist()
        button().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Auto recenter on · 5s"))
        composeRule.runOnIdle { assertTrue(autoRecenterEnabled) }
    }

    @Test
    fun offlineButtonKeepsBothGesturesDisabled() {
        offline = true
        launch()
        button().assertIsNotEnabled()
        button().performTouchInput { longClick() }
        button().performTouchInput { click() }
        composeRule.runOnIdle {
            assertFalse(autoRecenterEnabled)
            assertEquals(0, hapticCalls)
            assertEquals(0, recenterCalls)
            assertEquals(0, refreshCalls)
            assertEquals(0, orientationCalls)
        }
    }

    private fun button() = composeRule.onNodeWithContentDescription("Nav mode")

    private fun launch() {
        composeRule.setContent {
            val context = LocalContext.current
            val map =
                remember {
                    AndroidGraphicFactory.createInstance(context.applicationContext as Application)
                    MapView(context).apply {
                        model.mapViewPosition.setCenter(LatLong(60.0, 24.0))
                        model.mapViewPosition.setZoomLevel(12, false)
                        setBuiltInZoomControls(false)
                    }
                }
            DisposableEffect(map) { onDispose { map.destroyAll() } }
            withWearDeviceConfig(SmallRoundWatchMaxText) {
                GlanceMapTheme {
                    Box(Modifier.fillMaxSize()) {
                        NavModeButtonOverlay(
                            visible = true,
                            mapView = map,
                            navMode = navMode,
                            isOfflineMode = offline,
                            gpsIndicatorState = GpsFixIndicatorState.GOOD,
                            watchGpsDegradedWarning = false,
                            lastKnownLocation = LatLong(60.0, 24.0),
                            triggerHaptic = { hapticCalls++ },
                            navButtonBottomPadding = 16.dp,
                            navButtonSize = 26.dp,
                            navButtonIconSize = 16.dp,
                            onRecenter = { recenterCalls++ },
                            onRecenterRequested = { refreshCalls++ },
                            autoRecenterEnabled = autoRecenterEnabled,
                            autoRecenterDelay = autoRecenterDelay,
                            onToggleAutoRecenter = { autoRecenterEnabled = !autoRecenterEnabled },
                            onToggleOrientation = { orientationCalls++ },
                            navigationMarkerAnchorMode = SettingsRepository.NAVIGATION_MARKER_ANCHOR_CENTER,
                            isScreenInteractive = false,
                        )
                    }
                }
            }
        }
    }
}
