package com.glancemap.glancemapwearos.presentation.features.settings

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.data.repository.MapFocusControl
import com.glancemap.glancemapwearos.presentation.design.theme.GlanceMapTheme
import com.glancemap.glancemapwearos.test.SmallRoundWatchMaxText
import com.glancemap.glancemapwearos.test.withWearDeviceConfig
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class MapFocusSettingsUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun frenchFullscreenSettingsKeepLocalizedLabelsAndIndependentToggles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.FRENCH) }
        val localized = context.createConfigurationContext(configuration)
        var visible by mutableStateOf(emptySet<MapFocusControl>())
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalResources provides localized.resources) {
                withWearDeviceConfig(SmallRoundWatchMaxText) {
                    GlanceMapTheme {
                        MapFocusSettingsContent(
                            visibleControls = visible,
                            onControlVisibleChange = { control, show ->
                                visible = if (show) visible + control else visible - control
                            },
                            onOpenGeneralSettings = {},
                        )
                    }
                }
            }
        }
        val title = localized.getString(R.string.settings_fullscreen_mode)
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(title))
        composeRule.onNodeWithText(title).assertIsDisplayed()
        val label = localized.getString(R.string.settings_fullscreen_show_menu)
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(label))
        composeRule
            .onNodeWithText(label)
            .assertIsOff()
            .performClick()
            .assertIsOn()
        composeRule.runOnIdle { assertEquals(setOf(MapFocusControl.MENU_BUTTON), visible) }
    }

    @Test
    fun controlsStartOffAndCanBeEnabledIndependentlyOnSmallWatch() {
        var visible by mutableStateOf(emptySet<MapFocusControl>())
        composeRule.setContent {
            withWearDeviceConfig(SmallRoundWatchMaxText) {
                GlanceMapTheme {
                    MapFocusSettingsContent(
                        visibleControls = visible,
                        onControlVisibleChange = { control, show ->
                            visible = if (show) visible + control else visible - control
                        },
                        onOpenGeneralSettings = {},
                    )
                }
            }
        }
        MapFocusControl.entries.forEach { control ->
            val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(mapFocusControlLabelRes(control))
            composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(label))
            composeRule
                .onNodeWithText(label)
                .assertIsOff()
                .performClick()
                .assertIsOn()
        }
        composeRule.runOnIdle { assertEquals(MapFocusControl.entries.toSet(), visible) }
    }
}
