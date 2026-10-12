package com.glancemap.glancemapcompanionapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.content.ContextCompat
import com.glancemap.glancemapcompanionapp.R

@Composable
internal fun rememberMapPickerLocationAllowed(requestPermission: Boolean): Boolean {
    val textResources = LocalResources.current
    val context = LocalContext.current
    var allowed by remember {
        mutableStateOf(context.hasApproximateLocationPermission())
    }
    var showDisclosure by remember { mutableStateOf(false) }
    var disclosureHandled by remember { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            allowed = granted
        }

    LaunchedEffect(requestPermission, allowed, disclosureHandled) {
        if (requestPermission && !allowed && !disclosureHandled) {
            showDisclosure = true
        }
    }

    if (showDisclosure) {
        AlertDialog(
            onDismissRequest = {
                showDisclosure = false
                disclosureHandled = true
            },
            title = { Text(textResources.getString(R.string.map_picker_location_for_map_selection)) },
            text = {
                Text(
                    textResources.getString(R.string.map_picker_glancemap_uses_your_location_only_to_centre),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDisclosure = false
                        disclosureHandled = true
                        launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    },
                ) {
                    Text(textResources.getString(R.string.common_action_continue))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDisclosure = false
                        disclosureHandled = true
                    },
                ) {
                    Text(textResources.getString(R.string.common_action_cancel))
                }
            },
        )
    }

    return allowed
}

private fun Context.hasApproximateLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
