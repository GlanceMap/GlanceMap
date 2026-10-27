package com.glancemap.glancemapwearos.presentation.features.recording

import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RecordingHeartRateStatisticsJsonTest {
    @Test
    fun pausedCheckpointRetainsFinalDelayedBatchAfterFailedSaveAndRecovery() {
        val statistics = RecordingHeartRateStatistics()
        statistics.setActive(true, "WATCH", 1_000L)
        statistics.accept(listOf(RecordingHeartRateObservation(1_000L, 120.0, "WATCH")), 16_000L)
        statistics.setActive(false, "WATCH", 31_000L)
        statistics.accept(listOf(RecordingHeartRateObservation(16_000L, 180.0, "WATCH")), 40_000L)
        val checkpoint =
            checkNotNull(
                decodeRecordingHeartRateStatistics(encodeRecordingHeartRateStatistics(statistics.snapshot(40_000L))),
            )

        assertEquals(
            RecordingHeartRateSummary(150, 180, 30_000L),
            RecordingHeartRateStatistics(checkpoint).summary(60_000L),
        )
    }

    @Test
    fun checkpointRoundTripPreservesCommittedAndReorderableStatistics() {
        val statistics = RecordingHeartRateStatistics()
        statistics.setActive(true, "WATCH", 1_000L)
        statistics.accept(
            (1_000L..900_000L step 10_000L).map { RecordingHeartRateObservation(it, 120.0, "WATCH") },
            901_000L,
        )
        val checkpoint = statistics.snapshot(901_000L)
        val restored = decodeRecordingHeartRateStatistics(encodeRecordingHeartRateStatistics(checkpoint))

        assertEquals(checkpoint, restored)
        assertEquals(
            statistics.summary(901_000L),
            RecordingHeartRateStatistics(checkNotNull(restored)).summary(901_000L),
        )
    }

    @Test
    fun missingOrInvalidCheckpointDoesNotInventAnAverageForLegacyDrafts() {
        assertNull(decodeRecordingHeartRateStatistics(null))
        assertNull(decodeRecordingHeartRateStatistics("not json"))
        assertNull(decodeRecordingHeartRateStatistics("{\"coveredMillis\":-1}"))
    }

    @Test
    fun emptyCheckpointRoundTripKeepsUnknownAverageAndNullMaximum() {
        val state = RecordingHeartRateStatisticsState()

        assertEquals(state, decodeRecordingHeartRateStatistics(encodeRecordingHeartRateStatistics(state)))
    }

    @Test
    fun malformedAndIncompleteCheckpointsAreIgnored() {
        listOf("{}", "[]", "null", "true", "{\"recentObservations\":null}").forEach { json ->
            assertNull(json, decodeRecordingHeartRateStatistics(json))
        }
    }

    @Test
    fun invalidPrefixCannotRestoreAnAverageOrMaximum() {
        val invalidValues =
            listOf(
                "weightedBpmMillis" to "-1",
                "weightedBpmMillis" to "\"NaN\"",
                "coveredMillis" to "-1",
                "maxBpm" to "0",
                "maxBpm" to "\"Infinity\"",
                "committedBeforeMillis" to "-1",
            )
        invalidValues.forEach { (key, value) ->
            val json = JSONObject(VALID_CHECKPOINT)
            json.put(key, JSONTokener(value).nextValue())

            assertNull("$key=$value", decodeRecordingHeartRateStatistics(json.toString()))
        }
    }

    @Test
    fun invalidObservationsAndWindowsCannotEnterRestoredHistory() {
        val invalidValues =
            listOf(
                "recentObservations" to "null",
                "recentObservations" to "[{\"atMillis\":-1,\"bpm\":120.0,\"source\":\"WATCH\"}]",
                "recentObservations" to "[{\"atMillis\":1000,\"bpm\":0.0,\"source\":\"WATCH\"}]",
                "recentObservations" to "[{\"atMillis\":1000,\"bpm\":\"NaN\",\"source\":\"WATCH\"}]",
                "recentObservations" to "[{\"atMillis\":1000,\"bpm\":120.0,\"source\":null}]",
                "recentWindows" to "null",
                "recentWindows" to "[{\"startMillis\":-1,\"endMillis\":1000,\"source\":\"WATCH\"}]",
                "recentWindows" to "[{\"startMillis\":1000,\"endMillis\":1000,\"source\":\"WATCH\"}]",
                "recentWindows" to "[{\"startMillis\":1000,\"endMillis\":2000,\"source\":\"\"}]",
            )
        invalidValues.forEach { (key, value) ->
            val json = JSONObject(VALID_CHECKPOINT)
            json.put(key, JSONTokener(value).nextValue())

            assertNull("$key=$value", decodeRecordingHeartRateStatistics(json.toString()))
        }
    }

    @Test
    fun invalidCheckpointCannotBeEncodedIntoADraft() {
        assertThrows(IllegalArgumentException::class.java) {
            encodeRecordingHeartRateStatistics(RecordingHeartRateStatisticsState(weightedBpmMillis = Double.NaN))
        }
    }

    private companion object {
        const val VALID_CHECKPOINT =
            """{"weightedBpmMillis":120000.0,"coveredMillis":1000,"maxBpm":120.0,"committedBeforeMillis":1000,""" +
                """"recentObservations":[{"atMillis":1000,"bpm":120.0,"source":"WATCH"}],""" +
                """"recentWindows":[{"startMillis":0,"endMillis":9223372036854775807,"source":"WATCH"}]}"""
    }
}
