package com.glancemap.glancemapcompanionapp.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditLocationAlt
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun phonePoiCreationSheet(
    state: PhonePoiCreationState,
    coordinateSeed: PhoneMapCoordinate,
    actions: PhonePoiCreationActions,
) {
    var step by rememberSaveable(state.point?.latitude, state.point?.longitude) {
        mutableStateOf(if (state.point == null) PhonePoiCreationStep.LOCATION else PhonePoiCreationStep.REVIEW)
    }
    val saving by rememberUpdatedState(state.saving)
    val sheetState =
        rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { !saving || it != SheetValue.Hidden },
        )
    ModalBottomSheet(
        onDismissRequest = { if (!state.saving) actions.onDismiss() },
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
                    .testTag("poi-creation-sheet"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            phonePoiCreationHeader(step, state.saving, { step = PhonePoiCreationStep.LOCATION }, actions.onDismiss)
            when (step) {
                PhonePoiCreationStep.LOCATION ->
                    phonePoiLocationChoices(actions) {
                        step =
                            PhonePoiCreationStep.COORDINATES
                    }
                PhonePoiCreationStep.COORDINATES ->
                    phonePoiCoordinateForm(state.point ?: coordinateSeed) { point ->
                        actions.onCoordinatesSelected(point)
                        step = PhonePoiCreationStep.REVIEW
                    }
                PhonePoiCreationStep.REVIEW -> phonePoiReview(state, actions) { step = PhonePoiCreationStep.LOCATION }
            }
        }
    }
}

@Composable
private fun phonePoiCreationHeader(
    step: PhonePoiCreationStep,
    saving: Boolean,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (step != PhonePoiCreationStep.LOCATION) {
            IconButton(onClick = onBack, enabled = !saving) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.poi_creation_back))
            }
        }
        Text(
            stringResource(
                if (step ==
                    PhonePoiCreationStep.COORDINATES
                ) {
                    R.string.poi_creation_type_coordinates
                } else {
                    R.string.map_poi_add_title
                },
            ),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss, enabled = !saving) {
            Icon(Icons.Default.Close, stringResource(R.string.common_action_close))
        }
    }
}

@Composable
private fun phonePoiLocationChoices(
    actions: PhonePoiCreationActions,
    onCoordinates: () -> Unit,
) {
    Text(stringResource(R.string.poi_creation_choose_location), color = MaterialTheme.colorScheme.onSurfaceVariant)
    phonePoiLocationChoice(
        stringResource(R.string.poi_creation_pick_map),
        stringResource(R.string.poi_creation_pick_map_hint),
        Icons.Default.Map,
        actions.onPickMap,
    )
    phonePoiLocationChoice(
        stringResource(R.string.poi_creation_type_coordinates),
        stringResource(R.string.poi_creation_coordinates_hint),
        Icons.Default.EditLocationAlt,
        onCoordinates,
    )
}

@Composable
private fun phonePoiLocationChoice(
    title: String,
    detail: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Card {
                Icon(icon, null, Modifier.padding(12.dp).size(28.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Default.ChevronRight, null)
        }
    }
}
