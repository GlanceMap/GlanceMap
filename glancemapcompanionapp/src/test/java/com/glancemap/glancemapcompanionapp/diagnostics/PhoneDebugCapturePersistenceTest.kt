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
    fun checkpointPersistsPartialSummariesWithoutStoppingCapture() {
        PhoneDebugCapture.start()
        val removeListener =
            PhoneDebugCapture.addBeforeStopListener {
                PhoneDebugCapture.updateSection("counts", "Location callbacks\nAccepted: 2")
            }
        try {
            PhoneDebugCapture.checkpoint()
            assertTrue(PhoneDebugCapture.isActive())
            PhoneDebugCapture.reloadFromStorageForTest()
            assertTrue(PhoneDebugCapture.state.value.interrupted)
            assertEquals(
                "Location callbacks\nAccepted: 2",
                PhoneDebugCapture.sectionForTest(PhoneDebugCaptureSlot.CURRENT, "counts"),
            )
        } finally {
            removeListener()
        }
    }

    @Test
    fun crashCheckpointKeepsTheLatestSnapshotWhileBackgroundWritesAreQueued() {
        PhoneDebugCapture.start()
        repeat(200) { PhoneDebugCapture.log("Map", "line=$it") }
        PhoneDebugCapture.log("UncaughtException", "class=ExampleFailure")

        PhoneDebugCapture.persistCrashSnapshot()
        val savedCheckpoint = File(directory, "phone-debug-capture-current.bin").readBytes().toString(Charsets.UTF_8)
        assertTrue(savedCheckpoint.contains("ExampleFailure"))
        PhoneDebugCapture.reloadFromStorageForTest()

        assertTrue(PhoneDebugCapture.state.value.interrupted)
        assertEquals(201, PhoneDebugCapture.snapshot().size)
        assertTrue(PhoneDebugCapture.snapshot().last().contains("ExampleFailure"))
    }

    @Test
    fun failedCheckpointRetainsTheLastReadableCapture() {
        PhoneDebugCapture.start()
        PhoneDebugCapture.log("Map", "last-good-checkpoint")
        PhoneDebugCapture.flushForTest()

        // This cannot be encoded by the existing writeUTF storage format.
        PhoneDebugCapture.updateSection("too-large", "x".repeat(70_000))
        PhoneDebugCapture.reloadFromStorageForTest()

        assertTrue(PhoneDebugCapture.state.value.interrupted)
        assertTrue(PhoneDebugCapture.snapshot().single().contains("last-good-checkpoint"))
        assertEquals(null, PhoneDebugCapture.sectionForTest(PhoneDebugCaptureSlot.CURRENT, "too-large"))
        assertFalse(File(directory, "phone-debug-capture-current.bin.tmp").exists())
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
