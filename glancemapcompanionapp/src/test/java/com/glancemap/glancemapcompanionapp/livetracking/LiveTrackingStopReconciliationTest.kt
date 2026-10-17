package com.glancemap.glancemapcompanionapp.livetracking

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

class LiveTrackingStopReconciliationTest {
    @Test
    fun onlineStopReleasesLocalResourcesBeforeSendingRemoteClose() =
        runTest {
            val events = mutableListOf<String>()
            val store = InMemoryStopPendingStore()
            val reconciler =
                LiveTrackingStopReconciler(store) { update ->
                    events += if (update.stop) "remote close" else "control"
                }

            stopLiveTrackingLocalResources(
                stopLocationUpdates = { events += "GPS stopped" },
                stopSensors = { events += "sensors stopped" },
            )
            assertTrue(reconciler.persist(REQUEST_ID, listOf(stopUpdate())))

            assertEquals(
                LiveTrackingStopReconciliationResult.Complete,
                reconciler.reconcile(REQUEST_ID),
            )

            assertEquals(listOf("GPS stopped", "sensors stopped", "remote close"), events)
            assertTrue(store.load(REQUEST_ID).isEmpty())
        }

    @Test
    fun offlineStopReleasesLocalResourcesAndLeavesClosePending() {
        val events = mutableListOf<String>()
        val store = InMemoryStopPendingStore()
        val reconciler = LiveTrackingStopReconciler(store) { events += "remote close" }

        stopLiveTrackingLocalResources(
            stopLocationUpdates = { events += "GPS stopped" },
            stopSensors = { events += "sensors stopped" },
        )
        assertTrue(reconciler.persist(REQUEST_ID, listOf(stopUpdate())))

        assertEquals(listOf("GPS stopped", "sensors stopped"), events)
        assertEquals(listOf(stopUpdate()), store.load(REQUEST_ID))
    }

    @Test
    fun pendingRequestCanBeLoadedByReconcilerAfterRecreation() =
        runTest {
            val store = InMemoryStopPendingStore()
            val request = stopUpdate()
            assertTrue(LiveTrackingStopReconciler(store) { }.persist(REQUEST_ID, listOf(request)))
            val sent = mutableListOf<ArkluzLocationUpdate>()

            val result = LiveTrackingStopReconciler(store) { sent += it }.reconcile(REQUEST_ID)

            assertEquals(LiveTrackingStopReconciliationResult.Complete, result)
            assertEquals(listOf(request), sent)
            assertTrue(store.load(REQUEST_ID).isEmpty())
        }

    @Test
    fun repeatedWorkerExecutionDoesNotSendTheSameCloseAgain() =
        runTest {
            val store = InMemoryStopPendingStore()
            val sent = mutableListOf<ArkluzLocationUpdate>()
            val reconciler = LiveTrackingStopReconciler(store) { sent += it }
            assertTrue(reconciler.persist(REQUEST_ID, listOf(stopUpdate())))

            assertEquals(LiveTrackingStopReconciliationResult.Complete, reconciler.reconcile(REQUEST_ID))
            assertEquals(LiveTrackingStopReconciliationResult.Complete, reconciler.reconcile(REQUEST_ID))

            assertEquals(1, sent.size)
        }

    @Test
    fun acceptedControlsStayAheadOfTheRemoteClose() =
        runTest {
            val store = InMemoryStopPendingStore()
            val sent = mutableListOf<ArkluzLocationUpdate>()
            val reconciler = LiveTrackingStopReconciler(store) { sent += it }
            val pause = stopUpdate().copy(stop = false, pause = true)
            assertTrue(reconciler.persist(REQUEST_ID, listOf(pause, stopUpdate())))

            assertEquals(LiveTrackingStopReconciliationResult.Complete, reconciler.reconcile(REQUEST_ID))

            assertEquals(listOf(pause, stopUpdate()), sent)
        }

    @Test
    fun retryableNetworkFailureKeepsTheRequestPersistedForWorkManagerRetry() =
        runTest {
            val store = InMemoryStopPendingStore()
            val update = stopUpdate()
            assertTrue(LiveTrackingStopReconciler(store) { }.persist(REQUEST_ID, listOf(update)))

            val result = LiveTrackingStopReconciler(store) { throw UnknownHostException() }.reconcile(REQUEST_ID)

            assertTrue(result is LiveTrackingStopReconciliationResult.Failed)
            assertTrue((result as LiveTrackingStopReconciliationResult.Failed).error.isRetryableArkluzFailure())
            assertEquals(listOf(update), store.load(REQUEST_ID))
        }

    @Test
    fun retryUsesConnectedConstraintAndExponentialBackoffStartingAtThirtySeconds() {
        val request = LiveTrackingStopReconciliationWork.request(REQUEST_ID)

        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(30_000L, request.workSpec.backoffDelayDuration)
    }

    private fun stopUpdate() =
        ArkluzLocationUpdate(
            trackingUrl = "https://example.invalid/trk",
            latitude = 45.0,
            longitude = 6.0,
            altitudeMeters = null,
            speedMetersPerSecond = null,
            accuracyMeters = 10f,
            epochMilliseconds = 1_000L,
            batteryPercent = 50,
            gsmSignalPercent = 0,
            group = "test-group",
            participantPassword = "placeholder",
            userName = "test-user",
            notificationEmails = "",
            alertEmails = "",
            stuckAlarmMinutes = "",
            start = false,
            stop = true,
        )

    private class InMemoryStopPendingStore : LiveTrackingStopPendingStore {
        private val pending = mutableMapOf<String, MutableList<ArkluzLocationUpdate>>()

        override fun save(
            requestId: String,
            updates: List<ArkluzLocationUpdate>,
        ): Boolean {
            pending[requestId] = updates.toMutableList()
            return true
        }

        override fun load(requestId: String): List<ArkluzLocationUpdate> = pending[requestId].orEmpty().toList()

        override fun removeFirst(requestId: String): Boolean {
            val updates = pending[requestId] ?: return false
            updates.removeAt(0)
            if (updates.isEmpty()) pending.remove(requestId)
            return true
        }

        override fun requestIds(): Set<String> = pending.keys
    }

    private companion object {
        const val REQUEST_ID = "stop-request-1"
    }
}
