package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glancemap.glancemapcompanionapp.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnlineMapSourceSettingsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context =
        object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this

            override fun getSharedPreferences(
                name: String,
                mode: Int,
            ) = super.getSharedPreferences("online_map_settings_test_$name", mode)
        }

    @Before
    @After
    fun clearTestPreferences() {
        context
            .getSharedPreferences("phone_map_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun providerCheckboxesPersistAndHideThenRestoreEsriInTheDropdown() {
        val preferences = PhoneMapSettingsPreferences(context)
        val settings = mutableStateOf(preferences.load())
        val showSettings = mutableStateOf(true)
        val selected = mutableStateOf(PhoneOnlineMapSource.OPEN_TOPO)
        val esri = PhoneOnlineMapSource.ESRI_WORLD_IMAGERY
        val esriLabel = PhoneMapRendererCatalog.onlineSourceLabel(esri)
        composeRule.setContent {
            MaterialTheme {
                if (showSettings.value) {
                    mapToolsOnlineMapSettings(settings.value) { settings.value = preferences.save(it) }
                } else {
                    mapToolsOnlineSourcePicker(
                        PhoneMapRendererCatalog.availableOnlineSources(
                            settings.value.hiddenOnlineSources,
                            settings.value.onlineSourceOrder,
                        ),
                        selected.value,
                    ) { selected.value = it }
                }
            }
        }
        PhoneMapRendererCatalog.availableOnlineSources().forEach { source ->
            composeRule
                .onNodeWithText(PhoneMapRendererCatalog.onlineSourceLabel(source))
                .performScrollTo()
                .assertIsOn()
        }
        composeRule
            .onNodeWithText(esriLabel)
            .performScrollTo()
            .performClick()
            .assertIsOff()
        composeRule.runOnIdle {
            assertEquals(setOf(esri), PhoneMapSettingsPreferences(context).load().hiddenOnlineSources)
            showSettings.value = false
        }
        val online = context.getString(R.string.map_source_online)
        val selectedLabel = PhoneMapRendererCatalog.onlineSourceLabel(selected.value)
        composeRule.onNodeWithText("$online: $selectedLabel").performClick()
        composeRule.onNodeWithText(esriLabel).assertDoesNotExist()
        composeRule.onNodeWithText("CyclOSM").performScrollTo().performClick()
        composeRule.runOnIdle { showSettings.value = true }
        composeRule
            .onNodeWithText(esriLabel)
            .performScrollTo()
            .performClick()
            .assertIsOn()
        composeRule.runOnIdle {
            assertTrue(PhoneMapSettingsPreferences(context).load().hiddenOnlineSources.isEmpty())
            showSettings.value = false
        }
        composeRule.onNodeWithText("$online: CyclOSM").performClick()
        composeRule.onNodeWithText(esriLabel).performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(esri, selected.value) }
    }

    @Test
    fun longPressingTheLeftHandleReordersAndPersistsOnlineMaps() {
        val preferences = PhoneMapSettingsPreferences(context)
        val settings = mutableStateOf(preferences.load())
        val initiallyVisible =
            PhoneMapRendererCatalog.availableOnlineSources(sourceOrder = settings.value.onlineSourceOrder)
        assertTrue(initiallyVisible.size > 1)
        val first = initiallyVisible.first()

        composeRule.setContent {
            MaterialTheme {
                mapToolsOnlineMapSettings(settings.value) { settings.value = preferences.save(it) }
            }
        }

        val handle = composeRule.onNodeWithTag("online-map-reorder-${first.name}")
        val dragDistancePx = handle.fetchSemanticsNode().boundsInRoot.height * 1.6f
        handle.performTouchInput {
            down(center)
            advanceEventTime(1_000L)
            moveBy(Offset(0f, dragDistancePx))
            up()
        }

        composeRule.runOnIdle {
            val restoredOrder = PhoneMapSettingsPreferences(context).load().onlineSourceOrder
            val restoredVisible = PhoneMapRendererCatalog.availableOnlineSources(sourceOrder = restoredOrder)
            assertEquals(initiallyVisible[1], restoredVisible.first())
            assertEquals(restoredOrder, settings.value.onlineSourceOrder)
        }
    }

    @Test
    fun preferencesDefaultToAllEnabledAndPreserveAnExplicitAllHiddenSelection() {
        val preferences = PhoneMapSettingsPreferences(context)
        assertTrue(preferences.load().hiddenOnlineSources.isEmpty())
        val hidden = PhoneOnlineMapSource.entries.toSet()
        preferences.save(PhoneMapSettings(hiddenOnlineSources = hidden))
        assertEquals(hidden, PhoneMapSettingsPreferences(context).load().hiddenOnlineSources)

        context
            .getSharedPreferences("phone_map_settings", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("hidden_online_sources", setOf("removed_provider", PhoneOnlineMapSource.CYCLOSM.name))
            .commit()
        assertEquals(setOf(PhoneOnlineMapSource.CYCLOSM), preferences.load().hiddenOnlineSources)
    }

    @Test
    fun tapToToggleToolbarIsOptInAndPersistsAcrossPreferenceReloads() {
        val preferences = PhoneMapSettingsPreferences(context)
        assertTrue(!preferences.load().tapToToggleToolbarEnabled)
        preferences.save(preferences.load().copy(tapToToggleToolbarEnabled = true))
        assertTrue(PhoneMapSettingsPreferences(context).load().tapToToggleToolbarEnabled)
        preferences.save(preferences.load().copy(tapToToggleToolbarEnabled = false))
        assertTrue(!PhoneMapSettingsPreferences(context).load().tapToToggleToolbarEnabled)
    }

    @Test
    fun emptyProviderListDisablesThePickerWithoutChangingTheCurrentMap() {
        composeRule.setContent {
            MaterialTheme {
                mapToolsOnlineSourcePicker(emptyList(), PhoneOnlineMapSource.OPEN_TOPO) {
                    error("An empty picker must not change the current map")
                }
            }
        }
        val online = context.getString(R.string.map_source_online)
        composeRule.onNodeWithText("$online: OpenTopoMap").assertIsNotEnabled()
    }
}
