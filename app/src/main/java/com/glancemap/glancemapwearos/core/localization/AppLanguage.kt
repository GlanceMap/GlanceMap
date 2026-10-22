package com.glancemap.glancemapwearos.core.localization

enum class AppLanguage(
    val tag: String,
) {
    SYSTEM(""),
    ENGLISH("en"),
    FRENCH("fr"),
    ;

    companion object {
        fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag } ?: SYSTEM
    }
}
