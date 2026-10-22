package com.glancemap.glancemapwearos.core.localization

import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glancemap.glancemapwearos.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class AppLanguageResourceTest {
    @Test
    fun manualFrenchOverridesEnglishAndPreservesOtherConfiguration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        val french = AppLanguageSettings.localizedConfiguration(original, AppLanguage.FRENCH, LocaleList(Locale.ENGLISH))
        val resources = context.createConfigurationContext(french).resources

        assertEquals(Locale.FRENCH, french.locales[0])
        assertEquals("Langue", resources.getString(R.string.settings_language))
        assertEquals("Annuler", resources.getString(R.string.common_cancel))
        assertEquals(original.fontScale, french.fontScale)
        assertEquals(original.densityDpi, french.densityDpi)
        assertEquals(Locale.ENGLISH, original.locales[0])
    }

    @Test
    fun systemChoiceRestoresTheCurrentWatchLocales() {
        val configuration = Configuration().apply { setLocale(Locale.FRENCH) }
        val system = LocaleList(Locale.GERMANY, Locale.ENGLISH)
        val restored = AppLanguageSettings.localizedConfiguration(configuration, AppLanguage.SYSTEM, system)
        assertEquals(system, restored.locales)
        val context = InstrumentationRegistry.getInstrumentation().targetContext.createConfigurationContext(restored)
        assertEquals("Cancel", context.getString(R.string.common_cancel))
    }

    @Test
    fun manualEnglishOverridesAFrenchWatch() {
        val configuration = Configuration().apply { setLocale(Locale.FRENCH) }
        val english = AppLanguageSettings.localizedConfiguration(configuration, AppLanguage.ENGLISH, LocaleList(Locale.FRENCH))
        val context = InstrumentationRegistry.getInstrumentation().targetContext.createConfigurationContext(english)
        assertEquals("Language", context.getString(R.string.settings_language))
        assertEquals("Cancel", context.getString(R.string.common_cancel))
    }
}
