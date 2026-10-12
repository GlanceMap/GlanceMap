package com.glancemap.glancemapcompanionapp

import com.glancemap.glancemapcompanionapp.testsupport.englishCompanionText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.IllegalStateException
import java.util.concurrent.TimeoutException

class WatchConnectionUiSupportTest {
    @Test
    fun selectedWatchReachabilityMatchesAvailableWatchList() {
        val selected = WatchNode(id = "watch-1", displayName = "OnePlus Watch")
        val available =
            listOf(
                WatchNode(id = "watch-1", displayName = "OnePlus Watch"),
                WatchNode(id = "watch-2", displayName = "Other Watch"),
            )

        assertTrue(isSelectedWatchReachable(selected, available))
        assertFalse(isSelectedWatchReachable(selected, available.drop(1)))
        assertFalse(isSelectedWatchReachable(null, available))
    }

    @Test
    fun targetNodeNotConnectedIsMappedToFriendlyDisconnectedMessage() {
        val message =
            normalizeWatchMapsStatusMessage(
                ::englishCompanionText,
                IllegalStateException("4000: TARGET_NODE_NOT_CONNECTED"),
            )

        assertEquals(selectedWatchDisconnectedStatusMessage(::englishCompanionText), message)
    }

    @Test
    fun timeoutIsMappedToFriendlyTimeoutMessage() {
        val message =
            normalizeWatchMapsStatusMessage(
                ::englishCompanionText,
                TimeoutException("Watch did not answer in time while reading maps."),
            )

        assertEquals(selectedWatchTimeoutStatusMessage(::englishCompanionText), message)
    }
}
