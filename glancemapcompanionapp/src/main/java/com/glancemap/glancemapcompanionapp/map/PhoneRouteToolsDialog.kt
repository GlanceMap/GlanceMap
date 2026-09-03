@file:Suppress(
    "TooManyFunctions",
) // The dialog keeps the small creation and modification panels beside their chooser.

package com.glancemap.glancemapcompanionapp.map

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R

@Composable
@Suppress("FunctionNaming") // Compose entry point intentionally uses a component-style name.
internal fun BoxScope.PhoneRouteToolsDialog(
    state: PhoneRouteToolsUiState,
    currentLocationAvailable: Boolean,
    actions: PhoneRouteToolsActions,
) {
    if (!state.isOpen) return
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val popupBottomInset = 128.dp
        val popupMaxHeight = (maxHeight - popupBottomInset - 16.dp).coerceAtLeast(0.dp)
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 16.dp, end = 16.dp)
                    .widthIn(max = 420.dp),
        ) {
            PhoneMapPopupCard(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = popupMaxHeight),
                title = stringResource(R.string.map_route_tools_title),
                onDismiss = actions.onDismiss,
            ) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    Spacer(modifier = Modifier.height(8.dp))
                    when (state.mode) {
                        null -> routeToolsChooser(state, actions.onChooseMode)
                        PhoneRouteCreationMode.CURRENT_TO_DESTINATION ->
                            routeToolsCurrentToDestination(
                                state,
                                currentLocationAvailable,
                                actions.onResetMapPoints,
                                actions.onCreate,
                            )

                        PhoneRouteCreationMode.POINT_A_TO_B ->
                            routeToolsPointToPoint(state, actions.onResetMapPoints, actions.onCreate)

                        PhoneRouteCreationMode.MULTI_POINT_CHAIN ->
                            routeToolsMultiPoint(state, actions.onResetMapPoints, actions.onCreate)

                        PhoneRouteCreationMode.EXTEND_ROUTE_TO_DESTINATION ->
                            routeToolsExtend(state, actions)

                        PhoneRouteCreationMode.COORDINATES ->
                            routeToolsCoordinates(state, currentLocationAvailable, actions)

                        PhoneRouteCreationMode.MODIFY_ROUTE -> routeToolsModify(state, actions)
                    }
                    state.message?.let { message ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(message)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = actions.onDismiss, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.common_action_cancel))
                    }
                }
            }
            Spacer(modifier = Modifier.height(popupBottomInset))
        }
    }
}

@Composable
private fun routeToolsChooser(
    state: PhoneRouteToolsUiState,
    onChooseMode: (PhoneRouteCreationMode) -> Unit,
) {
    Text(stringResource(R.string.map_route_tools_choose_type))
    Spacer(modifier = Modifier.height(12.dp))
    Button(
        onClick = { onChooseMode(PhoneRouteCreationMode.CURRENT_TO_DESTINATION) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.map_route_tools_current_to_destination))
    }
    OutlinedButton(
        onClick = { onChooseMode(PhoneRouteCreationMode.POINT_A_TO_B) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.map_route_tools_point_a_to_b))
    }
    OutlinedButton(
        onClick = { onChooseMode(PhoneRouteCreationMode.MULTI_POINT_CHAIN) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.map_route_tools_multi_point))
    }
    OutlinedButton(
        onClick = { onChooseMode(PhoneRouteCreationMode.EXTEND_ROUTE_TO_DESTINATION) },
        enabled = state.editableRoutes.any(PhoneMapGpxItem::isEditable),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.map_route_tools_extend_route))
    }
    OutlinedButton(
        onClick = { onChooseMode(PhoneRouteCreationMode.COORDINATES) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.map_route_tools_coordinates))
    }
    OutlinedButton(
        onClick = { onChooseMode(PhoneRouteCreationMode.MODIFY_ROUTE) },
        enabled = state.editableRoutes.any(PhoneMapGpxItem::isEditable),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.map_route_tools_modify_route))
    }
}

@Composable
private fun routeToolsCurrentToDestination(
    state: PhoneRouteToolsUiState,
    currentLocationAvailable: Boolean,
    onResetMapPoints: () -> Unit,
    onCreate: () -> Unit,
) {
    Text(stringResource(R.string.map_route_tools_destination_hint))
    Text(
        stringResource(
            if (state.destination == null) {
                R.string.map_route_tools_destination_missing
            } else {
                R.string.map_route_tools_destination_selected
            },
        ),
    )
    if (!currentLocationAvailable) {
        Text(stringResource(R.string.map_route_tools_current_location_missing))
    }
    if (state.destination != null) {
        OutlinedButton(onClick = onResetMapPoints, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.map_route_tools_change_destination))
        }
    }
    routeToolsCreateButton(
        enabled = state.destination != null && currentLocationAvailable && !state.isRouting,
        isRouting = state.isRouting,
        onClick = onCreate,
        label = R.string.map_route_tools_create,
    )
}

