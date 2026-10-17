package com.glancemap.glancemapcompanionapp.livetracking

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class LiveTrackingPositionReplayTest {
    @Test
    fun boundedQueueReportsWhenOldestUnsentPointIsReplaced() {
        val queued = (1..LIVE_TRACKING_POSITION_QUEUE_MAX_SIZE).map(::position)

        val result = appendLiveTrackingPosition(queued, position(LIVE_TRACKING_POSITION_QUEUE_MAX_SIZE + 1))

        assertEquals(1, result.droppedOldestCount)
        assertEquals(LIVE_TRACKING_POSITION_QUEUE_MAX_SIZE, result.queueSize)
        assertEquals(2L, result.updates.first().epochMilliseconds)
        assertEquals((LIVE_TRACKING_POSITION_QUEUE_MAX_SIZE + 1).toLong(), result.updates.last().epochMilliseconds)
    }

    @Test
    fun emptyQueueDoesNotSendOrPersist() =
        runTest {
            var writes = 0
            var sends = 0

            val replayed =
                replayLiveTrackingPositionBatch(
                    updates = emptyList(),
                    send = { sends += 1 },
                    isRetryable = { true },
                    persistRemaining = { writes += 1 },
                )

            assertEquals(0, replayed)
            assertEquals(0, sends)
            assertEquals(0, writes)
        }

    @Test
    fun onePointSuccessClearsQueueWithOnePersistenceWrite() =
        runTest {
            val queue = mutableListOf(position(1))
            var writes = 0

            val replayed = replay(queue) { writes += 1 }

            assertEquals(1, replayed)
            assertTrue(queue.isEmpty())
            assertEquals(1, writes)
        }

    @Test
    fun nearMaximumBacklogPreservesOrderAndPersistsOncePerBoundedBatch() =
        runTest {
            val queue = (1..MAX_BACKLOG).map(::position).toMutableList()
            val sent = mutableListOf<Long>()
            var writes = 0
            var replayedCount = 0

            while (queue.isNotEmpty()) {
                val currentBatch = queue.toList()
                replayedCount +=
                    replayLiveTrackingPositionBatch(
                        updates = currentBatch,
                        send = { sent += it.epochMilliseconds },
                        isRetryable = { it is IOException },
                        persistRemaining = { remaining ->
                            writes += 1
                            queue.clear()
                            queue.addAll(remaining)
                        },
                    )
            }

            assertEquals(MAX_BACKLOG, replayedCount)
            assertEquals((1L..MAX_BACKLOG.toLong()).toList(), sent)
            assertEquals(
                (MAX_BACKLOG + LIVE_TRACKING_POSITION_REPLAY_BATCH_SIZE - 1) /
                    LIVE_TRACKING_POSITION_REPLAY_BATCH_SIZE,
                writes,
            )
        }

    @Test
    fun retryableFailureHalfwayKeepsFailedAndUnattemptedPointsInOrder() =
        runTest {
            val queue = (1..LIVE_TRACKING_POSITION_REPLAY_BATCH_SIZE + 5).map(::position).toMutableList()
            val attempted = mutableListOf<Long>()
            var writes = 0
            var caught: Throwable? = null

            try {
                replayLiveTrackingPositionBatch(
                    updates = queue.toList(),
                    send = { update ->
                        attempted += update.epochMilliseconds
                        if (update.epochMilliseconds == 13L) throw UnknownHostException("offline")
                    },
                    isRetryable = { it is IOException },
                    persistRemaining = { remaining ->
                        writes += 1
                        queue.clear()
                        queue.addAll(remaining)
                    },
                )
            } catch (error: Throwable) {
                caught = error
            }

            assertTrue(caught is UnknownHostException)
            assertEquals((1L..13L).toList(), attempted)
            assertEquals(
                (13L..(LIVE_TRACKING_POSITION_REPLAY_BATCH_SIZE + 5).toLong()).toList(),
                queue.map { it.epochMilliseconds },
            )
            assertEquals(1, writes)
        }

    @Test
    fun recreatedRepositoryResumesFromPersistedBatchBoundary() =
        runTest {
            val storage = InMemoryPositionQueue((1..40).map(::position))
            val firstProcess = PositionQueueRepository(storage)
            val firstSent = mutableListOf<Long>()

            firstProcess.replayBatch(firstSent)

            val recreatedProcess = PositionQueueRepository(storage)
            val secondSent = mutableListOf<Long>()
            recreatedProcess.replayBatch(secondSent)

            assertEquals((1L..LIVE_TRACKING_POSITION_REPLAY_BATCH_SIZE.toLong()).toList(), firstSent)
            assertEquals(
                ((LIVE_TRACKING_POSITION_REPLAY_BATCH_SIZE + 1).toLong()..40L).toList(),
                secondSent,
            )
            assertTrue(storage.updates.isEmpty())
            assertEquals(2, storage.writeCount)
        }

    @Test
    fun nonRetryableFailureAlsoRemainsQueued() =
        runTest {
            val queue = (1..3).map(::position).toMutableList()
            var caught: Throwable? = null

            try {
                replayLiveTrackingPositionBatch(
                    updates = queue.toList(),
                    send = { if (it.epochMilliseconds == 2L) throw IllegalArgumentException("rejected") },
                    isRetryable = { false },
                    persistRemaining = { remaining ->
                        queue.clear()
                        queue.addAll(remaining)
                    },
                )
            } catch (error: Throwable) {
                caught = error
            }

            assertTrue(caught is IllegalArgumentException)
            assertEquals(listOf(2L, 3L), queue.map { it.epochMilliseconds })
        }

    private suspend fun replay(
        queue: MutableList<ArkluzLocationUpdate>,
        onPersist: () -> Unit,
    ): Int =
        replayLiveTrackingPositionBatch(
            updates = queue.toList(),
            send = {},
            isRetryable = { it is IOException },
            persistRemaining = { remaining ->
                onPersist()
                queue.clear()
                queue.addAll(remaining)
            },
        )

    private fun position(index: Int) =
        ArkluzLocationUpdate(
            trackingUrl = "https://example.invalid/trk",
            latitude = 45.0,
            longitude = 7.0,
            altitudeMeters = null,
            speedMetersPerSecond = null,
            accuracyMeters = 5f,
            epochMilliseconds = index.toLong(),
            batteryPercent = 80,
            gsmSignalPercent = 50,
            group = "test",
            participantPassword = "placeholder",
            userName = "tester",
            notificationEmails = "",
            alertEmails = "",
            stuckAlarmMinutes = "",
            start = false,
            stop = false,
        )

    private class InMemoryPositionQueue(
        initialUpdates: List<ArkluzLocationUpdate>,
    ) {
        var updates = initialUpdates.toList()
            private set
        var writeCount = 0
            private set

        fun replaceAll(remaining: List<ArkluzLocationUpdate>) {
            writeCount += 1
            updates = remaining.toList()
        }
    }

    private class PositionQueueRepository(
        private val storage: InMemoryPositionQueue,
    ) {
        suspend fun replayBatch(sent: MutableList<Long>) {
            replayLiveTrackingPositionBatch(
                updates = storage.updates,
                send = { sent += it.epochMilliseconds },
                isRetryable = { it is IOException },
                persistRemaining = storage::replaceAll,
            )
        }
    }

    private companion object {
        const val MAX_BACKLOG = 500
    }
}
