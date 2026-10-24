package com.glancemap.glancemapwearos.core.routing

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class BRouterRoutePlannerHelpersResourceTest {
    private lateinit var previousLocale: Locale

    @Before
    fun useEnglishFormattingLocale() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun restoreFormattingLocale() {
        Locale.setDefault(previousLocale)
    }

    private val resources =
        InstrumentationRegistry.getInstrumentation().targetContext.let { context ->
            val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }
            context.createConfigurationContext(configuration).resources
        }

    @Test
    fun oldRoutingPackLookupVersionBecomesRefreshMessage() {
        val message =
            normalizeRoutingErrorMessage(
                "lookup version mismatch (old rd5?) lookups.dat=11 E0_N40.rd5=10",
                resources = resources,
            )

        assertEquals("Routing packs use an older format. Refresh the routing packs.", message)
    }

    @Test
    fun newerRoutingPackLookupVersionBecomesAppUpdateMessage() {
        val message =
            normalizeRoutingErrorMessage(
                "lookup version mismatch (old rd5?) lookups.dat=10 E0_N40.rd5=11",
                resources = resources,
            )

        assertEquals(
            "Routing packs are newer than this app supports. Update GlanceMap, then refresh routing packs.",
            message,
        )
    }

    @Test
    fun missingRoutingDataPrefixIsPreserved() {
        val message = normalizeRoutingErrorMessage("Missing routing data: E0_N40.rd5", resources = resources)

        assertEquals("Missing routing data: E0_N40.rd5", message)
    }

    @Test
    fun routingChecksumErrorExplainsHowToRepairTheBundle() {
        val message = normalizeRoutingErrorMessage("java.io.IOException: top index checksum error", resources = resources)

        assertEquals(
            "Routing data is damaged. Open Downloads, check the bundle for updates, then choose Refresh to repair it.",
            message,
        )
    }
}
