package com.glancemap.glancemapwearos.core.localization

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList

object AppLanguageSettings {
    private const val PREFERENCES = "app_language"
    private const val LANGUAGE_TAG = "language_tag"

    fun initialize(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val preferences = preferences(context)
            if (preferences.contains(LANGUAGE_TAG)) {
                val manager = context.getSystemService(LocaleManager::class.java)
                if (manager.applicationLocales.isEmpty) {
                    manager.applicationLocales =
                        LocaleList.forLanguageTags(AppLanguage.fromTag(preferences.getString(LANGUAGE_TAG, "")).tag)
                }
                preferences.edit().remove(LANGUAGE_TAG).apply()
            }
        } else {
            applyLegacy(context)
        }
    }

    fun current(context: Context): AppLanguage =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AppLanguage.fromTag(context.getSystemService(LocaleManager::class.java).applicationLocales[0]?.language)
        } else {
            AppLanguage.fromTag(preferences(context).getString(LANGUAGE_TAG, ""))
        }

    fun select(
        activity: Activity,
        language: AppLanguage,
    ) {
        if (current(activity) == language) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags(language.tag)
        } else {
            preferences(activity).edit().putString(LANGUAGE_TAG, language.tag).apply()
            applyLegacy(activity.application)
            // Deliver the same configuration update as the platform, keeping recording/navigation alive.
            activity.onConfigurationChanged(applyLegacy(activity))
        }
    }

    @Suppress("DEPRECATION") // API 28–32 have no platform per-app locale API; retain existing service contexts.
    fun applyLegacy(
        context: Context,
        configuration: Configuration = context.resources.configuration,
    ): Configuration {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return configuration
        val localized =
            localizedConfiguration(
                configuration,
                current(context),
                Resources.getSystem().configuration.locales,
            )
        LocaleList.setDefault(localized.locales)
        context.resources.updateConfiguration(localized, context.resources.displayMetrics)
        return localized
    }

    internal fun localizedConfiguration(
        configuration: Configuration,
        language: AppLanguage,
        systemLocales: LocaleList,
    ): Configuration =
        Configuration(configuration).apply {
            setLocales(if (language == AppLanguage.SYSTEM) systemLocales else LocaleList.forLanguageTags(language.tag))
        }

    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
