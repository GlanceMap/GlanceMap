package com.glancemap.glancemapcompanionapp.map

internal data class PhoneRouteToolsActions(
    val onChooseToolKind: (PhoneRouteToolKind) -> Unit,
    val onChooseMode: (PhoneRouteCreationMode) -> Unit,
    val onSelectModificationMode: (PhoneRouteModificationMode) -> Unit,
    val onSelectRoute: (String) -> Unit,
    val onCoordinatesChanged: (String, String) -> Unit,
    val onResetMapPoints: () -> Unit,
    val onCreate: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onDismiss: () -> Unit,
)
