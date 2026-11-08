package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchWorldWindRuntimeTest {
    private fun inputs(mapPath: String) =
        WatchWorldWindInputs(
            enabled = true,
            holder = null,
            mapPath = mapPath,
            appearanceInProgress = false,
        )

    @Test
    fun `hiding the map screen makes the session non-interactive without replacing it`() {
        val runtime = WatchWorldWindRuntime()
        runtime.routeVisible = true
        runtime.publish(inputs("bayern.map"), screenInteractive = true, bearing = 12f)
        val session = runtime.session
        assertTrue(runtime.interactive)

        runtime.routeVisible = false

        assertFalse(runtime.interactive)
        assertSame(session, runtime.session)
        assertEquals("bayern.map", runtime.inputs?.mapPath)
        assertEquals(12f, runtime.bearing)
    }

    @Test
    fun `returning keeps the session and preset angle for the same map`() {
        val runtime = WatchWorldWindRuntime()
        runtime.publish(inputs("bayern.map"), screenInteractive = true, bearing = 0f)
        val session = runtime.session
        session.presetAngle = 80

        runtime.publish(inputs("bayern.map"), screenInteractive = true, bearing = 5f)

        assertSame(session, runtime.session)
        assertEquals(80, runtime.session.presetAngle)
    }

    @Test
    fun `a different map gets a fresh session`() {
        val runtime = WatchWorldWindRuntime()
        runtime.publish(inputs("bayern.map"), screenInteractive = true, bearing = 0f)
        val session = runtime.session
        session.failed = true

        runtime.publish(inputs("tirol.map"), screenInteractive = true, bearing = 0f)

        assertNotSame(session, runtime.session)
        assertFalse(runtime.session.failed)
    }
}
