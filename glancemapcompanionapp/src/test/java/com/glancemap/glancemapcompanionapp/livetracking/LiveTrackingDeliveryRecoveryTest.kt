package com.glancemap.glancemapcompanionapp.livetracking

import com.glancemap.glancemapcompanionapp.diagnostics.PhoneDebugCapture
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LiveTrackingDeliveryRecoveryTest {
    @After
    fun stopCapture() {
        PhoneDebugCapture.stop()
    }

    @Test
    fun refinementReplacesOneRetainedPointAndCannotReplayUntilTheCycleReleasesIt() =
        runTest {
            val storage = Storage()
            val queue = storage.queue()
            val ownership = LiveTrackingRetainedCycles()
            val id = ownership.claim(1L)
            val fallback = point(1L).copy(pointId = id)
            queue.enqueue(fallback)
            val refined = point(2L).copy(pointId = ownership.claim(1L), accuracyMeters = 5f)
            queue.enqueue(refined)
            val sent = mutableListOf<ArkluzLocationUpdate>()
            assertEquals(listOf(refined), queue.load())
            assertFalse(
                replayOneLiveTrackingPosition(
                    queue.load(),
                    ownership::canReplay,
                    { sent += it },
                    { queue.acknowledge(it) },
                ),
            )
            assertTrue(sent.isEmpty())
            ownership.release(1L)
            assertTrue(
                replayOneLiveTrackingPosition(
                    queue.load(),
                    ownership::canReplay,
                    { sent += it },
                    { queue.acknowledge(it) },
                ),
            )
            assertEquals(listOf(refined), sent)
            assertTrue(queue.load().isEmpty())
        }

    @Test
    fun processRecreationRecoversTheAcceptedFallbackWithoutRevivingItsAcquisition() =
        runTest {
            val storage = Storage()
            val oldOwnership = LiveTrackingRetainedCycles()
            val accepted = point(1L).copy(pointId = oldOwnership.claim(1L))
            storage.queue().enqueue(accepted)
            val recreatedQueue = storage.queue()
            val recreatedOwnership = LiveTrackingRetainedCycles()
            var transmitted: ArkluzLocationUpdate? = null
            assertTrue(
                replayOneLiveTrackingPosition(
                    recreatedQueue.load(),
                    recreatedOwnership::canReplay,
                    { transmitted = it.asCatchUpPoint() },
                    { recreatedQueue.acknowledge(it) },
                ),
            )
            assertEquals(accepted.epochMilliseconds, transmitted?.epochMilliseconds)
            assertEquals(0, transmitted?.gsmSignalPercent)
            assertTrue(transmitted?.isCatchUp == true)
            assertTrue(recreatedQueue.load().isEmpty())
        }

    @Test
    fun slowControlWaitDoesNotRemoveTheHistoricalPoint() =
        runTest {
            val storage = Storage()
            val queue = storage.queue()
            val retained = point(1L)
            queue.enqueue(retained)
            val calls = mutableListOf<String>()
            recoverLiveTrackingPendingUploads(
                canAttemptUpload = { true },
                flushControls = {
                    delay(130_000L)
                    calls += "control"
                },
                replayPositions = {
                    replayOneLiveTrackingPosition(
                        queue.load(),
                        { true },
                        { calls += "historical" },
                        { queue.acknowledge(it) },
                    )
                },
            )
            assertEquals(listOf("control", "historical"), calls)
            assertTrue(queue.load().isEmpty())
        }

    @Test
    fun everyUnacknowledgedFailureIncludingCancellationAndPermanentErrorsRetainsThePoint() =
        runTest {
            val failures =
                listOf(
                    ArkluzHttpException(400, "test rejection"),
                    ArkluzHttpException(401, "test authentication"),
                    ArkluzHttpException(408, "test timeout"),
                    ArkluzHttpException(429, "test rate limit"),
                    ArkluzUnconfirmedResponseException(),
                    CancellationException("test cancellation"),
                )
            for (failure in failures) {
                val queue = Storage().queue()
                val retained = point(1L)
                queue.enqueue(retained)
                val result =
                    runCatching {
                        replayOneLiveTrackingPosition(
                            queue.load(),
                            { true },
                            { throw failure },
                            { queue.acknowledge(it) },
                        )
                    }
                assertTrue(result.isFailure)
                assertEquals(listOf(retained), queue.load())
            }
        }

    @Test
    fun eachReplayPassAcknowledgesOnlyOnePointInTimestampOrder() =
        runTest {
            val queue = Storage().queue()
            queue.enqueue(point(3L))
            queue.enqueue(point(1L))
            queue.enqueue(point(2L))
            val sent = mutableListOf<Long>()
            replayOneLiveTrackingPosition(
                queue.load(),
                { true },
                { sent += it.epochMilliseconds },
                { queue.acknowledge(it) },
            )
            assertEquals(listOf(BASE_TIME + 1L), sent)
            assertEquals(listOf(BASE_TIME + 2L, BASE_TIME + 3L), queue.load().map { it.epochMilliseconds })
        }

    @Test
    fun startPauseAndResumeAtTheSameFixRemainOrderedControls() {
        val queue = Storage().queue("control_queue")
        val original = point(1L)
        val start = original.copy(start = true)
        val pause = original.copy(pause = true)
        val resume = original.copy(resume = true)
        listOf(start, pause, resume).forEach(queue::enqueue)
        assertEquals(listOf(start, pause, resume), queue.load())
        queue.removeFirst()
        assertEquals(listOf(pause, resume), queue.load())
    }

    @Test
    fun aSecondPauseAtTheSameFixKeepsTheEarlierPauseAndResumeInOrder() {
        val queue = Storage().queue("control_queue")
        val pause = point(1L).copy(pause = true)
        val resume = point(1L).copy(resume = true)
        listOf(pause, pause, resume, resume, pause).forEach(queue::enqueue)
        assertEquals(listOf(pause, resume, pause), queue.load())
        queue.removeFirst()
        assertEquals(listOf(resume, pause), queue.load())
    }

    @Test
    fun queueWriteFailureIsReportedWithoutClaimingSuccess() {
        PhoneDebugCapture.start()
        val storage = Storage()
        storage.failWrites = true
        val result = runCatching { storage.queue().enqueue(point(1L)) }
        assertTrue(result.exceptionOrNull() is LiveTrackingQueueException)
        assertEquals("[]", storage.raw)
        assertTrue(PhoneDebugCapture.snapshot().single().contains("position_queue_write_failed"))
    }

    @Test
    fun malformedEntriesSurviveAcknowledgementAndMalformedWholeQueuesAreNeverReplaced() {
        val storage = Storage()
        val records =
            JsonArray().apply {
                add(JsonObject().apply { addProperty("unrecognized", "preserve this record") })
                add(point(1L).toStoredJson())
            }
        storage.raw = records.toString()
        val queue = storage.queue()
        assertEquals(listOf(point(1L)), queue.load())
        queue.acknowledge(point(1L))
        assertTrue(storage.raw.contains("preserve this record"))
        storage.raw = "invalid JSON"
        assertTrue(runCatching { queue.enqueue(point(2L)) }.exceptionOrNull() is LiveTrackingQueueException)
        assertEquals("invalid JSON", storage.raw)
    }

    @Test
    fun aLongOutageDoesNotEvictTheOldestUnacknowledgedPointAtFiveHundred() {
        val storage = Storage()
        storage.raw = JsonArray().apply { (1L..500L).forEach { add(point(it).toStoredJson()) } }.toString()
        storage.queue().enqueue(point(501L))
        val recreated = storage.queue().load()
        assertEquals(501, recreated.size)
        assertEquals(point(1L), recreated.first())
        assertEquals(point(501L), recreated.last())
    }

    @Test
    fun legacySecondTimestampsAndMissingPointIdsRemainReplayable() {
        val json = point(1L).toStoredJson()
        json.remove("epochMilliseconds")
        json.remove("pointId")
        json.addProperty("epochSeconds", BASE_TIME / 1_000L)
        val storage = Storage()
        storage.raw = JsonArray().apply { add(json) }.toString()
        val restored = storage.queue().load().single()
        assertEquals(BASE_TIME, restored.epochMilliseconds)
        assertTrue(LiveTrackingRetainedCycles().canReplay(restored))
    }

    @Test
    fun restoredCacheDoesNotDuplicateAnAlreadyRetainedPhysicalFixWithAnotherCycleId() {
        val queue = Storage().queue()
        val original = point(1L).copy(pointId = "old-cycle")
        val restoredCache = original.copy(pointId = "new-cycle")
        queue.enqueue(original)
        queue.enqueue(restoredCache)
        assertEquals(listOf(restoredCache), queue.load())
        queue.acknowledge(original)
        assertTrue(queue.load().isEmpty())
    }

    @Test
    fun malformedControlPreventsLaterControlsFromBeingReordered() {
        val storage = Storage()
        storage.raw =
            JsonArray()
                .apply {
                    add(JsonObject().apply { addProperty("unrecognized", "preserve") })
                    add(point(1L).copy(resume = true).toStoredJson())
                }.toString()
        val original = storage.raw
        val error = runCatching { storage.queue("control_queue").load() }.exceptionOrNull()
        assertTrue(error is LiveTrackingQueueException)
        assertEquals(original, storage.raw)
    }

    @Test
    fun invalidatedAcquisitionCannotReplaceItsDurableFallbackInTheResumedGeneration() {
        val queue = Storage().queue()
        val coordinator = LiveTrackingOrchestration<Acquisition>()
        val generation = coordinator.startGeneration(60_000L, 0L) { true }!!
        val acquisition = Acquisition(generation)
        assertTrue(coordinator.publishAcquisition(acquisition))
        val fallback = point(1L).copy(pointId = "retained-cycle")
        coordinator.withAcquisition(acquisition) { queue.enqueue(fallback) }
        coordinator.invalidateGeneration(onCancelled = {}, stopPeriodicUpdates = {})
        coordinator.startGeneration(60_000L, 1L) { true }
        val result =
            coordinator.withAcquisition(acquisition) {
                queue.enqueue(point(2L).copy(pointId = fallback.pointId))
            }
        assertNull(result)
        assertEquals(listOf(fallback), queue.load())
    }

    @Test
    fun stoppingRecoveryTakesPrecedenceOverPauseAndLegacyActiveStateRemainsActive() {
        assertEquals(LiveTrackingRecoveryMode.STOPPING, liveTrackingRecoveryMode(true, true))
        assertEquals(LiveTrackingRecoveryMode.PAUSED, liveTrackingRecoveryMode(true, false))
        assertEquals(LiveTrackingRecoveryMode.PAUSED, liveTrackingRecoveryMode(false, false, pausePending = true))
        assertEquals(LiveTrackingRecoveryMode.STOPPING, liveTrackingRecoveryMode(false, true, pausePending = true))
        assertEquals(LiveTrackingRecoveryMode.ACTIVE, liveTrackingRecoveryMode(false, false))
    }

    @Test
    fun legacyAcknowledgementsAreAcceptedButAnUnknownSuccessfulBodyIsNotAnAcknowledgement() {
        assertEquals("Server accepted request", "OK\n".toArkluzTrackingResult().message)
        assertEquals("session-1", "OK\nsession-1".toArkluzTrackingResult().dateId)
        assertEquals("Server accepted request", "".toArkluzTrackingResult().message)
        val error = runCatching { "Maintenance in progress".toArkluzTrackingResult() }.exceptionOrNull()
        assertTrue(error is ArkluzUnconfirmedResponseException)
    }

    @Test
    fun retryableResponsesAndRetryAfterSupportBothSecondsAndHttpDates() {
        listOf(401, 403, 408, 429, 500, 503).forEach {
            assertTrue(ArkluzHttpException(it, "test").isRetryableArkluzFailure())
        }
        assertFalse(ArkluzHttpException(400, "test").isRetryableArkluzFailure())
        assertEquals(30_000L, arkluzRetryAfterMillis("30", 0L))
        val now = Instant.parse("2026-10-10T12:00:00Z").toEpochMilli()
        assertEquals(60_000L, arkluzRetryAfterMillis("Sat, 10 Oct 2026 12:01:00 GMT", now))
        assertNull(arkluzRetryAfterMillis("invalid", now))
        assertNull(arkluzRetryAfterMillis("-1", now))
    }

    @Test
    fun diagnosticWatchReportsMissingDeliveryWithoutChangingCadence() {
        val watch = LiveTrackingDeliveryWatch(0L, 60_000L)
        assertNull(watch.deliveryGapMillis(119_999L))
        assertEquals(120_000L, watch.deliveryGapMillis(120_000L))
        assertNull(watch.deliveryGapMillis(120_001L))
        watch.delivered(130_000L)
        assertNull(watch.deliveryGapMillis(240_000L))
        assertEquals(120_000L, watch.deliveryGapMillis(250_000L))
        assertEquals(1, watch.deliveredCallbacks)
    }

    @Test
    fun storageDiagnosticsContainIdentityAndDispositionWithoutCredentialsOrCoordinates() {
        PhoneDebugCapture.start()
        val queue = Storage().queue()
        val retained = point(1L).copy(pointId = "test-point-id")
        queue.enqueue(retained)
        queue.acknowledge(retained)
        val capture = PhoneDebugCapture.snapshot().joinToString("\n")
        assertTrue(capture.contains("position_queue_stored pointId=test-point-id"))
        assertTrue(capture.contains("position_queue_acknowledged pointId=test-point-id"))
        assertFalse(capture.contains("placeholder"))
        assertFalse(capture.contains("latitude"))
        assertFalse(capture.contains("longitude"))
        assertFalse(capture.contains("https://"))
    }

    private class Storage {
        var raw = "[]"
        var failWrites = false

        fun queue(name: String = "position_queue") =
            LiveTrackingQueuePersistence(
                name,
                read = { raw },
                write = { value ->
                    if (failWrites) {
                        false
                    } else {
                        raw = value
                        true
                    }
                },
            )
    }

    private class Acquisition(
        override val generation: LiveTrackingGeneration,
        override val ownership: LiveTrackingAcquisitionOwnership = LiveTrackingAcquisitionOwnership(),
    ) : GenerationOwnedLiveTrackingAcquisition

    private companion object {
        const val BASE_TIME = 1_790_000_000_000L

        fun point(offset: Long) =
            ArkluzLocationUpdate(
                trackingUrl = "https://example.invalid/trk",
                latitude = 1.0,
                longitude = 2.0,
                altitudeMeters = null,
                speedMetersPerSecond = null,
                accuracyMeters = 10f,
                epochMilliseconds = BASE_TIME + offset,
                batteryPercent = 80,
                gsmSignalPercent = 100,
                group = "test-group",
                participantPassword = "placeholder",
                userName = "test-user",
                notificationEmails = "",
                alertEmails = "",
                stuckAlarmMinutes = "10",
                start = false,
                stop = false,
            )
    }
}
