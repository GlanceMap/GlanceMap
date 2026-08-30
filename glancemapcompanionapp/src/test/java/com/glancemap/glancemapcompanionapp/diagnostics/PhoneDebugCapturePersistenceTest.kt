package com.glancemap.glancemapcompanionapp.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class PhoneDebugCapturePersistenceTest {
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = File(System.getProperty("java.io.tmpdir"), "phone-debug-capture-${System.nanoTime()}")
        PhoneDebugCapture.useTestStorage(directory)
    }

    @After
    fun tearDown() {
        PhoneDebugCapture.stop()
        directory.deleteRecursively()
    }

    @Test
    fun activeCaptureIsRecoveredAsInterruptedWithItsLogsAndSections() {
        PhoneDebugCapture.start()
        PhoneDebugCapture.log("Renderer", "event=ready")
        PhoneDebugCapture.updateSection("offline_map_runtime", "Offline map runtime\nZoom: 14")
        PhoneDebugCapture.flushForTest()

        PhoneDebugCapture.reloadFromStorageForTest()

        assertFalse(PhoneDebugCapture.state.value.active)
        assertTrue(PhoneDebugCapture.state.value.interrupted)
        assertTrue(PhoneDebugCapture.snapshot().single().contains("event=ready"))
        assertEquals(
            "Offline map runtime\nZoom: 14",
            PhoneDebugCapture.sectionForTest(PhoneDebugCaptureSlot.CURRENT, "offline_map_runtime"),
        )
    }

    @Test
    fun startingAnotherCaptureRotatesTheOldCaptureAndSharingDoesNotDeleteIt() {
        PhoneDebugCapture.start()
        PhoneDebugCapture.log("Map", "first")
        PhoneDebugCapture.stop()

        PhoneDebugCapture.start()

        assertTrue(PhoneDebugCapture.state.value.hasPreviousCapture)
        assertTrue(PhoneDebugCapture.hasCapture(PhoneDebugCaptureSlot.PREVIOUS))
        assertTrue(PhoneDebugCapture.snapshot(PhoneDebugCaptureSlot.PREVIOUS).single().contains("first"))
        assertTrue(PhoneDebugCapture.hasCapture(PhoneDebugCaptureSlot.PREVIOUS))
    }

    @Test
    fun lineCapAndCoordinateFreeRuntimeSectionsAreRetained() {
        PhoneDebugCapture.start()
        repeat(4_001) { PhoneDebugCapture.log("Map", "line=$it") }
        PhoneDebugCapture.updateSection("offline_map_runtime", "Offline map runtime\nZoom: 14")
        PhoneDebugCapture.flushForTest()

        assertEquals(4_000, PhoneDebugCapture.snapshot().size)
        assertEquals(1, PhoneDebugCapture.state.value.droppedLines)
        val runtime =
            requireNotNull(
                PhoneDebugCapture.sectionForTest(PhoneDebugCaptureSlot.CURRENT, "offline_map_runtime"),
            )
        assertFalse(runtime.contains("latitude", ignoreCase = true))
    }
}
