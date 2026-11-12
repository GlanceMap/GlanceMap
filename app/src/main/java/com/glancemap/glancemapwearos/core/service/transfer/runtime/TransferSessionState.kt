package com.glancemap.glancemapwearos.core.service.transfer.runtime

import com.glancemap.glancemapwearos.core.service.transfer.storage.partialFileUseGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex

internal class TransferSessionState {
    data class ActiveHttpTransfer(
        val transferId: String,
        val fileName: String,
        val sourceNodeId: String,
        val job: Job?,
    )

    val transferMutex = Mutex()

    private val lock = Any()

    @Volatile
    private var activeTransferId: String? = null

    @Volatile
    private var activeTransferJob: Job? = null

    private val jobsById = mutableMapOf<String, Job>()
    private val fileNamesById = mutableMapOf<String, String>()
    private val sourceNodesById = mutableMapOf<String, String>()
    private val activeHttpTransfersByKey = mutableMapOf<String, ActiveHttpTransfer>()

    fun registerActiveTransfer(
        transferId: String,
        job: Job?,
        fileName: String,
        sourceNodeId: String,
    ) {
        if (job == null) return
        partialFileUseGuard.protectUntilComplete(job)
        synchronized(lock) {
            activeTransferId = transferId
            activeTransferJob = job
            jobsById[transferId] = job
            fileNamesById[transferId] = fileName
            sourceNodesById[transferId] = sourceNodeId
        }
    }

    fun clearActiveTransfer(transferId: String) {
        synchronized(lock) {
            if (activeTransferId == transferId) {
                activeTransferId = null
                activeTransferJob = null
            }
            jobsById.remove(transferId)
            fileNamesById.remove(transferId)
            sourceNodesById.remove(transferId)
            removeHttpTransferLocked(transferId)
        }
    }

    fun activeTransferId(): String? = activeTransferId

    fun activeTransferJob(): Job? = activeTransferJob

    fun fileNameForTransferId(transferId: String): String? =
        synchronized(lock) {
            fileNamesById[transferId]
        }

    fun registerHttpTransfer(transfer: ActiveHttpTransfer) {
        transfer.job?.let(partialFileUseGuard::protectUntilComplete)
        synchronized(lock) {
            activeHttpTransfersByKey[key(transfer.sourceNodeId, transfer.fileName)] = transfer
            transfer.job?.let { jobsById[transfer.transferId] = it }
            fileNamesById[transfer.transferId] = transfer.fileName
            sourceNodesById[transfer.transferId] = transfer.sourceNodeId
        }
    }

    fun currentHttpTransfer(
        sourceNodeId: String,
        fileName: String,
    ): ActiveHttpTransfer? =
        synchronized(lock) {
            activeHttpTransfersByKey[key(sourceNodeId, fileName)]
        }

    fun clearHttpTransfer(transferId: String) {
        synchronized(lock) {
            removeHttpTransferLocked(transferId)
        }
    }

    fun cancelTransferById(
        transferId: String,
        reason: String,
    ): Boolean {
        val job = synchronized(lock) { jobsById[transferId] } ?: return false
        job.cancel(CancellationException(reason))
        return true
    }

    fun cancelTransfersForFile(
        sourceNodeId: String,
        fileName: String,
        reason: String,
        excludeTransferId: String? = null,
    ): Int {
        val jobsToCancel =
            synchronized(lock) {
                val matchedIds =
                    fileNamesById.keys
                        .filter { it != excludeTransferId }
                        .filter { fileNamesById[it].equals(fileName, ignoreCase = true) }
                        .filter { sourceNodesById[it] == sourceNodeId }

                val jobs = matchedIds.mapNotNull { jobsById[it] }
                matchedIds.forEach { matchedId ->
                    if (activeTransferId == matchedId) {
                        activeTransferId = null
                        activeTransferJob = null
                    }
                    jobsById.remove(matchedId)
                    fileNamesById.remove(matchedId)
                    sourceNodesById.remove(matchedId)
                    removeHttpTransferLocked(matchedId)
                }
                jobs
            }

        jobsToCancel.forEach { job ->
            job.cancel(CancellationException(reason))
        }
        return jobsToCancel.size
    }

    private fun removeHttpTransferLocked(transferId: String) {
        val iterator = activeHttpTransfersByKey.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.transferId == transferId) {
                iterator.remove()
            }
        }
    }

    private fun key(
        sourceNodeId: String,
        fileName: String,
    ): String = "$sourceNodeId|${fileName.lowercase()}"
}
