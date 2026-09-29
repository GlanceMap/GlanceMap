package com.glancemap.glancemapcompanionapp.transfer.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

class TransferUtilsProgressTest {
    @Test
    fun fastTransferEmitsOncePerProgressStepBeforeSpeedWindowAdvances() {
        val events = mutableListOf<ProgressEvent>()
        val output = CountingOutputStream()

        val copied =
            runBlocking {
                TransferUtils.copyWithProgress(
                    input = GeneratedInputStream(6 * MIB),
                    output = output,
                    options =
                        CopyWithProgressOptions(
                            totalBytes = 6 * MIB,
                            bufferBytes = HALF_MIB.toInt(),
                            elapsedRealtimeMs = { 0L },
                            currentTimeMs = { 0L },
                        ),
                    onProgress = { progress, text -> events += ProgressEvent(progress, text) },
                )
            }

        assertEquals(6 * MIB, copied)
        assertEquals(copied, output.bytesWritten)
        assertEquals(3, events.size)
        assertEquals(1f / 3f, events[0].progress, 0.001f)
        assertEquals(2f / 3f, events[1].progress, 0.001f)
        assertEquals(1f, events[2].progress, 0f)
        assertTrue(events.none { it.text.contains("MiB/s") })
    }

    @Test
    fun speedSamplingWaitsForItsOwnOneSecondBaseline() {
        val events = mutableListOf<ProgressEvent>()
        val clock = AtomicLong(0L)

        runBlocking {
            TransferUtils.copyWithProgress(
                input =
                    GeneratedInputStream(6 * MIB) { bytesRead ->
                        clock.set(if (bytesRead >= 4 * MIB) 1_100L else 100L)
                    },
                output = CountingOutputStream(),
                options =
                    CopyWithProgressOptions(
                        totalBytes = 6 * MIB,
                        bufferBytes = HALF_MIB.toInt(),
                        elapsedRealtimeMs = { 0L },
                        currentTimeMs = clock::get,
                    ),
                onProgress = { progress, text -> events += ProgressEvent(progress, text) },
            )
        }

        assertEquals(3, events.size)
        assertFalse(events[0].text.contains("MiB/s"))
        assertTrue(events[1].text.contains("MiB/s"))
        assertFalse(events[2].text.contains("MiB/s"))
    }

    @Test
    fun slowUnknownSizeTransferEmitsByTimeAndKeepsIndeterminateProgress() {
        val events = mutableListOf<ProgressEvent>()
        val clock = AtomicLong(0L)

        runBlocking {
            TransferUtils.copyWithProgress(
                input =
                    GeneratedInputStream(5 * HALF_MIB) {
                        clock.addAndGet(600L)
                    },
                output = CountingOutputStream(),
                options =
                    CopyWithProgressOptions(
                        totalBytes = -1L,
                        bufferBytes = HALF_MIB.toInt(),
                        elapsedRealtimeMs = { 0L },
                        currentTimeMs = clock::get,
                    ),
                onProgress = { progress, text -> events += ProgressEvent(progress, text) },
            )
        }

        assertEquals(2, events.size)
        assertTrue(events.all { it.progress == 0f })
        assertTrue(events.all { !it.text.contains(" / ") })
        assertTrue(events[0].text.contains("1.00 MiB"))
        assertTrue(events[1].text.contains("2.00 MiB"))
    }

    @Test
    fun completionProgressIsPublishedImmediatelyBelowTheByteThreshold() {
        val events = mutableListOf<ProgressEvent>()

        runBlocking {
            TransferUtils.copyWithProgress(
                input = GeneratedInputStream(HALF_MIB),
                output = CountingOutputStream(),
                options =
                    CopyWithProgressOptions(
                        totalBytes = HALF_MIB,
                        bufferBytes = HALF_MIB.toInt(),
                        elapsedRealtimeMs = { 0L },
                        currentTimeMs = { 0L },
                    ),
                onProgress = { progress, text -> events += ProgressEvent(progress, text) },
            )
        }

        assertEquals(1, events.size)
        assertEquals(1f, events.single().progress, 0f)
    }

    @Test
    fun cancellationDuringPauseDoesNotPublishCompletionProgress() {
        val output = CountingOutputStream()
        val events = mutableListOf<ProgressEvent>()
        val exception =
            runCatching {
                runBlocking {
                    TransferUtils.copyWithProgress(
                        input = GeneratedInputStream(4 * MIB),
                        output = output,
                        options =
                            CopyWithProgressOptions(
                                totalBytes = 4 * MIB,
                                bufferBytes = HALF_MIB.toInt(),
                                awaitIfPaused = {
                                    if (output.bytesWritten >= MIB) {
                                        throw CancellationException("cancelled while paused")
                                    }
                                },
                                elapsedRealtimeMs = { 0L },
                                currentTimeMs = { 0L },
                            ),
                        onProgress = { progress, text -> events += ProgressEvent(progress, text) },
                    )
                }
            }.exceptionOrNull()

        assertTrue(exception is CancellationException)
        assertEquals(MIB, output.bytesWritten)
        assertTrue(events.isEmpty())
    }

    @Test
    fun pauseAndResumeContinueCopyAndPublishFinalProgress() =
        runBlocking {
            val clock = AtomicLong(0L)
            val pauseReached = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val events = mutableListOf<ProgressEvent>()
            var paused = false
            val output = CountingOutputStream()

            val copy =
                async {
                    TransferUtils.copyWithProgress(
                        input =
                            GeneratedInputStream(2 * MIB) { bytesRead ->
                                if (bytesRead == HALF_MIB) {
                                    paused = true
                                    pauseReached.complete(Unit)
                                }
                            },
                        output = output,
                        options =
                            CopyWithProgressOptions(
                                totalBytes = 2 * MIB,
                                bufferBytes = HALF_MIB.toInt(),
                                awaitIfPaused = { if (paused) resume.await() },
                                elapsedRealtimeMs = { 0L },
                                currentTimeMs = clock::get,
                            ),
                        onProgress = { progress, text -> events += ProgressEvent(progress, text) },
                    )
                }

            pauseReached.await()
            assertFalse(copy.isCompleted)
            clock.set(1_000L)
            resume.complete(Unit)

            assertEquals(2 * MIB, copy.await())
            assertEquals(2 * MIB, output.bytesWritten)
            assertEquals(2, events.size)
            assertEquals(1f, events.last().progress, 0f)
        }

    private data class ProgressEvent(
        val progress: Float,
        val text: String,
    )

    private class GeneratedInputStream(
        private val totalBytes: Long,
        private val afterRead: (bytesRead: Long) -> Unit = {},
    ) : InputStream() {
        private var bytesRead = 0L

        override fun read(): Int =
            if (bytesRead >= totalBytes) {
                -1
            } else {
                bytesRead += 1L
                afterRead(bytesRead)
                0
            }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (bytesRead >= totalBytes) return -1
            val count = minOf(length.toLong(), totalBytes - bytesRead).toInt()
            buffer.fill(1, offset, offset + count)
            bytesRead += count
            afterRead(bytesRead)
            return count
        }
    }

    private class CountingOutputStream : OutputStream() {
        var bytesWritten = 0L
            private set

        override fun write(value: Int) {
            bytesWritten += 1L
        }

        override fun write(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ) {
            bytesWritten += length
        }
    }

    private companion object {
        const val MIB = 1_048_576L
        const val HALF_MIB = MIB / 2
    }
}