@Composable
private fun routeToolsPointToPoint(
    state: PhoneRouteToolsUiState,
    onResetMapPoints: () -> Unit,
    onCreate: () -> Unit,
) {
    Text(stringResource(R.string.map_route_tools_point_a_to_b_hint))
    Text(stringResource(state.pointSelectionMessage()))
    if (state.pointA != null || state.pointB != null) {
        OutlinedButton(onClick = onResetMapPoints, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.map_route_tools_reset_points))
        }
    }
    routeToolsCreateButton(
        enabled = state.pointA != null && state.pointB != null && !state.isRouting,
        isRouting = state.isRouting,
        onClick = onCreate,
        label = R.string.map_route_tools_create,
    )
}

@Composable
private fun routeToolsMultiPoint(
    state: PhoneRouteToolsUiState,
    onResetMapPoints: () -> Unit,
    onCreate: () -> Unit,
) {
    Text(stringResource(R.string.map_route_tools_multi_point_hint))
    Text(
        stringResource(
            R.string.map_route_tools_multi_point_count,
            state.chainPoints.size,
        ),
    )
    if (state.chainPoints.isNotEmpty()) {
        OutlinedButton(onClick = onResetMapPoints, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.map_route_tools_reset_points))
        }
    }
    routeToolsCreateButton(
        enabled = state.chainPoints.size >= 2 && !state.isRouting,
        isRouting = state.isRouting,
        onClick = onCreate,
        label = R.string.map_route_tools_create,
    )
}

@Composable
private fun routeToolsExtend(
    state: PhoneRouteToolsUiState,
    actions: PhoneRouteToolsActions,
) {
    Text(stringResource(R.string.map_route_tools_extend_hint))
    routeToolsRouteList(state, actions.onSelectRoute)
    Text(
        stringResource(
            if (state.destination == null) {
                R.string.map_route_tools_destination_missing
            } else {
                R.string.map_route_tools_destination_selected
            },
        ),
    )
    if (state.destination != null) {
        OutlinedButton(onClick = actions.onResetMapPoints, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.map_route_tools_change_destination))
        }
    }
    routeToolsCreateButton(
        enabled = state.selectedRouteId != null && state.destination != null && !state.isRouting,
        isRouting = state.isRouting,
        onClick = actions.onCreate,
        label = R.string.map_route_tools_extend,
    )
}

@Composable
private fun routeToolsCoordinates(
    state: PhoneRouteToolsUiState,
    currentLocationAvailable: Boolean,
    actions: PhoneRouteToolsActions,
) {
    Text(stringResource(R.string.map_route_tools_coordinates_hint))
    OutlinedTextField(
        value = state.coordinateLatitude,
        onValueChange = { latitude ->
            actions.onCoordinatesChanged(latitude, state.coordinateLongitude)
        },
        label = { Text(stringResource(R.string.map_route_tools_latitude)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        enabled = !state.isRouting,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.coordinateLongitude,
        onValueChange = { longitude ->
            actions.onCoordinatesChanged(state.coordinateLatitude, longitude)
        },
        label = { Text(stringResource(R.string.map_route_tools_longitude)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        enabled = !state.isRouting,
        modifier = Modifier.fillMaxWidth(),
    )
    if (!currentLocationAvailable) {
        Text(stringResource(R.string.map_route_tools_current_location_missing))
    }
    if (state.coordinateLatitude.isNotBlank() || state.coordinateLongitude.isNotBlank()) {
        OutlinedButton(onClick = actions.onResetMapPoints, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.map_route_tools_reset_coordinates))
        }
    }
    routeToolsCreateButton(
        enabled =
            currentLocationAvailable &&
                state.coordinateLatitude.isNotBlank() &&
                state.coordinateLongitude.isNotBlank() &&
                !state.isRouting,
        isRouting = state.isRouting,
        onClick = actions.onCreate,
        label = R.string.map_route_tools_create,
    )
}

@Composable
private fun routeToolsModify(
    state: PhoneRouteToolsUiState,
    actions: PhoneRouteToolsActions,
) {
    Text(stringResource(R.string.map_route_tools_modify_hint))
    PhoneRouteModificationMode.entries.forEach { mode ->
        OutlinedButton(
            onClick = { actions.onSelectModificationMode(mode) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (mode == state.modificationMode) {
                    "✓ ${stringResource(mode.labelResource())}"
                } else {
                    stringResource(mode.labelResource())
                },
            )
        }
    }
    routeToolsRouteList(state, actions.onSelectRoute)
    Text(stringResource(state.modificationSelectionMessage()))
    if (state.pointA != null || state.pointB != null || state.destination != null) {
        OutlinedButton(onClick = actions.onResetMapPoints, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.map_route_tools_reset_points))
        }
    }
    routeToolsCreateButton(
        enabled = state.isReadyForModification() && !state.isRouting,
        isRouting = state.isRouting,
        onClick = actions.onCreate,
        label = R.string.map_route_tools_save_modified,
    )
}

