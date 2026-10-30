package com.glancemap.glancemapcompanionapp.map

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapwearos.data.repository.maps.theme.ThemePackageException
import com.glancemap.glancemapwearos.data.repository.maps.theme.ThemePackageFailureReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun phoneCustomThemeControls(onImported: (String) -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by PhoneCustomThemes.changes.collectAsState()
    val packages = remember(revision) { PhoneCustomThemes.installed().groupBy { it.packageId } }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) {
            busy = true
            error = null
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { PhoneCustomThemes.import(context, uri) } }
                result.onSuccess { themes ->
                    themes.firstOrNull()?.let { onImported(it.id) }
                    onChanged()
                }.onFailure { error = phoneThemeImportError(it) }
                busy = false
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.map_custom_themes_title), style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.map_custom_theme_import))
        }
        if (busy) CircularProgressIndicator()
        error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        packages.forEach { (id, themes) ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(themes.joinToString { it.displayName }, modifier = Modifier.weight(1f))
                TextButton(onClick = { confirmDelete = id }, enabled = !busy) {
                    Text(stringResource(R.string.map_content_action_delete))
                }
            }
        }
    }
    confirmDelete?.let { packageId ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.map_custom_theme_delete_title)) },
            text = { Text(stringResource(R.string.map_custom_theme_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    busy = true
                    error = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { PhoneCustomThemes.delete(packageId) } }
                        onChanged()
                        result.onFailure { error = R.string.map_custom_theme_delete_failed }
                        busy = false
                    }
                }) { Text(stringResource(R.string.map_content_action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.common_action_cancel)) }
            },
        )
    }
}

private fun phoneThemeImportError(error: Throwable): Int =
    when ((error as? ThemePackageException)?.reason) {
        ThemePackageFailureReason.MISSING_RESOURCES -> R.string.map_custom_theme_missing_resources
        ThemePackageFailureReason.TOO_LARGE -> R.string.map_custom_theme_too_large
        ThemePackageFailureReason.DAMAGED -> R.string.map_custom_theme_damaged
        else -> R.string.map_custom_theme_invalid
    }
