package com.glancemap.glancemapwearos.presentation.features.gpx

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class ActivityElevationProfileTest {
    @Test
    fun selectedTrackColorAppliesToEverySlopeWhileGapsStayGrey() {
        val from = sample(0)
        val slopes = listOf(sample(100), sample(100).copy(elevation = 120.0), sample(100).copy(elevation = 80.0))

        for (trackColor in listOf(Color.Magenta, Color.Blue)) {
            for (to in slopes) {
                assertEquals(trackColor, elevationProfileSegmentColor(from, to, trackColor))
                assertEquals(Color.Gray, elevationProfileSegmentColor(from, to.copy(gapBefore = true), trackColor))
            }
        }
    }

    @Test
    fun usesCorrectedGeometryDistanceAndGreyGapWithoutAddingDistance() {
        val points =
            listOf(
                point(0, 100.0),
                point(1, 110.0),
                point(100, 140.0).copy(startsNewSegment = true),
                point(101, 130.0),
            )
        val canonical = buildProfile(FileSig(1L, 1L), points)
        val profile = buildActivityElevationProfile(canonical)

        assertEquals(canonical.totalDistance, profile.totalDistance, 0.0)
        assertEquals(canonical.cumDist.toList(), profile.samples.map { it.distance })
        assertTrue(profile.totalDistance in 100.0..200.0)
        assertEquals(profile.samples[1].distance, profile.samples[2].distance, 0.0)
        assertTrue(profile.samples[2].gapBefore)
        assertEquals(Color.Gray, elevationProfileSegmentColor(profile.samples[1], profile.samples[2]))
        assertNotEquals(Color.Gray, elevationProfileSegmentColor(profile.samples[0], profile.samples[1]))
        assertEquals(points.map { it.elevation }, profile.samples.map { it.elevation })
    }

    @Test
    fun missingAndNonfiniteElevationsStayMissingAndMarkTheNextConnectionAsAGap() {
        val points =
            listOf(
                point(0, null),
                point(1, 100.0),
                point(2, Double.NaN),
                point(3, null).copy(startsNewSegment = true),
                point(4, 120.0),
                point(5, 125.0),
            )
        val canonical = buildProfile(FileSig(1L, 1L), points)
        val profile = buildActivityElevationProfile(canonical)

        assertEquals(listOf(100.0, 120.0, 125.0), profile.samples.map { it.elevation })
        assertEquals(listOf(false, true, false), profile.samples.map { it.gapBefore })
        assertEquals(listOf(1, 4, 5).map { canonical.cumDist[it] }, profile.samples.map { it.distance })
    }

    @Test
    fun downsamplingPreservesPeaksValleysEndpointsAndBothSidesOfGaps() {
        val samples =
            (0 until 10_000).map { index ->
                sample(index).copy(
                    elevation =
                        when (index) {
                            1_234 -> -42.0
                            7_890 -> 999.0
                            else -> 100.0 + index % 20
                        },
                    gapBefore = index == 5_001,
                )
            }

        val reduced = downsampleActivityElevationSamples(samples)

        assertTrue(reduced.size <= 120)
        assertEquals(samples.first(), reduced.first())
        assertEquals(samples.last(), reduced.last())
        assertTrue(reduced.containsAll(listOf(samples[1_234], samples[7_890], samples[5_000], samples[5_001])))
        assertEquals(listOf(samples[5_001]), reduced.filter { it.gapBefore })
        val gapIndex = reduced.indexOf(samples[5_001])
        assertEquals(samples[5_000], reduced[gapIndex - 1])
        assertEquals(reduced.sortedBy { it.distance }, reduced)
    }

    @Test
    fun manyGapsKeepEveryBoundaryEvenWhenTheyExceedTheDisplayTarget() {
        val samples = (0 until 500).map { index -> sample(index).copy(gapBefore = index > 0 && index % 3 == 0) }

        val reduced = downsampleActivityElevationSamples(samples)

        assertTrue(reduced.size > 120)
        samples.indices.filter { samples[it].gapBefore }.forEach { index ->
            assertTrue(reduced.contains(samples[index - 1]))
            assertTrue(reduced.contains(samples[index]))
        }
    }

    @Test
    fun emptySinglePointAndFlatRecordingsAreSafe() {
        val empty = buildActivityElevationProfile(buildProfile(FileSig(0L, 0L), emptyList()))
        val missing = buildActivityElevationProfile(buildProfile(FileSig(0L, 0L), listOf(point(0, null))))
        val single = buildActivityElevationProfile(buildProfile(FileSig(0L, 0L), listOf(point(0, 100.0))))
        val flat =
            buildActivityElevationProfile(buildProfile(FileSig(0L, 0L), listOf(point(0, 100.0), point(1, 100.0))))

        assertTrue(empty.samples.isEmpty())
        assertTrue(missing.samples.isEmpty())
        assertEquals(1, single.samples.size)
        assertFalse(single.samples.single().gapBefore)
        assertTrue(elevationVisualBounds(flat.samples).range >= 50.0)
    }

    private fun point(
        index: Int,
        elevation: Double?,
    ) = TrackPoint(latLong = LatLong(45.0, 6.0 + index * 0.001), elevation = elevation)

    private fun sample(index: Int) = ElevationSample(index.toDouble(), 100.0, 0.0, 0.0, null)
}
