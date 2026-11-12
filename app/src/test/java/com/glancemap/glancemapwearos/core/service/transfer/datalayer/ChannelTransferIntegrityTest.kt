package com.glancemap.glancemapwearos.core.service.transfer.datalayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelTransferIntegrityTest {
    @Test
    fun mapTransferRejectsMissingOrNonPositiveExpectedSize() {
        assertEquals(
            "missing_expected_size",
            channelTransferIntegrityFailureReason("region.map", null, "expected-hash"),
        )
        assertEquals(
            "missing_expected_size",
            channelTransferIntegrityFailureReason("region.map", 0L, "expected-hash"),
        )
    }

    @Test
    fun channelTransferRejectsMissingExpectedChecksum() {
        assertEquals(
            "missing_expected_checksum",
            channelTransferIntegrityFailureReason("route.gpx", 0L, null),
        )
    }

    @Test
    fun mapWithKnownPositiveSizeAndChecksumIsEligibleForReceive() {
        assertNull(channelTransferIntegrityFailureReason("region.map", 4_567_781L, "expected-hash"))
    }

    @Test
    fun legitimateEmptyNonMapFileCanKeepZeroExpectedSize() {
        assertNull(channelTransferIntegrityFailureReason("empty.gpx", 0L, "empty-file-hash"))
    }

    @Test
    fun receiverFailureReasonsAreNormalizedWithoutPayloadDetails() {
        assertEquals("unexpected_eof", channelTransferFailureReason("INCOMPLETE_FILE: expected=10 got=4"))
        assertEquals("unexpected_extra_data", channelTransferFailureReason("TOO_MUCH_DATA: expected=4 got>5"))
        assertEquals("checksum_compute_failed", channelTransferFailureReason("checksum_compute_failed"))
        assertEquals("transfer_failed", channelTransferFailureReason("/private/storage/path.map could not be opened"))
    }
}
