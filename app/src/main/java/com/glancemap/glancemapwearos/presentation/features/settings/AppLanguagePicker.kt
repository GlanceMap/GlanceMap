package com.glancemap.glancemapwearos.presentation.features.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.core.localization.AppLanguage
import com.glancemap.glancemapwearos.core.localization.AppLanguageSettings

@Suppress("FunctionNaming") // Compose functions follow the existing settings naming convention.
@Composable
internal fun AppLanguagePicker() {
    val activity = LocalActivity.current ?: return
    val configuration = LocalConfiguration.current
    var language by remember(configuration) { mutableStateOf(AppLanguageSettings.current(activity)) }
    AppLanguagePickerRow(
        selectedLanguage = language,
        onSelect = {
            AppLanguageSettings.select(activity, it)
            language = it
        },
    )
}

@Suppress("FunctionNaming") // Compose functions follow the existing settings naming convention.
@Composable
internal fun AppLanguagePickerRow(
    selectedLanguage: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
) {
    SettingsOptionPickerRow(
        label = stringResource(R.string.settings_language),
        selectedValue = selectedLanguage,
        options =
            listOf(
                AppLanguage.SYSTEM to stringResource(R.string.settings_language_system),
                AppLanguage.ENGLISH to stringResource(R.string.language_english),
                AppLanguage.FRENCH to stringResource(R.string.language_french),
            ),
        onSelect = onSelect,
    )
}
