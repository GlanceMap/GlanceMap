package com.glancemap.glancemapcompanionapp.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CancellationException

class PhoneOfflineBundleDownloadSemanticsTest {
    @Test
    fun onlyAnExplicitRemote404IsCoverageUnavailable() {
        assertTrue(isPhoneOfflineRemoteUnavailableStatus(404))
        assertFalse(isPhoneOfflineRemoteUnavailableStatus(401))
        assertFalse(isPhoneOfflineRemoteUnavailableStatus(500))
    }

    @Test
    fun httpFailureRetainsItsStatusForTheOptionalTileDecision() {
        val failure = PhoneOfflineHttpException(404, "https://example.test/tile")

        assertEquals(404, failure.statusCode)
        assertTrue(isPhoneOfflineRemoteUnavailable(failure))
        assertTrue(isPhoneOfflineRemoteUnavailableStatus(failure.statusCode))
    }

    @Test
    fun localFailuresAndCancellationAreNotCoverageUnavailable() {
        assertFalse(isPhoneOfflineRemoteUnavailable(FileNotFoundException("local file missing")))
        assertFalse(isPhoneOfflineRemoteUnavailable(IOException("storage failure")))
        assertFalse(isPhoneOfflineRemoteUnavailable(CancellationException("cancelled")))
        assertFalse(isPhoneOfflineRemoteUnavailable(PhoneOfflineHttpException(401, "https://example.test/tile")))
        assertFalse(isPhoneOfflineRemoteUnavailable(PhoneOfflineHttpException(500, "https://example.test/tile")))
    }
}
