package com.glancemap.glancemapwearos.presentation.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.style.TextOverflow
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.presentation.design.theme.GlanceMapTheme
import com.glancemap.glancemapwearos.test.LargeRoundWatch
import com.glancemap.glancemapwearos.test.MediumSquareWatch
import com.glancemap.glancemapwearos.test.PlayLargeRoundWatchMaxText
import com.glancemap.glancemapwearos.test.SmallRoundWatchLargeText
import com.glancemap.glancemapwearos.test.SmallRoundWatchMaxText
import com.glancemap.glancemapwearos.test.WearDeviceTestConfig
import com.glancemap.glancemapwearos.test.withWearDeviceConfig
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsMenuDenseLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun smallRoundLargeText_keepsBottomActionsReachable() {
        assertDenseMenuScrolls(config = SmallRoundWatchLargeText)
    }

    @Test
    fun smallRoundMaxText_keepsBottomActionsReachable() {
        assertDenseMenuScrolls(config = SmallRoundWatchMaxText)
    }

    @Test
    fun playLargeRoundMaxText_keepsBottomActionsReachable() {
        assertDenseMenuScrolls(config = PlayLargeRoundWatchMaxText)
    }

    @Test
    fun mediumSquare_keepsBottomActionsReachable() {
        assertDenseMenuScrolls(config = MediumSquareWatch)
    }

    @Test
    fun largeRound_keepsBottomActionsReachable() {
        assertDenseMenuScrolls(config = LargeRoundWatch)
    }

    private fun assertDenseMenuScrolls(config: WearDeviceTestConfig) {
        launchDenseMenu(config = config)

        composeRule.onNodeWithText("General").assertIsDisplayed()
        composeRule
            .onNodeWithTag(TAG_UNITS_CHIP)
            .assertIsDisplayed()
            .assertHasClickAction()

        composeRule.runOnIdle {
            denseMenuCenterItemIndex.value = 11
        }
        composeRule.waitForIdle()
        composeRule
            .onNodeWithTag(TAG_CLEAR_PARTIAL_CHIP)
            .assertIsDisplayed()
            .assertHasClickAction()

        composeRule.runOnIdle {
            denseMenuCenterItemIndex.value = 13
        }
        composeRule.waitForIdle()

        composeRule
            .onNodeWithTag(TAG_CREDITS_CHIP)
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    private fun launchDenseMenu(config: WearDeviceTestConfig) {
        denseMenuCenterItemIndex.value = 1
        composeRule.setContent {
            withWearDeviceConfig(config = config) {
                GlanceMapTheme {
                    denseSettingsMenuShowcase()
                }
            }
        }
        composeRule.waitForIdle()
    }
}

@Composable
private fun denseSettingsMenuShowcase() {
    key(denseMenuCenterItemIndex.value) {
        val listState =
            rememberScalingLazyListState(
                initialCenterItemIndex = denseMenuCenterItemIndex.value,
            )
        val listTokens = rememberSettingsListTokens()

        ScreenScaffold(scrollState = listState) {
            ScalingLazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .testTag(TAG_SETTINGS_LIST),
                state = listState,
                contentPadding =
                    PaddingValues(
                        start = listTokens.horizontalPadding,
                        end = listTokens.horizontalPadding,
                        top = listTokens.topPadding,
                        bottom = listTokens.bottomPadding,
                    ),
                verticalArrangement = Arrangement.spacedBy(listTokens.itemSpacing),
            ) {
                item {
                    Text(
                        text = "General",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                item {
                    SettingsPickerChip(
                        label = "Units",
                        secondaryLabel = "Metric",
                        onClick = {},
                        modifier = Modifier.testTag(TAG_UNITS_CHIP),
                    )
                }
                item {
                    SettingsSectionChip(
                        label = "GPS settings",
                        onClick = {},
                    )
                }
                item {
                    SettingsSectionChip(
                        label = "Compass settings",
                        onClick = {},
                    )
                }
                item {
                    Text(
                        text = "Screen settings",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                item {
                    SettingsSectionChip(
                        label = "POI settings",
                        onClick = {},
                    )
                }
                item {
                    SettingsSectionChip(
                        label = "GPX settings",
                        onClick = {},
                    )
                }
                item {
                    SettingsSectionChip(
                        label = "Map settings",
                        onClick = {},
                    )
                }
                item {
                    Text(
                        text = "Advanced settings",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                item {
                    SettingsSectionChip(
                        label = "Debugging",
                        onClick = {},
                    )
                }
                item {
                    SettingsPickerChip(
                        label = "Clear cache",
                        iconImageVector = null,
                        onClick = {},
                    )
                }
                item {
                    SettingsPickerChip(
                        label = "Clear partial transfer",
                        secondaryLabel = "1 file · 123.4 MB",
                        iconImageVector = null,
                        onClick = {},
                        modifier = Modifier.testTag(TAG_CLEAR_PARTIAL_CHIP),
                    )
                }
                item {
                    SettingsPickerChip(
                        label = "Reset to Default",
                        iconImageVector = null,
                        onClick = {},
                    )
                }
                item {
                    SettingsSectionChip(
                        label = "Credits & Legal",
                        onClick = {},
                        modifier = Modifier.testTag(TAG_CREDITS_CHIP),
                    )
                }
            }
        }
    }
}

private const val TAG_SETTINGS_LIST = "settings_dense_list"
private const val TAG_UNITS_CHIP = "settings_dense_units_chip"
private const val TAG_CLEAR_PARTIAL_CHIP = "settings_dense_clear_partial_chip"
private const val TAG_CREDITS_CHIP = "settings_dense_credits_chip"

private val denseMenuCenterItemIndex = mutableStateOf(1)
