package com.glancemap.glancemapwearos.presentation.features.download

import com.glancemap.glancemapwearos.data.repository.internal.AtomicStreamWriter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OamResumableHandoffTest {
    @Test
    fun handoffDisconnectsPayloadsOnceAndLeavesMetadataAlone() {
        val registry = OamActiveConnectionRegistry()
        val first = RecordingConnection()
        val second = RecordingConnection()
        val metadata = RecordingConnection()
        registry.connections.add(metadata)
        registry.registerResumable(first, registry.handoffGeneration)
        registry.registerResumable(second, registry.handoffGeneration)

        assertEquals(2, registry.requestHandoff())
        assertEquals(0, registry.requestHandoff())
        assertEquals(1, first.disconnectCount)
        assertEquals(1, second.disconnectCount)
        assertEquals(0, metadata.disconnectCount)
        assertTrue(registry.isHandoffRequested(first))
        registry.unregisterResumable(first)
        assertFalse(registry.connections.contains(first))
    }

    @Test
    fun delayedDisconnectCannotSelectNewConnectionsAfterRecovery() {
        val registry = OamActiveConnectionRegistry()
        val old = RecordingConnection()
        val queued = mutableListOf<HttpURLConnection>()
        registry.registerResumable(old, registry.handoffGeneration)
        registry.requestHandoff { queued.add(it) }
        val newWifi = RecordingConnection()
        registry.registerResumable(newWifi, registry.handoffGeneration)

        assertEquals(listOf(old), queued)
        assertTrue(registry.isHandoffRequested(old))
        assertFalse(registry.isHandoffRequested(newWifi))
        queued.forEach { it.disconnect() }
        assertEquals(1, old.disconnectCount)
        assertEquals(0, newWifi.disconnectCount)
    }

    @Test
    fun connectionBeingOpenedCannotMissHandoff() {
        val registry = OamActiveConnectionRegistry()
        val generationBeforeOpening = registry.handoffGeneration
        registry.requestHandoff()
        val connection = RecordingConnection()

        registry.registerResumable(connection, generationBeforeOpening)

        assertEquals(1, connection.disconnectCount)
        assertTrue(registry.isHandoffRequested(connection))
    }

    @Test
    fun userStopStillDisconnectsEveryConnectionWithoutMarkingHandoff() {
        val registry = OamActiveConnectionRegistry()
        val payload = RecordingConnection()
        val metadata = RecordingConnection()
        registry.registerResumable(payload, registry.handoffGeneration)
        registry.connections.add(metadata)

        assertEquals(2, registry.abortAll())
        assertEquals(1, payload.disconnectCount)
        assertEquals(1, metadata.disconnectCount)
        assertFalse(registry.isHandoffRequested(payload))
    }

    @Test
    fun eofAfterDeliberateDisconnectIsStillAPlannedIOException() {
        val registry = OamActiveConnectionRegistry()
        val connection = RecordingConnection()
        registry.registerResumable(connection, registry.handoffGeneration)
        val input = registry.checkedInput(connection, ByteArrayInputStream(byteArrayOf()))
        registry.requestHandoff()

        val error = runCatching { input.read() }.exceptionOrNull()

        assertTrue(error is IOException)
        assertTrue(registry.isHandoffRequested(connection))
    }

    @Test
    fun handoffRetainsWrittenBytesAndRangeResumeProducesExactFile() =
        runTest {
            val directory = Files.createTempDirectory("oam-handoff").toFile()
            try {
                val registry = OamActiveConnectionRegistry()
                val connection = RecordingConnection()
                registry.registerResumable(connection, registry.handoffGeneration)
                val input =
                    object : ByteArrayInputStream("ABCDEFGHIJ".toByteArray()) {
                        override fun read(
                            buffer: ByteArray,
                            offset: Int,
                            length: Int,
                        ): Int {
                            if (pos >= 4) {
                                val disconnected = connection.disconnected.await(3, TimeUnit.SECONDS)
                                check(disconnected) { "Handoff never disconnected input" }
                            }
                            return super.read(buffer, offset, minOf(length, 4))
                        }
                    }
                val error =
                    runCatching {
                        AtomicStreamWriter.writeAtomic(
                            directory,
                            "map.zip",
                            registry.checkedInput(connection, input),
                            onProgress = { if (it == 4L) registry.requestHandoff() },
                            options =
                                AtomicStreamWriter.Options(
                                    bufferSize = 4,
                                    progressStepBytes = 4,
                                    fsync = false,
                                    expectedSize = 10,
                                    requireExactSize = true,
                                    keepPartialOnFailure = true,
                                ),
                        )
                    }.exceptionOrNull()
                assertTrue(error is IOException)
                val partial = File(directory, ".map.zip.part")
                assertArrayEquals("ABCD".toByteArray(), partial.readBytes())
                registry.unregisterResumable(connection)
                val resumed = RecordingConnection()
                val connections = OamDownloadConnections { resumed }
                val next = connections.download("https://example.test/map.zip", partial.length())
                assertEquals("bytes=4-", next.getRequestProperty("Range"))
                registry.registerResumable(next, registry.handoffGeneration)
                AtomicStreamWriter.writeAtomic(
                    directory,
                    "map.zip",
                    registry.checkedInput(next, ByteArrayInputStream("EFGHIJ".toByteArray())),
                    onProgress = {},
                    options =
                        AtomicStreamWriter.Options(
                            bufferSize = 4,
                            fsync = false,
                            expectedSize = 10,
                            requireExactSize = true,
                            resumeOffset = 4,
                            keepPartialOnFailure = true,
                        ),
                )

                assertArrayEquals("ABCDEFGHIJ".toByteArray(), File(directory, "map.zip").readBytes())
                assertFalse(partial.exists())
            } finally {
                directory.deleteRecursively()
            }
        }

    private class RecordingConnection : HttpURLConnection(URL("https://example.test/file")) {
        var disconnectCount = 0
        val disconnected = CountDownLatch(1)

        override fun disconnect() {
            disconnectCount++
            disconnected.countDown()
        }

        override fun usingProxy(): Boolean = false

        override fun connect() = Unit
    }
}
