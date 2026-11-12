package com.glancemap.glancemapwearos.presentation.features.navigate

import android.Manifest
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.GlanceMapWearApp
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.data.repository.MapFocusControl
import com.glancemap.glancemapwearos.presentation.design.theme.GlanceMapTheme
import com.glancemap.glancemapwearos.presentation.navigation.WatchRoutes
import com.glancemap.glancemapwearos.test.LargeRoundWatch
import com.glancemap.glancemapwearos.test.withWearDeviceConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class MapFocusNavigationUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as GlanceMapWearApp).container
    private var menuWasVisible = false

    @Before
    fun prepareMapControls() {
        composeRule.runOnUiThread {
            composeRule.activity.setTurnScreenOn(true)
            composeRule.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
        automation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        AndroidGraphicFactory.createInstance(context.applicationContext as GlanceMapWearApp)
        runBlocking {
            menuWasVisible = MapFocusControl.MENU_BUTTON in container.settingsRepository.mapFocusVisibleControls.first()
            container.settingsRepository.setMapFocusControlVisible(MapFocusControl.MENU_BUTTON, true)
        }
    }

    @After
    fun restoreMapControls() {
        runBlocking {
            container.settingsRepository.setMapFocusControlVisible(MapFocusControl.MENU_BUTTON, menuWasVisible)
        }
        composeRule.runOnUiThread {
            composeRule.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @Test
    fun menuRoundTripsRestoreFocusUntilTheUserExplicitlyExits() {
        val creations = AtomicInteger()
        val disposals = AtomicInteger()
        composeRule.setContent {
            withWearDeviceConfig(LargeRoundWatch) {
                GlanceMapTheme {
                    val navController = rememberNavController()
                    NavHost(navController, startDestination = WatchRoutes.NAVIGATE) {
                        composable(WatchRoutes.NAVIGATE) {
                            DisposableEffect(Unit) {
                                creations.incrementAndGet()
                                onDispose { disposals.incrementAndGet() }
                            }
                            NavigateScreen(
                                mapViewModel = container.mapViewModel,
                                gpxViewModel = container.gpxViewModel,
                                poiViewModel = container.poiViewModel,
                                settingsViewModel = container.settingsViewModel,
                                locationViewModel = container.locationViewModel,
                                traceRecordingViewModel = container.traceRecordingViewModel,
                                compassViewModel = container.compassViewModel,
                                isAmbient = false,
                                isDeviceInteractive = true,
                                ambientTickMs = 0L,
                                showNavigateTime = false,
                                onMenuClick = { navController.navigate(WatchRoutes.MAIN_MENU) },
                                onCloseApp = {},
                            )
                        }
                        composable(WatchRoutes.MAIN_MENU) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    "Return to map",
                                    Modifier.testTag("return_to_map").clickable { navController.popBackStack() },
                                )
                            }
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.gpx_open_shortcuts)).performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.navigation_enter_map_focus)).performClick()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.navigation_exit_map_focus))
            .assertIsDisplayed()
        returnFromMenuAfterDisposal(disposals, expectedDisposals = 1)
        composeRule.waitUntil { creations.get() >= 2 }
        composeRule.onNodeWithContentDescription(context.getString(R.string.navigation_exit_map_focus)).performClick()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.navigation_exit_map_focus))
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(R.string.gpx_open_shortcuts)).assertIsDisplayed()
        returnFromMenuAfterDisposal(disposals, expectedDisposals = 2)
        composeRule.waitUntil { creations.get() >= 3 }
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.navigation_exit_map_focus))
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(R.string.gpx_open_shortcuts)).assertIsDisplayed()
    }

    private fun returnFromMenuAfterDisposal(
        disposals: AtomicInteger,
        expectedDisposals: Int,
    ) {
        composeRule.onNodeWithContentDescription(context.getString(R.string.navigate_menu)).performClick()
        composeRule.onNodeWithTag("return_to_map").assertIsDisplayed()
        composeRule.waitUntil { disposals.get() >= expectedDisposals }
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.navigation_exit_map_focus))
            .assertDoesNotExist()
        composeRule.onNodeWithTag("return_to_map").performClick()
    }
}
