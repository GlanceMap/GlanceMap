package com.glancemap.glancemapwearos.presentation.features.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.EmptyCoroutineContext

class OamNetworkHandoffAndRetryTest {
    @Test
    fun `wifi recovery reports telemetry without owning an active download abort`() {
        var recoveryCallbacks = 0
        val observer =
            OamDownloadNetworkRecoveryObserver(
                initialState = networkState(isWifi = true, isValidated = true),
                onWifiRecovered = { recoveryCallbacks += 1 },
            )

        observer.onChanged(networkState(isBluetooth = true))
        observer.onChanged(networkState(isWifi = true, isValidated = true))

        assertEquals(1, recoveryCallbacks)
    }

    @Test
    fun `progress-making failure resets the budget before subsequent no-progress failures`() {
        val budget = OamDownloadRetryBudget(maxNoProgressRetries = 3)

        assertTrue(budget.shouldRetry(attemptStartOffset = 100L, resumeOffsetAfterFailure = 124L))
        assertEquals(0, budget.currentRetryCount())

        repeat(3) { retryIndex ->
            assertTrue(
                budget.shouldRetry(
                    attemptStartOffset = 124L,
                    resumeOffsetAfterFailure = 124L,
                ),
            )
            assertEquals(retryIndex + 1, budget.currentRetryCount())
        }
        assertFalse(budget.shouldRetry(attemptStartOffset = 124L, resumeOffsetAfterFailure = 124L))
        assertEquals(3, budget.currentRetryCount())
    }

    @Test
    fun plannedHandoffDoesNotSpendOrResetNoProgressFailureBudget() {
        val budget = OamDownloadRetryBudget(maxNoProgressRetries = 1)
        assertTrue(budget.shouldRetry(100, 100))
        assertTrue(budget.shouldRetry(100, 100, networkHandoff = true))
        assertEquals(1, budget.currentRetryCount())
        assertFalse(budget.shouldRetry(100, 100))
    }

    @Test
    fun bytesWrittenBeforeHandoffStillResetTheFailureBudget() {
        val budget = OamDownloadRetryBudget(maxNoProgressRetries = 1)
        assertTrue(budget.shouldRetry(100, 100))
        assertTrue(budget.shouldRetry(100, 124, networkHandoff = true))
        assertEquals(0, budget.currentRetryCount())
        assertTrue(budget.shouldRetry(124, 124))
    }

    @Test
    fun userCancellationWinsOverPendingHandoffWithoutSpendingRetryBudget() {
        val budget = OamDownloadRetryBudget(maxNoProgressRetries = 1)
        val owner = Job().apply { cancel() }
        val failure =
            runCatching {
                budget.shouldRetryAfterFailure(IOException("Socket closed"), 4, 4, true, owner)
            }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(0, budget.currentRetryCount())
    }

    @Test
    fun ordinaryIoAndHttpErrorsKeepTheirRetryPolicy() {
        val budget = OamDownloadRetryBudget(maxNoProgressRetries = 1)
        assertTrue(budget.shouldRetryAfterFailure(IOException("Socket closed"), 0, 0, false, EmptyCoroutineContext))
        assertFalse(budget.shouldRetryAfterFailure(IOException("Socket closed"), 0, 0, false, EmptyCoroutineContext))
        val http = IOException("HTTP 404")
        val failure =
            runCatching {
                budget.shouldRetryAfterFailure(http, 0, 0, true, EmptyCoroutineContext)
            }.exceptionOrNull()
        assertEquals(http, failure)
    }

    @Test
    fun `active connection abort disconnects immediately for explicit user stop paths`() {
        val registry = OamActiveConnectionRegistry()
        val connection = RecordingConnection()
        registry.connections += connection

        assertEquals(1, registry.abortAll())
        assertEquals(1, connection.disconnectCount)
    }

    private fun networkState(
        isWifi: Boolean = false,
        isBluetooth: Boolean = false,
        isValidated: Boolean = false,
    ): OamDownloadNetworkState =
        OamDownloadNetworkState(
            isWifi = isWifi,
            isBluetooth = isBluetooth,
            isCellular = false,
            isEthernet = false,
            isVpn = false,
            hasInternet = isValidated,
            isValidated = isValidated,
            isUnmetered = isWifi,
            isMetered = !isWifi,
        )

    private class RecordingConnection : HttpURLConnection(URL("http://localhost")) {
        var disconnectCount = 0

        override fun disconnect() {
            disconnectCount += 1
        }

        override fun usingProxy(): Boolean = false

        override fun connect() = Unit
    }
}
