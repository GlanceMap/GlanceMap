package com.glancemap.glancemapwearos.presentation.features.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingHeartRateStatisticsTest {
    @Test
    fun `one minute at 120 and two minutes at 180 average to 160 at either cadence`() {
        listOf(1_000L, 15_000L).forEach { cadence ->
            val statistics = active()
            val samples =
                (0L until 180_000L step cadence).map { at ->
                    observation(at, if (at < 60_000L) 120.0 else 180.0)
                }

            statistics.accept(samples, 180_000L)

            assertEquals(RecordingHeartRateSummary(160, 180, 180_000L), statistics.summary(180_000L))
        }
    }

    @Test
    fun `immediate batched reversed and duplicated deliveries produce the same integral`() {
        val samples =
            (0L until 180_000L step 5_000L).map { at ->
                observation(at, if (at < 60_000L) 120.0 else 180.0)
            }
        val immediate = active()
        samples.forEach { immediate.accept(listOf(it), it.atMillis) }
        val batched = active().apply { accept(samples, 180_000L) }
        val reversed = active().apply { accept(samples.reversed() + samples, 180_000L) }
        val lateGroups =
            active().apply {
                accept(samples.filterIndexed { index, _ -> index % 2 == 0 }, 180_000L)
                accept(samples.filterIndexed { index, _ -> index % 2 == 1 }.reversed(), 180_000L)
            }

        listOf(immediate, batched, reversed, lateGroups).forEach {
            assertEquals(RecordingHeartRateSummary(160, 180, 180_000L), it.summary(180_000L))
        }
    }

    @Test
    fun `a repeated source timestamp cannot replace its first accepted measurement`() {
        val samples = listOf(observation(0L, 100.0), observation(0L, 120.0))
        val forward = active().apply { samples.forEach { accept(listOf(it), 10_000L) } }

        assertEquals(RecordingHeartRateSummary(100, 100, 10_000L), forward.summary(10_000L))
    }

    @Test
    fun `unknown gaps and final sample hold contribute at most fifteen seconds each`() {
        val statistics = active()
        statistics.accept(listOf(observation(0L, 120.0), observation(60_000L, 180.0)), 90_000L)

        assertEquals(RecordingHeartRateSummary(150, 180, 30_000L), statistics.summary(90_000L))
        assertEquals(RecordingHeartRateSummary(150, 180, 30_000L), statistics.summary(120_000L))
    }

    @Test
    fun `last sample contributes only the elapsed tail until now`() {
        val statistics = active()
        statistics.accept(listOf(observation(0L, 100.0), observation(10_000L, 160.0)), 12_000L)

        assertEquals(RecordingHeartRateSummary(110, 160, 12_000L), statistics.summary(12_000L))
    }

    @Test
    fun `pause ends sample hold and resume requires a measurement in its own window`() {
        val statistics = active()
        statistics.setActive(false, WATCH, 5_000L)
        statistics.setActive(true, WATCH, 10_000L)
        statistics.accept(listOf(observation(0L, 120.0), observation(12_000L, 180.0)), 20_000L)

        assertEquals(RecordingHeartRateSummary(157, 180, 13_000L), statistics.summary(20_000L))
    }

    @Test
    fun `retroactive pause removes buffered coverage and paused peaks`() {
        val statistics = active()
        statistics.accept(
            listOf(observation(0L, 100.0), observation(5_000L, 120.0), observation(10_000L, 220.0)),
            20_000L,
        )
        statistics.setActive(false, WATCH, 8_000L)
        statistics.setActive(true, WATCH, 15_000L)
        statistics.accept(listOf(observation(16_000L, 160.0)), 20_000L)

        assertEquals(RecordingHeartRateSummary(125, 160, 12_000L), statistics.summary(20_000L))
    }

    @Test
    fun `retroactive pause truncates all later selected source windows`() {
        val statistics = active()
        statistics.setActive(true, STRAP, 15_000L)
        statistics.accept(
            listOf(observation(0L, 100.0), observation(16_000L, 180.0, STRAP)),
            20_000L,
        )
        statistics.setActive(false, WATCH, 5_000L)

        assertEquals(RecordingHeartRateSummary(100, 100, 5_000L), statistics.summary(20_000L))
    }

    @Test
    fun `retroactive pause before the first source window removes its later coverage`() {
        val statistics = RecordingHeartRateStatistics()
        statistics.setActive(true, STRAP, 15_000L)
        statistics.accept(listOf(observation(16_000L, 180.0, STRAP)), 20_000L)
        statistics.setActive(false, WATCH, 5_000L)

        assertEquals(RecordingHeartRateSummary(null, null, 0L), statistics.summary(20_000L))
    }

    @Test
    fun `resume permits new source windows after a retroactive pause`() {
        val statistics = active()
        statistics.setActive(true, STRAP, 15_000L)
        statistics.setActive(false, WATCH, 5_000L)
        statistics.setActive(true, STRAP, 20_000L)
        statistics.setActive(true, WATCH, 25_000L)
        statistics.accept(
            listOf(
                observation(0L, 100.0),
                observation(16_000L, 240.0, STRAP),
                observation(21_000L, 120.0, STRAP),
                observation(26_000L, 180.0),
            ),
            30_000L,
        )

        assertEquals(RecordingHeartRateSummary(131, 180, 13_000L), statistics.summary(30_000L))
    }

    @Test
    fun `retroactive resume extends its source window without interrupting sample hold`() {
        val statistics = RecordingHeartRateStatistics()
        statistics.setActive(true, WATCH, 20_000L)
        statistics.setActive(true, WATCH, 10_000L)
        statistics.accept(listOf(observation(10_000L, 120.0)), 25_000L)

        assertEquals(RecordingHeartRateSummary(120, 120, 15_000L), statistics.summary(25_000L))
    }

    @Test
    fun `selected sources isolate both coverage and peaks regardless of sample arrival order`() {
        val samples =
            listOf(
                observation(0L, 100.0),
                observation(5_000L, 240.0, STRAP),
                observation(10_000L, 160.0, STRAP),
                observation(12_000L, 250.0),
            )
        listOf(samples, samples.reversed()).forEach { delivery ->
            val statistics = active()
            statistics.setActive(true, STRAP, 10_000L)
            statistics.accept(delivery, 20_000L)

            assertEquals(RecordingHeartRateSummary(130, 160, 20_000L), statistics.summary(20_000L))
        }
    }

    @Test
    fun `repeated active notifications do not split the sample hold`() {
        val statistics = active()
        statistics.setActive(true, WATCH, 5_000L)
        statistics.accept(listOf(observation(0L, 120.0)), 10_000L)

        assertEquals(RecordingHeartRateSummary(120, 120, 10_000L), statistics.summary(10_000L))
    }

    @Test
    fun `invalid nonpositive negative time and future readings are ignored`() {
        val statistics = active()
        statistics.accept(
            listOf(
                observation(-1L, 240.0),
                observation(0L, Double.NaN),
                observation(0L, Double.POSITIVE_INFINITY),
                observation(0L, 0.0),
                observation(0L, -1.0),
                observation(11_000L, 250.0),
            ),
            10_000L,
        )

        assertEquals(RecordingHeartRateSummary(null, null, 0L), statistics.summary(10_000L))
    }

    @Test
    fun `active final sample can set max before any time has elapsed`() {
        val statistics = active()
        statistics.accept(listOf(observation(10_000L, 175.5)), 10_000L)

        assertEquals(RecordingHeartRateSummary(null, 176, 0L), statistics.summary(10_000L))
    }

    @Test
    fun `checkpoint restore preserves committed prefix recent samples and an open source window`() {
        val statistics = active()
        val samples = (0L until 900_000L step 5_000L).map { observation(it, 120.0) }
        samples.forEach { statistics.accept(listOf(it), it.atMillis) }
        val checkpoint = statistics.snapshot(900_000L)
        val restored = RecordingHeartRateStatistics(checkpoint)
        val next = listOf(observation(900_000L, 180.0), observation(910_000L, 180.0))
        statistics.accept(next, 920_000L)
        restored.accept(next.reversed(), 920_000L)

        assertTrue(checkpoint.coveredMillis > 0L)
        assertEquals(RecordingHeartRateSummary(121, 180, 920_000L), restored.summary(920_000L))
        assertEquals(statistics.summary(920_000L), restored.summary(920_000L))
    }

    @Test
    fun `long recording commits old windows without changing its complete integral`() {
        val statistics = RecordingHeartRateStatistics()
        repeat(180) { index ->
            val at = index * 10_000L
            statistics.setActive(true, WATCH, at)
            statistics.accept(listOf(observation(at, 120.0)), at)
            statistics.setActive(false, WATCH, at + 5_000L)
        }
        val checkpoint = statistics.snapshot(1_800_000L)

        assertEquals(RecordingHeartRateSummary(120, 120, 900_000L), statistics.summary(1_800_000L))
        assertEquals(1_200_000L, checkpoint.committedBeforeMillis)
        assertTrue(checkpoint.recentObservations.size <= 61)
        assertTrue(checkpoint.recentWindows.size <= 60)
        assertEquals(statistics.summary(1_800_000L), RecordingHeartRateStatistics(checkpoint).summary(1_800_000L))
    }

    @Test
    fun `old late samples and boundaries cannot rewrite the committed integral`() {
        val statistics = active()
        statistics.accept((0L until 900_000L step 10_000L).map { observation(it, 120.0) }, 900_000L)
        val checkpoint = statistics.snapshot(900_000L)
        statistics.accept(listOf(observation(1_000L, 250.0)), 900_000L)
        statistics.setActive(false, WATCH, 1_000L)

        assertEquals(RecordingHeartRateSummary(120, 120, 900_000L), statistics.summary(900_000L))
        assertEquals(checkpoint.weightedBpmMillis, statistics.snapshot(900_000L).weightedBpmMillis, 0.0)
    }

    @Test
    fun `tail crossing a commit boundary is neither lost nor counted twice`() {
        val statistics = active()
        statistics.accept(listOf(observation(295_000L, 100.0)), 900_000L)
        val restored = RecordingHeartRateStatistics(statistics.snapshot(900_000L))

        assertEquals(RecordingHeartRateSummary(100, 100, 15_000L), restored.summary(900_000L))
        assertNull(RecordingHeartRateStatistics().summary(900_000L).averageBpm)
    }

    private fun active() = RecordingHeartRateStatistics().apply { setActive(true, WATCH, 0L) }

    private fun observation(
        at: Long,
        bpm: Double,
        source: String = WATCH,
    ) = RecordingHeartRateObservation(at, bpm, source)

    private companion object {
        const val WATCH = "watch"
        const val STRAP = "strap"
    }
}
