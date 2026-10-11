package com.glancemap.glancemapcompanionapp.map

import com.glancemap.trailcore.geo.GeoPoint
import com.glancemap.trailcore.geo.haversineDistanceMeters
import com.glancemap.trailcore.profile.TrailPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMapRouteAnalysisRequestTest {
    @Test
    fun latestSelectionWinsWhenRequestsCompleteOutOfOrder() {
        val firstTrack = route("first", 46.0)
        val latestTrack = route("latest", 47.0)
        val candidates = listOf(firstTrack, latestTrack)
        val settings = PhoneMapGpxSettings()
        val generalSettings = PhoneGeneralSettings()
        val (firstState, firstRequest) =
            PhoneMapRouteAnalysisRequestState().begin(
                PhoneMapRouteAnalysisRequestInput(
                    phase = PhoneMapRouteAnalysisSelectionPhase.POINT_A,
                    press = PhoneMapCoordinate(46.0, 6.005),
                    candidateItems = candidates,
                    currentAnalysis = null,
                    currentTrackRevision = null,
                    settings = settings,
                    generalSettings = generalSettings,
                ),
            )
        val firstResult = requireNotNull(computePhoneMapRouteAnalysisRequest(firstRequest))
        val (latestState, latestRequest) =
            firstState.begin(
                PhoneMapRouteAnalysisRequestInput(
                    phase = PhoneMapRouteAnalysisSelectionPhase.POINT_A,
                    press = PhoneMapCoordinate(47.0, 6.015),
                    candidateItems = candidates,
                    currentAnalysis = null,
                    currentTrackRevision = null,
                    settings = settings,
                    generalSettings = generalSettings,
                ),
            )
        val latestResult = requireNotNull(computePhoneMapRouteAnalysisRequest(latestRequest))

        assertTrue(latestState.canPublish(latestRequest, latestResult, currentState(latestRequest, candidates)))
        assertFalse(latestState.canPublish(firstRequest, firstResult, currentState(firstRequest, candidates)))
        assertEquals("latest", latestResult.analysis.trackId)
    }

    @Test
    fun dismissalInvalidatesACompletedButUnpublishedRequest() {
        val item = route("route", 46.0)
        val state = PhoneMapRouteAnalysisRequestState()
        val (pendingState, request) =
            state.begin(
                PhoneMapRouteAnalysisRequestInput(
                    phase = PhoneMapRouteAnalysisSelectionPhase.POINT_A,
                    press = PhoneMapCoordinate(46.0, 6.005),
                    candidateItems = listOf(item),
                    currentAnalysis = null,
                    currentTrackRevision = null,
                    settings = PhoneMapGpxSettings(),
                    generalSettings = PhoneGeneralSettings(),
                ),
            )
        val result = requireNotNull(computePhoneMapRouteAnalysisRequest(request))
        val dismissedState = pendingState.invalidate()

        assertFalse(dismissedState.canPublish(request, result, currentState(request, listOf(item))))
    }

    @Test
    fun hiddenDeletedOrReplacedTrackCannotPublishPendingResult() {
        val item = route("route", 46.0)
        val state = PhoneMapRouteAnalysisRequestState()
        val (requestState, request) =
            state.begin(
                PhoneMapRouteAnalysisRequestInput(
                    phase = PhoneMapRouteAnalysisSelectionPhase.POINT_A,
                    press = PhoneMapCoordinate(46.0, 6.005),
                    candidateItems = listOf(item),
                    currentAnalysis = null,
                    currentTrackRevision = null,
                    settings = PhoneMapGpxSettings(),
                    generalSettings = PhoneGeneralSettings(),
                ),
            )
        val result = requireNotNull(computePhoneMapRouteAnalysisRequest(request))

        val hiddenGlobally =
            currentState(request, listOf(item)).copy(routeAnalysisAvailable = false, gpxTracksVisible = false)
        assertFalse(requestState.canPublish(request, result, hiddenGlobally))
        assertFalse(requestState.canPublish(request, result, currentState(request, listOf(item.copy(enabled = false)))))
        assertFalse(requestState.canPublish(request, result, currentState(request, emptyList())))
        val replacement = item.copy(track = item.track.copy(points = item.track.points.toList()))
        assertFalse(requestState.canPublish(request, result, currentState(request, listOf(replacement))))
    }

    @Test
    fun displayedAnalysisRevisionBecomesUnavailableWhenItsTrackChanges() {
        val item = route("route", 46.0)
        val state = PhoneMapRouteAnalysisRequestState()
        val (_, request) =
            state.begin(
                PhoneMapRouteAnalysisRequestInput(
                    phase = PhoneMapRouteAnalysisSelectionPhase.POINT_A,
                    press = PhoneMapCoordinate(46.0, 6.005),
                    candidateItems = listOf(item),
                    currentAnalysis = null,
                    currentTrackRevision = null,
                    settings = PhoneMapGpxSettings(),
                    generalSettings = PhoneGeneralSettings(),
                ),
            )
        val revision = requireNotNull(computePhoneMapRouteAnalysisRequest(request)).trackRevision

        assertTrue(revision.isAvailableIn(listOf(item), gpxTracksVisible = true))
        assertFalse(revision.isAvailableIn(listOf(item.copy(enabled = false)), gpxTracksVisible = true))
        assertFalse(revision.isAvailableIn(emptyList(), gpxTracksVisible = true))
        assertFalse(
            revision.isAvailableIn(
                listOf(item.copy(track = item.track.copy(points = item.track.points.toList()))),
                gpxTracksVisible = true,
            ),
        )
    }

    @Test
    fun requestsComputeNormalAAndReverseAToBWithCapturedInputs() {
        val item = route("route", 46.0)
        val candidates = listOf(item)
        val settings =
            PhoneMapGpxSettings(
                flatSpeedMetersPerSecond = 4f,
                advancedEtaEnabled = true,
                uphillVerticalMetersPerHour = 3_600f,
                downhillVerticalMetersPerHour = 900f,
            )
        val generalSettings = PhoneGeneralSettings()
        val (pointAState, pointARequest) =
            PhoneMapRouteAnalysisRequestState().begin(
                PhoneMapRouteAnalysisRequestInput(
                    phase = PhoneMapRouteAnalysisSelectionPhase.POINT_A,
                    press = PhoneMapCoordinate(46.0, 6.015),
                    candidateItems = candidates,
                    currentAnalysis = null,
                    currentTrackRevision = null,
                    settings = settings,
                    generalSettings = generalSettings,
                ),
            )
        val pointAResult = requireNotNull(computePhoneMapRouteAnalysisRequest(pointARequest))
        assertNull(pointAResult.analysis.aToB)
        assertTrue(pointAState.canPublish(pointARequest, pointAResult, currentState(pointARequest, candidates)))

        val (pointBState, pointBRequest) =
            pointAState.begin(
                PhoneMapRouteAnalysisRequestInput(
                    phase = PhoneMapRouteAnalysisSelectionPhase.POINT_B,
                    press = PhoneMapCoordinate(46.0, 6.0025),
                    candidateItems = candidates,
                    currentAnalysis = pointAResult.analysis,
                    currentTrackRevision = pointAResult.trackRevision,
                    settings = settings,
                    generalSettings = generalSettings,
                ),
            )
        val pointBResult = requireNotNull(computePhoneMapRouteAnalysisRequest(pointBRequest))
        val expectedReverseSeconds = independentlyCalculatedReverseDuration(item)

        val current = currentState(pointBRequest, candidates)
        assertTrue(pointBState.canPublish(pointBRequest, pointBResult, current))
        assertPointBRequestRejectsChangedInputs(pointBState, pointBRequest, pointBResult)
        assertEquals(15.0, requireNotNull(pointBResult.analysis.aToB).ascentMeters, 0.5)
        assertEquals(37.5, requireNotNull(pointBResult.analysis.aToB).descentMeters, 0.5)
        assertEquals(expectedReverseSeconds, requireNotNull(pointBResult.analysis.aToB).durationSeconds, 1.0)
    }

    private fun route(
        id: String,
        latitude: Double,
    ): PhoneMapGpxItem =
        PhoneMapGpxItem(
            id = id,
            displayName = id,
            track =
                PhoneMapGpxTrack(
                    id = id,
                    points =
                        listOf(
                            TrailPoint(GeoPoint(latitude, 6.0), elevationMeters = 100.0),
                            TrailPoint(GeoPoint(latitude, 6.01), elevationMeters = 150.0),
                            TrailPoint(GeoPoint(latitude, 6.02), elevationMeters = 120.0),
                        ),
                ),
            enabled = true,
        )
}

