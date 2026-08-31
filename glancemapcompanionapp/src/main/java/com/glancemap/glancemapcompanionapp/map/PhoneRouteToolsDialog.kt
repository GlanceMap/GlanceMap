package com.glancemap.glancemapcompanionapp.map

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
    PhoneMapPopupCard(
        modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .widthIn(max = 420.dp),
        title = stringResource(R.string.map_route_tools_title),
        onDismiss = actions.onDismiss,
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        when (state.mode) {
            null -> routeToolsChooser(state, actions.onChooseMode)
            PhoneRouteCreationMode.CURRENT_TO_DESTINATION ->
                routeToolsCurrentToDestination(state, currentLocationAvailable, actions.onCreate)
            PhoneRouteCreationMode.POINT_A_TO_B -> routeToolsPointToPoint(state, actions.onCreate)
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
        onClick = { onChooseMode(PhoneRouteCreationMode.MODIFY_ROUTE) },
        enabled = state.editableRoutes.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.map_route_tools_modify_route))
    }
}

@Composable
private fun routeToolsCurrentToDestination(
    state: PhoneRouteToolsUiState,
    currentLocationAvailable: Boolean,
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
    onCreate: () -> Unit,
) {
    Text(stringResource(R.string.map_route_tools_point_a_to_b_hint))
    Text(stringResource(state.pointSelectionMessage()))
    routeToolsCreateButton(
        enabled = state.pointA != null && state.pointB != null && !state.isRouting,
        isRouting = state.isRouting,
        onClick = onCreate,
        label = R.string.map_route_tools_create,
    )
}

@Composable
private fun routeToolsModify(
    state: PhoneRouteToolsUiState,
    actions: PhoneRouteToolsActions,
) {
    Text(stringResource(R.string.map_route_tools_modify_hint))
    state.editableRoutes.forEach { route ->
        OutlinedButton(
            onClick = { actions.onSelectRoute(route.id) },
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
    Text(
        stringResource(
            when {
                state.editableRoutes.isEmpty() -> R.string.map_route_tools_no_routes
                state.selectedRouteId == null -> R.string.map_route_tools_route_missing
                else -> state.pointSelectionMessage()
            },
        ),
    )
    routeToolsCreateButton(
        enabled =
            state.selectedRouteId != null &&
                state.pointA != null &&
                state.pointB != null &&
                !state.isRouting,
        isRouting = state.isRouting,
        onClick = actions.onCreate,
        label = R.string.map_route_tools_save_modified,
    )
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
