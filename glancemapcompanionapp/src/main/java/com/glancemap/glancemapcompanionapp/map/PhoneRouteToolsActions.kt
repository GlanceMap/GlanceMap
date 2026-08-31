package com.glancemap.glancemapcompanionapp.map

internal data class PhoneRouteToolsActions(
    val onChooseMode: (PhoneRouteCreationMode) -> Unit,
    val onSelectRoute: (String) -> Unit,
    val onCreate: () -> Unit,
    val onDismiss: () -> Unit,
)
