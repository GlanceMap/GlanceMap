package com.glancemap.glancemapwearos.core.service.transfer.runtime

import com.glancemap.glancemapwearos.core.service.diagnostics.TransferDiagnostics
import java.util.concurrent.atomic.AtomicReference

internal enum class TransferTerminalOutcome {
    DONE,
    ERROR,
    TIMEOUT,
    CANCELLED,
}

internal class TransferTerminalState(
    private val transferId: String = "",
) {
    private val outcome = AtomicReference<TransferTerminalOutcome?>(null)

    fun tryClaim(next: TransferTerminalOutcome): Boolean {
        val won = outcome.compareAndSet(null, next)
        TransferDiagnostics.log(
            "Terminal",
            "event=terminal_claim transferId=${transferId.ifBlank { "na" }} requested=$next " +
                "won=$won existing=${if (won) "na" else outcome.get()}",
        )
        return won
    }

    fun current(): TransferTerminalOutcome? = outcome.get()
}

internal fun foregroundTimeoutMatchesStartId(
    timeoutStartId: Int,
    foregroundOwnerStartId: Int,
): Boolean = timeoutStartId == foregroundOwnerStartId
