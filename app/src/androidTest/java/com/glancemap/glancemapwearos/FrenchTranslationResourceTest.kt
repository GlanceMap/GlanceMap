package com.glancemap.glancemapwearos

import android.content.res.Configuration
import android.text.Annotation
import android.text.Spanned
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glancemap.glancemapwearos.presentation.features.navigate.formatSpokenDistance
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class FrenchTranslationResourceTest {
    private fun resources(locale: Locale) =
        InstrumentationRegistry.getInstrumentation().targetContext.let { context ->
            val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
            context.createConfigurationContext(configuration).resources
        }

    @Test
    fun frenchLocaleSelectsFrenchAndKeepsNontranslatableNames() {
        val french = resources(Locale.FRENCH)
        assertEquals("Annuler", french.getString(R.string.common_cancel))
        assertEquals("Cancel", resources(Locale.ENGLISH).getString(R.string.common_cancel))
        assertEquals("GlanceMap", french.getString(R.string.app_name))
        assertEquals("OpenAndroMaps", french.getString(R.string.download_openandromaps))
        val document = french.openRawResource(R.raw.legal_safety_and_limitations).bufferedReader().use { it.readLine() }
        assertEquals("# Sécurité et limites", document)
    }

    @Test
    fun compiledFrenchPreservesPositionalArgumentsPercentAndWhitespace() {
        val french = resources(Locale.FRENCH)
        assertEquals("Alpes - 120 MB", french.getString(R.string.download_area_size, "120 MB", "Alpes"))
        assertEquals("Altitude 50% · 2/4", french.getString(R.string.map_dem_download_progress, 50, 2, 4))
        assertEquals("premier\nsecond", french.getString(R.string.debug_value, "premier", "second"))
        assertEquals("\nFichiers : carte.map", french.getString(R.string.download_files, "carte.map"))
        assertEquals(" 123 m", french.getString(R.string.navigation_spaced_elevation, "123 m"))
    }

    @Test
    fun frenchPluralsCoverZeroOneTwoAndTwentyOne() {
        val french = resources(Locale.FRENCH)
        listOf(0 to "fichier", 1 to "fichier", 2 to "fichiers", 21 to "fichiers").forEach { (count, noun) ->
            assertEquals(
                "$count $noun · 1 MB",
                french.getQuantityString(R.plurals.settings_partial_files_summary, count, count, "1 MB"),
            )
        }
        assertEquals(
            "1000000 fichiers · 1 MB",
            french.getQuantityString(R.plurals.settings_partial_files_summary, 1_000_000, 1_000_000, "1 MB"),
        )
        // Completion titles intentionally receive no formatting argument.
        assertEquals("Lot installé", french.getQuantityString(R.plurals.download_bundle_status_installed, 1))
        assertEquals("Lots actualisés", french.getQuantityString(R.plurals.download_bundle_status_refreshed, 2))
    }

    @Test
    fun frenchSpokenPatternsKeepDecimalQuantitiesUnitsAndPrepositions() {
        val french = resources(Locale.FRENCH)
        val cases =
            listOf(
                SpokenCase(0.0, 0, "0", true),
                SpokenCase(1.0, 0, "1", true),
                SpokenCase(1.0, 1, "1,0", true),
                SpokenCase(1.5, 1, "1,5", true),
                SpokenCase(2.0, 0, "2", false),
                SpokenCase(2.5, 1, "2,5", false),
                SpokenCase(21.0, 0, "21", false),
                SpokenCase(22.0, 0, "22", false),
                SpokenCase(25.0, 0, "25", false),
            )
        listOf(
            Triple(R.string.navigation_for_kilometers, "sur", "kilomètre"),
            Triple(R.string.navigation_for_miles, "sur", "mile"),
            Triple(R.string.navigation_in_miles, "dans", "mile"),
        ).forEach { (id, prefix, unit) ->
            cases.forEach { case ->
                val noun = if (case.singular) unit else unit + "s"
                assertEquals(
                    prefix + " " + case.number + " " + noun,
                    formatSpokenDistance(case.value, case.fractionDigits, french.getString(id), Locale.FRENCH),
                )
            }
        }
    }

    @Test
    fun frenchOrdinalsRetainTheirSuperscriptAnnotation() {
        val french = resources(Locale.FRENCH)
        listOf(
            R.string.gpx_add_second_point_label to "Ajouter le 2e point (B)",
            R.string.navigation_select_second_point_label to "Choisir le 2e point",
        ).forEach { (id, expected) ->
            val text = french.getText(id) as Spanned
            assertEquals(expected, text.toString())
            val annotation = text.getSpans(0, text.length, Annotation::class.java).single()
            assertEquals("superscript", annotation.key)
            assertEquals("ordinal", annotation.value)
            assertEquals("e", text.subSequence(text.getSpanStart(annotation), text.getSpanEnd(annotation)).toString())
        }
    }

    private data class SpokenCase(
        val value: Double,
        val fractionDigits: Int,
        val number: String,
        val singular: Boolean,
    )
}
