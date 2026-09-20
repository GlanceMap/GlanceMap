@file:Suppress("FunctionNaming")

package com.glancemap.glancemapwearos.presentation.features.routetools

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Polyline
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.presentation.features.poi.PoiSearchUiState
import com.glancemap.glancemapwearos.presentation.ui.WearActionButtonRole
import com.glancemap.glancemapwearos.presentation.ui.WearActionDialog
import com.glancemap.glancemapwearos.presentation.ui.WearActionDialogButton
import com.glancemap.glancemapwearos.presentation.ui.WearFormDialog
import com.glancemap.glancemapwearos.presentation.ui.rememberWearAdaptiveSpec
import org.mapsforge.core.model.LatLong

@Composable
internal fun RouteToolsScrollIndicator(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    if (scrollState.maxValue <= 0) return

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxHeight()
                .width(6.dp),
    ) {
        val thumbHeight = (maxHeight * 0.22f).coerceIn(24.dp, 46.dp)
        val travel = (maxHeight - thumbHeight).coerceAtLeast(0.dp)
        val progress = scrollState.value.toFloat() / scrollState.maxValue.toFloat()
        val thumbOffset = travel * progress

        Box(
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .fillMaxHeight()
                    .width(2.dp)
                    .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(50)),
        )
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = thumbOffset)
                    .width(6.dp)
                    .height(thumbHeight)
                    .background(Color.White.copy(alpha = 0.72f), RoundedCornerShape(50)),
        )
    }
}

@Composable
internal fun RouteToolDraftSummaryDialog(
    visible: Boolean,
    session: RouteToolSession?,
    isExecuting: Boolean,
    executionMessage: String?,
    loopRetryOptions: List<RouteToolLoopRetryOption> = emptyList(),
    onDismiss: () -> Unit,
    onConfirmCreate: (() -> Unit)? = null,
    onConfirmModify: (() -> Unit)? = null,
    onSelectLoopRetryOption: ((RouteToolLoopRetryOption) -> Unit)? = null,
) {
    if (!visible || session == null) return

    val isCreate = session.options.toolKind == RouteToolKind.CREATE
    val isReplaceCurrent = session.options.saveBehavior == RouteSaveBehavior.REPLACE_CURRENT
    val title =
        when {
            executionMessage != null && isCreate -> "Create failed"
            executionMessage != null -> "Save failed"
            isCreate -> "Create GPX?"
            isReplaceCurrent -> "Replace GPX?"
            else -> "Save GPX?"
        }
    val actionLabel =
        if (executionMessage != null) {
            "Retry"
        } else if (isCreate) {
            if (isExecuting) "Creating..." else "Create GPX"
        } else {
            if (isExecuting) "Saving..." else "Save GPX"
        }
    val supportingLine =
        buildString {
            append(session.modeTitle)
            if (!isCreate) {
                append(" • ")
                append(session.options.saveBehavior.title)
            }
        }

    val actionButtons =
        when {
            executionMessage != null &&
                session.options.toolKind == RouteToolKind.CREATE &&
                loopRetryOptions.isNotEmpty() &&
                onSelectLoopRetryOption != null ->
                loopRetryOptions.map { option ->
                    WearActionDialogButton(
                        text = option.label,
                        onClick = { onSelectLoopRetryOption(option) },
                        enabled = !isExecuting,
                    )
                } +
                    WearActionDialogButton(
                        text = "Back",
                        onClick = onDismiss,
                        role = WearActionButtonRole.Secondary,
                    )

            session.options.toolKind == RouteToolKind.CREATE && onConfirmCreate != null ->
                listOf(
                    WearActionDialogButton(
                        text = actionLabel,
                        onClick = onConfirmCreate,
                        enabled = !isExecuting,
                    ),
                    WearActionDialogButton(
                        text = "Back",
                        onClick = onDismiss,
                        role = WearActionButtonRole.Secondary,
                    ),
                )

            session.options.toolKind == RouteToolKind.MODIFY && onConfirmModify != null ->
                listOf(
                    WearActionDialogButton(
                        text = actionLabel,
                        onClick = onConfirmModify,
                        enabled = !isExecuting,
                    ),
                    WearActionDialogButton(
                        text = "Back",
                        onClick = onDismiss,
                        role = WearActionButtonRole.Secondary,
                    ),
                )

            else ->
                listOf(
                    WearActionDialogButton(
                        text = "Close",
                        onClick = onDismiss,
                    ),
                )
        }

    WearActionDialog(
        visible = true,
        title = title,
        onDismissRequest = onDismiss,
        backgroundColor = Color.Black.copy(alpha = 0.92f),
        buttons = actionButtons,
    ) {
        Text(
            text = supportingLine,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.84f),
            textAlign = TextAlign.Center,
        )

        Text(
            text =
                when {
                    executionMessage != null -> executionMessage
                    isCreate -> "Ready to generate the GPX."
                    session.options.modifyMode.previewBeforeSaving ->
                        "Preview the edit, then save."
                    isReplaceCurrent -> "This will replace the active GPX."
                    else -> "Ready to save the GPX edit."
                },
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = Color(0xFFFFCC80),
        )
    }
}

