package com.glancemap.glancemapcompanionapp.map

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneMaptoolkitAttributionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun narrowMapKeepsCreditsBelowTheMapAndToolbarWithAClickable24dpLogo() {
        val opened = mutableListOf<String>()
        composeRule.setContent {
            CompositionLocalProvider(
                LocalUriHandler provides
                    object : UriHandler {
                        override fun openUri(uri: String) {
                            opened.add(uri)
                        }
                    },
            ) {
                MaterialTheme {
                    Box(Modifier.width(240.dp).fillMaxSize()) {
                        MapToolScaffold(
                            presentation =
                                MapToolScaffoldState(MapToolPanelState(), maptoolkitAttributionVisible = true),
                            actions = emptyActions(),
                            mapContent = { Box(Modifier.fillMaxSize().testTag("map-content")) },
                            panelContent = { _, _, _ -> },
                        )
                    }
                }
            }
        }
        val logo = composeRule.onNodeWithContentDescription("Maptoolkit", useUnmergedTree = true)
        logo.assertIsDisplayed()
        composeRule.onNodeWithText("© Maptoolkit © Openstreetmap").assertIsDisplayed()
        val credits = composeRule.onNodeWithTag("maptoolkit-attribution").fetchSemanticsNode().boundsInRoot
        val map = composeRule.onNodeWithTag("map-content").fetchSemanticsNode().boundsInRoot
        val toolbar = composeRule.onNodeWithTag("map-tool-toolbar").fetchSemanticsNode().boundsInRoot
        assertTrue(map.bottom <= credits.top)
        assertTrue(toolbar.bottom <= credits.top)
        val density = composeRule.activity.resources.displayMetrics.density
        assertTrue(logo.fetchSemanticsNode().boundsInRoot.height >= 24f * density)
        composeRule.onNodeWithContentDescription("Maptoolkit").performClick()
        composeRule.runOnIdle { assertEquals(listOf("https://www.maptoolkit.org/"), opened) }
    }

    private fun emptyActions() =
        MapToolScaffoldActions(
            onToolSelected = {},
            onToggleLauncher = {},
            onAddPoi = {},
            onOpenRecording = {},
            onOpenGpxTools = {},
            onCycleSize = {},
            onResize = {},
            onResizeFinished = {},
            onBack = {},
            onClose = {},
            onFeatureSettings = {},
        )
}
