package com.glancemap.glancemapwearos.presentation.features.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.localization.AppLanguage
import com.glancemap.glancemapwearos.presentation.design.theme.GlanceMapTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLanguagePickerTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pickerOffersAllSupportedLanguagesAndReportsTheSelection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val title = context.getString(R.string.settings_language)
        var selected by mutableStateOf(AppLanguage.SYSTEM)
        composeRule.setContent {
            GlanceMapTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AppLanguagePickerRow(selectedLanguage = selected, onSelect = { selected = it })
                }
            }
        }

        listOf(
            AppLanguage.FRENCH to R.string.language_french,
            AppLanguage.SPANISH to R.string.language_spanish,
            AppLanguage.GERMAN to R.string.language_german,
            AppLanguage.ITALIAN to R.string.language_italian,
            AppLanguage.POLISH to R.string.language_polish,
            AppLanguage.ENGLISH to R.string.language_english,
            AppLanguage.SYSTEM to R.string.settings_language_system,
        ).forEach { (language, label) ->
            composeRule.onNodeWithText(title).performClick()
            composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(context.getString(label)))
            composeRule.onNodeWithText(context.getString(label)).performClick()
            composeRule.runOnIdle { assertEquals(language, selected) }
        }
    }
}
