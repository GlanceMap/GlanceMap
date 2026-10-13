package com.glancemap.glancemapcompanionapp.map

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneMapSearchUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun narrowSearchControlRemainsClickableAndScaleUsesPhysicalPixelsAtLargeFontSize() {
        var opened = 0
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.7f)) {
                MaterialTheme {
                    Box(modifier = Modifier.width(320.dp).padding(horizontal = 72.dp)) {
                        phoneMapSearchControl(
                            camera = PhoneMapCameraSnapshot(48.85, 2.35, 14.0, groundResolutionMetersPerPixel = 2.0),
                            isMetric = true,
                            onClick = { opened += 1 },
                        )
                    }
                }
            }
        }
        val search = composeRule.activity.getString(R.string.map_search_hint)
        composeRule.onNodeWithText(search).assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, opened) }
        val bar = composeRule.onNodeWithTag("map-search-scale-bar", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        // 2 metres per physical pixel: the rendered bar and its metre label must agree.
        val scaleNodes =
            composeRule.onAllNodes(
                androidx.compose.ui.test
                    .hasText("m", substring = true),
                useUnmergedTree = true,
            )
        val metresLabel =
            scaleNodes
                .fetchSemanticsNodes()
                .mapNotNull { node ->
                    node.config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                        .firstOrNull()
                        ?.text
                        ?.takeIf { it.endsWith(" m") }
                }.single()
        assertEquals(metresLabel.removeSuffix(" m").toDouble(), bar.width * 2.0, 2.1)
        assertTrue(bar.left >= 0f && bar.width > 0f)
    }

    @Test
    fun typingDoesNotSearchUntilSubmissionAndOfflineScopeIsVisible() {
        val state = mutableStateOf(PhoneMapSearchUiState(online = false))
        var submissions = 0
        val result = PhoneMapSearchResult("fixture", "Museum", "source.poi", PhoneMapCoordinate(48.85, 2.35))
        var selected: PhoneMapSearchResult? = null
        composeRule.setContent {
            MaterialTheme {
                PhoneMapSearchPanel(
                    state = state.value,
                    actions =
                        PhoneMapSearchActions(
                            onQueryChanged = { state.value = state.value.copy(query = it) },
                            onSearch = { submissions += 1 },
                            onResultSelected = { selected = it },
                            onDismiss = {},
                        ),
                )
            }
        }
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.map_search_offline_scope)).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.map_search_poi_hint)).performTextInput("Museum")
        composeRule.runOnIdle { assertEquals(0, submissions) }
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.map_search_action)).performClick()
        composeRule.runOnIdle {
            assertEquals(1, submissions)
            state.value = state.value.copy(searched = true, reply = PhoneMapSearchReply(listOf(result)))
        }
        composeRule.onNodeWithText("source.poi").performClick()
        composeRule.runOnIdle { assertEquals(result, selected) }
    }

    @Test
    fun selectedResultSurvivesSavedStateRestoration() {
        val restoration = StateRestorationTester(composeRule)
        lateinit var selection: MutableState<PhoneMapSearchResult?>
        val result = PhoneMapSearchResult("fixture", "Museum", "Paris", PhoneMapCoordinate(48.85, 2.35))
        restoration.setContent {
            selection = rememberSaveable(stateSaver = phoneMapSearchResultSaver) { mutableStateOf(null) }
        }
        composeRule.runOnIdle { selection.value = result }
        restoration.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle { assertEquals(result, selection.value) }
    }
}
