package com.glancemap.glancemapcompanionapp.localization

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList

/** Follows the watch locale behavior without restarting recording or guidance services. */
internal object PhoneAppLanguageSettings {
    private const val PREFERENCES = "phone_app_language"
    private const val LANGUAGE_TAG = "language_tag"

    fun initialize(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val preferences = preferences(context)
            if (preferences.contains(LANGUAGE_TAG)) {
                val manager = context.getSystemService(LocaleManager::class.java)
                if (manager.applicationLocales.isEmpty) {
                    manager.applicationLocales = LocaleList.forLanguageTags(preferences.getString(LANGUAGE_TAG, ""))
                }
                preferences.edit().remove(LANGUAGE_TAG).apply()
            }
        } else {
            applyLegacy(context)
        }
    }

    fun current(context: Context): PhoneAppLanguage =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PhoneAppLanguage.fromTag(context.getSystemService(LocaleManager::class.java).applicationLocales[0]?.toLanguageTag())
        } else {
            PhoneAppLanguage.fromTag(preferences(context).getString(LANGUAGE_TAG, ""))
        }

    fun select(activity: Activity, language: PhoneAppLanguage) {
        if (current(activity) == language || language !in PhoneAppLanguage.available()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language.tag)
        } else {
            preferences(activity).edit().putString(LANGUAGE_TAG, language.tag).apply()
            applyLegacy(activity.application)
            activity.onConfigurationChanged(applyLegacy(activity))
        }
    }

    @Suppress("DEPRECATION") // API 26–32 lack per-app locales; retain existing service contexts.
    fun applyLegacy(context: Context, configuration: Configuration = context.resources.configuration): Configuration {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return configuration
        val localized = localizedConfiguration(configuration, current(context), Resources.getSystem().configuration.locales)
        LocaleList.setDefault(localized.locales)
        context.resources.updateConfiguration(localized, context.resources.displayMetrics)
        return localized
    }

    internal fun localizedConfiguration(
        configuration: Configuration,
        language: PhoneAppLanguage,
        systemLocales: LocaleList,
    ): Configuration = Configuration(configuration).apply {
        setLocales(if (language == PhoneAppLanguage.SYSTEM) systemLocales else LocaleList.forLanguageTags(language.tag))
    }

    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
