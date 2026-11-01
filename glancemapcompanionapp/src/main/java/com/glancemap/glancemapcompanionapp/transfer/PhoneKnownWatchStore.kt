package com.glancemap.glancemapcompanionapp.transfer

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.glancemap.glancemapcompanionapp.transfer.datalayer.DataLayerPaths
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

private const val WATCH_QUERY_TIMEOUT_MS = 5_000L

/** A watch stays known while out of range, so only phones that never had one lose watch actions. */
internal fun phoneWatchKnown(
    alreadyKnown: Boolean,
    reachableNow: Boolean,
    pairedNow: Boolean,
): Boolean = alreadyKnown || reachableNow || pairedNow

internal class PhoneKnownWatchStore(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("phone_known_watch", Context.MODE_PRIVATE)

    fun load(): Boolean = preferences.getBoolean(KEY_KNOWN, false)

    suspend fun refresh(reachableNow: Boolean): Boolean {
        val known = phoneWatchKnown(load(), reachableNow, reachableNow || hasPairedWatch())
        if (known) preferences.edit().putBoolean(KEY_KNOWN, true).apply()
        return known
    }

    // Phones without Wear OS support fail this query, which simply leaves the watch unknown.
    private suspend fun hasPairedWatch(): Boolean =
        try {
            withTimeoutOrNull(WATCH_QUERY_TIMEOUT_MS) {
                Wearable
                    .getCapabilityClient(appContext)
                    .getCapability(DataLayerPaths.WEAR_CAPABILITY, CapabilityClient.FILTER_ALL)
                    .await()
                    .nodes
                    .isNotEmpty()
            } == true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }

    private companion object {
        const val KEY_KNOWN = "known"
    }
}

@Composable
internal fun rememberPhoneWatchKnown(reachableNow: Boolean): Boolean {
    val context = LocalContext.current
    val store = remember(context) { PhoneKnownWatchStore(context) }
    var known by remember(store) { mutableStateOf(store.load()) }
    LaunchedEffect(store, reachableNow) {
        if (!known) known = store.refresh(reachableNow)
    }
    return known
}
