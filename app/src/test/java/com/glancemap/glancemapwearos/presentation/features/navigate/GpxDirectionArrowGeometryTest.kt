package com.glancemap.glancemapwearos.presentation.features.navigate

import com.glancemap.glancemapwearos.presentation.features.gpx.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import kotlin.math.roundToInt

class GpxDirectionArrowGeometryTest {
    @Test
    fun buildsEastboundArrowsWithEastHeading() {
        val arrows =
            buildGpxDirectionArrows(
                points =
                    listOf(
                        trackPoint(lat = 45.0, lon = 6.0),
                        trackPoint(lat = 45.0, lon = 6.05),
                    ),
                zoom = 16,
                tileSize = 256,
            )

        assertTrue(arrows.isNotEmpty())
        assertEquals(90f, arrows.first().headingDeg, 0.5f)
    }

    @Test
    fun buildsNorthboundArrowsWithNorthHeading() {
        val arrows =
            buildGpxDirectionArrows(
                points =
                    listOf(
                        trackPoint(lat = 45.0, lon = 6.0),
                        trackPoint(lat = 45.05, lon = 6.0),
                    ),
                zoom = 16,
                tileSize = 256,
            )

        assertTrue(arrows.isNotEmpty())
        assertEquals(0f, arrows.first().headingDeg, 0.5f)
    }

    @Test
    fun capsVeryLongTracks() {
        val arrows =
            buildGpxDirectionArrows(
                points =
                    listOf(
                        trackPoint(lat = 45.0, lon = 6.0),
                        trackPoint(lat = 45.0, lon = 9.0),
                    ),
                zoom = 16,
                tileSize = 256,
            )

        assertEquals(36, arrows.size)
    }

    @Test
    fun buildsArrowsAcrossDenseShortSegments() {
        val points =
            (0..400).map { index ->
                trackPoint(
                    lat = 45.0,
                    lon = 6.0 + index * 0.000125,
                )
            }

        val arrows =
            buildGpxDirectionArrows(
                points = points,
                zoom = 16,
                tileSize = 256,
            )

        assertTrue(arrows.size >= 10)
        assertEquals(90f, arrows.first().headingDeg, 0.5f)
    }

    @Test
    fun buildsVisibleArrowsForViewportSubset() {
        val points =
            (0..400).map { index ->
                trackPoint(
                    lat = 45.0,
                    lon = 6.0 + index * 0.000025,
                )
            }

        val arrows =
            buildVisibleGpxDirectionArrows(
                points = points,
                zoom = 15,
                tileSize = 256,
                boundingBox =
                    BoundingBox(
                        44.9995,
                        6.003,
                        45.0005,
                        6.006,
                    ),
            )

        assertTrue(arrows.isNotEmpty())
        assertTrue(arrows.size <= MAX_VISIBLE_GPX_DIRECTION_ARROWS_PER_TRACK)
        assertEquals(90f, arrows.first().headingDeg, 0.5f)
    }

    @Test
    fun cachedGeometryKeepsVisibleArrowResults() {
        val points =
            (0..400).map { index ->
                trackPoint(
                    lat = 45.0,
                    lon = 6.0 + index * 0.000025,
                )
            }
        val viewport =
            BoundingBox(
                44.9995,
                6.003,
                45.0005,
                6.006,
            )

        val direct =
            buildVisibleGpxDirectionArrows(
                points = points,
                zoom = 15,
                tileSize = 256,
                boundingBox = viewport,
            )
        val cached =
            buildVisibleGpxDirectionArrows(
                geometry = requireNotNull(buildGpxDirectionArrowGeometry(points, zoom = 15, tileSize = 256)),
                boundingBox = viewport,
            )

        assertEquals(direct, cached)
    }

    @Test
    fun capsVisibleArrowsPerTrack() {
        val points =
            (0..2400).map { index ->
                trackPoint(
                    lat = 45.0,
                    lon = 6.0 + index * 0.000025,
                )
            }

        val arrows =
            buildVisibleGpxDirectionArrows(
                points = points,
                zoom = 16,
                tileSize = 256,
                boundingBox =
                    BoundingBox(
                        44.999,
                        6.0,
                        45.001,
                        6.06,
                    ),
            )

        assertEquals(MAX_VISIBLE_GPX_DIRECTION_ARROWS_PER_TRACK, arrows.size)
    }

