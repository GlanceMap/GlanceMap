package com.glancemap.trailcore.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** Expected arrays were frozen from the watch on origin/main (83bee235) before extraction. */
class RecordingElevationProfileParityTest {
    @Test
    fun recordedHillMatchesTheWatchBeforeExtraction() {
        assertWatchFixture(
            heights = listOf(100.0, 102.0, 104.0, 106.0, 108.0, 110.0, 108.0, 106.0, 104.0, 102.0, 100.0),
            stepMeters = 20.0,
            boundaryIndex = -1,
            hasTimestamp = true,
            expectedAscent =
                doubleArrayOf(
                    0.0,
                    0.0,
                    0.0,
                    3.9286694101508886,
                    5.738149672306051,
                    7.632305373503357,
                    7.79572520750186,
                    7.79572520750186,
                    7.79572520750186,
                    7.79572520750186,
                    7.79572520750186,
                ),
            expectedDescent =
                doubleArrayOf(
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    3.7594211595763056,
                    5.553334069765441,
                ),
        )
    }

    @Test
    fun subThresholdNoiseMatchesTheWatchBeforeExtraction() {
        assertWatchFixture(
            heights = listOf(100.0, 100.2, 100.4, 100.1, 100.8, 101.2, 100.9),
            stepMeters = 25.0,
            boundaryIndex = -1,
            hasTimestamp = true,
            expectedAscent = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            expectedDescent = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        )
    }

    @Test
    fun coarseMountainMatchesTheWatchBeforeExtraction() {
        assertWatchFixture(
            heights = listOf(500.0, 600.0, 700.0, 600.0, 500.0, 400.0, 500.0, 600.0, 700.0),
            stepMeters = 80.0,
            boundaryIndex = -1,
            hasTimestamp = true,
            expectedAscent =
                doubleArrayOf(
                    0.0,
                    76.19047619047615,
                    170.52154195011337,
                    170.52154195011337,
                    170.52154195011337,
                    170.52154195011337,
                    223.52701249016496,
                    312.3378388092249,
                    409.6737498375725,
                ),
            expectedDescent =
                doubleArrayOf(
                    0.0,
                    0.0,
                    0.0,
                    53.73069862865782,
                    142.7141983021478,
                    240.0912220339311,
                    240.0912220339311,
                    240.0912220339311,
                    240.0912220339311,
                ),
        )
    }

    @Test
    fun missingElevationAndPauseMatchesTheWatchBeforeExtraction() {
        assertWatchFixture(
            heights = listOf(null, 100.0, 102.0, null, 110.0, 300.0, 305.0, null, 310.0),
            stepMeters = 25.0,
            boundaryIndex = 5,
            hasTimestamp = true,
            expectedAscent = doubleArrayOf(0.0, 0.0, 0.0, 3.5, 6.75, 6.75, 6.75, 11.75, 14.25),
            expectedDescent = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        )
    }

    @Test
    fun untimedHillMatchesTheWatchBeforeExtraction() {
        assertWatchFixture(
            heights = listOf(100.0, 102.0, 104.0, 106.0, 108.0, 110.0, 108.0, 106.0, 104.0, 102.0, 100.0),
            stepMeters = 20.0,
            boundaryIndex = -1,
            hasTimestamp = false,
            expectedAscent =
                doubleArrayOf(
                    0.0,
                    0.0,
                    0.0,
                    3.9286694101508886,
                    5.738149672306051,
                    7.632305373503357,
                    7.79572520750186,
                    7.79572520750186,
                    7.79572520750186,
                    7.79572520750186,
                    7.79572520750186,
                ),
            expectedDescent =
                doubleArrayOf(
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    3.7594211595763056,
                    5.553334069765441,
                ),
        )
    }

    @Suppress("LongParameterList")
    private fun assertWatchFixture(
        heights: List<Double?>,
        stepMeters: Double,
        boundaryIndex: Int,
        hasTimestamp: Boolean,
        expectedAscent: DoubleArray,
        expectedDescent: DoubleArray,
    ) {
        val points =
            heights.mapIndexed { index, height ->
                RecordingElevationProfilePoint(height, index == boundaryIndex, hasTimestamp)
            }
        val segments = DoubleArray(heights.size - 1) { if (it + 1 == boundaryIndex) 0.0 else stepMeters }
        val distances = DoubleArray(heights.size)
        for (index in 1 until heights.size) distances[index] = distances[index - 1] + segments[index - 1]
        val (ascent, descent) = buildRecordingElevationProfileCumulative(points, segments, distances)
        assertArrayEquals(expectedAscent, ascent, 1e-10)
        assertArrayEquals(expectedDescent, descent, 1e-10)
    }
}
