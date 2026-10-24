package com.glancemap.glancemapwearos.presentation.features.routetools

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class RouteToolPreflightResourceTest {
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
    fun coordinatesRouteValidatesMissingAndOutOfRangeValues() {
        val missing =
            RouteToolOptions(
                createMode = RouteCreateMode.COORDINATES,
                destinationEndpointSource = RouteEndpointSource.COORDINATES,
            )
        assertTrue(missing.coordinateEndpointValidationMessage(null, resources = resources).orEmpty().contains("valid destination"))

        val invalid =
            missing.copy(
                destinationCoordinateLatitude = 91.0,
                destinationCoordinateLongitude = 2.0,
            )
        assertTrue(invalid.coordinateEndpointValidationMessage(null, resources = resources).orEmpty().contains("valid destination"))
    }
}
