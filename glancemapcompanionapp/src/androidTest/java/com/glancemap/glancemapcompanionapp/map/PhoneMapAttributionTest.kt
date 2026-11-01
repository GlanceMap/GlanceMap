package com.glancemap.glancemapcompanionapp.map

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneMapAttributionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun creditsForBothProvidersRemainVisibleAboveSearchOnANarrowMap() {
        composeRule.setContent {
            MaterialTheme {
                Column(Modifier.width(240.dp)) {
                    phoneMapAttribution(
                        listOf(
                            "<a href=\"https://example.com/base\">Base map</a>",
                            "<a href=\"https://example.com/overlay\">Comparison map</a>",
                        ),
                    )
                    Text("Search", modifier = Modifier.testTag("search"))
                }
            }
        }
        composeRule.onNodeWithText("Base map · Comparison map").assertIsDisplayed()
        val credits = composeRule.onNodeWithTag("map-provider-attribution").fetchSemanticsNode().boundsInRoot
        val search = composeRule.onNodeWithTag("search").fetchSemanticsNode().boundsInRoot
        assertTrue(credits.bottom <= search.top)
    }
}
