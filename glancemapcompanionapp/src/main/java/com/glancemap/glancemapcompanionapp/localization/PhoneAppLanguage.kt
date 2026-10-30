package com.glancemap.glancemapcompanionapp.localization

import java.util.Locale

internal enum class PhoneAppLanguage(val tag: String) {
    SYSTEM(""), ENGLISH("en"), FRENCH("fr"), GERMAN("de"), SPANISH("es"), ITALIAN("it"), POLISH("pl");

    companion object {
        // Add a language here only when its phone translation is complete and reviewed.
        fun available(): List<PhoneAppLanguage> = listOf(SYSTEM, ENGLISH)

        fun fromTag(tag: String?): PhoneAppLanguage {
            val language = tag?.let(Locale::forLanguageTag)?.language.orEmpty()
            return entries.firstOrNull { it.tag == language } ?: SYSTEM
        }
    }
}
