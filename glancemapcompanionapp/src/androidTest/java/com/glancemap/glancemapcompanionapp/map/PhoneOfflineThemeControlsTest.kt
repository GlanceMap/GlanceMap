package com.glancemap.glancemapcompanionapp.map

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.StringReader

@RunWith(AndroidJUnit4::class)
class PhoneOfflineThemeControlsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun overlayGroupsToggleLayersAndAllPresetEnablesTheCurrentStyle() {
        val config = mutableStateOf(PhoneOfflineThemeCatalog.defaultConfig)
        val overlays = PhoneOfflineThemeOverlayCatalog.overlaysFor(config.value, composeRule.activity)
        val overlay = overlays.first()
        val groupId = PhoneOfflineThemeOverlayCatalog.groupIdForOverlay(overlay.id)
        val currentIds =
            config.value.enabledOverlayLayerIds
                ?: PhoneOfflineThemeOverlayCatalog.idsForPreset(overlays, PhoneOfflineThemeOverlayPreset.DEFAULT)
        val shouldBeEnabled = overlay.id !in currentIds

        composeRule.setContent {
            MaterialTheme {
                phoneOfflineThemeOverlayControls(config.value, "base") { config.value = it }
            }
        }

        composeRule.onNodeWithTag("offline-theme-overlay-group-base-$groupId").performClick()
        composeRule.onNodeWithTag("offline-theme-overlay-base-${overlay.id}").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(shouldBeEnabled, config.value.enabledOverlayLayerIds?.contains(overlay.id))
        }
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.map_theme_overlay_all)).performClick()
        composeRule.runOnIdle {
            assertEquals(
                overlays.mapTo(linkedSetOf(), PhoneOfflineThemeOverlay::id),
                config.value.enabledOverlayLayerIds,
            )
        }
    }

    @Test
    fun styleMenuExposesSelectedStyleOverlaysAndTheirDefaults() {
        val menu =
            parsePhoneOfflineThemeStyleMenu(
                StringReader(
                    """
                    <rendertheme><stylemenu defaultvalue="hiking">
                      <layer id="common" enabled="true" visible="false">
                        <overlay id="routes"/>
                      </layer>
                      <layer id="hiking" visible="true" parent="common">
                        <name lang="en" value="Hiking"/>
                        <overlay id="contours"/>
                      </layer>
                      <layer id="routes" enabled="true" visible="false">
                        <name lang="en" value="Routes"/>
                        <cat id="route-lines"/>
                      </layer>
                      <layer id="contours" enabled="false" visible="false">
                        <name lang="en" value="Contours"/>
                        <cat id="contour-lines"/>
                      </layer>
                      <layer id="city" visible="true">
                        <overlay id="buildings"/>
                      </layer>
                      <layer id="buildings" enabled="true" visible="false">
                        <name lang="en" value="Buildings"/>
                      </layer>
                    </stylemenu></rendertheme>
                    """.trimIndent(),
                ),
            )

        assertEquals("hiking", menu.defaultStyleId)
        assertEquals(
            listOf(
                PhoneOfflineThemeOverlay("routes", "Routes", true),
                PhoneOfflineThemeOverlay("contours", "Contours", false),
            ),
            menu.overlaysFor("hiking"),
        )
        assertEquals(listOf("buildings"), menu.overlaysFor("city").map(PhoneOfflineThemeOverlay::id))
        assertEquals(listOf("routes", "contours"), menu.overlaysFor(null).map(PhoneOfflineThemeOverlay::id))
    }
}
