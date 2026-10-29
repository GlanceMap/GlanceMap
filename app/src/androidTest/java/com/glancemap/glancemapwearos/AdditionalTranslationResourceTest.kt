package com.glancemap.glancemapwearos

import android.content.res.Configuration
import android.content.res.Resources
import android.icu.text.NumberFormat
import android.text.Annotation
import android.text.Spanned
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glancemap.glancemapwearos.core.localization.AppLanguage
import com.glancemap.glancemapwearos.presentation.features.navigate.formatSpokenDistance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class AdditionalTranslationResourceTest {
    private fun resources(locale: Locale): Resources {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(configuration).resources
    }

    @Test
    fun newLocalesSelectTheirCompiledResourcesAndPreserveFormatting() {
        listOf(
            Triple("es", "Cancelar", "Idioma"),
            Triple("de", "Abbrechen", "Sprache"),
            Triple("it", "Annulla", "Lingua"),
            Triple("pl", "Anuluj", "Język"),
        ).forEach { (tag, cancel, language) ->
            val localized = resources(Locale.forLanguageTag(tag))
            assertEquals(cancel, localized.getString(R.string.common_cancel))
            assertEquals(language, localized.getString(R.string.settings_language))
            assertEquals("GlanceMap", localized.getString(R.string.app_name))
            assertEquals("Alps - 120 MB", localized.getString(R.string.download_area_size, "120 MB", "Alps"))
            assertEquals("first\nsecond", localized.getString(R.string.debug_value, "first", "second"))
            assertTrue(localized.getString(R.string.map_dem_download_progress, 50, 2, 4).contains("50% · 2/4"))
            assertEquals(" 123 m", localized.getString(R.string.navigation_spaced_elevation, "123 m"))
            assertTrue(localized.getString(R.string.download_files, "map.map").startsWith("\n"))
            val ordinal = localized.getText(R.string.gpx_add_second_point_label) as Spanned
            assertEquals(1, ordinal.getSpans(0, ordinal.length, Annotation::class.java).size)
            val heading = localized.openRawResource(R.raw.legal_safety_and_limitations).bufferedReader().use { it.readLine() }
            assertTrue(heading.startsWith("# "))
            assertTrue(heading != "# Safety and Limitations")
        }
    }

    @Test
    fun pluralsCoverZeroOneTwoTwentyOneAndOneMillion() {
        listOf(
            Triple("es", "archivo", "archivos"),
            Triple("de", "Datei", "Dateien"),
            Triple("it", "file", "file"),
        ).forEach { (tag, one, other) ->
            val localized = resources(Locale.forLanguageTag(tag))
            listOf(0, 1, 2, 21, 1_000_000).forEach { count ->
                val noun = if (count == 1) one else other
                assertEquals(
                    "$count $noun · 1 MB",
                    localized.getQuantityString(R.plurals.settings_partial_files_summary, count, count, "1 MB"),
                )
            }
        }
    }

    @Test
    fun polishPluralsDistinguishOneFewAndManyIncludingTeenExceptions() {
        val localized = resources(Locale.forLanguageTag("pl"))
        val cases =
            mapOf(
                0 to "plików",
                1 to "plik",
                2 to "pliki",
                5 to "plików",
                12 to "plików",
                21 to "plików",
                22 to "pliki",
                25 to "plików",
                101 to "plików",
                102 to "pliki",
            )
        cases.forEach { (count, noun) ->
            assertEquals(
                "$count $noun · 1 MB",
                localized.getQuantityString(R.plurals.settings_partial_files_summary, count, count, "1 MB"),
            )
        }
    }

    @Test
    fun polishSpokenDistancesKeepPrepositionsAndDecimalInflection() {
        val locale = Locale.forLanguageTag("pl")
        val localized = resources(locale)
        listOf(
            Triple(R.string.navigation_for_kilometers, 1.5, "przez 1,5 kilometra"),
            Triple(R.string.navigation_for_miles, 2.5, "przez 2,5 mili"),
            Triple(R.string.navigation_in_miles, 1.5, "za 1,5 mili"),
        ).forEach { (id, distance, expected) ->
            assertEquals(expected, formatSpokenDistance(distance, 1, localized.getString(id), locale))
        }
    }

    @Test
    fun identicalEnglishCountLabelsStillInflectInSpanishAndItalian() {
        listOf(
            Triple("es", "descargado", "descargados"),
            Triple("it", "scaricato", "scaricati"),
        ).forEach { (tag, one, other) ->
            val localized = resources(Locale.forLanguageTag(tag))
            listOf(0, 1, 2, 21, 1_000_000).forEach { count ->
                val word = if (count == 1) one else other
                assertEquals(
                    "$count $word",
                    localized.getQuantityString(R.plurals.dem_downloaded_count, count, count),
                )
            }
        }
    }

    @Test
    fun allSpokenDistancePatternsParseAndKeepDecimalQuantities() {
        listOf("es", "de", "it", "pl").forEach { tag ->
            val locale = Locale.forLanguageTag(tag)
            val localized = resources(locale)
            listOf(R.string.navigation_for_kilometers, R.string.navigation_for_miles, R.string.navigation_in_miles).forEach { id ->
                listOf(0.0, 1.0, 1.5, 2.0, 2.5, 21.0, 22.0, 25.0).forEach { distance ->
                    listOf(0, 1).forEach { digits ->
                        val expected =
                            NumberFormat
                                .getNumberInstance(locale)
                                .apply {
                                    minimumFractionDigits = digits
                                    maximumFractionDigits = digits
                                    isGroupingUsed = false
                                }.format(distance)
                        val spoken = formatSpokenDistance(distance, digits, localized.getString(id), locale)
                        assertTrue("$tag: $spoken", spoken.contains(expected))
                        assertTrue(spoken.none { it == '{' || it == '}' || it == '#' })
                    }
                }
            }
        }
        assertEquals(
            "in 1,5 Meilen",
            formatSpokenDistance(1.5, 1, resources(Locale.GERMAN).getString(R.string.navigation_in_miles), Locale.GERMAN),
        )
    }

    @Test
    fun regionalEnglishUsesOneSharedTranslationAndLocaleConfigMatchesPicker() {
        listOf(Locale.US, Locale.UK, Locale.forLanguageTag("en-AU")).forEach { locale ->
            assertEquals("Cancel", resources(locale).getString(R.string.common_cancel))
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tags = mutableSetOf<String>()
        context.resources.getXml(R.xml.locales_config).use { xml ->
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG && xml.name == "locale") {
                    tags += xml.getAttributeValue("http://schemas.android.com/apk/res/android", "name")
                }
            }
        }
        assertEquals(
            AppLanguage.entries
                .filter { it != AppLanguage.SYSTEM }
                .map { it.tag }
                .toSet(),
            tags,
        )
    }
}
