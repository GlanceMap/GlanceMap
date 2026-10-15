package com.glancemap.glancemapcompanionapp.map

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneOfflineBundleRetryTest {
    @Test
    fun transientDnsFailureReconnectsAndCompletes() =
        runTest {
            withPartialFile { file ->
                var attempts = 0
                val retries = mutableListOf<Int>()
                val result =
                    retryPhoneOfflineBundleTransfer(
                        temporary = file,
                        onRetry = { retry, offset, _ ->
                            retries += retry
                            assertEquals(0L, offset)
                            delay(2_000L * retry)
                        },
                    ) {
                        attempts += 1
                        if (attempts == 1) throw UnknownHostException("ftp.gwdg.de")
                        file.writeText("bundle")
                        file.length()
                    }

                assertEquals(6L, result)
                assertEquals(2, attempts)
                assertEquals(listOf(1), retries)
                assertEquals(2_000L, testScheduler.currentTime)
            }
        }

    @Test
    fun persistentDnsFailureStopsAfterThreeRetriesAndPreservesPartialData() =
        runTest {
            withPartialFile { file ->
                file.writeText("saved")
                var attempts = 0
                val retries = mutableListOf<Int>()
                val failure = UnknownHostException("ftp.gwdg.de")
                val result =
                    runCatching {
                        retryPhoneOfflineBundleTransfer(
                            temporary = file,
                            onRetry = { retry, offset, _ ->
                                retries += retry
                                assertEquals(5L, offset)
                            },
                        ) {
                            attempts += 1
                            throw failure
                        }
                    }

                assertSame(failure, result.exceptionOrNull())
                assertEquals(4, attempts)
                assertEquals(listOf(1, 2, 3), retries)
                assertEquals("saved", file.readText())
            }
        }

    @Test
    fun interruptedTransferResumesFromTheBytesActuallySaved() =
        runTest {
            withPartialFile { file ->
                file.writeText("head")
                val offsets = mutableListOf<Long>()
                val result =
                    retryPhoneOfflineBundleTransfer(
                        temporary = file,
                        onRetry = { _, offset, _ -> assertEquals(8L, offset) },
                    ) {
                        offsets += file.length()
                        if (offsets.size == 1) {
                            file.appendText("body")
                            throw SocketTimeoutException("connection interrupted")
                        }
                        file.appendText("tail")
                        file.length()
                    }

                assertEquals(12L, result)
                assertEquals(listOf(4L, 8L), offsets)
                assertEquals("headbodytail", file.readText())
            }
        }

    @Test
    fun payloadProgressResetsTheConsecutiveFailureBudget() =
        runTest {
            withPartialFile { file ->
                var attempts = 0
                val retries = mutableListOf<Int>()
                retryPhoneOfflineBundleTransfer(
                    temporary = file,
                    onRetry = { retry, _, _ -> retries += retry },
                ) {
                    attempts += 1
                    if (attempts == 4) file.appendText("part")
                    if (attempts < 8) throw SocketException("connection interrupted")
                    file.appendText("rest")
                    file.length()
                }

                assertEquals(8, attempts)
                assertEquals(listOf(1, 2, 3, 1, 1, 2, 3), retries)
                assertEquals("partrest", file.readText())
            }
        }

    @Test
    fun httpAndStorageFailuresAreNotRetried() =
        runTest {
            val storageFailure =
                runCatching { downloadFailure(PhoneOfflineBundleFailure.STORAGE) }.exceptionOrNull() as IOException
            val failures =
                listOf(
                    PhoneOfflineHttpException(404, "https://example.test/tile"),
                    PhoneOfflineHttpException(500, "https://example.test/tile"),
                    FileNotFoundException("local file unavailable"),
                    storageFailure,
                )
            withPartialFile { file ->
                failures.forEach { failure ->
                    var retries = 0
                    val result =
                        runCatching {
                            retryPhoneOfflineBundleTransfer(file, { _, _, _ -> retries += 1 }) { throw failure }
                        }
                    assertSame(failure, result.exceptionOrNull())
                    assertEquals(0, retries)
                }
            }
        }

    @Test
    fun pauseOrCancelDuringBackoffPreventsAnotherAttempt() =
        runTest {
            withPartialFile { file ->
                file.writeText("saved")
                var attempts = 0
                val job =
                    launch {
                        retryPhoneOfflineBundleTransfer(file, { _, _, _ -> delay(2_000L) }) {
                            attempts += 1
                            throw UnknownHostException("ftp.gwdg.de")
                        }
                    }
                runCurrent()
                job.cancel()
                runCurrent()

                assertTrue(job.isCancelled)
                assertEquals(1, attempts)
                assertEquals("saved", file.readText())
            }
        }

    @Test
    fun disconnectFromACancelledTransferDoesNotReconnect() =
        runTest {
            withPartialFile { file ->
                var retries = 0
                var cancelled = false
                val job =
                    launch {
                        try {
                            retryPhoneOfflineBundleTransfer(file, { _, _, _ -> retries += 1 }) {
                                currentCoroutineContext().cancel()
                                throw SocketException("disconnected")
                            }
                        } catch (_: CancellationException) {
                            cancelled = true
                        }
                    }
                runCurrent()

                assertTrue(job.isCancelled)
                assertTrue(cancelled)
                assertEquals(0, retries)
            }
        }

    private suspend fun withPartialFile(block: suspend (File) -> Unit) {
        val file = File.createTempFile("phone-bundle", ".part")
        try {
            block(file)
        } finally {
            file.delete()
        }
    }
}
