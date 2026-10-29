package com.glancemap.glancemapwearos.presentation.features.download

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class OamNetworkHandoffAndRetryResourceTest {
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
    fun consecutiveNoProgressFailuresRemainBounded() {
        val budget = OamDownloadRetryBudget(maxNoProgressRetries = 3)

        assertTrue(budget.shouldRetry(attemptStartOffset = 0L, resumeOffsetAfterFailure = 0L))
        assertTrue(budget.shouldRetry(attemptStartOffset = 0L, resumeOffsetAfterFailure = 0L))
        assertTrue(budget.shouldRetry(attemptStartOffset = 0L, resumeOffsetAfterFailure = 0L))
        assertFalse(budget.shouldRetry(attemptStartOffset = 0L, resumeOffsetAfterFailure = 0L))

        val message =
            oamNoProgressFailureMessage(
                fileName = "area.map.zip",
                maxRetries = 3,
                offset = 0L,
                error = IOException("Socket closed"),
                resources = resources,
            )
        assertTrue(message.contains("No payload progress"))
        assertTrue(message.contains("area.map.zip"))
        assertTrue(message.contains("Socket closed"))
    }
}