@Composable
private fun routeToolsRouteList(
    state: PhoneRouteToolsUiState,
    onSelectRoute: (String) -> Unit,
) {
    val routes = state.editableRoutes.filter(PhoneMapGpxItem::isEditable)
    if (routes.isEmpty()) {
        Text(stringResource(R.string.map_route_tools_no_routes))
    } else {
        routes.forEach { route ->
            OutlinedButton(
                onClick = { onSelectRoute(route.id) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (route.id == state.selectedRouteId) {
                        "✓ ${route.displayName}"
                    } else {
                        route.displayName
                    },
                )
            }
        }
    }
}

@Composable
private fun routeToolsCreateButton(
    enabled: Boolean,
    isRouting: Boolean,
    onClick: () -> Unit,
    label: Int,
) {
    Spacer(modifier = Modifier.height(12.dp))
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(if (isRouting) R.string.map_route_tools_routing else label))
    }
}

private fun PhoneRouteToolsUiState.pointSelectionMessage(): Int =
    when {
        pointA == null -> R.string.map_route_tools_point_a_missing
        pointB == null -> R.string.map_route_tools_point_b_missing
        else -> R.string.map_route_tools_points_selected
    }

private fun PhoneRouteModificationMode.labelResource(): Int =
    when (this) {
        PhoneRouteModificationMode.RESHAPE_ROUTE -> R.string.map_route_tools_reshape_route
        PhoneRouteModificationMode.REPLACE_SECTION_A_TO_B -> R.string.map_route_tools_replace_section
        PhoneRouteModificationMode.KEEP_ONLY_A_TO_B -> R.string.map_route_tools_keep_section
        PhoneRouteModificationMode.TRIM_START_TO_HERE -> R.string.map_route_tools_change_start
        PhoneRouteModificationMode.TRIM_END_FROM_HERE -> R.string.map_route_tools_change_end
        PhoneRouteModificationMode.REVERSE_GPX -> R.string.map_route_tools_reverse_gpx
    }

@Suppress("CyclomaticComplexMethod") // Each modification mode exposes a distinct selection prerequisite.
private fun PhoneRouteToolsUiState.modificationSelectionMessage(): Int =
    when (modificationMode) {
        PhoneRouteModificationMode.RESHAPE_ROUTE ->
            when {
                selectedRouteId == null -> R.string.map_route_tools_route_missing
                pointA == null -> R.string.map_route_tools_reshape_anchor_missing
                destination == null -> R.string.map_route_tools_reshape_bend_missing
                else -> R.string.map_route_tools_points_selected
            }

        PhoneRouteModificationMode.REPLACE_SECTION_A_TO_B,
        PhoneRouteModificationMode.KEEP_ONLY_A_TO_B,
        ->
            when {
                selectedRouteId == null -> R.string.map_route_tools_route_missing
                pointA == null -> R.string.map_route_tools_point_a_missing
                pointB == null -> R.string.map_route_tools_point_b_missing
                else -> R.string.map_route_tools_points_selected
            }

        PhoneRouteModificationMode.TRIM_START_TO_HERE ->
            when {
                selectedRouteId == null -> R.string.map_route_tools_route_missing
                pointA == null -> R.string.map_route_tools_new_start_missing
                else -> R.string.map_route_tools_point_selected
            }

        PhoneRouteModificationMode.TRIM_END_FROM_HERE ->
            when {
                selectedRouteId == null -> R.string.map_route_tools_route_missing
                pointB == null -> R.string.map_route_tools_new_end_missing
                else -> R.string.map_route_tools_point_selected
            }

        PhoneRouteModificationMode.REVERSE_GPX ->
            if (selectedRouteId == null) {
                R.string.map_route_tools_route_missing
            } else {
                R.string.map_route_tools_reverse_ready
            }
    }

private fun PhoneRouteToolsUiState.isReadyForModification(): Boolean =
    selectedRouteId != null &&
        when (modificationMode) {
            PhoneRouteModificationMode.RESHAPE_ROUTE -> pointA != null && destination != null
            PhoneRouteModificationMode.REPLACE_SECTION_A_TO_B,
            PhoneRouteModificationMode.KEEP_ONLY_A_TO_B,
            -> pointA != null && pointB != null

            PhoneRouteModificationMode.TRIM_START_TO_HERE -> pointA != null
            PhoneRouteModificationMode.TRIM_END_FROM_HERE -> pointB != null
            PhoneRouteModificationMode.REVERSE_GPX -> true
        }
