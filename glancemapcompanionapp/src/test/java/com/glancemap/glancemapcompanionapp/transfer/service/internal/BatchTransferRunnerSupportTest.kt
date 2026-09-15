package com.glancemap.glancemapcompanionapp.transfer.service.internal

import com.glancemap.glancemapcompanionapp.transfer.TransferStrategyFactory
import com.glancemap.glancemapcompanionapp.transfer.strategy.HttpTransferServer
import com.glancemap.glancemapcompanionapp.transfer.strategy.TransferResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchTransferRunnerSupportTest {
    @Test
    fun `detects different private subnet http failure`() {
        val message =
            "Cannot reach phone HTTP server at http://192.168.43.1:45558/ " +
                "(failed to connect to /192.168.43.1 (port 45558) from /192.168.0.241 " +
                "(port 36600) after 1500ms)"

        assertTrue(isLikelyDifferentSubnetHttpFailure(message))
    }

    @Test
    fun `ignores same subnet http failure`() {
        val message =
            "Cannot reach phone HTTP server at http://192.168.0.189:45055/ " +
                "(failed to connect to /192.168.0.189 (port 45055) from /192.168.0.241 " +
                "(port 36600) after 1500ms)"

        assertFalse(isLikelyDifferentSubnetHttpFailure(message))
    }

    @Test
    fun `detects explicit phone http unreachable failure on same subnet`() {
        val message =
            "Cannot reach phone HTTP server at http://192.168.0.189:45055/ " +
                "(failed to connect to /192.168.0.189 (port 45055) from /192.168.0.241 " +
                "(port 36600) after 1500ms)"

        assertTrue(isExplicitPhoneHttpUnreachableFailure(message))
    }

    @Test
    fun `treats different private subnets as unreachable peers`() {
        assertTrue(areClearlyDifferentPrivateSubnets("192.168.43.1", "192.168.0.241"))
        assertTrue(areClearlyDifferentPrivateSubnets("10.0.0.5", "10.0.1.8"))
        assertFalse(areClearlyDifferentPrivateSubnets("192.168.0.5", "192.168.0.241"))
    }

    @Test
    fun `extracts transferred bytes from retry result message`() {
        val message = "HTTP_STALLED_RETRY: file=Bayern_oam.osm.map idleMs=30574 timeoutMs=75000 sent=985631096"

        assertEquals(985631096L, extractTransferredBytesFromRetryMessage(message))
    }

    @Test
    fun `detects meaningful retry progress for large file`() {
        assertTrue(
            hasMeaningfulFreshHttpRetryProgress(
                previousSentBytes = 766185744L,
                currentSentBytes = 985631096L,
                totalSize = 1_032_008_362L,
            ),
        )
        assertFalse(
            hasMeaningfulFreshHttpRetryProgress(
                previousSentBytes = 766185744L,
                currentSentBytes = 770000000L,
                totalSize = 1_032_008_362L,
            ),
        )
    }

    @Test
    fun `allows large channel rescue fallback when phone http server is unreachable`() {
        val result =
            TransferResult(
                success = false,
                message =
                    "Cannot reach phone HTTP server at http://192.168.0.189:45055/ " +
                        "(failed to connect to /192.168.0.189 (port 45055) from /192.168.0.241 " +
                        "(port 36600) after 1500ms)",
            )

        assertTrue(
            shouldFallbackToChannel(
                strategy = HttpTransferServer(),
                fileSize = TransferStrategyFactory.CHANNEL_FALLBACK_MAX_BYTES + 1L,
                result = result,
            ),
        )
        assertTrue(shouldAllowLargeChannelRescueFallback(result))
        assertTrue(shouldPreferChannelForRemainingBatch(result))
    }

    @Test
    fun `does not poison remaining batch on recoverable http stall`() {
        val result =
            TransferResult(
                success = false,
                message = "HTTP_STALLED_RETRY: file=Bayern_oam.osm.map idleMs=45789 timeoutMs=45000 sent=624951296",
            )

        assertFalse(shouldPreferChannelForRemainingBatch(result))
    }

    @Test
    fun `does not poison remaining batch on generic server error`() {
        val result =
            TransferResult(
                success = false,
                message = "Server Error: CHECKSUM_MISMATCH",
            )

        assertFalse(shouldPreferChannelForRemainingBatch(result))
    }

    @Test
    fun `poisons remaining batch when watch never connects to http server`() {
        val result =
            TransferResult(
                success = false,
                message = "${HttpTransferServer.RESULT_HTTP_NO_FIRST_REQUEST_PREFIX} detail=Watch did not connect",
            )

        assertTrue(shouldPreferChannelForRemainingBatch(result))
        assertTrue(
            shouldFallbackToChannel(
                strategy = HttpTransferServer(),
                fileSize = TransferStrategyFactory.CHANNEL_FALLBACK_MAX_BYTES,
                result = result,
            ),
        )
        assertFalse(
            shouldFallbackToChannel(
                strategy = HttpTransferServer(),
                fileSize = TransferStrategyFactory.CHANNEL_FALLBACK_MAX_BYTES + 1L,
                result = result,
            ),
        )
    }

    @Test
    fun `foreground quota failure is terminal and never falls back to channel`() {
        val result =
            TransferResult(
                success = false,
                message = "FGS_DATA_SYNC_QUOTA_EXHAUSTED",
            )

        assertTrue(isForegroundServiceQuotaExhaustedFailure(result.message))
        assertTrue(isTerminalForegroundServiceFailure(result.message))
        assertFalse(
            shouldFallbackToChannel(
                strategy = HttpTransferServer(),
                fileSize = TransferStrategyFactory.CHANNEL_FALLBACK_MAX_BYTES,
                result = result,
            ),
        )
        assertEquals(
            "The watch cannot start another background file transfer right now. " +
                "Open GlanceMap on the watch and try again.",
            toUserFacingTransferError(result),
        )
    }

    @Test
    fun `generic foreground rejection is also terminal for same foreground transport`() {
        val result = TransferResult(false, "FGS_START_NOT_ALLOWED")

        assertTrue(isTerminalForegroundServiceFailure(result.message))
        assertFalse(
            shouldFallbackToChannel(
                strategy = HttpTransferServer(),
                fileSize = TransferStrategyFactory.CHANNEL_FALLBACK_MAX_BYTES,
                result = result,
            ),
        )
    }

    @Test
    fun `data sync timeout is terminal and never falls back or retries`() {
        val result = TransferResult(false, "FGS_DATA_SYNC_TIMEOUT")

        assertTrue(isForegroundServiceDataSyncTimeoutFailure(result.message))
        assertTrue(isTerminalForegroundServiceFailure(result.message))
        assertFalse(shouldConsiderFreshHttpRetry(result))
        assertFalse(
            shouldFallbackToChannel(
                strategy = HttpTransferServer(),
                fileSize = TransferStrategyFactory.CHANNEL_FALLBACK_MAX_BYTES,
                result = result,
            ),
        )
        assertEquals(
            "The watch reached Android's background transfer time limit. " +
                "Open GlanceMap on the watch and try the transfer again.",
            toUserFacingTransferError(result),
        )
    }

    @Test
    fun `resume failure replaces stale resume text with the error`() {
        val text = terminalTransferErrorProgressText("Watch did not reconnect")

        assertEquals("Error: Watch did not reconnect", text)
        assertFalse(text.contains("resuming", ignoreCase = true))
    }

    @Test
    fun `pausing again replaces resume text with paused state`() {
        val text =
            buildManualPauseProgressText(
                filePrefix = "File 1/1: map.map",
                existingText = "Resuming current file from partial…\nHTTP: 214.00 MiB / 931.53 MiB",
            )

        assertTrue(text.contains("Paused. Ready to resume from partial."))
        assertFalse(text.contains("Resuming", ignoreCase = true))
    }

    @Test
    fun `ordinary network failure remains eligible for fresh http retry`() {
        assertTrue(
            shouldConsiderFreshHttpRetry(
                TransferResult(false, "Socket timeout while connecting to phone"),
            ),
        )
    }

    @Test
    fun `routing packs are replaceable transfer targets`() {
        assertTrue(isReplaceableTransferFileName("E5_N45.rd5"))
        assertFalse(isReplaceableTransferFileName("Alps.map"))
        assertFalse(isReplaceableTransferFileName("track.gpx"))
    }

    @Test
    fun `supports watch dem tile file names on companion`() {
        assertTrue(isSupportedTransferFileName("N45E006.hgt"))
        assertTrue(isSupportedTransferFileName("N45E006.hgt.zip"))
        assertTrue(isSupportedTransferFileName("N45E006.hgt.gz"))
        assertTrue(isDemTransferFile("s01w002.HGT.GZ"))
        assertTrue(isDemTransferFile("s01w002.HGT.ZIP"))
        assertFalse(isMapLikeTransferFile("N45E006.hgt.zip"))
        assertFalse(isSupportedTransferFileName("N45E006.zip"))
    }
}
