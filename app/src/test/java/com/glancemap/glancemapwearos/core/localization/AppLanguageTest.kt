package com.glancemap.glancemapwearos.core.localization

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun onlySupportedLanguageTagsAreRestored() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en"))
        assertEquals(AppLanguage.FRENCH, AppLanguage.fromTag("fr"))
        assertEquals(AppLanguage.SPANISH, AppLanguage.fromTag("es"))
        assertEquals(AppLanguage.GERMAN, AppLanguage.fromTag("de"))
        assertEquals(AppLanguage.ITALIAN, AppLanguage.fromTag("it"))
        assertEquals(AppLanguage.POLISH, AppLanguage.fromTag("pl"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("nl"))
    }

    @Test
    fun systemChoiceClearsTheLocaleOverride() {
        assertEquals("", AppLanguage.SYSTEM.tag)
        assertEquals("en", AppLanguage.ENGLISH.tag)
        assertEquals("fr", AppLanguage.FRENCH.tag)
        assertEquals("es", AppLanguage.SPANISH.tag)
        assertEquals("de", AppLanguage.GERMAN.tag)
        assertEquals("it", AppLanguage.ITALIAN.tag)
        assertEquals("pl", AppLanguage.POLISH.tag)
        assertEquals(setOf("", "en", "es", "fr", "de", "it", "pl"), AppLanguage.entries.map { it.tag }.toSet())
    }
}
