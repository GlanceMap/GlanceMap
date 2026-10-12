@file:Suppress("FunctionNaming")

package com.glancemap.glancemapcompanionapp

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.glancemap.glancemapcompanionapp.R

private const val REQUIRED_MAP_PICKER_VULKAN_VERSION = 0x400003

internal fun Context.hasNativeMapPickerGraphicsSupport(): Boolean =
    packageManager.hasSystemFeature(
        PackageManager.FEATURE_VULKAN_HARDWARE_VERSION,
        REQUIRED_MAP_PICKER_VULKAN_VERSION,
    )

@Composable
internal fun NativeMapPickerUnavailableDialog(
    title: String,
    onDismiss: () -> Unit,
) {
    val textResources = LocalResources.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(20.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(
                    textResources.getString(R.string.map_picker_this_device_does_not_report_vulkan_support),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Button(onClick = onDismiss) {
                        Text(textResources.getString(R.string.common_action_ok))
                    }
                }
            }
        }
    }
}
