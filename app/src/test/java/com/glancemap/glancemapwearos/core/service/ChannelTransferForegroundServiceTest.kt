package com.glancemap.glancemapwearos.core.service

import android.os.Parcel
import com.glancemap.glancemapwearos.core.service.transfer.datalayer.ChannelTransferAckOutcome
import com.glancemap.glancemapwearos.core.service.transfer.datalayer.ChannelTransferAdmissionGate
import com.glancemap.glancemapwearos.core.service.transfer.datalayer.ChannelTransferHandoff
import com.glancemap.glancemapwearos.core.service.transfer.datalayer.ChannelTransferHandoffRegistry
import com.glancemap.glancemapwearos.core.service.transfer.datalayer.missingChannelHandoffAck
import com.glancemap.glancemapwearos.core.service.transfer.notifications.FGS_START_NOT_ALLOWED
import com.glancemap.glancemapwearos.core.service.transfer.notifications.foregroundStartFailureDetail
import com.glancemap.glancemapwearos.core.service.transfer.runtime.TransferSessionState
import com.glancemap.glancemapwearos.core.service.transfer.runtime.TransferTerminalOutcome
import com.google.android.gms.wearable.ChannelClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelTransferForegroundServiceTest {
    @Test
    fun `queued channel admission does not promote or register while another transfer holds mutex`() =
        runTest {
            val mutex = Mutex()
            val gate = ChannelTransferAdmissionGate(mutex)
            val currentTransfer = Any()
            mutex.lock(currentTransfer)
            val sessionState = TransferSessionState()
            val existingJob = Job()
            sessionState.registerActiveTransfer("existing", existingJob, "existing.map", "phone")
            val events = mutableListOf<String>()
            val channelJob = Job()

            val queued =
                async {
                    val owner = gate.acquire()
                    events += "promote"
                    sessionState.registerActiveTransfer("channel", channelJob, "channel.map", "phone")
                    events += "register_active"
                    gate.release(owner)
                }
            runCurrent()

            assertTrue(events.isEmpty())
            assertTrue(mutex.isLocked)
            assertEquals("existing", sessionState.activeTransferId())

            mutex.unlock(currentTransfer)
            queued.await()

            assertEquals(listOf("promote", "register_active"), events)
            assertEquals("channel", sessionState.activeTransferId())
            sessionState.clearActiveTransfer("channel")
            existingJob.cancel()
            channelJob.cancel()
        }

    @Test
    fun `service destruction keeps admission until channel cleanup completes`() =
        runTest {
            val mutex = Mutex()
            val gate = ChannelTransferAdmissionGate(mutex)
            val admissionOwner = gate.acquire()
            val cleanupMayFinish = CompletableDeferred<Unit>()
            val cleanupStarted = CompletableDeferred<Unit>()
            val admissionReleased = AtomicBoolean(false)
            var releaseCalls = 0

            suspend fun releaseAdmissionOnce() {
                if (admissionReleased.compareAndSet(false, true)) {
                    releaseCalls++
                    gate.release(admissionOwner)
                }
            }

            val channelJob =
                async {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            cleanupStarted.complete(Unit)
                            cleanupMayFinish.await()
                            releaseAdmissionOnce()
                        }
                    }
                }
            val secondTransfer =
                async {
                    val secondOwner = gate.acquire()
                    gate.release(secondOwner)
                }
            runCurrent()

            channelJob.cancel(CancellationException("Channel transfer service destroyed"))
            cleanupStarted.await()
            runCurrent()

            assertFalse(secondTransfer.isCompleted)
            assertEquals(0, releaseCalls)

            cleanupMayFinish.complete(Unit)
            channelJob.join()
            secondTransfer.await()

            releaseAdmissionOnce()
            assertEquals(1, releaseCalls)
        }

    @Test
    fun `handoff is offered and taken by the dedicated transfer owner`() {
        val transferId = "channel-handoff-owner"
        val channel = FakeChannel(nodeId = "phone", path = "/transfer/$transferId/map.map")
        val handoff = ChannelTransferHandoff(channel = channel, expectedChecksum = "sha256")
        val owner = ChannelTransferSessionOwner()
        val job = Job()

        try {
            assertTrue(ChannelTransferHandoffRegistry.offer(transferId, handoff))
            val taken = ChannelTransferHandoffRegistry.take(transferId)
            assertNotNull(taken)
            assertSame(channel, taken?.channel)

            owner.begin(
                startId = 7,
                transferId = transferId,
                fileName = "map.map",
                sourceNodeId = channel.nodeId,
                notificationId = 7,
                job = job,
            )

            assertSame(job, owner.activeForTransferId(transferId)?.job)
        } finally {
            ChannelTransferHandoffRegistry.remove(transferId)
            job.cancel()
        }
    }

    @Test
    fun `listener destruction cannot cancel a transferred channel session`() {
        val owner = ChannelTransferSessionOwner()
        val channelJob = Job()
        val listenerJob = Job()

        owner.begin(
            startId = 8,
            transferId = "channel-transferred",
            fileName = "map.map",
            sourceNodeId = "phone",
            notificationId = 8,
            job = channelJob,
        )

        listenerJob.cancel(CancellationException("Data layer service destroyed"))

        assertTrue(channelJob.isActive)
        assertNotNull(owner.activeForTransferId("channel-transferred"))
        channelJob.cancel()
    }

    @Test
    fun `missing handoff produces one explicit error ack outcome`() {
        val outcomes = mutableListOf<ChannelTransferAckOutcome>()

        outcomes += missingChannelHandoffAck(sourceNodeId = "phone", transferId = "missing-channel")

        assertEquals(1, outcomes.size)
        val outcome = outcomes.single()
        assertEquals("phone", outcome.sourceNodeId)
        assertEquals("missing-channel", outcome.transferId)
        assertEquals("ERROR", outcome.status)
        assertEquals("CHANNEL_HANDOFF_MISSING", outcome.detail)
    }

    @Test
    fun `foreground service start rejection remains an explicit error path`() {
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
    fun `normal channel terminal claim produces one ack outcome`() {
        val owner = ChannelTransferSessionOwner()
        val job = Job()
        val transferId = "channel-terminal"
        val outcomes = mutableListOf<ChannelTransferAckOutcome>()
        owner.begin(9, transferId, "map.map", "phone", 9, job)

        if (owner.claim(transferId, TransferTerminalOutcome.DONE)) {
            outcomes += ChannelTransferAckOutcome("phone", transferId, "DONE", "")
        }
        if (owner.claim(transferId, TransferTerminalOutcome.ERROR)) {
            outcomes += ChannelTransferAckOutcome("phone", transferId, "ERROR", "failure")
        }

        assertEquals(1, outcomes.size)
        assertEquals("DONE", outcomes.single().status)
        job.cancel()
    }

    private class FakeChannel(
        private val nodeId: String,
        private val path: String,
    ) : ChannelClient.Channel {
        override fun getNodeId(): String = nodeId

        override fun getPath(): String = path

        override fun describeContents(): Int = 0

        override fun writeToParcel(
            destination: Parcel,
            flags: Int,
        ) = Unit
    }
}
