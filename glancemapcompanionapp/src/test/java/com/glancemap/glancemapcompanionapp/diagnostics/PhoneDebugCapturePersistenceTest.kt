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
    fun stopFlushesFinalAggregatesBeforeDisablingLoggingAndPersistsThem() {
        PhoneDebugCapture.start()
        var calls = 0
        val removeListener =
            PhoneDebugCapture.addBeforeStopListener {
                calls += 1
                assertTrue(PhoneDebugCapture.isActive())
                assertTrue(PhoneDebugCapture.log("PhoneMapLocation", "event=location_callback_summary accepted=1"))
                PhoneDebugCapture.updateSection("final_counts", "Final counts\nAccepted: 1")
            }
        try {
            PhoneDebugCapture.stop()
            assertEquals(1, calls)
            assertFalse(PhoneDebugCapture.isActive())
            PhoneDebugCapture.reloadFromStorageForTest()
            assertTrue(PhoneDebugCapture.snapshot().single().contains("event=location_callback_summary accepted=1"))
            assertEquals(
                "Final counts\nAccepted: 1",
                PhoneDebugCapture.sectionForTest(PhoneDebugCaptureSlot.CURRENT, "final_counts"),
            )
            PhoneDebugCapture.stop()
            assertEquals(1, calls)
        } finally {
            removeListener()
        }
    }

    @Test
    fun disposedListenersAreRemovedAndOneBrokenSummaryDoesNotPreventCaptureStop() {
        var removedCalls = 0
        val removeDisposed = PhoneDebugCapture.addBeforeStopListener { removedCalls += 1 }
        removeDisposed()
        val removeBroken = PhoneDebugCapture.addBeforeStopListener { error("private details") }
        val removeHealthy = PhoneDebugCapture.addBeforeStopListener { PhoneDebugCapture.log("Map", "final") }
        try {
            PhoneDebugCapture.start()
            PhoneDebugCapture.stop()

            assertEquals(0, removedCalls)
            assertFalse(PhoneDebugCapture.isActive())
            val logs = PhoneDebugCapture.snapshot().joinToString()
            assertTrue(logs.contains("event=final_summary_failed"))
            assertTrue(logs.contains("[Map] final"))
            assertFalse(logs.contains("private details"))
        } finally {
            removeBroken()
            removeHealthy()
        }
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
        val firstSessionId = PhoneDebugCapture.state.value.sessionId
        PhoneDebugCapture.stop()

        PhoneDebugCapture.start()

        assertTrue(PhoneDebugCapture.state.value.active)
        assertTrue(PhoneDebugCapture.state.value.sessionId > firstSessionId)
        assertTrue(PhoneDebugCapture.snapshot().isEmpty())
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