@Composable
internal fun RouteToolKindSelector(
    selected: RouteToolKind,
    onSelected: (RouteToolKind) -> Unit,
) {
    val adaptive = rememberWearAdaptiveSpec()
    if (adaptive.isRound && adaptive.fontScale >= 1.25f) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RouteSegmentButton(
                modifier = Modifier.fillMaxWidth(),
                text = RouteToolKind.CREATE.title,
                selected = selected == RouteToolKind.CREATE,
                onClick = { onSelected(RouteToolKind.CREATE) },
            )
            RouteSegmentButton(
                modifier = Modifier.fillMaxWidth(),
                text = RouteToolKind.MODIFY.title,
                selected = selected == RouteToolKind.MODIFY,
                onClick = { onSelected(RouteToolKind.MODIFY) },
            )
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RouteSegmentButton(
            modifier = Modifier.weight(1f),
            text = RouteToolKind.CREATE.title,
            selected = selected == RouteToolKind.CREATE,
            onClick = { onSelected(RouteToolKind.CREATE) },
        )
        RouteSegmentButton(
            modifier = Modifier.weight(1f),
            text = RouteToolKind.MODIFY.title,
            selected = selected == RouteToolKind.MODIFY,
            onClick = { onSelected(RouteToolKind.MODIFY) },
        )
    }
}

