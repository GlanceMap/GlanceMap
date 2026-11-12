package com.glancemap.glancemapwearos.presentation.features.download

import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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
    fun `completed background extraction is not replayed after download`() =
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

            try {
                extractionStarted.await()
                arbiter.runNetwork { progress ->
                    progress(OamDownloadProgress("DOWNLOADING", "POI zip", 50L, 100L))
                    finishExtraction.complete(Unit)
                    extraction.await()
                }
                arbiter.awaitExtraction(extraction)

                assertEquals(listOf("DOWNLOADING"), emitted.map(OamDownloadProgress::phase))
            } finally {
                finishExtraction.complete(Unit)
                extraction.await()
            }
        }

    @Test
    fun `latest extraction progress and live updates appear when waiting for extraction`() =
        runTest {
            val emitted = mutableListOf<OamDownloadProgress>()
            val waitingStarted = CompletableDeferred<Unit>()
            val arbiter =
                OamBundleProgressArbiter { progress ->
                    emitted.add(progress)
                    if (progress.phase == "EXTRACTING") waitingStarted.complete(Unit)
                }
            val extractionStarted = CompletableDeferred<Unit>()
            val updateExtraction = CompletableDeferred<Unit>()
            val extractionUpdated = CompletableDeferred<Unit>()
            val finishExtraction = CompletableDeferred<Unit>()
            val extraction =
                async {
                    arbiter.runExtraction { progress ->
                        progress(OamDownloadProgress("EXTRACTING", "Area.map", 25L, 100L))
                        extractionStarted.complete(Unit)
                        updateExtraction.await()
                        progress(OamDownloadProgress("EXTRACTING", "Area.map", 75L, 100L))
                        extractionUpdated.complete(Unit)
                        finishExtraction.await()
                        progress(OamDownloadProgress("EXTRACTING", "Area.map", 100L, 100L))
                    }
                }

            try {
                extractionStarted.await()
                arbiter.runNetwork { progress ->
                    progress(OamDownloadProgress("DOWNLOADING", "POI zip", 100L, 100L))
                    updateExtraction.complete(Unit)
                    extractionUpdated.await()
                }
                assertEquals(listOf("DOWNLOADING"), emitted.map(OamDownloadProgress::phase))
                val waiting = async { arbiter.awaitExtraction(extraction) }
                waitingStarted.await()
                finishExtraction.complete(Unit)
                waiting.await()

                assertEquals(
                    listOf(
                        OamDownloadProgress("DOWNLOADING", "POI zip", 100L, 100L),
                        OamDownloadProgress("EXTRACTING", "Area.map", 75L, 100L),
                        OamDownloadProgress("EXTRACTING", "Area.map", 100L, 100L),
                    ),
                    emitted,
                )
            } finally {
                updateExtraction.complete(Unit)
                finishExtraction.complete(Unit)
                extraction.await()
            }
        }

    @Test
    fun `cancelling an extraction wait hides subsequent background progress`() =
        runTest {
            val emitted = mutableListOf<OamDownloadProgress>()
            val waitingStarted = CompletableDeferred<Unit>()
            val arbiter =
                OamBundleProgressArbiter { progress ->
                    emitted.add(progress)
                    waitingStarted.complete(Unit)
                }
            val extractionStarted = CompletableDeferred<Unit>()
            val updateExtraction = CompletableDeferred<Unit>()
            val extractionUpdated = CompletableDeferred<Unit>()
            val finishExtraction = CompletableDeferred<Unit>()
            val extraction =
                async {
                    arbiter.runExtraction { progress ->
                        progress(OamDownloadProgress("EXTRACTING", "Area.map", 25L, 100L))
                        extractionStarted.complete(Unit)
                        updateExtraction.await()
                        progress(OamDownloadProgress("EXTRACTING", "Area.map", 50L, 100L))
                        extractionUpdated.complete(Unit)
                        finishExtraction.await()
                    }
                }
            val waiting =
                async {
                    extractionStarted.await()
                    arbiter.awaitExtraction(extraction)
                }

            try {
                waitingStarted.await()
                waiting.cancelAndJoin()
                updateExtraction.complete(Unit)
                extractionUpdated.await()

                assertEquals(listOf(25L), emitted.map(OamDownloadProgress::bytesDone))
            } finally {
                waiting.cancelAndJoin()
                updateExtraction.complete(Unit)
                finishExtraction.complete(Unit)
                extraction.await()
            }
        }

    @Test
    fun `waiting for a completed map does not reveal background POI extraction`() =
        runTest {
            val emitted = mutableListOf<OamDownloadProgress>()
            val arbiter = OamBundleProgressArbiter(emitted::add)
            val completedMap =
                async {
                    arbiter.runExtraction { progress ->
                        progress(OamDownloadProgress("EXTRACTING", "Area.map", 100L, 100L))
                    }
                }
            completedMap.await()
            val poiStarted = CompletableDeferred<Unit>()
            val finishPoi = CompletableDeferred<Unit>()
            val poi =
                async {
                    arbiter.runExtraction { progress ->
                        progress(OamDownloadProgress("EXTRACTING", "Area.poi", 25L, 100L))
                        poiStarted.complete(Unit)
                        finishPoi.await()
                    }
                }
            try {
                poiStarted.await()
                arbiter.awaitExtraction(completedMap)
                assertTrue(emitted.isEmpty())
            } finally {
                finishPoi.complete(Unit)
                poi.await()
            }
        }

    @Test
    fun `failed progress publication closes the extraction wait`() =
        runTest {
            val emitted = mutableListOf<OamDownloadProgress>()
            var rejectNext = true
            val arbiter =
                OamBundleProgressArbiter { progress ->
                    if (rejectNext) {
                        rejectNext = false
                        error("Publication failed")
                    }
                    emitted.add(progress)
                }
            val extractionStarted = CompletableDeferred<Unit>()
            val finishExtraction = CompletableDeferred<Unit>()
            val extraction =
                async {
                    arbiter.runExtraction { progress ->
                        progress(OamDownloadProgress("EXTRACTING", "Area.map", 25L, 100L))
                        extractionStarted.complete(Unit)
                        finishExtraction.await()
                        progress(OamDownloadProgress("EXTRACTING", "Area.map", 50L, 100L))
                    }
                }
            try {
                extractionStarted.await()
                val failure = runCatching { arbiter.awaitExtraction(extraction) }.exceptionOrNull()
                assertTrue(failure is IllegalStateException)
                finishExtraction.complete(Unit)
                extraction.await()
                assertTrue(emitted.isEmpty())
            } finally {
                finishExtraction.complete(Unit)
                extraction.await()
            }
        }

    @Test
    fun `background extraction does not replace download progress between files`() =
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

            try {
                extractionStarted.await()
                arbiter.runNetwork { progress ->
                    progress(OamDownloadProgress("DOWNLOADING", "POI zip", 100L, 100L))
                }
                assertEquals(listOf("DOWNLOADING"), emitted.map(OamDownloadProgress::phase))
                arbiter.runNetwork { progress ->
                    progress(OamDownloadProgress("DOWNLOADING", "Routing", 50L, 100L))
                }
                assertEquals(listOf("POI zip", "Routing"), emitted.map(OamDownloadProgress::detail))
            } finally {
                finishExtraction.complete(Unit)
                extraction.await()
            }
        }

    @Test
    fun `extraction lifecycle stays active while download progress remains visible`() =
        runTest {
            val visibleProgress = mutableListOf<OamDownloadProgress>()
            val lifecycle = mutableListOf<Boolean>()
            val arbiter = OamBundleProgressArbiter(visibleProgress::add)
            val extractionStarted = CompletableDeferred<Unit>()
            val finishExtraction = CompletableDeferred<Unit>()
            val extraction =
                async {
                    arbiter.runExtraction {
                        runInlineOamExtraction(lifecycle::add) {
                            extractionStarted.complete(Unit)
                            finishExtraction.await()
                        }
                    }
                }

            try {
                extractionStarted.await()
                arbiter.runNetwork { progress ->
                    progress(OamDownloadProgress("DOWNLOADING", "POI zip", 50L, 100L))
                }

                assertEquals(listOf(true), lifecycle)
                assertEquals(listOf("DOWNLOADING"), visibleProgress.map(OamDownloadProgress::phase))
            } finally {
                finishExtraction.complete(Unit)
                extraction.await()
            }

            assertEquals(listOf(true, false), lifecycle)
        }

    @Test
    fun `extraction lifecycle clears after cancellation or failure`() =
        runTest {
            val lifecycle = mutableListOf<Boolean>()
            val arbiter = OamBundleProgressArbiter(emit = {})
            val cancellationStarted = CompletableDeferred<Unit>()
            val cancelled =
                async {
                    arbiter.runExtraction {
                        runInlineOamExtraction(
                            onExtractionRunningChanged = { running ->
                                lifecycle.add(running)
                                if (running) cancellationStarted.complete(Unit)
                            },
                        ) {
                            kotlinx.coroutines.awaitCancellation()
                        }
                    }
                }

            cancellationStarted.await()
            cancelled.cancelAndJoin()
            assertEquals(listOf(true, false), lifecycle)

            lifecycle.clear()
            val failure =
                runCatching {
                    arbiter.runExtraction {
                        runInlineOamExtraction(lifecycle::add) { error("write failed") }
                    }
                }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals(listOf(true, false), lifecycle)
        }

    @Test
    fun `installation screen hold follows extraction lifecycle instead of visible phase`() {
        val downloadingWhileExtracting =
            OamOwnedDownloadState(
                status = OamOwnedDownloadStatus.RUNNING,
                phase = "DOWNLOADING",
                isExtracting = true,
            )
        val extracting = downloadingWhileExtracting.copy(isExtracting = false)

        assertTrue(shouldKeepOamInstallationScreenOn(downloadingWhileExtracting.isExtracting))
        assertFalse(shouldKeepOamInstallationScreenOn(extracting.isExtracting))
    }

    @Test
    fun `installation screen hold releases in background and resumes in foreground`() {
        val paused = oamInstallationScreenActiveAfter(isScreenResumed = true, event = Lifecycle.Event.ON_PAUSE)
        val resumed = oamInstallationScreenActiveAfter(isScreenResumed = paused, event = Lifecycle.Event.ON_RESUME)

        assertFalse(shouldKeepOamInstallationScreenOn(isExtracting = true, isInstallationScreenActive = paused))
        assertTrue(shouldKeepOamInstallationScreenOn(isExtracting = true, isInstallationScreenActive = resumed))
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
