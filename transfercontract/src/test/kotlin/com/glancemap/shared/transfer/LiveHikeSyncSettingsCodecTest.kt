package com.glancemap.shared.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveHikeSyncSettingsCodecTest {
    @Test
    fun `codec round trips enabled and disabled settings`() {
        assertTrue(LiveHikeSyncSettingsCodec.decode(LiveHikeSyncSettingsCodec.encode(true)) == true)
        assertEquals(false, LiveHikeSyncSettingsCodec.decode(LiveHikeSyncSettingsCodec.encode(false)))
    }

    @Test
    fun `codec rejects malformed settings`() {
        assertNull(LiveHikeSyncSettingsCodec.decode(byteArrayOf()))
        assertNull(LiveHikeSyncSettingsCodec.decode(byteArrayOf(2)))
        assertNull(LiveHikeSyncSettingsCodec.decode(byteArrayOf(1, 0)))
    }
}
