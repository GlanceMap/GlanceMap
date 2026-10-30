package com.glancemap.glancemapcompanionapp.localization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PhoneLocalizationTest {
    @Test
    fun currentPickerOnlyAdvertisesLanguagesActuallyAvailableInTheApp() {
        assertEquals(listOf(PhoneAppLanguage.SYSTEM, PhoneAppLanguage.ENGLISH), PhoneAppLanguage.available())
        assertFalse(PhoneAppLanguage.FRENCH in PhoneAppLanguage.available())
    }

    @Test
    fun resolvesSystemAndManualLanguageWithoutConfusingUnsupportedTags() {
        assertEquals(PhoneAppLanguage.SYSTEM, PhoneAppLanguage.fromTag(""))
        assertEquals(PhoneAppLanguage.ENGLISH, PhoneAppLanguage.fromTag("en-US"))
        assertEquals(PhoneAppLanguage.FRENCH, PhoneAppLanguage.fromTag("fr-FR"))
        assertEquals(PhoneAppLanguage.SYSTEM, PhoneAppLanguage.fromTag("unknown"))
    }
}
