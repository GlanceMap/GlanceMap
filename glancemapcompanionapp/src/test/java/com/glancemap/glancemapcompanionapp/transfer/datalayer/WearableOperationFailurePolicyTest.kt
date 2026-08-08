package com.glancemap.glancemapcompanionapp.transfer.datalayer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class WearableOperationFailurePolicyTest {
    @Test
    fun `cancellation propagates without reporting a Wear API failure`() =
        runBlocking {
            var reported = false

            try {
                runCancellableWearableOperation<String>(
                    operation = { throw CancellationException("service stopped") },
                    onFailure = { reported = true },
                )
                fail("CancellationException should propagate")
            } catch (_: CancellationException) {
                // Expected: lifecycle cancellation must not be converted into an API error.
            }

            assertFalse(reported)
        }

    @Test
    fun `non cancellation failure is reported as a recoverable null result`() =
        runBlocking {
            var reported: Throwable? = null

            val result =
                runCancellableWearableOperation<String>(
                    operation = { throw IllegalStateException("binder unavailable") },
                    onFailure = { reported = it },
                )

            assertNull(result)
            assertEquals("binder unavailable", reported?.message)
        }
}