    @Test
    fun distributesCappedVisibleArrowsAcrossViewport() {
        val points =
            (0..1200).map { index ->
                trackPoint(
                    lat = 45.0,
                    lon = 6.0 + index * 0.000025,
                )
            }

        val arrows =
            buildVisibleGpxDirectionArrows(
                points = points,
                zoom = 16,
                tileSize = 256,
                boundingBox =
                    BoundingBox(
                        44.999,
                        6.0,
                        45.001,
                        6.03,
                    ),
                maxArrows = 4,
            )

        assertEquals(4, arrows.size)
        assertTrue(arrows.first().latLong.longitude < 6.005)
        assertTrue(arrows.last().latLong.longitude > 6.025)
    }

    @Test
    fun buildsVisibleArrowForClippedLongSegment() {
        val arrows =
            buildVisibleGpxDirectionArrows(
                points =
                    listOf(
                        trackPoint(lat = 45.0, lon = 6.0),
                        trackPoint(lat = 45.0, lon = 7.0),
                    ),
                zoom = 16,
                tileSize = 256,
                boundingBox =
                    BoundingBox(
                        44.999,
                        6.49,
                        45.001,
                        6.51,
                    ),
                maxArrows = 3,
            )

        assertTrue(arrows.isNotEmpty())
        assertTrue(arrows.first().latLong.longitude in 6.49..6.51)
    }

    @Test
    fun keepsVisibleArrowPositionsAnchoredWhenViewportPans() {
        val points =
            (0..2400).map { index ->
                trackPoint(
                    lat = 45.0,
                    lon = 6.0 + index * 0.000025,
                )
            }

        val firstViewport =
            buildVisibleGpxDirectionArrows(
                points = points,
                zoom = 16,
                tileSize = 256,
                boundingBox =
                    BoundingBox(
                        44.999,
                        6.0,
                        45.001,
                        6.03,
                    ),
            )
        val slightlyPannedViewport =
            buildVisibleGpxDirectionArrows(
                points = points,
                zoom = 16,
                tileSize = 256,
                boundingBox =
                    BoundingBox(
                        44.999,
                        6.001,
                        45.001,
                        6.031,
                    ),
            )

        val firstKeys = firstViewport.map { it.latLong.longitude.roundKey() }.toSet()
        val pannedKeys = slightlyPannedViewport.map { it.latLong.longitude.roundKey() }.toSet()

        assertTrue(firstKeys.intersect(pannedKeys).isNotEmpty())
    }

    @Test
    fun lodPreservesTrackSegmentBoundaries() {
        val points =
            buildList {
                repeat(80) { index ->
                    add(trackPoint(lat = 45.0, lon = 6.0 + index * 0.00001))
                }
                repeat(80) { index ->
                    add(
                        trackPoint(
                            lat = 46.0,
                            lon = 7.0 + index * 0.00001,
                            startsNewSegment = index == 0,
                        ),
                    )
                }
            }

        val lod = buildTrackLodLevels(points)

        listOf(lod.low, lod.medium, lod.full).forEach { level ->
            val segments = level.splitTrackSegments()
            assertEquals(2, segments.size)
            assertEquals(
                45.0,
                segments
                    .first()
                    .first()
                    .latLong.latitude,
                0.0,
            )
            assertEquals(
                46.0,
                segments
                    .last()
                    .first()
                    .latLong.latitude,
                0.0,
            )
        }
    }

    @Test
    fun changingTrackWidthReusesLodGeometry() =
        assertAppearanceChangeDoesNotBuild { appearance ->
            appearance.copy(width = appearance.width + 1f)
        }

    @Test
    fun changingTrackOpacityReusesLodGeometry() =
        assertAppearanceChangeDoesNotBuild { appearance ->
            appearance.copy(opacityPercent = 40)
        }

    @Test
    fun changingElevationColorModeReusesLodGeometry() =
        assertAppearanceChangeDoesNotBuild { appearance ->
            appearance.copy(useElevationColors = true)
        }

