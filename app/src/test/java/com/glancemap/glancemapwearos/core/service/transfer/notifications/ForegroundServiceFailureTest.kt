package com.glancemap.glancemapwearos.core.service.transfer.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundServiceFailureTest {
    @Test
    fun `quota rejection gets machine readable quota detail`() {
        assertEquals(
            FGS_DATA_SYNC_QUOTA_EXHAUSTED,
            foregroundStartFailureDetail(
                exceptionClassName = "android.app.ForegroundServiceStartNotAllowedException",
                sdkInt = 35,
                exceptionMessage = "Time limit already exhausted for foreground service type dataSync",
            ),
        )
    }

    @Test
    fun `other start not allowed rejection stays distinct from quota`() {
        assertEquals(
            FGS_START_NOT_ALLOWED,
            foregroundStartFailureDetail(
                exceptionClassName = "android.app.ForegroundServiceStartNotAllowedException",
                sdkInt = 35,
                exceptionMessage = "Background start not allowed",
            ),
        )
    }

    @Test
    fun `other foreground failures preserve exception class`() {
        assertEquals(
            "FGS_START_FAILED:IllegalStateException",
            foregroundStartFailureDetail("java.lang.IllegalStateException", 35),
        )
    }
}
