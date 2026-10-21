package com.glancemap.glancemapwearos.presentation.features.routetools

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glancemap.glancemapwearos.core.routing.RouteGeometryPoint
import com.glancemap.glancemapwearos.presentation.features.gpx.FileSig
import com.glancemap.glancemapwearos.presentation.features.gpx.TrackPoint
import com.glancemap.glancemapwearos.presentation.features.gpx.buildProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mapsforge.core.model.LatLong
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class GpxRouteEditorResourceTest {
    private lateinit var previousLocale: Locale

    @Before
    fun useEnglishFormattingLocale() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun restoreFormattingLocale() {
        Locale.setDefault(previousLocale)
    }

    private val resources =
        InstrumentationRegistry.getInstrumentation().targetContext.let { context ->
            val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }
            context.createConfigurationContext(configuration).resources
        }

    @Test
    fun extensionOutputAppendsRoutedSegmentWithoutDuplicatingJoint() {
        val profile =
            buildTestProfile(
                trackPoint(0.0, 0.0),
                trackPoint(0.0, 0.001),
                trackPoint(0.0, 0.002),
                trackPoint(0.0, 0.003),
            )

        val output =
            buildRouteToolExtensionOutput(
                sourceFileName = "ridge.gpx",
                sourceTitle = "Ridge",
                profile = profile,
                routedPoints =
                    listOf(
                        routeGeometryPoint(0.0, 0.003),
                        routeGeometryPoint(0.0, 0.004),
                        routeGeometryPoint(0.0, 0.005),
                    ),
                resources = resources,
            )

        assertTrue(output.fileName.startsWith("ridge-extend-"))
        assertTrue(output.fileName.endsWith(".gpx"))
        assertEquals("Ridge (extended)", output.title)
        assertEquals(6, output.points.size)
        assertEquals(LatLong(0.0, 0.003), output.points[3].latLong)
        assertEquals(LatLong(0.0, 0.005), output.points.last().latLong)
    }

    @Test
    fun replaceSectionOutputKeepsPrefixAndSuffixAroundReroutedSegment() {
        val profile =
            buildTestProfile(
                trackPoint(0.0, 0.0),
                trackPoint(0.0, 0.001),
                trackPoint(0.0, 0.002),
                trackPoint(0.0, 0.003),
                trackPoint(0.0, 0.004),
            )

        val output =
            buildRouteToolReplaceSectionOutput(
                sourcePath = "/tmp/ridge.gpx",
                sourceFileName = "ridge.gpx",
                sourceTitle = "Ridge",
                profile = profile,
                session =
                    replaceSectionSession(
                        pointA = LatLong(0.0, 0.001),
                        pointB = LatLong(0.0, 0.003),
                    ),
                routedPoints =
                    listOf(
                        routeGeometryPoint(0.0, 0.001),
                        routeGeometryPoint(0.001, 0.002),
                        routeGeometryPoint(0.0, 0.003),
                    ),
                resources = resources,
            )

        assertTrue(output.fileName.startsWith("ridge-replace-ab-"))
        assertEquals("Ridge (rerouted)", output.title)
        assertEquals(
            listOf(
                LatLong(0.0, 0.0),
                LatLong(0.0, 0.001),
                LatLong(0.001, 0.002),
                LatLong(0.0, 0.003),
                LatLong(0.0, 0.004),
            ),
            output.points.map { it.latLong },
        )
    }

    @Test
    fun replaceSectionOutputOrdersReversedABSelectionsOnTheTrack() {
        val profile =
            buildTestProfile(
                trackPoint(0.0, 0.0),
                trackPoint(0.0, 0.001),
                trackPoint(0.0, 0.002),
                trackPoint(0.0, 0.003),
                trackPoint(0.0, 0.004),
            )

        val output =
            buildRouteToolReplaceSectionOutput(
                sourcePath = "/tmp/ridge.gpx",
                sourceFileName = "ridge.gpx",
                sourceTitle = "Ridge",
                profile = profile,
                session =
                    replaceSectionSession(
                        pointA = LatLong(0.0, 0.003),
                        pointB = LatLong(0.0, 0.001),
                    ),
                routedPoints =
                    listOf(
                        routeGeometryPoint(0.0, 0.001),
                        routeGeometryPoint(-0.001, 0.002),
                        routeGeometryPoint(0.0, 0.003),
                    ),
                resources = resources,
            )

        assertEquals(
            listOf(
                LatLong(0.0, 0.0),
                LatLong(0.0, 0.001),
                LatLong(-0.001, 0.002),
                LatLong(0.0, 0.003),
                LatLong(0.0, 0.004),
            ),
            output.points.map { it.latLong },
        )
    }

    @Test
    fun changeStartOutputPrependsRoutedSegmentBeforeRemainingTrack() {
        val profile =
            buildTestProfile(
                trackPoint(0.0, 0.0),
                trackPoint(0.0, 0.001),
                trackPoint(0.0, 0.002),
                trackPoint(0.0, 0.003),
            )

        val snapped =
            resolveRouteToolTrackMatch(
                sourcePath = "/tmp/ridge.gpx",
                sourceTitle = "Ridge",
                profile = profile,
                target = LatLong(0.0, 0.0012),
            )

        val output =
            buildRouteToolEndpointChangeOutput(
                sourceFileName = "ridge.gpx",
                sourceTitle = "Ridge",
                profile = profile,
                session =
                    RouteToolSession(
                        options =
                            RouteToolOptions(
                                toolKind = RouteToolKind.MODIFY,
                                modifyMode = RouteModifyMode.TRIM_START_TO_HERE,
                                saveBehavior = RouteSaveBehavior.SAVE_AS_NEW,
                            ),
                        pointA = LatLong(0.001, 0.0002),
                    ),
                snappedPosition = snapped.position,
                routedPoints =
                    listOf(
                        routeGeometryPoint(0.001, 0.0002),
                        routeGeometryPoint(0.0004, 0.0008),
                        routeGeometryPoint(snapped.latLong.latitude, snapped.latLong.longitude),
                    ),
                resources = resources,
            )

        assertTrue(output.fileName.startsWith("ridge-trim-start-"))
        assertEquals("Ridge (edited)", output.title)
        assertEquals(LatLong(0.001, 0.0002), output.points.first().latLong)
        assertEquals(LatLong(0.0, 0.003), output.points.last().latLong)
        assertEquals(snapped.latLong, output.points[2].latLong)
    }

    @Test
    fun changeStartWithStoredEndpointKeepsLoopBodyEvenWhenEndpointIsNearEnd() {
        val profile =
            buildTestProfile(
                trackPoint(0.0, 0.0),
                trackPoint(0.0, 0.010),
                trackPoint(0.010, 0.010),
                trackPoint(0.010, 0.0),
                trackPoint(0.0001, 0.0),
            )

        val output =
            buildRouteToolEndpointChangeOutput(
                sourceFileName = "loop.gpx",
                sourceTitle = "Loop",
                profile = profile,
                session =
                    RouteToolSession(
                        options =
                            RouteToolOptions(
                                toolKind = RouteToolKind.MODIFY,
                                modifyMode = RouteModifyMode.TRIM_START_TO_HERE,
                                saveBehavior = RouteSaveBehavior.SAVE_AS_NEW,
                            ),
                        pointA = LatLong(-0.001, 0.0),
                        pointATrackPosition = routeToolTrackStartPosition(),
                    ),
                snappedPosition = routeToolTrackStartPosition(),
                routedPoints =
                    listOf(
                        routeGeometryPoint(-0.001, 0.0),
                        routeGeometryPoint(-0.0005, 0.0),
                        routeGeometryPoint(0.0, 0.0),
                    ),
                resources = resources,
            )

        assertEquals(LatLong(-0.001, 0.0), output.points.first().latLong)
        assertEquals(LatLong(0.0001, 0.0), output.points.last().latLong)
        assertTrue(output.points.map { it.latLong }.contains(LatLong(0.010, 0.010)))
        assertTrue(output.points.map { it.latLong }.contains(LatLong(0.010, 0.0)))
    }

    @Test
    fun changeEndOutputAppendsRoutedSegmentAfterRemainingTrack() {
        val profile =
            buildTestProfile(
                trackPoint(0.0, 0.0),
                trackPoint(0.0, 0.001),
                trackPoint(0.0, 0.002),
                trackPoint(0.0, 0.003),
            )

        val snapped =
            resolveRouteToolTrackMatch(
                sourcePath = "/tmp/ridge.gpx",
                sourceTitle = "Ridge",
                profile = profile,
                target = LatLong(0.0, 0.0018),
            )

        val output =
            buildRouteToolEndpointChangeOutput(
                sourceFileName = "ridge.gpx",
                sourceTitle = "Ridge",
                profile = profile,
                session =
                    RouteToolSession(
                        options =
                            RouteToolOptions(
                                toolKind = RouteToolKind.MODIFY,
                                modifyMode = RouteModifyMode.TRIM_END_FROM_HERE,
                                saveBehavior = RouteSaveBehavior.SAVE_AS_NEW,
                            ),
                        pointB = LatLong(0.001, 0.0038),
                    ),
                snappedPosition = snapped.position,
                routedPoints =
                    listOf(
                        routeGeometryPoint(snapped.latLong.latitude, snapped.latLong.longitude),
                        routeGeometryPoint(0.0005, 0.0031),
                        routeGeometryPoint(0.001, 0.0038),
                    ),
                resources = resources,
            )

        assertTrue(output.fileName.startsWith("ridge-trim-end-"))
        assertEquals("Ridge (edited)", output.title)
        assertEquals(LatLong(0.0, 0.0), output.points.first().latLong)
        assertEquals(LatLong(0.001, 0.0038), output.points.last().latLong)
        assertEquals(snapped.latLong, output.points[2].latLong)
    }

    @Test
    fun trimEndWithStoredMatchedPositionDropsTrimmedTail() {
        val profile =
            buildTestProfile(
                trackPoint(0.0, 0.0),
                trackPoint(0.0, 0.001),
                trackPoint(0.0, 0.002),
                trackPoint(0.0, 0.003),
            )
        val snapped =
            resolveRouteToolTrackMatch(
                sourcePath = "/tmp/ridge.gpx",
                sourceTitle = "Ridge",
                profile = profile,
                target = LatLong(0.0, 0.0018),
            )

        val output =
            buildRouteToolEditOutput(
                sourcePath = "/tmp/ridge.gpx",
                sourceFileName = "ridge.gpx",
                sourceTitle = "Ridge",
                profile = profile,
                session =
                    RouteToolSession(
                        options =
                            RouteToolOptions(
                                toolKind = RouteToolKind.MODIFY,
                                modifyMode = RouteModifyMode.TRIM_END_FROM_HERE,
                                saveBehavior = RouteSaveBehavior.SAVE_AS_NEW,
                            ),
                        pointB = LatLong(0.0, 0.0018),
                        pointBTrackPosition = snapped.position,
                    ),
                resources = resources,
            )

        assertEquals(LatLong(0.0, 0.0), output.points.first().latLong)
        assertEquals(snapped.latLong, output.points.last().latLong)
        assertFalse(output.points.map { it.latLong }.contains(LatLong(0.0, 0.003)))
    }

    @Test
    fun changeEndWithStoredEndpointKeepsLoopBodyEvenWhenEndpointIsNearStart() {
        val profile =
            buildTestProfile(
                trackPoint(0.0, 0.0),
                trackPoint(0.0, 0.010),
                trackPoint(0.010, 0.010),
                trackPoint(0.010, 0.0),
                trackPoint(0.0001, 0.0),
            )
        val endPosition = routeToolTrackEndPosition().resolveRouteToolTrackPosition(profile)

        val output =
            buildRouteToolEndpointChangeOutput(
                sourceFileName = "loop.gpx",
                sourceTitle = "Loop",
                profile = profile,
                session =
                    RouteToolSession(
                        options =
                            RouteToolOptions(
                                toolKind = RouteToolKind.MODIFY,
                                modifyMode = RouteModifyMode.TRIM_END_FROM_HERE,
                                saveBehavior = RouteSaveBehavior.SAVE_AS_NEW,
                            ),
                        pointB = LatLong(0.001, 0.0),
                        pointBTrackPosition = routeToolTrackEndPosition(),
                    ),
                snappedPosition = endPosition,
                routedPoints =
                    listOf(
                        routeGeometryPoint(0.0001, 0.0),
                        routeGeometryPoint(0.0005, 0.0),
                        routeGeometryPoint(0.001, 0.0),
                    ),
                resources = resources,
            )

        assertEquals(LatLong(0.0, 0.0), output.points.first().latLong)
        assertEquals(LatLong(0.001, 0.0), output.points.last().latLong)
        assertTrue(output.points.map { it.latLong }.contains(LatLong(0.010, 0.010)))
        assertTrue(output.points.map { it.latLong }.contains(LatLong(0.010, 0.0)))
    }

    @Test
    fun reverseOutputReversesTrackOrderWithoutRouting() {
        val profile =
            buildTestProfile(
                trackPoint(0.0, 0.0, 10.0),
                trackPoint(0.0, 0.001, 20.0),
                trackPoint(0.0, 0.002, 30.0),
                trackPoint(0.0, 0.003, 40.0),
            )

        val output =
            buildRouteToolEditOutput(
                sourcePath = "/tmp/ridge.gpx",
                sourceFileName = "ridge.gpx",
                sourceTitle = "Ridge",
                profile = profile,
                session =
                    RouteToolSession(
                        options =
                            RouteToolOptions(
                                toolKind = RouteToolKind.MODIFY,
                                modifyMode = RouteModifyMode.REVERSE_GPX,
                                saveBehavior = RouteSaveBehavior.SAVE_AS_NEW,
                            ),
                    ),
                resources = resources,
            )

        assertTrue(output.fileName.startsWith("ridge-reverse-"))
        assertEquals("Ridge (edited)", output.title)
        assertEquals(
            listOf(
                LatLong(0.0, 0.003),
                LatLong(0.0, 0.002),
                LatLong(0.0, 0.001),
                LatLong(0.0, 0.0),
            ),
            output.points.map { it.latLong },
        )
        assertEquals(40.0, output.points.first().elevation ?: 0.0, 0.0)
        assertEquals(10.0, output.points.last().elevation ?: 0.0, 0.0)
    }

    private fun replaceSectionSession(
        pointA: LatLong,
        pointB: LatLong,
    ): RouteToolSession =
        RouteToolSession(
            options =
                RouteToolOptions(
                    toolKind = RouteToolKind.MODIFY,
                    modifyMode = RouteModifyMode.REPLACE_SECTION_A_TO_B,
                    saveBehavior = RouteSaveBehavior.SAVE_AS_NEW,
                ),
            pointA = pointA,
            pointB = pointB,
        )

    private fun buildTestProfile(vararg points: TrackPoint) =
        buildProfile(
            sig = FileSig(lastModified = 0L, length = points.size.toLong()),
            pts = points.toList(),
        )

    private fun trackPoint(
        latitude: Double,
        longitude: Double,
        elevation: Double? = null,
    ) = TrackPoint(
        latLong = LatLong(latitude, longitude),
        elevation = elevation,
    )

    private fun routeGeometryPoint(
        latitude: Double,
        longitude: Double,
        elevation: Double? = null,
    ) = RouteGeometryPoint(
        latLong = LatLong(latitude, longitude),
        elevation = elevation,
    )
}
