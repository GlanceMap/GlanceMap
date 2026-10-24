package com.glancemap.glancemapwearos.presentation.features.navigate

import android.content.res.Configuration
import android.hardware.SensorManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glancemap.glancemapwearos.domain.sensors.CompassMagneticQuality
import com.glancemap.glancemapwearos.domain.sensors.CompassNorthBasis
import com.glancemap.glancemapwearos.domain.sensors.CompassProviderType
import com.glancemap.glancemapwearos.domain.sensors.CompassTrackingReason
import com.glancemap.glancemapwearos.domain.sensors.CompassTrackingState
import com.glancemap.glancemapwearos.domain.sensors.HeadingSource
import com.glancemap.glancemapwearos.domain.sensors.initialCompassRenderState
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class NavigateDebugOverlayStateResourceTest {
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
    fun compassPopupShowsInterferenceIntegrityAndHeldAnchor() {
        val label = compassIntegrityDebugOverlayLabel(interferenceState(), renderedHeadingDeg = 85f, resources = resources)

        assertTrue(label.contains("I:DEGRADED(magnetic_interference)"))
        assertTrue(label.contains("F:138.6µT M:interference Q:yes"))
        assertTrue(label.contains("D:held/frozen anchor B:google_automatic"))
    }

    private fun interferenceState() =
        initialCompassRenderState(CompassProviderType.GOOGLE_FUSED).copy(
            headingDeg = 121f,
            headingSource = HeadingSource.FUSED_ORIENTATION,
            accuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
            headingSampleElapsedRealtimeMs = 1_000L,
            headingRenderable = true,
            trackingState = CompassTrackingState.DEGRADED,
            trackingReason = CompassTrackingReason.MAGNETIC_INTERFERENCE,
            magneticQuality = CompassMagneticQuality.INTERFERENCE,
            magneticInterference = true,
            magneticFieldUt = 138.6f,
            quarantineActive = true,
            northBasis = CompassNorthBasis.GOOGLE_AUTOMATIC,
        )
}
