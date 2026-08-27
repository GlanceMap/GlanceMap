package com.glancemap.glancemapcompanionapp

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glancemap.glancemapcompanionapp.filepicker.FilePickerScreen
import com.glancemap.glancemapcompanionapp.layout.companionLayoutProvider
import com.glancemap.glancemapcompanionapp.livetracking.LiveTrackingOpenIntentContract
import com.glancemap.glancemapcompanionapp.transfer.WatchGpxSaveIntentContract
import com.glancemap.glancemapcompanionapp.ui.theme.GlanceMapTheme

class MainActivityMobile : ComponentActivity() {
    private var incomingUris by mutableStateOf<List<Uri>>(emptyList())
    private var incomingIntentToken by mutableStateOf(0L)
    private var pendingWatchGpxSaveFiles by mutableStateOf<List<GeneratedPhoneFile>>(emptyList())
    private var pendingWatchGpxSaveToken by mutableStateOf(0L)
    private var openLiveTrackingToken by mutableStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleLaunchIntent(intent)

        setContent {
            GlanceMapTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val vm: FileTransferViewModel = viewModel()

                    LaunchedEffect(incomingIntentToken) {
                        val uris = incomingUris
                        if (uris.isEmpty()) return@LaunchedEffect

                        uris.forEach { uri ->
                            try {
                                val takeFlags = (
                                    intent.flags and
                                        (
                                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                                        )
                                )
                                if (takeFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
                                    contentResolver.takePersistableUriPermission(
                                        uri,
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                    )
                                }
                            } catch (_: Exception) {
                                // Ignore providers that do not expose persistable permissions.
                            }
                        }

                        vm.loadFilesFromUris(this@MainActivityMobile, uris)
                    }

                    companionLayoutProvider(activity = this@MainActivityMobile) {
                        FilePickerScreen(
                            viewModel = vm,
                            openSendToWatchToken = incomingIntentToken,
                            openLiveTrackingToken = openLiveTrackingToken,
                            watchGpxSaveToken = pendingWatchGpxSaveToken,
                            watchGpxSaveFiles = pendingWatchGpxSaveFiles,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        when (intent?.action) {
            WatchGpxSaveIntentContract.ACTION_SAVE_WATCH_GPX -> updateWatchGpxSaveRequest(intent)
            LiveTrackingOpenIntentContract.ACTION_OPEN_LIVE_TRACKING -> openLiveTracking()
            else -> updateIncomingSelection(intent)
        }
    }

    private fun openLiveTracking() {
        openLiveTrackingToken = SystemClock.elapsedRealtimeNanos()
    }

    private fun updateWatchGpxSaveRequest(intent: Intent) {
        val uri = intent.data ?: return
        val fileName =
            intent
                .getStringExtra(WatchGpxSaveIntentContract.EXTRA_FILE_NAME)
                ?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment
                ?: "watch-track.gpx"
        pendingWatchGpxSaveFiles = listOf(GeneratedPhoneFile(uri = uri, fileName = fileName))
        pendingWatchGpxSaveToken = SystemClock.elapsedRealtimeNanos()
    }

    private fun updateIncomingSelection(intent: Intent?) {
        logIncomingIntent(intent)
        val uris = extractIncomingUris(intent)
        if (uris.isEmpty()) return
        incomingUris = uris
        incomingIntentToken = SystemClock.elapsedRealtimeNanos()
    }

    private fun extractIncomingUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()

        val uris = LinkedHashSet<Uri>()
        intent.data?.let(uris::add)

        val stream: Uri? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
        when (stream) {
            null -> Unit
            else -> uris += stream
        }

        val extraStreams =
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
            }.getOrNull().orEmpty()
        uris += extraStreams

        val clipData = intent.clipData
        if (clipData != null) {
            for (index in 0 until clipData.itemCount) {
                clipData.getItemAt(index)?.uri?.let(uris::add)
            }
        }

        return uris.toList()
    }

    private fun logIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val data = intent.data
        Log.d(
            "GpxOpenIntent",
            "action=${intent.action} type=${intent.type ?: "none"} " +
                "scheme=${data?.scheme ?: "none"} host=${data?.host ?: "none"} " +
                "path=${data?.path ?: "none"} flags=${intent.flags}",
        )
    }
}
