package com.glancemap.glancemapcompanionapp.transfer.service.internal

import com.glancemap.glancemapcompanionapp.transfer.util.TransferUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionPreparationTest {
    @Test
    fun `slow metadata lookup runs on IO and preserves order names and unknown sizes`() =
        runBlocking {
            val mainThread = Thread.currentThread()
            val resolverThreads = mutableListOf<Thread>()
            val convertedUris = listOf("converted.poi", "shared-route", "map.map", "ignored.txt", "missing.poi")

            val selection =
                prepareSelectionMetadata(convertedUris) { uri ->
                    resolverThreads += Thread.currentThread()
                    Thread.sleep(15L)
                    when (uri) {
                        "converted.poi" -> "refuges.poi" to 412L
                        "shared-route" ->
                            TransferUtils.resolveTransferDisplayName("Shared route", "application/gpx+xml") to -1L
                        "map.map" -> "regional.map" to 0L
                        "ignored.txt" -> "notes.txt" to 83L
                        else -> null
                    }
                }

            assertEquals(5, resolverThreads.size)
            assertTrue(resolverThreads.all { it !== mainThread })
            assertEquals(
                listOf(
                    SelectionFileMetadata("converted.poi", "refuges.poi", 412L),
                    SelectionFileMetadata("shared-route", "Shared route.gpx", -1L),
                    SelectionFileMetadata("map.map", "regional.map", 0L),
                ),
                selection.items,
            )
            assertEquals(2, selection.skippedCount)
        }

    @Test
    fun `selection request state rejects stale work and keeps delayed binding pending latest`() {
        val requests = SelectionRequestState<String>()
        val firstRequest = requests.beginRequest()
        assertTrue(requests.storePending(firstRequest, "first"))

        val latestRequest = requests.beginRequest()
        assertFalse(requests.isCurrent(firstRequest))
        assertFalse(requests.storePending(firstRequest, "stale"))
        assertTrue(requests.isCurrent(latestRequest))
        assertTrue(requests.canPublish(latestRequest, isTransferring = false))
        assertFalse(requests.canPublish(latestRequest, isTransferring = true))
        assertTrue(requests.storePending(latestRequest, "latest"))

        val delayedBindingSelection = requests.takePending()
        assertEquals(SelectionRequestState.Pending(latestRequest, "latest"), delayedBindingSelection)
        assertNull(requests.takePending())
    }

    @Test
    fun `clear invalidates preparation and drops a pending selection`() {
        val requests = SelectionRequestState<String>()
        val requestId = requests.beginRequest()
        assertTrue(requests.storePending(requestId, "selected"))

        requests.invalidate()

        assertFalse(requests.isCurrent(requestId))
        assertFalse(requests.canPublish(requestId, isTransferring = false))
        assertNull(requests.takePending())
    }
}
