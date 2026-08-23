package com.glancemap.glancemapwearos.presentation.features.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OamDownloadForegroundServiceTimeoutTest {
    @Test
    fun `timeout during download claims a resumable pause`() {
        val gate = OamDownloadOperationGate()
        val session = gate.begin(startId = 41)
        val paused = runningPlan().copy(status = OamPersistedDownloadStatus.PAUSED)

        assertSame(session, gate.claimTimeout(startId = 41))
        assertEquals(OamDownloadOperationEnd.TIMEOUT, gate.terminalEndFor(session))
        assertEquals(OamPersistedDownloadStatus.PAUSED, paused.status)
    }

    @Test
    fun `resume after timeout during extraction preserves existing position`() {
        val gate = OamDownloadOperationGate()
        val session = gate.begin(startId = 42)
        val paused = runningPlan(nextAreaIndex = 1).copy(status = OamPersistedDownloadStatus.PAUSED)

        gate.claimTimeout(startId = 42)
        val resumed = resumedOrNewDownloadPlan(paused, paused.areaIds, paused.selection)

        assertEquals(OamDownloadOperationEnd.TIMEOUT, gate.terminalEndFor(session))
        assertEquals(OamPersistedDownloadStatus.RUNNING, resumed.status)
        assertEquals(1, resumed.nextAreaIndex)
    }

    @Test
    fun `timeout after completion does nothing`() {
        val gate = OamDownloadOperationGate()
        val session = gate.begin(startId = 43)

        assertSame(session, gate.claim(session, OamDownloadOperationEnd.COMPLETE))
        assertTrue(gate.finish(session))
        assertNull(gate.claimTimeout(startId = 43))
    }

    @Test
    fun `timeout and user pause race has one terminal owner`() {
        val gate = OamDownloadOperationGate()
        val session = gate.begin(startId = 44)

        assertSame(session, gate.claimActive(OamDownloadOperationEnd.PAUSE))
        assertNull(gate.claimTimeout(startId = 44))
        assertEquals(OamDownloadOperationEnd.PAUSE, gate.terminalEndFor(session))
    }

    @Test
    fun `timeout claims keepalive cleanup exactly once`() {
        val gate = OamDownloadOperationGate()
        val session = gate.begin(startId = 45)

        assertSame(session, gate.claimTimeout(startId = 45))
        assertNull(gate.claimTimeout(startId = 45))
    }

    @Test
    fun `stale timeout cannot affect a restarted operation`() {
        val gate = OamDownloadOperationGate()
        val first = gate.begin(startId = 46)
        gate.claim(first, OamDownloadOperationEnd.PAUSE)
        gate.finish(first)
        val restarted = gate.begin(startId = 47)

        assertNull(gate.claimTimeout(startId = 46))
        assertSame(restarted, gate.claimTimeout(startId = 47))
    }

    private fun runningPlan(nextAreaIndex: Int = 0): OamPersistedDownloadPlan =
        OamPersistedDownloadPlan(
            areaIds = listOf("area"),
            selection = OamDownloadSelection(includeMap = true),
            nextAreaIndex = nextAreaIndex,
        )
}
