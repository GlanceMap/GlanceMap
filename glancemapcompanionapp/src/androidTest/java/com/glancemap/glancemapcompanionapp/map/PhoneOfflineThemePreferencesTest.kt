package com.glancemap.glancemapcompanionapp.map

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneOfflineThemePreferencesTest {
    private val context =
        object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this

            override fun getSharedPreferences(
                name: String,
                mode: Int,
            ) = super.getSharedPreferences("offline_theme_test_$name", mode)
        }

    @Test
    fun baseAndComparisonKeepIndependentOverlayChoicesPerStyle() {
        val preferences = PhoneOfflineThemePreferences(context)
        val base = PhoneOfflineThemeCatalog.defaultConfig
        val overlays = PhoneOfflineThemeOverlayCatalog.overlaysFor(base, context)
        val baseChoices = setOf(overlays.first().id)
        val comparisonChoices = if (overlays.size > 1) setOf(overlays.last().id) else emptySet()

        try {
            preferences.save(base.copy(enabledOverlayLayerIds = baseChoices))
            preferences.saveComparison(base.copy(enabledOverlayLayerIds = comparisonChoices))

            assertEquals(baseChoices, PhoneOfflineThemePreferences(context).load().enabledOverlayLayerIds)
            assertEquals(
                comparisonChoices,
                PhoneOfflineThemePreferences(context).loadComparison().enabledOverlayLayerIds,
            )
            assertNotEquals(
                PhoneOfflineThemePreferences(context).load().enabledOverlayLayerIds,
                PhoneOfflineThemePreferences(context).loadComparison().enabledOverlayLayerIds,
            )

            val otherStyle =
                PhoneOfflineThemeCatalog.resolve(
                    PhoneOfflineThemeCatalog.ELEVATE_THEME_ID,
                    PhoneOfflineThemeCatalog.ELEVATE_CITY_STYLE_ID,
                )
            assertEquals(otherStyle, preferences.save(otherStyle).copy(enabledOverlayLayerIds = null))
            assertEquals(baseChoices, preferences.save(base).enabledOverlayLayerIds)
        } finally {
            context
                .getSharedPreferences("phone_offline_theme", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }
}
