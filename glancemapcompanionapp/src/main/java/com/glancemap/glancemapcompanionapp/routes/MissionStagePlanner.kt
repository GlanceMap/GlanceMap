@file:Suppress("FunctionNaming")

package com.glancemap.glancemapcompanionapp.routes

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.glancemap.glancemapcompanionapp.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Suppress(
    "LongMethod",
    "LongParameterList",
    "TooGenericExceptionCaught",
    "SwallowedException",
    "CyclomaticComplexMethod",
)
// Keep unreadable GPX/storage failures in the dialog, with cancellation propagated and the draft intact.
@Composable
internal fun MissionStagePlanner(
    adventures: List<MissionAdventure>,
    routes: List<RouteLibraryRoute>,
    initialAdventureId: String?,
    initialRouteId: String?,
    focusDayId: String?,
    onLoad: suspend (String, String) -> MissionRouteStageSource,
    onImport: suspend (Uri) -> String,
    onApply: suspend (String, String, List<String>, List<Double>) -> Unit,
    onDismiss: () -> Unit,
    onApplied: () -> Unit,
) {
    var adventureId by rememberSaveable { mutableStateOf(initialAdventureId ?: adventures.firstOrNull()?.id) }
    var routeId by rememberSaveable { mutableStateOf(initialRouteId) }
    var source by remember { mutableStateOf<MissionRouteStageSource?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var generation by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val loadError = stringResource(R.string.stage_load_error)
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    busy = true
                    error = null
                    try {
                        routeId = onImport(uri)
                        generation++
                    } catch (failure: Exception) {
                        failure.rethrowCancellation()
                        error = failure.missionStageErrorMessage(loadError)
                    } finally {
                        busy = false
                    }
                }
            }
        }
    LaunchedEffect(adventureId, routeId, generation) {
        source = null
        error = null
        val adventure = adventureId
        val route = routeId
        if (adventure != null && route != null) {
            busy = true
            try {
                source = onLoad(adventure, route)
            } catch (failure: Exception) {
                failure.rethrowCancellation()
                error = failure.missionStageErrorMessage(loadError)
            } finally {
                busy = false
            }
        }
    }
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(
                        if (focusDayId == null) R.string.stage_plan_across_days else R.string.stage_change_arrival,
                    ),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(stringResource(R.string.stage_explanation), style = MaterialTheme.typography.bodySmall)
                if (adventures.isEmpty()) Text(stringResource(R.string.stage_create_adventure_first))
                if (focusDayId == null) {
                    StageSelection(
                        label = stringResource(R.string.adventure_list),
                        value = adventures.firstOrNull { it.id == adventureId }?.name.orEmpty(),
                        choices = adventures.map { it.id to it.name },
                        enabled = !busy,
                        onSelect = { adventureId = it },
                    )
                    StageSelection(
                        label = stringResource(R.string.adventure_choose_route),
                        value =
                            source?.route?.route?.displayName
                                ?: routes.firstOrNull { it.id == routeId }?.displayName.orEmpty(),
                        choices = routes.map { it.id to it.displayName },
                        enabled = !busy,
                        onSelect = { routeId = it },
                    )
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                        enabled = !busy,
                    ) { Text(stringResource(R.string.adventure_import_gpx)) }
                } else {
                    source
                        ?.route
                        ?.route
                        ?.displayName
                        ?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                }
                if (busy) CircularProgressIndicator()
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    if (source == null) {
                        TextButton(onClick = { generation++ }, enabled = !busy) {
                            Text(stringResource(R.string.mission_plan_try_again))
                        }
                    }
                }
                source?.let { loaded ->
                    MissionStageEditor(
                        source = loaded,
                        focusDayId = focusDayId,
                        enabled = !busy,
                        onApply = { ids, stops ->
                            scope.launch {
                                busy = true
                                error = null
                                try {
                                    onApply(requireNotNull(adventureId), loaded.route.route.id, ids, stops)
                                    onApplied()
                                } catch (failure: Exception) {
                                    failure.rethrowCancellation()
                                    error =
                                        failure.missionStageErrorMessage(
                                            resources.getString(R.string.stage_save_error),
                                        )
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    )
                }
                TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.common_action_cancel)) }
            }
        }
    }
}

@Composable
private fun StageSelection(
    label: String,
    value: String,
    choices: List<Pair<String, String>>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(value.ifBlank { label })
        }
        androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = {
                    expanded = false
                    onSelect(id)
                })
            }
        }
    }
}

private fun Exception.rethrowCancellation() {
    if (this is CancellationException) throw this
}

internal fun Exception.missionStageErrorMessage(fallback: String): String {
    if (this !is IllegalArgumentException) return fallback
    return message?.takeIf { it.isNotBlank() } ?: fallback
}
