package com.glancemap.glancemapcompanionapp.localization

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.mapToolsMapSettingPicker

@Composable
internal fun phoneLanguagePicker() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    val activity = context.phoneLanguageActivity()
    var selected by remember(configuration) { mutableStateOf(PhoneAppLanguageSettings.current(context)) }
    mapToolsMapSettingPicker(
        label = resources.getString(R.string.phone_language_label),
        selectedLabel = resources.getString(selected.labelResource()),
        options = PhoneAppLanguage.available().map { it to resources.getString(it.labelResource()) },
        onSelect = { language ->
            if (activity != null) {
                PhoneAppLanguageSettings.select(activity, language)
                selected = language
            }
        },
    )
}

private tailrec fun Context.phoneLanguageActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> if (baseContext === this) null else baseContext.phoneLanguageActivity()
        else -> null
    }

private fun PhoneAppLanguage.labelResource(): Int =
    when (this) {
        PhoneAppLanguage.SYSTEM -> R.string.phone_language_system
        PhoneAppLanguage.ENGLISH -> R.string.phone_language_english
        PhoneAppLanguage.FRENCH -> R.string.phone_language_french
        PhoneAppLanguage.GERMAN -> R.string.phone_language_german
        PhoneAppLanguage.SPANISH -> R.string.phone_language_spanish
        PhoneAppLanguage.ITALIAN -> R.string.phone_language_italian
        PhoneAppLanguage.POLISH -> R.string.phone_language_polish
    }
