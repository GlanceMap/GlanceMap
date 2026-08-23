package com.glancemap.glancemapwearos.presentation.features.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OamBundlePipelineTest {
    @Test
    fun `completed archive is reused when size and entry match`() {
        withTempDirectory { directory ->
            val archive = writeZip(directory, "area.map.zip", "Area.map")

            val result =
                reusableBundleArchiveOrNull(
                    directory = directory,
                    fileName = archive.name,
                    entryExtension = ".map",
                    expectedSize = archive.length(),
                )

            assertNotNull(result)
            assertEquals(archive, result)
        }
    }

    @Test
    fun `stale or invalid archive is removed instead of being reused`() {
        withTempDirectory { directory ->
            val staleArchive = writeZip(directory, "stale.map.zip", "Area.map")
            assertNull(
                reusableBundleArchiveOrNull(
                    directory = directory,
                    fileName = staleArchive.name,
                    entryExtension = ".map",
                    expectedSize = staleArchive.length() + 1L,
                ),
            )
            assertFalse(staleArchive.exists())

            val wrongEntryArchive = writeZip(directory, "wrong.map.zip", "Area.txt")
            assertNull(
                reusableBundleArchiveOrNull(
                    directory = directory,
                    fileName = wrongEntryArchive.name,
                    entryExtension = ".map",
                ),
            )
            assertFalse(wrongEntryArchive.exists())
        }
    }

    @Test
    fun `extraction progress stays hidden while download remains active`() =
        runTest {
            val emitted = mutableListOf<OamDownloadProgress>()
            val arbiter = OamBundleProgressArbiter(emitted::add)
            val extractionStarted = CompletableDeferred<Unit>()
            val finishExtraction = CompletableDeferred<Unit>()
            val extraction =
                async {
                    arbiter.runExtraction { progress ->
                        progress(OamDownloadProgress("EXTRACTING", "Area.map", 25L, 100L))
                        extractionStarted.complete(Unit)
                        finishExtraction.await()
                        progress(OamDownloadProgress("EXTRACTING", "Area.map", 100L, 100L))
                    }
                }

            extractionStarted.await()
            arbiter.runNetwork { progress ->
                progress(OamDownloadProgress("DOWNLOADING", "POI zip", 50L, 100L))
                finishExtraction.complete(Unit)
                extraction.await()
            }

            assertEquals(listOf("EXTRACTING", "DOWNLOADING"), emitted.map(OamDownloadProgress::phase))
        }

    @Test
    fun `latest extraction progress appears when download finishes first`() =
        runTest {
            val emitted = mutableListOf<OamDownloadProgress>()
            val arbiter = OamBundleProgressArbiter(emitted::add)
            val extractionStarted = CompletableDeferred<Unit>()
            val finishExtraction = CompletableDeferred<Unit>()
            val extraction =
                async {
                    arbiter.runExtraction { progress ->
                        progress(OamDownloadProgress("EXTRACTING", "Area.map", 25L, 100L))
                        extractionStarted.complete(Unit)
                        finishExtraction.await()
                    }
                }

            extractionStarted.await()
            arbiter.runNetwork { progress ->
                progress(OamDownloadProgress("DOWNLOADING", "POI zip", 100L, 100L))
            }
            finishExtraction.complete(Unit)
            extraction.await()

            assertEquals(
                listOf("EXTRACTING", "DOWNLOADING", "EXTRACTING"),
                emitted.map(OamDownloadProgress::phase),
            )
        }

    @Test
    fun `progress throttler emits phase changes and periodic updates`() {
        var nowMs = 1_000L
        val throttler = OamProgressThrottler(minimumIntervalMs = 1_000L, nowMs = { nowMs })

        assertTrue(throttler.shouldEmit(OamDownloadProgress("DOWNLOADING", "Map", 0L, 100L)))
        nowMs += 100L
        assertFalse(throttler.shouldEmit(OamDownloadProgress("DOWNLOADING", "Map", 10L, 100L)))
        nowMs += 100L
        assertTrue(throttler.shouldEmit(OamDownloadProgress("EXTRACTING", "Map", 10L, 100L)))
        nowMs += 1_000L
        assertTrue(throttler.shouldEmit(OamDownloadProgress("EXTRACTING", "Map", 20L, 100L)))

        assertEquals(4L, throttler.requestedCount)
        assertEquals(3L, throttler.emittedCount)
        assertEquals(1L, throttler.suppressedCount)
    }

    @Test
    fun `metadata is fetched only when it can validate reusable content`() {
        assertFalse(
            shouldFetchRemoteMetadataBeforeDownload(
                localFileAvailable = false,
                completedArchiveAvailable = false,
            ),
        )
        assertTrue(
            shouldFetchRemoteMetadataBeforeDownload(
                localFileAvailable = true,
            ),
        )
        assertTrue(
            shouldFetchRemoteMetadataBeforeDownload(
                localFileAvailable = false,
                completedArchiveAvailable = true,
            ),
        )
        assertFalse(
            shouldFetchRemoteMetadataBeforeDownload(
                localFileAvailable = true,
                forceDownload = true,
            ),
        )
    }

    @Test
    fun `large detailed DEM progress includes aggregate tile position`() {
        val progress =
            OamDownloadProgress("DOWNLOADING", "tile", 50L, 100L).withDemBatchContext(
                tileIndex = 123,
                tileCount = 506,
                tileId = "N04E038",
                sourceLabel = "Detailed",
                isLargeDetailedDownload = true,
            )

        assertEquals("Large detailed DEM 124/506 · N04E038", progress.detail)
        assertEquals(50L, progress.bytesDone)
        assertEquals(100L, progress.totalBytes)
    }

    @Test
    fun `extraction telemetry keeps reporting after the screen turns off and retains keepalive state`() {
        var wallMs = 0L
        var uptimeMs = 0L
        var cpuMs = 10L
        var snapshot = extractionSnapshot(interactive = true, charging = false)
        val events = mutableListOf<String>()
        val reporter =
            OamExtractionTelemetryReporter(
                label = "Map",
                entryFileName = "Area.map",
                totalBytes = 100L,
                wallNowMs = { wallMs },
                uptimeNowMs = { uptimeMs },
                processCpuMs = { cpuMs },
                runtimeSnapshot = { snapshot },
                emit = events::add,
                progressIntervalMs = 5_000L,
                heartbeatIntervalMs = 30_000L,
            )

        wallMs = 5_000L
        uptimeMs = 5_000L
        cpuMs = 30L
        snapshot = extractionSnapshot(interactive = false, charging = false)
        reporter.onBytesWritten(50L)

        assertEquals(1, events.size)
        assertTrue(events.single().contains("event=extract_progress"))
        assertTrue(events.single().contains("screenState=OFF"))
        assertTrue(events.single().contains("wakeLockType=unknown"))
        assertTrue(events.single().contains("wakeLockIsHeld=true"))
        assertTrue(events.single().contains("wakeLockGeneration=0"))
        assertTrue(events.single().contains("wifiLockHeld=true"))
    }

    @Test
    fun `extraction telemetry throttles progress and emits a bounded stall heartbeat`() {
        var wallMs = 0L
        var uptimeMs = 0L
        var cpuMs = 0L
        val events = mutableListOf<String>()
        val reporter =
            OamExtractionTelemetryReporter(
                label = "Map",
                entryFileName = "Area.map",
                totalBytes = 100L,
                wallNowMs = { wallMs },
                uptimeNowMs = { uptimeMs },
                processCpuMs = { cpuMs },
                runtimeSnapshot = { extractionSnapshot(interactive = false, charging = false) },
                emit = events::add,
                progressIntervalMs = 5_000L,
                heartbeatIntervalMs = 30_000L,
            )

        wallMs = 4_999L
        uptimeMs = 4_999L
        reporter.onBytesWritten(20L)
        wallMs = 5_000L
        uptimeMs = 5_000L
        reporter.onBytesWritten(30L)
        wallMs = 35_000L
        uptimeMs = 35_000L
        cpuMs = 40L
        reporter.emitStallHeartbeatIfNeeded()

        assertEquals(2, events.size)
        assertTrue(events.first().contains("event=extract_progress"))
        assertTrue(events.last().contains("event=extract_stall_heartbeat"))
        assertTrue(events.last().contains("wallNoProgressMs=30000"))
    }

    @Test
    fun `extraction telemetry exposes screen off suspension as wall time`() {
        var wallMs = 0L
        var uptimeMs = 0L
        val events = mutableListOf<String>()
        val reporter =
            OamExtractionTelemetryReporter(
                label = "Map",
                entryFileName = "Area.map",
                totalBytes = 100L,
                wallNowMs = { wallMs },
                uptimeNowMs = { uptimeMs },
                processCpuMs = { uptimeMs },
                runtimeSnapshot = { extractionSnapshot(interactive = false, charging = false) },
                emit = events::add,
                progressIntervalMs = 5_000L,
            )

        wallMs = 5_000L
        uptimeMs = 5_000L
        reporter.onBytesWritten(50L)
        wallMs = 390_000L
        uptimeMs = 10_000L
        reporter.onBytesWritten(100L)
        reporter.complete(100L)

        assertTrue(events[1].contains("wallSinceLastProgressMs=385000"))
        assertTrue(events[1].contains("uptimeSinceLastProgressMs=5000"))
        assertTrue(events[1].contains("suspendOrSleepDeltaMs=380000"))
        assertTrue(events.last().contains("wallDurationMs=390000"))
        assertTrue(events.last().contains("activeCopyDurationMs=10000"))
        assertTrue(events.last().contains("maxWallNoProgressMs=385000"))
    }

    private fun writeZip(
        directory: File,
        fileName: String,
        entryName: String,
    ): File =
        File(directory, fileName).also { archive ->
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write("bundle-data".toByteArray())
                zip.closeEntry()
            }
        }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("oam-pipeline-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun extractionSnapshot(
        interactive: Boolean,
        charging: Boolean,
    ) = OamExtractionRuntimeSnapshot(
        interactive = interactive,
        charging = charging,
        plugged = if (charging) "ac" else "battery",
        batteryPercent = "50",
        thermalStatus = "0",
        wakeLockHeld = true,
        wifiLockHeld = true,
    )
}
