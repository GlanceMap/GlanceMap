package com.glancemap.glancemapwearos.presentation.features.maps.theme

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.SocketTimeoutException
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class ThemeViewModelDemSupportResourceTest {
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
    fun timeoutFailureGetsFriendlyRetryMessage() {
        val message =
            buildDemFailureMessage(
                throwable = SocketTimeoutException("Read timed out"),
                networkUnavailable = false,
                resources = resources,
            )

        assertEquals(
            "DEM download timed out. Retry when the watch internet connection is stable.",
            message,
        )
    }
}
