package com.glancemap.glancemapwearos.presentation.features.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

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
    fun `consecutive no-progress failures remain bounded`() {
        val budget = OamDownloadRetryBudget(maxNoProgressRetries = 3)

        assertTrue(budget.shouldRetry(attemptStartOffset = 0L, resumeOffsetAfterFailure = 0L))
        assertTrue(budget.shouldRetry(attemptStartOffset = 0L, resumeOffsetAfterFailure = 0L))
        assertTrue(budget.shouldRetry(attemptStartOffset = 0L, resumeOffsetAfterFailure = 0L))
        assertFalse(budget.shouldRetry(attemptStartOffset = 0L, resumeOffsetAfterFailure = 0L))

        val message =
            oamNoProgressFailureMessage(
                fileName = "area.map.zip",
                maxRetries = 3,
                offset = 0L,
                error = IOException("Socket closed"),
            )
        assertTrue(message.contains("No payload progress"))
        assertTrue(message.contains("area.map.zip"))
        assertTrue(message.contains("Socket closed"))
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
