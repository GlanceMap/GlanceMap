package com.glancemap.glancemapcompanionapp.map

import com.glancemap.glancemapcompanionapp.map.maplibre.phoneMapGpxFitIsStillEligible
import com.glancemap.trailcore.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMapComparisonTest {
    @Test
    fun cameraSynchronizationUsesTolerancesIncludingAcrossNorth() {
        val camera =
            PhoneMapCameraSnapshot(
                latitude = 46.0,
                longitude = 7.0,
                zoom = 12.0,
                bearingDegrees = 359.8f,
            )

        assertFalse(
            phoneMapComparisonCameraNeedsSync(
                current = camera,
                target = camera.copy(longitude = 7.0000005, bearingDegrees = 0.1f),
            ),
        )
        assertTrue(
            phoneMapComparisonCameraNeedsSync(
                current = camera,
                target = camera.copy(zoom = 12.2),
            ),
        )
    }

    @Test
    fun transparencyControlsOnlyTheUpperLayerOpacity() {
        val comparison =
            PhoneMapComparisonState(
                layer = PhoneMapComparisonLayer.Online(PhoneOnlineMapSource.OPEN_STREET_MAP),
            )

        assertEquals(1f, comparison.withTransparency(0f).overlayAlpha(), 0f)
        assertEquals(0.75f, comparison.withTransparency(25f).overlayAlpha(), 0f)
        assertEquals(0.5f, comparison.overlayAlpha(), 0f)
        assertEquals(0.25f, comparison.withTransparency(75f).overlayAlpha(), 0f)
        assertEquals(0f, comparison.withTransparency(100f).overlayAlpha(), 0f)
        assertEquals(1f, comparison.withTransparency(-1f).overlayAlpha(), 0f)
        assertEquals(0f, comparison.withTransparency(101f).overlayAlpha(), 0f)
        assertEquals(comparison.layer, comparison.withTransparency(51f).layer)
        assertEquals(comparison.layer, comparison.withTransparency(100f).layer)
    }

    @Test
    fun onlyComparisonMapsUseTextureSurfaces() {
        assertFalse(phoneMapLibreSurfaceUsesTexture(PhoneMapLibreSurfaceMode.PRIMARY))
        assertTrue(phoneMapLibreSurfaceUsesTexture(PhoneMapLibreSurfaceMode.COMPARISON))
    }

    @Test
    fun visibleComparisonOwnsSemanticOverlays() {
        assertFalse(
            phoneMapComparisonOwnsSemanticOverlays(
                offlineComparisonActive = false,
                onlineComparisonActive = false,
            ),
        )
        assertTrue(
            phoneMapComparisonOwnsSemanticOverlays(
                offlineComparisonActive = true,
                onlineComparisonActive = false,
            ),
        )
        assertTrue(
            phoneMapComparisonOwnsSemanticOverlays(
                offlineComparisonActive = false,
                onlineComparisonActive = true,
            ),
        )
    }

    @Test
    fun failedOfflineComparisonClearsAndReturnsOwnershipToTheBase() {
        val layer = PhoneMapComparisonLayer.Offline(offlineMap())
        val selected = PhoneMapUiState().selectComparisonLayer(layer)

        val failed =
            selected.clearFailedOfflineComparison(
                layer = layer,
                selectionGeneration = selected.comparison.selectionGeneration,
            )
        val selectedOfflineComparisonActive =
            selected.comparison.layer is PhoneMapComparisonLayer.Offline && selected.comparison.overlayAlpha() > 0f
        val failedOfflineComparisonActive =
            failed.comparison.layer is PhoneMapComparisonLayer.Offline && failed.comparison.overlayAlpha() > 0f

        assertEquals(PhoneMapSource.Online, failed.source)
        assertEquals(null, failed.comparison.layer)
        assertTrue(
            phoneMapComparisonOwnsSemanticOverlays(
                offlineComparisonActive = selectedOfflineComparisonActive,
                onlineComparisonActive = false,
            ),
        )
        assertFalse(
            phoneMapComparisonOwnsSemanticOverlays(
                offlineComparisonActive = failedOfflineComparisonActive,
                onlineComparisonActive = false,
            ),
        )
        assertTrue(
            phoneMapComparisonOwnsUserCamera(
                offlineComparisonActive = selectedOfflineComparisonActive,
                onlineComparisonActive = false,
            ),
        )
        assertFalse(
            phoneMapComparisonOwnsUserCamera(
                offlineComparisonActive = failedOfflineComparisonActive,
                onlineComparisonActive = false,
            ),
        )
        assertFalse(phoneMapComparisonOwnsFollowAndCommands(failed.source, onlineComparisonActive = false))
    }

    @Test
    fun oldOfflineRendererFailureCannotClearANewerOrReselectedComparison() {
        val oldLayer = PhoneMapComparisonLayer.Offline(offlineMap())
        val oldSelection = PhoneMapUiState().selectComparisonLayer(oldLayer)
        val newerLayer = PhoneMapComparisonLayer.Offline(PhoneOfflineMap(java.io.File("pyrenees.map")))
        val newerSelection = oldSelection.selectComparisonLayer(newerLayer)

        assertEquals(
            newerSelection,
            newerSelection.clearFailedOfflineComparison(oldLayer, oldSelection.comparison.selectionGeneration),
        )

        val reselectedOldLayer = newerSelection.selectComparisonLayer(oldLayer)
        assertEquals(
            reselectedOldLayer,
            reselectedOldLayer.clearFailedOfflineComparison(oldLayer, oldSelection.comparison.selectionGeneration),
        )
    }

    @Test
    fun offlineRendererFailureAfterComparisonWasClearedIsIgnored() {
        val layer = PhoneMapComparisonLayer.Offline(offlineMap())
        val selected = PhoneMapUiState().selectComparisonLayer(layer)
        val cleared = selected.selectComparisonLayer(null)

        assertEquals(
            cleared,
            cleared.clearFailedOfflineComparison(layer, selected.comparison.selectionGeneration),
        )
    }

    @Test
    fun laterOfflineSelectionWinsWhenEarlierValidationCompletesLast() {
        val layerA = PhoneMapComparisonLayer.Offline(offlineMap("a.map"))
        val layerB = PhoneMapComparisonLayer.Offline(offlineMap("b.map"))
        val installedMaps = listOf(layerA.map, layerB.map)
        val requestA = PhoneMapUiState().nextComparisonSelectionRequest()
        val requestAGeneration = requestA.comparison.validationRequestGeneration
        val requestB = requestA.nextComparisonSelectionRequest()
        val requestBGeneration = requestB.comparison.validationRequestGeneration

        val afterB =
            requestB.completeOfflineComparisonSelection(
                requestGeneration = requestBGeneration,
                layer = layerB,
                validationError = null,
                offlineMaps = installedMaps,
            )
        val afterLateA =
            afterB.state.completeOfflineComparisonSelection(
                requestGeneration = requestAGeneration,
                layer = layerA,
                validationError = null,
                offlineMaps = installedMaps,
            )

        assertEquals(layerB, afterLateA.state.comparison.layer)
        assertEquals(null, afterLateA.validationError)
    }

    @Test
    fun clearingComparisonInvalidatesPendingValidation() {
        val layer = PhoneMapComparisonLayer.Offline(offlineMap("pending.map"))
        val pending = PhoneMapUiState().nextComparisonSelectionRequest()
        val requestGeneration = pending.comparison.validationRequestGeneration
        val cleared = pending.nextComparisonSelectionRequest().selectComparisonLayer(null)

        val completion =
            cleared.completeOfflineComparisonSelection(
                requestGeneration = requestGeneration,
                layer = layer,
                validationError = null,
                offlineMaps = listOf(layer.map),
            )

        assertEquals(cleared, completion.state)
        assertEquals(null, completion.state.comparison.layer)
        assertEquals(null, completion.validationError)
    }

    @Test
    fun staleValidationFailureDoesNotProduceAnError() {
        val layerA = PhoneMapComparisonLayer.Offline(offlineMap("a.map"))
        val layerB = PhoneMapComparisonLayer.Offline(offlineMap("b.map"))
        val requestA = PhoneMapUiState().nextComparisonSelectionRequest()
        val requestAGeneration = requestA.comparison.validationRequestGeneration
        val requestB = requestA.nextComparisonSelectionRequest()

        val completion =
            requestB.completeOfflineComparisonSelection(
                requestGeneration = requestAGeneration,
                layer = layerA,
                validationError = PhoneOfflineMapError.INVALID,
                offlineMaps = listOf(layerA.map, layerB.map),
            )

        assertEquals(requestB, completion.state)
        assertEquals(null, completion.validationError)
    }

    @Test
    fun reselectingSameLayerInvalidatesItsEarlierValidation() {
        val currentLayer = PhoneMapComparisonLayer.Online(PhoneOnlineMapSource.OPEN_STREET_MAP)
        val requestedLayer = PhoneMapComparisonLayer.Offline(offlineMap("requested.map"))
        val current = PhoneMapUiState().selectComparisonLayer(currentLayer)
        val oldRequest = current.nextComparisonSelectionRequest()
        val oldGeneration = oldRequest.comparison.validationRequestGeneration
        val latestRequest = oldRequest.nextComparisonSelectionRequest()
        val latestGeneration = latestRequest.comparison.validationRequestGeneration

        val staleFailure =
            latestRequest.completeOfflineComparisonSelection(
                requestGeneration = oldGeneration,
                layer = requestedLayer,
                validationError = PhoneOfflineMapError.INVALID,
                offlineMaps = listOf(requestedLayer.map),
            )
        val latestSuccess =
            staleFailure.state.completeOfflineComparisonSelection(
                requestGeneration = latestGeneration,
                layer = requestedLayer,
                validationError = null,
                offlineMaps = listOf(requestedLayer.map),
            )

        assertEquals(currentLayer, staleFailure.state.comparison.layer)
        assertEquals(null, staleFailure.validationError)
        assertEquals(requestedLayer, latestSuccess.state.comparison.layer)
        assertEquals(null, latestSuccess.validationError)
    }

    @Test
    fun baseSourceChangeMakesPendingOfflineSelectionObsolete() {
        val baseMap = offlineMap("base.map")
        val requestedLayer = PhoneMapComparisonLayer.Offline(offlineMap("comparison.map"))
        val currentLayer = PhoneMapComparisonLayer.Online(PhoneOnlineMapSource.OPEN_STREET_MAP)
        val current = PhoneMapUiState().selectComparisonLayer(currentLayer)
        val pending = current.nextComparisonSelectionRequest()
        val requestGeneration = pending.comparison.validationRequestGeneration
        val changedBase = pending.changeBaseSource(PhoneMapSource.Offline(baseMap))

        val completion =
            changedBase.completeOfflineComparisonSelection(
                requestGeneration = requestGeneration,
                layer = requestedLayer,
                validationError = PhoneOfflineMapError.INVALID,
                offlineMaps = listOf(baseMap, requestedLayer.map),
            )

        assertEquals(PhoneMapSource.Offline(baseMap), completion.state.source)
        assertEquals(currentLayer, completion.state.comparison.layer)
        assertEquals(changedBase, completion.state)
        assertEquals(null, completion.validationError)
    }

    @Test
    fun latestValidationCanSelectLayerAndCurrentFailureStillReportsError() {
        val currentLayer = PhoneMapComparisonLayer.Online(PhoneOnlineMapSource.OPEN_STREET_MAP)
        val requestedLayer = PhoneMapComparisonLayer.Offline(offlineMap("requested.map"))
        val current = PhoneMapUiState().selectComparisonLayer(currentLayer)
        val failedRequest = current.nextComparisonSelectionRequest()
        val failedGeneration = failedRequest.comparison.validationRequestGeneration

        val failure =
            failedRequest.completeOfflineComparisonSelection(
                requestGeneration = failedGeneration,
                layer = requestedLayer,
                validationError = PhoneOfflineMapError.INVALID,
                offlineMaps = listOf(requestedLayer.map),
            )
        val latestRequest = failure.state.nextComparisonSelectionRequest()
        val success =
            latestRequest.completeOfflineComparisonSelection(
                requestGeneration = latestRequest.comparison.validationRequestGeneration,
                layer = requestedLayer,
                validationError = null,
                offlineMaps = listOf(requestedLayer.map),
            )

        assertEquals(currentLayer, failure.state.comparison.layer)
        assertEquals(PhoneOfflineMapError.INVALID, failure.validationError)
        assertEquals(requestedLayer, success.state.comparison.layer)
        assertEquals(null, success.validationError)
    }

    @Test
    fun removedOfflineMapCannotBeSelectedAfterValidation() {
        val requestedLayer = PhoneMapComparisonLayer.Offline(offlineMap("removed.map"))
        val pending = PhoneMapUiState().nextComparisonSelectionRequest()
        val completion =
            pending.completeOfflineComparisonSelection(
                requestGeneration = pending.comparison.validationRequestGeneration,
                layer = requestedLayer,
                validationError = null,
                offlineMaps = emptyList(),
            )

        assertEquals(pending, completion.state)
        assertEquals(null, completion.validationError)
    }

    @Test
    fun comparisonOwnsUserCameraOnlyWhileVisible() {
        assertFalse(phoneMapComparisonOwnsUserCamera(false, false))
        assertTrue(phoneMapComparisonOwnsUserCamera(true, false))
        assertTrue(phoneMapComparisonOwnsUserCamera(false, true))
    }

    @Test
    fun onlineComparisonOwnsFollowAndCommandsOnlyAboveOfflineBase() {
        assertFalse(phoneMapComparisonOwnsFollowAndCommands(PhoneMapSource.Online, true))
        assertFalse(phoneMapComparisonOwnsFollowAndCommands(PhoneMapSource.Offline(offlineMap()), false))
        assertTrue(phoneMapComparisonOwnsFollowAndCommands(PhoneMapSource.Offline(offlineMap()), true))
    }

    @Test
    fun initialGpxFitHasExactlyOneOwnerAcrossSourceTransitions() {
        assertFalse(phoneMapComparisonOwnsInitialGpxFit(PhoneMapSource.Online, true))
        assertFalse(phoneMapComparisonOwnsInitialGpxFit(PhoneMapSource.Offline(offlineMap()), false))
        assertTrue(phoneMapComparisonOwnsInitialGpxFit(PhoneMapSource.Offline(offlineMap()), true))
    }

    @Test
    fun delayedGpxFitRequiresCurrentRendererOwnerAndUnfittedState() {
        assertTrue(phoneMapGpxFitIsStillEligible(true, true, false))
        assertFalse(phoneMapGpxFitIsStillEligible(false, true, false))
        assertFalse(phoneMapGpxFitIsStillEligible(true, false, false))
        assertFalse(phoneMapGpxFitIsStillEligible(true, true, true))
    }

    @Test
    fun fractionalCameraSyncStillRequiresNoFeedbackWhenValuesMatch() {
        val camera = PhoneMapCameraSnapshot(46.0, 7.0, 12.75, 45f)

        assertFalse(phoneMapComparisonCameraNeedsSync(camera, camera.copy()))
    }

    @Test
    fun cameraZoomConversionPreservesGroundScaleAcrossDensitiesAndFractionalZooms() {
        val latitude = 47.5
        val mapLibreZoom = 12.75

        listOf(1.0, 2.0, 3.0).forEach { density ->
            val mapLibreTileScale = PHONE_MAPLIBRE_CAMERA_TILE_SIZE_PX * density
            val mapsforgeTileSize = 256.0 * density
            val expectedResolution =
                phoneGroundResolutionMetersPerPixel(latitude, mapLibreZoom, mapLibreTileScale)
            val mapsforgeZoom =
                phoneMapsforgeZoomForGroundResolution(latitude, expectedResolution, mapsforgeTileSize)
            val mapLibreZoomAgain =
                phoneMapLibreZoomForGroundResolution(latitude, expectedResolution, density)

            assertEquals(
                expectedResolution,
                phoneGroundResolutionMetersPerPixel(latitude, mapsforgeZoom, mapsforgeTileSize),
                1e-9,
            )
            assertEquals(mapLibreZoom, mapLibreZoomAgain, 1e-9)
            assertEquals(
                mapLibreZoom + 1.0,
                phoneMapsforgeZoomForMapLibreZoom(mapLibreZoom, mapsforgeTileSize, density),
                1e-9,
            )
        }
    }

    @Test
    fun comparisonOwnershipRemovesGpxSegmentsFromTheBaseRenderer() {
        val segments =
            listOf(
                PhoneMapRouteSegment(
                    points = listOf(GeoPoint(46.0, 7.0), GeoPoint(46.1, 7.1)),
                ),
            )

        assertEquals(
            emptyList<PhoneMapRouteSegment>(),
            phoneMapComparisonBaseGpxSegments(
                segments = segments,
                comparisonOwnsSemanticOverlays = true,
            ),
        )
        assertEquals(
            segments,
            phoneMapComparisonBaseGpxSegments(
                segments = segments,
                comparisonOwnsSemanticOverlays = false,
            ),
        )
    }

    @Test
    fun sameOfflineMapCanBeSelectedForIndependentThemeComparison() {
        val map = offlineMap()
        val layer = PhoneMapComparisonLayer.Offline(map)
        val pending = PhoneMapUiState(source = PhoneMapSource.Offline(map)).nextComparisonSelectionRequest()
        val completion =
            pending.completeOfflineComparisonSelection(
                pending.comparison.validationRequestGeneration,
                layer,
                null,
                listOf(map),
            )
        assertEquals(layer, completion.state.comparison.layer)
        assertTrue(
            comparisonLayerOptions(
                pending.source,
                PhoneOnlineMapSource.OPEN_TOPO,
                listOf(map),
                emptyList(),
            ).contains(layer),
        )
        assertEquals(
            null,
            completion.state
                .clearUnavailableComparison(emptyList())
                .comparison.layer,
        )
    }

    @Test
    fun offlineComparisonOwnsFollowAndCommandsAboveOfflineBaseOnlyWhileVisible() {
        val base = PhoneMapSource.Offline(offlineMap())
        assertTrue(phoneMapComparisonOwnsFollowAndCommands(base, false, true))
        assertFalse(phoneMapComparisonOwnsFollowAndCommands(base, false, false))
        assertFalse(phoneMapComparisonOwnsFollowAndCommands(PhoneMapSource.Online, false, true))
    }

    @Test
    fun swapPreservesBlendingAndInvalidatesOutstandingComparisonRequests() {
        val base = PhoneMapSource.Offline(offlineMap("base.map"))
        val layer = PhoneMapComparisonLayer.Offline(offlineMap("upper.map"))
        val original = PhoneMapUiState(source = base).selectComparisonLayer(layer).setComparisonTransparency(25f)
        val swapped = original.swapComparison(PhoneOnlineMapSource.OPEN_TOPO)
        assertEquals(PhoneMapSource.Offline(layer.map), swapped.source)
        assertEquals(PhoneMapComparisonLayer.Offline(base.map), swapped.comparison.layer)
        assertEquals(75f, swapped.comparison.transparencyPercent, 0f)
        assertEquals(original.toolPanel, swapped.toolPanel)
        assertTrue(swapped.comparison.validationRequestGeneration > original.comparison.validationRequestGeneration)
        val restored = swapped.swapComparison(PhoneOnlineMapSource.OPEN_TOPO)
        assertEquals(original.source, restored.source)
        assertEquals(original.comparison.layer, restored.comparison.layer)
        assertEquals(original.comparison.transparencyPercent, restored.comparison.transparencyPercent, 0f)
    }

    @Test
    fun swapRetainsTheSelectedOnlineProviderInEitherDirectionAndNoComparisonIsANoOp() {
        val provider = PhoneOnlineMapSource.CYCLOSM
        val layer = PhoneMapComparisonLayer.Offline(offlineMap())
        val original = PhoneMapUiState().selectComparisonLayer(layer)
        val swapped = original.swapComparison(provider)
        assertEquals(PhoneMapSource.Offline(layer.map), swapped.source)
        assertEquals(PhoneMapComparisonLayer.Online(provider), swapped.comparison.layer)
        assertEquals(layer, swapped.swapComparison(provider).comparison.layer)
        val empty = PhoneMapUiState()
        assertEquals(empty, empty.swapComparison(provider))
    }

    private fun offlineMap(name: String = "alps.map"): PhoneOfflineMap = PhoneOfflineMap(java.io.File(name))
}
