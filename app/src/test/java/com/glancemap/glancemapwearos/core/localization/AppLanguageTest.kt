package com.glancemap.glancemapwearos.core.localization

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun onlySupportedLanguageTagsAreRestored() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en"))
        assertEquals(AppLanguage.FRENCH, AppLanguage.fromTag("fr"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("de"))
    }

    @Test
    fun systemChoiceClearsTheLocaleOverride() {
        assertEquals("", AppLanguage.SYSTEM.tag)
        assertEquals("en", AppLanguage.ENGLISH.tag)
        assertEquals("fr", AppLanguage.FRENCH.tag)
    }
}
