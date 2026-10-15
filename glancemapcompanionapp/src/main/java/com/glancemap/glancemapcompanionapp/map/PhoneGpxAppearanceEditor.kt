package com.glancemap.glancemapcompanionapp.map

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.glancemap.glancemapcompanionapp.R

@Composable
internal fun phoneGpxGeneralAppearanceSettings(
    state: MapToolsGpxState,
    actions: MapToolsPanelActions,
) {
    var draft by remember(state.settings) { mutableStateOf(state.settings) }
    var chooseScope by remember { mutableStateOf(false) }
    mapToolsGpxAppearanceSettings(draft) { draft = it }
    Button(onClick = { chooseScope = true }, enabled = draft != state.settings, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.gpx_appearance_apply))
    }
    if (chooseScope) {
        AlertDialog(
            onDismissRequest = { chooseScope = false },
            title = { Text(stringResource(R.string.gpx_appearance_apply_title)) },
            text = { Text(stringResource(R.string.gpx_appearance_apply_description)) },
            confirmButton = {
                TextButton(onClick = {
                    actions.onGpxSettingsChanged(draft)
                    chooseScope = false
                }) { Text(stringResource(R.string.gpx_appearance_apply_inherited)) }
            },
            dismissButton = {
                Column {
                    TextButton(onClick = {
                        actions.onGpxAppearanceAppliedToAll(draft)
                        chooseScope = false
                    }) { Text(stringResource(R.string.gpx_appearance_apply_all)) }
                    TextButton(onClick = { chooseScope = false }) { Text(stringResource(R.string.map_poi_cancel)) }
                }
            },
        )
    }
}

internal data class PhoneGpxAppearanceEditState(
    val inherited: PhoneMapGpxSettings,
    val override: PhoneGpxAppearanceOverride,
)

@Composable
internal fun phoneGpxAppearanceOverrideDialog(
    title: String,
    state: PhoneGpxAppearanceEditState,
    onSave: (PhoneGpxAppearanceOverride) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(state) { mutableStateOf(state.override) }
    val effective = draft.applyTo(state.inherited)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.gpx_appearance_inheritance_hint))
                mapToolsGpxAppearanceSettings(effective) { next -> draft = draft.changedFrom(effective, next) }
                phoneGpxAppearanceResetControls(draft) { draft = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }) { Text(stringResource(R.string.map_poi_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.map_poi_cancel)) }
        },
    )
}

@Composable
private fun phoneGpxAppearanceResetControls(
    draft: PhoneGpxAppearanceOverride,
    onChanged: (PhoneGpxAppearanceOverride) -> Unit,
) {
    val resets =
        listOf(
            R.string.gpx_inherit_color.takeIf { draft.trackColorArgb != null } to draft.copy(trackColorArgb = null),
            R.string.gpx_inherit_mode.takeIf { draft.colorMode != null } to draft.copy(colorMode = null),
            R.string.gpx_inherit_width.takeIf { draft.trackWidth != null } to draft.copy(trackWidth = null),
            R.string.gpx_inherit_opacity.takeIf { draft.trackOpacityPercent != null } to
                draft.copy(trackOpacityPercent = null),
            R.string.gpx_inherit_arrows.takeIf { draft.directionArrowsEnabled != null } to
                draft.copy(directionArrowsEnabled = null),
        )
    resets.forEach { (label, reset) ->
        if (label != null) TextButton(onClick = { onChanged(reset) }) { Text(stringResource(label)) }
    }
}
