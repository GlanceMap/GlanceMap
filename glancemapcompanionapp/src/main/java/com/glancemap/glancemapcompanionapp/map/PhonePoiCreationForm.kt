package com.glancemap.glancemapcompanionapp.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R

@Composable
internal fun phonePoiCoordinateForm(
    seed: PhoneMapCoordinate,
    onSelected: (PhoneMapCoordinate) -> Unit,
) {
    var latitude by rememberSaveable { mutableStateOf(seed.latitude.toString()) }
    var longitude by rememberSaveable { mutableStateOf(seed.longitude.toString()) }
    val point = parsePhonePoiCoordinate(latitude, longitude)
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val submit: () -> Unit = {
        point?.let {
            focus.clearFocus()
            keyboard?.hide()
            onSelected(it)
        }
    }
    Text(stringResource(R.string.poi_creation_coordinates_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
    phonePoiCoordinateField(latitude, { latitude = it }, true, { focus.moveFocus(FocusDirection.Down) })
    phonePoiCoordinateField(longitude, { longitude = it }, false, submit)
    Button(
        onClick = submit,
        enabled = point != null,
        modifier = Modifier.fillMaxWidth().testTag("poi-use-coordinates"),
    ) {
        Text(stringResource(R.string.poi_creation_use_location))
    }
}

@Composable
private fun phonePoiCoordinateField(
    value: String,
    onChange: (String) -> Unit,
    latitude: Boolean,
    onIme: () -> Unit,
) {
    val valid = parsePhonePoiAxis(value, latitude) != null
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = {
            Text(
                stringResource(if (latitude) R.string.map_route_tools_latitude else R.string.map_route_tools_longitude),
            )
        },
        supportingText = {
            Text(
                stringResource(
                    if (latitude) R.string.poi_creation_latitude_range else R.string.poi_creation_longitude_range,
                ),
            )
        },
        modifier = Modifier.fillMaxWidth().testTag(if (latitude) "poi-latitude" else "poi-longitude"),
        singleLine = true,
        isError = value.isNotBlank() && !valid,
        // A text keyboard keeps minus signs available for southern and western coordinates.
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                autoCorrectEnabled = false,
                imeAction = if (latitude) ImeAction.Next else ImeAction.Done,
            ),
        keyboardActions = KeyboardActions(onNext = { onIme() }, onDone = { onIme() }),
    )
}

@Composable
internal fun phonePoiReview(
    state: PhonePoiCreationState,
    actions: PhonePoiCreationActions,
    onChangeLocation: () -> Unit,
) {
    val point = state.point ?: return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Place, null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.map_poi_selected_location, point.latitude, point.longitude))
            TextButton(onClick = onChangeLocation, enabled = !state.saving) {
                Text(stringResource(R.string.map_poi_change_location))
            }
        }
    }
    OutlinedTextField(
        value = state.name,
        onValueChange = actions.onNameChanged,
        label = { Text(stringResource(R.string.map_poi_name_hint)) },
        supportingText = { Text(stringResource(R.string.poi_creation_name_hint)) },
        modifier = Modifier.fillMaxWidth().testTag("poi-name"),
        singleLine = true,
        enabled = !state.saving,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (!state.saving) actions.onSave() }),
    )
    if (state.saveFailed) {
        Text(stringResource(R.string.map_poi_save_failed), color = MaterialTheme.colorScheme.error)
    }
    Button(onClick = actions.onSave, enabled = !state.saving, modifier = Modifier.fillMaxWidth().testTag("poi-save")) {
        if (state.saving) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Default.Save, null)
        }
        Spacer(Modifier.width(8.dp))
        Text(stringResource(if (state.saving) R.string.map_poi_saving else R.string.map_poi_save))
    }
    Text(
        stringResource(R.string.poi_creation_saved_in_library),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