@Composable
internal fun RouteActionSelector(
    options: RouteToolOptions,
    canModifyActiveGpx: Boolean,
    coordinateSeed: LatLong?,
    onOptionsChange: (RouteToolOptions) -> Unit,
    onStartSelection: (RouteToolSession) -> Unit,
    onOpenCoordinateEditor: (RouteToolOptions) -> Unit,
    onOpenPoiSearchDialog: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (options.toolKind) {
            RouteToolKind.CREATE -> {
                createMenuModes().forEach { mode ->
                    val updatedOptions =
                        when (mode) {
                            RouteCreateMode.COORDINATES ->
                                options
                                    .copy(createMode = mode)
                                    .seedCoordinateEndpoints(coordinateSeed)

                            else -> options.copy(createMode = mode)
                        }
                    RouteActionButton(
                        text = mode.title,
                        icon = createModeIcon(mode),
                        selected = options.createMode == mode,
                        enabled = true,
                        onClick = {
                            when (mode) {
                                RouteCreateMode.CURRENT_TO_HERE,
                                RouteCreateMode.MULTI_POINT_CHAIN,
                                RouteCreateMode.POINT_A_TO_B,
                                -> {
                                    onOptionsChange(updatedOptions)
                                    onStartSelection(RouteToolSession(options = updatedOptions))
                                }

                                RouteCreateMode.SEARCH -> {
                                    onOptionsChange(updatedOptions)
                                    onOpenPoiSearchDialog()
                                }

                                RouteCreateMode.COORDINATES -> {
                                    onOpenCoordinateEditor(updatedOptions)
                                }

                                RouteCreateMode.LOOP_AROUND_HERE -> {
                                    onOptionsChange(updatedOptions)
                                }

                                RouteCreateMode.ACTIVE_GPX_END_TO_HERE -> Unit
                            }
                        },
                    )
                }
            }

            RouteToolKind.MODIFY -> {
                visibleModifyModes().forEach { mode ->
                    RouteActionButton(
                        text = mode.title,
                        icon = null,
                        selected = options.modifyMode == mode,
                        enabled = canModifyActiveGpx,
                        onClick = {
                            val updatedOptions = options.copy(modifyMode = mode)
                            onOptionsChange(updatedOptions)
                            onStartSelection(RouteToolSession(options = updatedOptions))
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun RouteSaveBehaviorSelector(
    selected: RouteSaveBehavior,
    onSelected: (RouteSaveBehavior) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Save mode",
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RouteSegmentButton(
                modifier = Modifier.weight(1f),
                text = RouteSaveBehavior.SAVE_AS_NEW.title,
                selected = selected == RouteSaveBehavior.SAVE_AS_NEW,
                onClick = { onSelected(RouteSaveBehavior.SAVE_AS_NEW) },
            )
            RouteSegmentButton(
                modifier = Modifier.weight(1f),
                text = RouteSaveBehavior.REPLACE_CURRENT.title,
                selected = selected == RouteSaveBehavior.REPLACE_CURRENT,
                onClick = { onSelected(RouteSaveBehavior.REPLACE_CURRENT) },
            )
        }
    }
}

@Composable
internal fun LoopTargetModeSelector(
    selected: LoopTargetMode,
    onSelected: (LoopTargetMode) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Loop by",
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RouteSegmentButton(
                modifier = Modifier.weight(1f),
                text = LoopTargetMode.DISTANCE.title,
                selected = selected == LoopTargetMode.DISTANCE,
                onClick = { onSelected(LoopTargetMode.DISTANCE) },
            )
            RouteSegmentButton(
                modifier = Modifier.weight(1f),
                text = LoopTargetMode.TIME.title,
                selected = selected == LoopTargetMode.TIME,
                onClick = { onSelected(LoopTargetMode.TIME) },
            )
        }
    }
}

@Composable
internal fun LoopTargetEditor(
    targetMode: LoopTargetMode,
    distanceKm: Int,
    durationMinutes: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text =
                when (targetMode) {
                    LoopTargetMode.DISTANCE -> "Loop distance"
                    LoopTargetMode.TIME -> "Loop time"
                },
            style = MaterialTheme.typography.labelMedium,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onDecrease,
                colors =
                    IconButtonDefaults.iconButtonColors(
                        containerColor = Color.White.copy(alpha = 0.14f),
                        contentColor = Color.White,
                    ),
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Decrease loop distance")
            }
            Text(
                text =
                    when (targetMode) {
                        LoopTargetMode.DISTANCE -> "$distanceKm km"
                        LoopTargetMode.TIME -> formatLoopDuration(durationMinutes)
                    },
                style = MaterialTheme.typography.titleSmall,
            )
            IconButton(
                onClick = onIncrease,
                colors =
                    IconButtonDefaults.iconButtonColors(
                        containerColor = Color.White.copy(alpha = 0.14f),
                        contentColor = Color.White,
                    ),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Increase loop distance")
            }
        }
        if (targetMode == LoopTargetMode.TIME) {
            Text(
                text = "Time is estimated from hiking pace.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun LoopStartModeSelector(
    selected: LoopStartMode,
    onSelected: (LoopStartMode) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Start from",
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RouteSegmentButton(
                modifier = Modifier.weight(1f),
                text = LoopStartMode.CURRENT_LOCATION.title,
                selected = selected == LoopStartMode.CURRENT_LOCATION,
                onClick = { onSelected(LoopStartMode.CURRENT_LOCATION) },
            )
            RouteSegmentButton(
                modifier = Modifier.weight(1f),
                text = LoopStartMode.PICK_ON_MAP.title,
                selected = selected == LoopStartMode.PICK_ON_MAP,
                onClick = { onSelected(LoopStartMode.PICK_ON_MAP) },
            )
        }
    }
}