private fun currentState(
    request: PhoneMapRouteAnalysisRequest,
    items: List<PhoneMapGpxItem>,
    phase: PhoneMapRouteAnalysisSelectionPhase = request.input.phase,
    analysis: PhoneMapRouteAnalysis? = request.input.currentAnalysis,
    trackRevision: PhoneMapRouteAnalysisTrackRevision? = request.input.currentTrackRevision,
): PhoneMapRouteAnalysisRequestCurrentState =
    PhoneMapRouteAnalysisRequestCurrentState(
        phase = phase,
        analysis = analysis,
        trackRevision = trackRevision,
        items = items,
        routeAnalysisAvailable = true,
        gpxTracksVisible = true,
        settings = request.input.settings,
        generalSettings = request.input.generalSettings,
    )

private fun independentlyCalculatedReverseDuration(item: PhoneMapGpxItem): Double {
    val points = item.track.points
    val distanceMeters =
        haversineDistanceMeters(points[0].location, points[1].location) * 0.75 +
            haversineDistanceMeters(points[1].location, points[2].location) * 0.5
    return distanceMeters / 4.0 + 15.0 + 37.5 / 900.0 * 3_600.0
}

private fun assertPointBRequestRejectsChangedInputs(
    state: PhoneMapRouteAnalysisRequestState,
    request: PhoneMapRouteAnalysisRequest,
    result: PhoneMapRouteAnalysisRequestResult,
) {
    val current = currentState(request, request.input.candidateItems)
    assertFalse(
        state.canPublish(
            request,
            result,
            current.copy(phase = PhoneMapRouteAnalysisSelectionPhase.POINT_A),
        ),
    )
    assertFalse(
        state.canPublish(
            request,
            result,
            current.copy(analysis = current.analysis?.copy(pointA = PhoneMapCoordinate(46.0, 6.005))),
        ),
    )
    assertFalse(
        state.canPublish(
            request,
            result,
            current.copy(settings = current.settings.copy(flatSpeedMetersPerSecond = 8f)),
        ),
    )
}
