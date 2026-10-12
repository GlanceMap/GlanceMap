package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File

class PhoneMapCameraStateTest {
    private val base = PhoneMapCameraRenderer.BASE
    private val comparison = PhoneMapCameraRenderer.COMPARISON
    private val initial = PhoneMapCameraSnapshot(46.0, 7.0, 12.75, 45f)

    @Test
    fun comparisonGestureIgnoresClampedBaseEchoAndNeverSynchronizesItsOwnFling() {
        val gesture = initial.copy(longitude = 7.1, zoom = 15.75)
        val authoritative = PhoneMapCameraState(initial).beginInput(comparison).update(comparison, gesture, true)
        val echoed = authoritative.update(base, gesture.copy(zoom = 14.0), true)

        assertSame(authoritative, echoed)
        assertEquals(gesture, echoed.cameraForSynchronization(base))
        assertNull(echoed.cameraForSynchronization(comparison))
        val fling = gesture.copy(longitude = 7.2)
        val nextFrame = echoed.update(comparison, fling, true)
        assertEquals(fling, nextFrame.camera)
        assertNull(nextFrame.cameraForSynchronization(comparison))
    }

    @Test
    fun explicitFollowZoomOrGpxFitCanTakeAuthorityWithoutAcceptingComparisonEchoes() {
        val gestured = PhoneMapCameraState(initial).beginInput(comparison)
        val requested = initial.copy(latitude = 47.0, zoom = 13.25)
        val appInput = gestured.beginInput(base).update(base, requested, true)
        val passive = appInput.update(comparison, requested.copy(zoom = 13.0), true)

        assertSame(appInput, passive)
        assertEquals(requested, passive.cameraForSynchronization(comparison))
        assertNull(passive.cameraForSynchronization(base))
        val animation = passive.update(base, requested.copy(zoom = 13.5), true)
        assertEquals(13.5, animation.camera.zoom, 0.0)
        val resumedGesture = animation.beginInput(comparison).update(comparison, initial, true)
        assertEquals(initial, resumedGesture.camera)
    }

    @Test
    fun inactiveComparisonCannotPublishAfterClearOrAtFullTransparency() {
        val previous = PhoneMapCameraState(initial).beginInput(comparison)
        assertSame(previous, previous.update(comparison, initial.copy(zoom = 18.0), false))
        val restoredBase = previous.update(base, initial.copy(zoom = 13.0), false)
        assertEquals(base, restoredBase.publisher)
        assertNull(restoredBase.cameraForSynchronization(base))
        assertSame(restoredBase, restoredBase.update(comparison, initial.copy(zoom = 19.0), false))
    }

    @Test
    fun passiveStyleReloadCannotReplaceTheAuthoritativeCamera() {
        val gesture = initial.copy(latitude = 47.0, zoom = 15.75)
        val state = PhoneMapCameraState(initial).beginInput(comparison).update(comparison, gesture, true)
        assertSame(state, state.update(base, initial, true))
        assertEquals(gesture, state.cameraForSynchronization(base))
    }

    @Test
    fun allComparisonCombinationsPreserveTheirProgrammaticOwnersAtEveryTransparency() {
        val offlineMap = PhoneOfflineMap(File("alps.map"), contentRevision = "test")
        val combinations =
            listOf(
                PhoneMapSource.Online to PhoneMapComparisonLayer.Online(PhoneOnlineMapSource.OPEN_STREET_MAP),
                PhoneMapSource.Online to PhoneMapComparisonLayer.Offline(offlineMap),
                PhoneMapSource.Offline(offlineMap) to
                    PhoneMapComparisonLayer.Online(PhoneOnlineMapSource.OPEN_STREET_MAP),
            )
        combinations.forEach { (source, layer) ->
            listOf(0f, 25f, 50f, 75f, 100f).forEach { transparency ->
                assertCameraSequence(source, layer, transparency)
            }
        }
    }

    private fun assertCameraSequence(
        source: PhoneMapSource,
        layer: PhoneMapComparisonLayer,
        transparency: Float,
    ) {
        val visible = PhoneMapComparisonState(layer, transparency).overlayAlpha() > 0f
        val gestureOwner = if (visible) comparison else base
        val passiveRenderer = if (visible) base else comparison
        val gesture = initial.copy(longitude = 7.1, zoom = 14.25)
        val state = PhoneMapCameraState(initial).beginInput(gestureOwner).update(gestureOwner, gesture, visible)
        assertSame(state, state.update(passiveRenderer, gesture.copy(zoom = 13.0), visible))
        assertNull(state.cameraForSynchronization(gestureOwner))

        val programmaticOwner =
            if (phoneMapComparisonOwnsFollowAndCommands(source, visible && layer is PhoneMapComparisonLayer.Online)) {
                comparison
            } else {
                base
            }
        val requested = initial.copy(latitude = 47.0, zoom = 12.5)
        val appInput = state.beginInput(programmaticOwner).update(programmaticOwner, requested, visible)
        assertEquals(requested, appInput.camera)
        assertNull(appInput.cameraForSynchronization(programmaticOwner))
    }
}
