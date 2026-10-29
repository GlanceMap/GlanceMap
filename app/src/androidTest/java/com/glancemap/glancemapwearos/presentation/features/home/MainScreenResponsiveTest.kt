package com.glancemap.glancemapwearos.presentation.features.home

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.presentation.design.theme.GlanceMapTheme
import com.glancemap.glancemapwearos.presentation.navigation.WatchRoutes
import com.glancemap.glancemapwearos.test.LargeRoundWatch
import com.glancemap.glancemapwearos.test.MediumSquareWatch
import com.glancemap.glancemapwearos.test.PlayLargeRoundWatchMaxText
import com.glancemap.glancemapwearos.test.SmallRoundWatchLargeText
import com.glancemap.glancemapwearos.test.SmallRoundWatchMaxText
import com.glancemap.glancemapwearos.test.WearDeviceTestConfig
import com.glancemap.glancemapwearos.test.withWearDeviceConfig
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainScreenResponsiveTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var navController: NavHostController

    @Test
    fun smallRoundLargeText_keepsCoreActionsVisible() {
        launchMainScreen(config = SmallRoundWatchLargeText)

        assertCoreActionsVisible()
    }

    @Test
    fun smallRoundMaxText_keepsCoreActionsVisible() {
        launchMainScreen(config = SmallRoundWatchMaxText)

        assertCoreActionsVisible()
    }

    @Test
    fun playLargeRoundMaxText_keepsCoreActionsVisible() {
        launchMainScreen(config = PlayLargeRoundWatchMaxText)

        assertCoreActionsVisible()
    }

    private fun assertCoreActionsVisible() {
        composeRule.onNodeWithText("POI").assertIsDisplayed()
        composeRule.onNodeWithText("GPX").assertIsDisplayed()
        composeRule.onNodeWithText("Maps").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Navigate").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test
    fun mediumSquare_navigatesToPoi() {
        launchMainScreen(config = MediumSquareWatch)
        composeRule.onNodeWithText("POI").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("POI destination").assertIsDisplayed()
    }

    @Test
    fun mediumSquare_navigatesToSettings() {
        launchMainScreen(config = MediumSquareWatch)
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Settings destination").assertIsDisplayed()
    }

    @Test
    fun largeRound_navigatesToMaps() {
        launchMainScreen(config = LargeRoundWatch)
        composeRule.onNodeWithText("Maps").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Maps destination").assertIsDisplayed()
    }

    @Test
    fun largeRound_navigatesToNavigate() {
        launchMainScreen(config = LargeRoundWatch)
        composeRule.onNodeWithContentDescription("Navigate").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Navigate destination").assertIsDisplayed()
    }

    @Test
    fun menuNavigateButton_returnsToExistingNavigateEntry() {
        launchMainScreen(
            config = LargeRoundWatch,
            startDestination = WatchRoutes.NAVIGATE,
        )
        val originalNavigateEntryId =
            composeRule.runOnUiThread {
                requireNotNull(navController.currentBackStackEntry).id
            }

        composeRule.onNodeWithText("Open menu").performClick()
        composeRule.onNodeWithContentDescription("Navigate").performClick()
        composeRule.waitForIdle()

        val returnedNavigateEntryId =
            composeRule.runOnUiThread {
                requireNotNull(navController.currentBackStackEntry).id
            }
        assertEquals(originalNavigateEntryId, returnedNavigateEntryId)
    }

    private fun launchMainScreen(
        config: WearDeviceTestConfig,
        startDestination: String = WatchRoutes.MAIN_MENU,
    ) {
        composeRule.setContent {
            withWearDeviceConfig(config = config) {
                GlanceMapTheme {
                    mainScreenTestHost(startDestination = startDestination) { navController = it }
                }
            }
        }
        composeRule.waitForIdle()
    }
}

@Composable
private fun mainScreenTestHost(
    startDestination: String,
    onNavControllerReady: (NavHostController) -> Unit,
) {
    val navController = rememberNavController()
    SideEffect { onNavControllerReady(navController) }
    val navigateViaSwipeLeft: () -> Unit = {
        val popped = navController.popBackStack(WatchRoutes.NAVIGATE, inclusive = false)
        if (!popped) {
            navController.navigate(WatchRoutes.NAVIGATE) {
                popUpTo(WatchRoutes.NAVIGATE) {
                    inclusive = false
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(WatchRoutes.NAVIGATE) {
            Column {
                Text("Navigate destination")
                Button(onClick = { navController.navigate(WatchRoutes.MAIN_MENU) }) {
                    Text("Open menu")
                }
            }
        }
        composable(WatchRoutes.MAIN_MENU) {
            MainScreen(navController = navController, onNavigateClick = navigateViaSwipeLeft)
        }
        composableDestination(route = WatchRoutes.POI, label = "POI destination")
        composableDestination(route = WatchRoutes.GPX, label = "GPX destination")
        composableDestination(route = WatchRoutes.MAPS, label = "Maps destination")
        composableDestination(route = WatchRoutes.SETTINGS, label = "Settings destination")
    }
}

private fun androidx.navigation.NavGraphBuilder.composableDestination(
    route: String,
    label: String,
) {
    composable(route) {
        Text(text = label)
    }
}
