package com.glancemap.glancemapcompanionapp.transfer

import android.net.Uri
import android.util.Log
import com.glancemap.glancemapcompanionapp.transfer.datalayer.DataLayerPaths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Phone-side helper: requests deletion of an existing file on watch by file name.
 *
 * Contract:
 * Phone -> Watch:  PATH_DELETE_FILE
 *   JSON: { "id": "<requestId>", "name": "<uriEncodedFileName>" }
 *
 * Watch -> Phone: PATH_DELETE_FILE_RESULT
 *   JSON: { "id": "<requestId>", "ok": true/false }
 */
class WatchFileDeleteRequester(
    private val sendMessage: suspend (nodeId: String, path: String, payload: ByteArray) -> Unit,
) {
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    suspend fun delete(
        nodeId: String,
        fileName: String,
    ): Boolean? {
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Boolean>()
        pendingRequests[requestId] = deferred

        val payload =
            JSONObject()
                .put("id", requestId)
                .put("name", Uri.encode(fileName))
                .toString()
                .toByteArray(Charsets.UTF_8)

        return try {
            sendMessage(nodeId, DataLayerPaths.PATH_DELETE_FILE, payload)
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            Log.e(TAG, "Delete request failed for '$fileName'", e)
            null
        } finally {
            pendingRequests.remove(requestId)?.cancel()
        }
    }

    fun handleDeleteResult(data: ByteArray) {
        runCatching {
            val json = JSONObject(String(data, Charsets.UTF_8))
            val requestId = json.optString("id", "")
            if (requestId.isBlank()) return

            val ok = json.optBoolean("ok", false)
            pendingRequests.remove(requestId)?.complete(ok)
        }.onFailure {
            Log.w(TAG, "Failed to parse delete-file result", it)
        }
    }

    private companion object {
        private const val TAG = "WatchFileDeleteReq"
        private const val REQUEST_TIMEOUT_MS = 4_000L
    }
}
