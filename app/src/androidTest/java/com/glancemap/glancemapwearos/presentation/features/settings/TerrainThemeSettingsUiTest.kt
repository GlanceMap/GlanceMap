package com.glancemap.glancemapwearos.presentation.features.settings

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glancemap.glancemapwearos.GlanceMapWearApp
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.presentation.features.maps.theme.ThemeViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerrainThemeSettingsUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun hikingThemeAppearsOnlyWhile3dIsEnabled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as GlanceMapWearApp
        val repository = app.container.settingsRepository
        val settings = SettingsViewModel(repository)
        val theme = ThemeViewModel(app.container.themeRepository, context, repository)
        val label = context.getString(R.string.map_terrain_3d_hiking_theme)
        runBlocking { repository.setTerrain3dEnabled(false) }
        composeRule.setContent { MapSettingsScreen(rememberNavController(), settings, theme) {} }
        composeRule.onNodeWithText(label).assertDoesNotExist()
        runBlocking { repository.setTerrain3dEnabled(true) }
        composeRule.waitUntil(5_000) { settings.terrain3dEnabled.value }
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(label))
        composeRule.onNodeWithText(label).assertExists()
        runBlocking { repository.setTerrain3dEnabled(false) }
        composeRule.waitUntil(5_000) { !settings.terrain3dEnabled.value }
        composeRule.onNodeWithText(label).assertDoesNotExist()
    }
}
