package com.glancemap.glancemapcompanionapp.livetracking

import org.junit.Assert.assertEquals
import org.junit.Test

class ArkluzTrackingEndpointTest {
    @Test
    fun restoresPersistedDevelopmentEndpoint() {
        assertEquals(
            ArkluzTrackingEndpoint.DEVELOPMENT,
            ArkluzTrackingEndpoint.fromPersistedName("DEVELOPMENT"),
        )
    }

    @Test
    fun restoresPersistedProductionEndpoint() {
        assertEquals(
            ArkluzTrackingEndpoint.PRODUCTION,
            ArkluzTrackingEndpoint.fromPersistedName("PRODUCTION"),
        )
    }

    @Test
    fun unknownOrMissingPersistedEndpointUsesProductionDefault() {
        assertEquals(
            ArkluzTrackingEndpoint.PRODUCTION,
            ArkluzTrackingEndpoint.fromPersistedName("unknown"),
        )
        assertEquals(
            ArkluzTrackingEndpoint.PRODUCTION,
            ArkluzTrackingEndpoint.fromPersistedName(null),
        )
    }

    @Test
    fun activeSessionUrlWinsOverNewlySelectedEndpoint() {
        assertEquals(
            ArkluzTrackingEndpoint.PRODUCTION.url,
            ArkluzTrackingEndpoint.resolveUrl(
                activeSessionUrl = ArkluzTrackingEndpoint.PRODUCTION.url,
                selectedEndpoint = ArkluzTrackingEndpoint.DEVELOPMENT,
            ),
        )
        assertEquals(
            ArkluzTrackingEndpoint.DEVELOPMENT.url,
            ArkluzTrackingEndpoint.resolveUrl(
                activeSessionUrl = ArkluzTrackingEndpoint.DEVELOPMENT.url,
                selectedEndpoint = ArkluzTrackingEndpoint.PRODUCTION,
            ),
        )
    }

    @Test
    fun selectedEndpointIsUsedWhenThereIsNoActiveSession() {
        assertEquals(
            ArkluzTrackingEndpoint.DEVELOPMENT.url,
            ArkluzTrackingEndpoint.resolveUrl(
                activeSessionUrl = null,
                selectedEndpoint = ArkluzTrackingEndpoint.DEVELOPMENT,
            ),
        )
    }
}
