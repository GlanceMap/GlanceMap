package com.glancemap.glancemapcompanionapp.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.glancemap.glancemapcompanionapp.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private const val GPX_MIME_TYPE = "application/gpx+xml"

internal data class PhoneLibraryRouteSharing(
    val onShare: () -> Unit,
    val onExport: () -> Unit,
)

internal fun phoneLibraryRouteExportName(title: String): String {
    val trimmed = title.trim().ifBlank { "route" }
    return if (trimmed.endsWith(".gpx", ignoreCase = true)) trimmed else "$trimmed.gpx"
}

/** Registered outside the overflow menu, because the menu leaves composition before the save picker returns. */
@Composable
internal fun rememberLibraryRouteSharing(row: PhoneLibraryRowState): PhoneLibraryRouteSharing {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(GPX_MIME_TYPE)) { destination ->
            if (destination == null) return@rememberLauncherForActivityResult
            scope.launch {
                val exported = exportLibraryRoute(context, row.resolveRouteUri(), destination)
                val message = if (exported) R.string.library_route_exported else R.string.library_route_export_failed
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    return PhoneLibraryRouteSharing(
        onShare = {
            scope.launch {
                val uri = row.resolveRouteUri()
                if (uri == null) {
                    Toast.makeText(context, R.string.library_gpx_unavailable, Toast.LENGTH_SHORT).show()
                } else {
                    context.startActivity(libraryRouteShareIntent(context, uri))
                }
            }
        },
        onExport = { exportLauncher.launch(phoneLibraryRouteExportName(row.item.title)) },
    )
}

@Composable
internal fun libraryRouteShareItems(
    sharing: PhoneLibraryRouteSharing,
    onClose: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.library_share_route)) },
        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
        onClick = {
            onClose()
            sharing.onShare()
        },
        modifier = Modifier.testTag("library-gpx-share"),
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.library_export_route)) },
        leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
        onClick = {
            onClose()
            sharing.onExport()
        },
        modifier = Modifier.testTag("library-gpx-export"),
    )
}

private suspend fun PhoneLibraryRowState.resolveRouteUri(): Uri? =
    when (val item = item) {
        is PhoneLibraryItem.Gpx -> routeUri(item.route.id)
        is PhoneLibraryItem.FolderGpx ->
            item.item.id
                .takeIf { it.startsWith("folder:") }
                ?.removePrefix("folder:")
                ?.let(Uri::parse)
        else -> null
    }

private fun libraryRouteShareIntent(
    context: Context,
    uri: Uri,
): Intent {
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = GPX_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    return Intent.createChooser(send, context.getString(R.string.gpx_notification_share_gpx))
}

private suspend fun exportLibraryRoute(
    context: Context,
    source: Uri?,
    destination: Uri,
): Boolean =
    source != null &&
        withContext(Dispatchers.IO) {
            try {
                val resolver = context.contentResolver
                resolver.openInputStream(source)?.use { input ->
                    resolver.openOutputStream(destination)?.use { output -> input.copyTo(output) }
                } != null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