@Composable
internal fun RouteSettingRow(
    title: String,
    value: String,
    icon: ImageVector? = null,
    iconTint: Color = Color.White,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = Color.White.copy(alpha = 0.10f),
                contentColor = Color.White,
            ),
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp),
                )
                Box(modifier = Modifier.width(8.dp))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = title, style = MaterialTheme.typography.labelMedium)
                Text(text = value, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun RouteActionButton(
    text: String,
    icon: ImageVector?,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors =
            ButtonDefaults.buttonColors(
                containerColor =
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.White.copy(alpha = 0.10f)
                    },
                contentColor =
                    if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        Color.White
                    },
                disabledContainerColor = Color.White.copy(alpha = 0.06f),
                disabledContentColor = Color.White.copy(alpha = 0.40f),
            ),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                )
            }
            Text(text = text, textAlign = TextAlign.Center)
        }
    }
}

@Composable
internal fun RouteSegmentButton(
    modifier: Modifier,
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors =
            ButtonDefaults.buttonColors(
                containerColor =
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.White.copy(alpha = 0.10f)
                    },
                contentColor =
                    if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        Color.White
                    },
            ),
    ) {
        Text(text = text, textAlign = TextAlign.Center)
    }
}

internal fun RouteStylePreset.next(): RouteStylePreset {
    val entries = RouteStylePreset.entries
    val currentIndex = entries.indexOf(this)
    return entries[(currentIndex + 1) % entries.size]
}

internal fun visibleModifyModes(): List<RouteModifyMode> =
    listOf(
        RouteModifyMode.TRIM_START_TO_HERE,
        RouteModifyMode.TRIM_END_FROM_HERE,
        RouteModifyMode.RESHAPE_ROUTE,
        RouteModifyMode.REVERSE_GPX,
    )

internal enum class CoordinateStep(
    val delta: Double,
    val label: String,
) {
    TENTH(0.1, "0.1"),
    HUNDREDTH(0.01, "0.01"),
    THOUSANDTH(0.001, "0.001"),
    ONE_TEN_THOUSANDTH(0.0001, "0.0001"),
    ;

    fun next(): CoordinateStep {
        val entries = CoordinateStep.entries
        val currentIndex = entries.indexOf(this)
        return entries[(currentIndex + 1).coerceAtMost(entries.lastIndex)]
    }

    fun previous(): CoordinateStep {
        val entries = CoordinateStep.entries
        val currentIndex = entries.indexOf(this)
        return entries[(currentIndex - 1).coerceAtLeast(0)]
    }

    companion object {
        val ONE_THOUSANDTH = THOUSANDTH
    }
}

private enum class CoordinateEntryField(
    val title: String,
    val isLatitude: Boolean,
) {
    START_LATITUDE("Start latitude", true),
    START_LONGITUDE("Start longitude", false),
    DESTINATION_LATITUDE("Destination latitude", true),
    DESTINATION_LONGITUDE("Destination longitude", false),
}