    @Test
    fun changingDirectionArrowVisibilityReusesLodGeometry() =
        assertAppearanceChangeDoesNotBuild { appearance ->
            appearance.copy(showDirectionArrows = true)
        }

    @Test
    fun changingTrackCoordinatesRebuildsLodGeometry() {
        val points = geometryTestPoints()
        val builder = CountingTrackLodBuilder()
        val initial = builder.getOrBuild(TRACK_ID, points)
        val changedPoints =
            points.toMutableList().apply {
                this[40] = this[40].copy(latLong = LatLong(45.01, 6.01))
            }

        val updated = builder.getOrBuild(TRACK_ID, changedPoints, mapOf(TRACK_ID to initial))

        assertTrue(initial !== updated)
        assertEquals(2, builder.buildCount)
    }

    @Test
    fun changingSegmentBoundariesRebuildsLodGeometry() {
        val points = geometryTestPoints()
        val builder = CountingTrackLodBuilder()
        val initial = builder.getOrBuild(TRACK_ID, points)
        val changedPoints =
            points.toMutableList().apply {
                this[40] = this[40].copy(startsNewSegment = true)
            }

        val updated = builder.getOrBuild(TRACK_ID, changedPoints, mapOf(TRACK_ID to initial))

        assertTrue(initial !== updated)
        assertEquals(2, builder.buildCount)
    }

    @Test
    fun changingElevationRepresentedByExistingSignatureRebuildsLodGeometry() {
        val points = geometryTestPoints()
        val builder = CountingTrackLodBuilder()
        val initial = builder.getOrBuild(TRACK_ID, points)
        val changedPoints =
            points.toMutableList().apply {
                this[40] = this[40].copy(elevation = 123.0)
            }

        val updated = builder.getOrBuild(TRACK_ID, changedPoints, mapOf(TRACK_ID to initial))

        assertTrue(initial !== updated)
        assertEquals(2, builder.buildCount)
    }

    @Test
    fun sameGeometryForDifferentTrackIdBuildsSeparateLodEntry() {
        val points = geometryTestPoints()
        val builder = CountingTrackLodBuilder()
        val initial = builder.getOrBuild(TRACK_ID, points)

        val separateTrack = builder.getOrBuild("other-track", points, mapOf(TRACK_ID to initial))

        assertTrue(initial !== separateTrack)
        assertEquals(2, builder.buildCount)
    }

    private fun assertAppearanceChangeDoesNotBuild(change: (TrackAppearance) -> TrackAppearance) {
        val appearance =
            TrackAppearance(
                width = 3f,
                opacityPercent = 100,
                useElevationColors = false,
                showDirectionArrows = false,
            )
        val changedAppearance = change(appearance)
        assertNotEquals(appearance, changedAppearance)

        val points = geometryTestPoints()
        val builder = CountingTrackLodBuilder()
        val initial = builder.getOrBuild(TRACK_ID, points)
        val updated = builder.getOrBuild(TRACK_ID, points, mapOf(TRACK_ID to initial))

        assertSame(initial, updated)
        assertEquals(1, builder.buildCount)
    }

    private fun geometryTestPoints(): List<TrackPoint> = (0..80).map { trackPoint(45.0, 6.0 + it * 0.00001) }

    private data class TrackAppearance(
        val width: Float,
        val opacityPercent: Int,
        val useElevationColors: Boolean,
        val showDirectionArrows: Boolean,
    )

    private class CountingTrackLodBuilder {
        var buildCount = 0
            private set

        fun getOrBuild(
            trackId: String,
            points: List<TrackPoint>,
            cachedById: Map<String, TrackLodLevels> = emptyMap(),
        ): TrackLodLevels =
            getOrBuildTrackLodLevels(trackId, points, cachedById) { buildPoints, signature ->
                buildCount += 1
                buildTrackLodLevels(buildPoints, signature)
            }
    }

    private companion object {
        const val TRACK_ID = "track"
    }

    private fun trackPoint(
        lat: Double,
        lon: Double,
        startsNewSegment: Boolean = false,
    ) = TrackPoint(
        latLong = LatLong(lat, lon),
        elevation = null,
        startsNewSegment = startsNewSegment,
    )

    private fun Double.roundKey(): Int = (this * 1_000_000).roundToInt()
}
