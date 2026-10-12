package com.glancemap.glancemapcompanionapp.map

import androidx.compose.runtime.saveable.SaverScope
import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.profile.TrailPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMapRouteAnalysisSavedStateTest {
    private val settings = PhoneMapGpxSettings()
    private val generalSettings = PhoneGeneralSettings()
    private val item =
        PhoneMapGpxItem(
            "route",
            "Fixture",
            PhoneMapGpxTrack(
                "route",
                listOf(
                    TrailPoint(GeoPoint(46.0, 7.0), 10.0),
                    TrailPoint(GeoPoint(46.01, 7.01), 100.0),
                    TrailPoint(GeoPoint(46.02, 7.02), 25.0),
                ),
            ),
            enabled = true,
        )
    private val saverScope =
        object : SaverScope {
            override fun canBeSaved(value: Any): Boolean =
                when (value) {
                    is String, is Double, is Int, is Boolean -> true
                    else -> false
                }
        }

    @Test
    fun selectionRoundTripRebuildsAOnlyOrReverseABOnTheCurrentTrackInstance() {
        listOf(false, true).forEach { withB ->
            val original = result(withB)
            val selection = original.savedSelection()
            val saved = with(phoneMapRouteAnalysisSelectionSaver) { saverScope.save(selection) }
            assertTrue((saved as List<*>).all { it == null || saverScope.canBeSaved(it) })
            val restoredSelection = requireNotNull(phoneMapRouteAnalysisSelectionSaver.restore(saved))
            val reloaded = item.copy(track = PhoneMapGpxTrack(item.id, item.track.points.toList()))
            val restored =
                requireNotNull(
                    restorePhoneMapRouteAnalysis(restoredSelection, listOf(reloaded), settings, generalSettings),
                )

            assertEquals(selection, restoredSelection)
            assertEquals(original.analysis, restored.analysis)
            assertNotSame(original.trackRevision.track, restored.trackRevision.track)
            assertTrue(restored.trackRevision.isAvailableIn(listOf(reloaded), gpxTracksVisible = true))
        }
    }

    @Test
    fun changedGeometryElevationAndSegmentBoundariesRejectTheSavedSelection() {
        val selection = result(true).savedSelection()
        val first = item.track.points.first()
        val replacements =
            listOf(
                first.copy(location = GeoPoint(46.001, 7.0)),
                first.copy(elevationMeters = 11.0),
                first.copy(startsNewSegment = true),
            )
        replacements.forEach { replacement ->
            val changed = item.copy(track = item.track.copy(points = listOf(replacement) + item.track.points.drop(1)))
            assertNull(restorePhoneMapRouteAnalysis(selection, listOf(changed), settings, generalSettings))
        }
    }

    @Test
    fun missingDisabledRenamedAndInvalidPositionsCannotRestore() {
        val selection = result(true).savedSelection()
        listOf(emptyList(), listOf(item.copy(enabled = false)), listOf(item.copy(displayName = "Renamed"))).forEach {
            assertNull(restorePhoneMapRouteAnalysis(selection, it, settings, generalSettings))
        }
        val invalid = selection.copy(positionA = PhoneMapRouteAnalysisPosition(99, 0.5))
        assertNull(restorePhoneMapRouteAnalysis(invalid, listOf(item), settings, generalSettings))
        val invalidFraction = selection.copy(positionA = PhoneMapRouteAnalysisPosition(0, Double.NaN))
        assertNull(restorePhoneMapRouteAnalysis(invalidFraction, listOf(item), settings, generalSettings))
    }

    @Test
    fun clearingOrStartingAnotherRequestRejectsDelayedRestoration() {
        val selection = result(true).savedSelection()
        val requests = PhoneMapRouteAnalysisRequestState(4L)
        assertTrue(phoneMapRouteAnalysisRestoreIsCurrent(selection, selection, 4L, requests))
        assertFalse(phoneMapRouteAnalysisRestoreIsCurrent(selection, null, 4L, requests))
        assertFalse(phoneMapRouteAnalysisRestoreIsCurrent(selection, selection, 4L, requests.invalidate()))
        assertFalse(phoneMapRouteAnalysisRestoreIsCurrent(selection, result(false).savedSelection(), 4L, requests))
        assertNull(with(phoneMapRouteAnalysisSelectionSaver) { saverScope.save(null) })
    }

    @Test
    fun incompletePointBInSavedStateIsRejected() {
        val selection = result(true).savedSelection()
        val saved = with(phoneMapRouteAnalysisSelectionSaver) { saverScope.save(selection) } as List<*>
        val invalid = saved.toMutableList()
        invalid[invalid.indexOf("bIndex") + 1] = null
        assertNull(phoneMapRouteAnalysisSelectionSaver.restore(invalid))
    }

    private fun result(withB: Boolean): PhoneMapRouteAnalysisRequestResult {
        val pointA =
            PhoneMapRouteAnalysisHit(
                item,
                PhoneMapRouteAnalysisPosition(1, 0.5),
                PhoneMapCoordinate(46.015, 7.015),
                0.0,
            )
        val pointB =
            PhoneMapRouteAnalysisHit(
                item,
                PhoneMapRouteAnalysisPosition(0, 0.25),
                PhoneMapCoordinate(46.0025, 7.0025),
                0.0,
            ).takeIf { withB }
        return PhoneMapRouteAnalysisRequestResult(
            buildPhoneMapRouteAnalysis(pointA, pointB, settings, generalSettings),
            PhoneMapRouteAnalysisTrackRevision(item.id, item.displayName, item.track),
        )
    }
}