@Composable
@Suppress("LongParameterList", "LongMethod")
internal fun RouteEndpointEditorDialog(
    visible: Boolean,
    options: RouteToolOptions,
    coordinateSeed: LatLong?,
    preflightMessage: String?,
    step: CoordinateStep,
    onOptionsChange: (RouteToolOptions) -> Unit,
    onStepChange: (CoordinateStep) -> Unit,
    onCreateRoute: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    var editingField by remember(visible) { mutableStateOf<CoordinateEntryField?>(null) }
    val selectedField = editingField
    if (selectedField != null) {
        CoordinateTextEntryDialog(
            visible = true,
            title = selectedField.title,
            initialValue = options.coordinateValue(selectedField)?.let(::formatCoordinateValue).orEmpty(),
            isLatitude = selectedField.isLatitude,
            onConfirm = { value ->
                onOptionsChange(options.withCoordinateValue(selectedField, value))
                editingField = null
            },
            onDismiss = { editingField = null },
        )
        return
    }

    val endpointValidation = options.coordinateEndpointValidationMessage(currentLocation = null)
    WearFormDialog(
        visible = true,
        title = "Route endpoints",
        onDismiss = onDismiss,
        backgroundColor = Color.Black.copy(alpha = 0.92f),
    ) { formTokens ->
        RouteEndpointEditorSection(
            title = "START",
            source = options.startEndpointSource,
            latitude = options.startCoordinateLatitude,
            longitude = options.startCoordinateLongitude,
            step = step,
            onSourceChange = { source ->
                onOptionsChange(
                    options.copy(startEndpointSource = source).seedCoordinateEndpoints(coordinateSeed),
                )
            },
            onLatitudeEdit = { editingField = CoordinateEntryField.START_LATITUDE },
            onLongitudeEdit = { editingField = CoordinateEntryField.START_LONGITUDE },
            onLatitudeChange = { value ->
                onOptionsChange(options.copy(startCoordinateLatitude = value.coerceIn(-90.0, 90.0)))
            },
            onLongitudeChange = { value ->
                onOptionsChange(options.copy(startCoordinateLongitude = normalizeLongitude(value)))
            },
            onStepChange = onStepChange,
            modifier = formTokens.controlModifier,
        )
        RouteEndpointEditorSection(
            title = "DESTINATION",
            source = options.destinationEndpointSource,
            latitude = options.destinationCoordinateLatitude,
            longitude = options.destinationCoordinateLongitude,
            step = step,
            onSourceChange = { source ->
                onOptionsChange(
                    options.copy(destinationEndpointSource = source).seedCoordinateEndpoints(coordinateSeed),
                )
            },
            onLatitudeEdit = { editingField = CoordinateEntryField.DESTINATION_LATITUDE },
            onLongitudeEdit = { editingField = CoordinateEntryField.DESTINATION_LONGITUDE },
            onLatitudeChange = { value ->
                onOptionsChange(options.copy(destinationCoordinateLatitude = value.coerceIn(-90.0, 90.0)))
            },
            onLongitudeChange = { value ->
                onOptionsChange(options.copy(destinationCoordinateLongitude = normalizeLongitude(value)))
            },
            onStepChange = onStepChange,
            modifier = formTokens.controlModifier,
        )
        preflightMessage?.takeIf { it.isNotBlank() }?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFFCC80),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (endpointValidation != null) {
            Text(
                text = endpointValidation,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFFCC80),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Button(
            onClick = onCreateRoute,
            enabled = endpointValidation == null,
            modifier = formTokens.controlModifier.heightIn(min = formTokens.buttonMinHeight),
        ) {
            Text("Create route")
        }
        Button(
            onClick = onDismiss,
            modifier = formTokens.controlModifier.heightIn(min = formTokens.buttonMinHeight),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
        ) {
            Text("Back")
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun RouteEndpointEditorSection(
    title: String,
    source: RouteEndpointSource,
    latitude: Double?,
    longitude: Double?,
    step: CoordinateStep,
    onSourceChange: (RouteEndpointSource) -> Unit,
    onLatitudeEdit: () -> Unit,
    onLongitudeEdit: () -> Unit,
    onLatitudeChange: (Double) -> Unit,
    onLongitudeChange: (Double) -> Unit,
    onStepChange: (CoordinateStep) -> Unit,
    modifier: Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
    RouteEndpointSourceSelector(selected = source, onSelected = onSourceChange, modifier = modifier)
    if (source == RouteEndpointSource.COORDINATES) {
        CoordinateValueEditorRow(
            label = "Lat",
            value = latitude,
            onEdit = onLatitudeEdit,
            onDecrease = { latitude?.let { onLatitudeChange(it - step.delta) } },
            onIncrease = { latitude?.let { onLatitudeChange(it + step.delta) } },
            modifier = modifier,
        )
        CoordinateValueEditorRow(
            label = "Lon",
            value = longitude,
            onEdit = onLongitudeEdit,
            onDecrease = { longitude?.let { onLongitudeChange(it - step.delta) } },
            onIncrease = { longitude?.let { onLongitudeChange(it + step.delta) } },
            modifier = modifier,
        )
        CoordinateStepSelector(step = step, onStepChange = onStepChange, modifier = modifier)
    }
}

@Composable
internal fun CoordinateStepSelector(
    step: CoordinateStep,
    onStepChange: (CoordinateStep) -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Step ${step.label}", style = MaterialTheme.typography.bodySmall)
        IconButton(
            enabled = step != CoordinateStep.ONE_TEN_THOUSANDTH,
            onClick = { onStepChange(step.next()) },
            colors =
                IconButtonDefaults.iconButtonColors(
                    containerColor = Color.White.copy(alpha = 0.14f),
                    contentColor = Color.White,
                ),
        ) {
            Icon(Icons.Default.Remove, contentDescription = "Decrease coordinate step")
        }
        IconButton(
            enabled = step != CoordinateStep.TENTH,
            onClick = { onStepChange(step.previous()) },
            colors =
                IconButtonDefaults.iconButtonColors(
                    containerColor = Color.White.copy(alpha = 0.14f),
                    contentColor = Color.White,
                ),
        ) {
            Icon(Icons.Default.Add, contentDescription = "Increase coordinate step")
        }
    }
}

@Composable
private fun RouteEndpointSourceSelector(
    selected: RouteEndpointSource,
    onSelected: (RouteEndpointSource) -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RouteSegmentButton(
            modifier = Modifier.weight(1f),
            text = RouteEndpointSource.CURRENT_LOCATION.title,
            selected = selected == RouteEndpointSource.CURRENT_LOCATION,
            onClick = { onSelected(RouteEndpointSource.CURRENT_LOCATION) },
        )
        RouteSegmentButton(
            modifier = Modifier.weight(1f),
            text = RouteEndpointSource.COORDINATES.title,
            selected = selected == RouteEndpointSource.COORDINATES,
            onClick = { onSelected(RouteEndpointSource.COORDINATES) },
        )
    }
}

@Composable
@Suppress("LongParameterList", "LongMethod")
internal fun CoordinateTextEntryDialog(
    visible: Boolean,
    title: String,
    initialValue: String,
    isLatitude: Boolean,
    onConfirm: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var draftValue by remember(initialValue) { mutableStateOf(initialValue) }
    var errorMessage by remember(initialValue) { mutableStateOf<String?>(null) }

    LaunchedEffect(visible) {
        if (visible) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    WearFormDialog(
        visible = visible,
        title = title,
        onDismiss = onDismiss,
        backgroundColor = Color.Black.copy(alpha = 0.92f),
    ) { formTokens ->
        BasicTextField(
            value = draftValue,
            onValueChange = {
                draftValue = it.take(24)
                errorMessage = null
            },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier =
                formTokens.controlModifier
                    .focusRequester(focusRequester)
                    .background(Color(0xFF1F1F1F), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = formTokens.textFieldVerticalPadding),
        )
        errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Button(
            onClick = {
                val parsed = draftValue.trim().toDoubleOrNull()
                when {
                    parsed == null || !parsed.isFinite() -> errorMessage = "Enter a number."
                    isLatitude && parsed !in -90.0..90.0 -> errorMessage = "Latitude: -90 to 90."
                    else -> onConfirm(if (isLatitude) parsed else normalizeLongitude(parsed))
                }
            },
            modifier = formTokens.controlModifier.heightIn(min = formTokens.buttonMinHeight),
        ) {
            Text("Apply")
        }
        Button(
            onClick = onDismiss,
            modifier = formTokens.controlModifier.heightIn(min = formTokens.buttonMinHeight),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
        ) {
            Text("Back")
        }
    }
}

@Composable
@Suppress("LongParameterList")
internal fun CoordinateValueEditorRow(
    label: String,
    value: Double?,
    onEdit: () -> Unit,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onDecrease,
            colors =
                IconButtonDefaults.iconButtonColors(
                    containerColor = Color.White.copy(alpha = 0.14f),
                    contentColor = Color.White,
                ),
        ) {
            Icon(Icons.Default.Remove, contentDescription = "Decrease $label")
        }
        Column(
            modifier = Modifier.weight(1f).clickable(onClick = onEdit),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value?.let(::formatCoordinateValue) ?: "Not set", style = MaterialTheme.typography.bodySmall)
        }
        IconButton(
            onClick = onIncrease,
            colors =
                IconButtonDefaults.iconButtonColors(
                    containerColor = Color.White.copy(alpha = 0.14f),
                    contentColor = Color.White,
                ),
        ) {
            Icon(Icons.Default.Add, contentDescription = "Increase $label")
        }
    }
}

