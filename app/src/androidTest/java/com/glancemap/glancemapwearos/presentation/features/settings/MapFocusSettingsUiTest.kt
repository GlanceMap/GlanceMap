package com.glancemap.glancemapwearos.presentation.features.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapwearos.data.repository.MapFocusControl
import com.glancemap.glancemapwearos.presentation.design.theme.GlanceMapTheme
import com.glancemap.glancemapwearos.test.SmallRoundWatchMaxText
import com.glancemap.glancemapwearos.test.withWearDeviceConfig
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapFocusSettingsUiTest {
    @get:Rule val composeRule = createComposeRule()

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
            val label = "Show ${mapFocusControlLabel(control)}"
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