internal fun createMenuModes(): List<RouteCreateMode> =
    listOf(
        RouteCreateMode.CURRENT_TO_HERE,
        RouteCreateMode.POINT_A_TO_B,
        RouteCreateMode.MULTI_POINT_CHAIN,
        RouteCreateMode.SEARCH,
        RouteCreateMode.COORDINATES,
        RouteCreateMode.LOOP_AROUND_HERE,
    )

internal fun createModeIcon(mode: RouteCreateMode): ImageVector =
    when (mode) {
        RouteCreateMode.CURRENT_TO_HERE -> Icons.Default.MyLocation
        RouteCreateMode.MULTI_POINT_CHAIN -> Icons.Default.Polyline
        RouteCreateMode.SEARCH -> Icons.Default.Search
        RouteCreateMode.POINT_A_TO_B -> Icons.AutoMirrored.Filled.CallSplit
        RouteCreateMode.COORDINATES -> Icons.Default.Place
        RouteCreateMode.LOOP_AROUND_HERE -> Icons.Default.Loop
        RouteCreateMode.ACTIVE_GPX_END_TO_HERE -> Icons.Default.Loop
    }

internal fun routeToolsHintText(options: RouteToolOptions): String =
    when {
        options.toolKind == RouteToolKind.CREATE &&
            options.createMode == RouteCreateMode.MULTI_POINT_CHAIN -> {
            "Pick one point at a time and build the route step by step."
        }

        options.toolKind == RouteToolKind.CREATE &&
            options.createMode == RouteCreateMode.SEARCH -> {
            "Search enabled offline POIs and route from current location."
        }

        options.toolKind == RouteToolKind.CREATE &&
            options.createMode == RouteCreateMode.COORDINATES -> {
            "Set the route endpoints, then create the route."
        }

        options.toolKind == RouteToolKind.CREATE &&
            options.createMode == RouteCreateMode.LOOP_AROUND_HERE -> {
            "Choose target, shape, and where the loop should start."
        }

        options.toolKind == RouteToolKind.MODIFY -> {
            "Tap an action to start editing on the map."
        }

        else -> {
            "Tap an action to start on the map."
        }
    }

private fun RouteToolOptions.coordinateValue(field: CoordinateEntryField): Double? =
    when (field) {
        CoordinateEntryField.START_LATITUDE -> startCoordinateLatitude
        CoordinateEntryField.START_LONGITUDE -> startCoordinateLongitude
        CoordinateEntryField.DESTINATION_LATITUDE -> destinationCoordinateLatitude
        CoordinateEntryField.DESTINATION_LONGITUDE -> destinationCoordinateLongitude
    }

private fun RouteToolOptions.withCoordinateValue(
    field: CoordinateEntryField,
    value: Double,
): RouteToolOptions =
    when (field) {
        CoordinateEntryField.START_LATITUDE -> copy(startCoordinateLatitude = value)
        CoordinateEntryField.START_LONGITUDE -> copy(startCoordinateLongitude = value)
        CoordinateEntryField.DESTINATION_LATITUDE -> copy(destinationCoordinateLatitude = value)
        CoordinateEntryField.DESTINATION_LONGITUDE -> copy(destinationCoordinateLongitude = value)
    }

internal fun poiSearchSummary(state: PoiSearchUiState): String =
    when {
        state.isLoading -> "Searching..."
        !state.errorMessage.isNullOrBlank() -> state.errorMessage
        state.results.isNotEmpty() -> "${state.results.size} result(s)"
        state.query.isNotBlank() -> "Search again"
        else -> "Enabled .poi files"
    }

internal fun formatCoordinateValue(value: Double): String = String.format("%.5f", value)

internal fun normalizeLongitude(value: Double): Double {
    check(value.isFinite()) { "Longitude must be finite" }
    val remainder = value % 360.0
    return when {
        remainder > 180.0 -> remainder - 360.0
        remainder < -180.0 -> remainder + 360.0
        remainder == -180.0 && value > 0.0 -> 180.0
        else -> remainder
    }
}
