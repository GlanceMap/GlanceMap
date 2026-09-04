@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList")

package com.glancemap.glancemapcompanionapp.routes

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.SendToMobile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun RouteLibraryScreen(
    uiState: RouteLibraryUiState,
    onBack: () -> Unit,
    onImportRoute: (Uri) -> Unit,
    onSelectRoute: (String) -> Unit,
    onOpenWeather: (RouteLibraryRoute) -> Unit,
    onSendToWatch: (RouteLibraryRoute) -> Unit,
) {
    val importRouteLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onImportRoute(uri)
        }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to home",
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Routes",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = "Build a briefing before you hike",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Button(
            onClick = { importRouteLauncher.launch(arrayOf("*/*")) },
            enabled = !uiState.isImporting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (uiState.isImporting) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp).width(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text("Importing route…")
            } else {
                Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Import GPX route")
            }
        }

        uiState.message?.let { message ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
            ) {
                Text(
                    text = message,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        when {
            uiState.isLoading -> {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            uiState.routes.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "No routes yet",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Import a GPX to see distance, climb and the first 30 minutes.",
                        modifier = Modifier.padding(horizontal = 24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(uiState.routes, key = RouteLibraryRoute::id) { route ->
                        RouteLibraryRouteCard(
                            route = route,
                            selected = route.id == uiState.selectedRouteId,
                            onSelect = { onSelectRoute(route.id) },
                            onOpenWeather = { onOpenWeather(route) },
                            onSendToWatch = { onSendToWatch(route) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteLibraryRouteCard(
    route: RouteLibraryRoute,
    selected: Boolean,
    onSelect: () -> Unit,
    onOpenWeather: () -> Unit,
    onSendToWatch: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = route.displayName,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Selected route",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Text(
                text = routeSummary(route.summary),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = firstThirtyMinutesSummary(route.summary),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onSelect,
                    enabled = !selected,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (selected) "Selected" else "Choose")
                }
                Button(
                    onClick = onSendToWatch,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.SendToMobile,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Send")
                }
            }
            OutlinedButton(
                onClick = onOpenWeather,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Weather")
            }
        }
    }
}

private fun routeSummary(summary: RouteLibrarySummary): String =
    listOf(
        formatDistance(summary.distanceMeters),
        "+${summary.elevationGainMeters.roundToInt()} m",
        formatDuration(summary.estimatedDurationSeconds),
    ).joinToString("  •  ")

private fun firstThirtyMinutesSummary(summary: RouteLibrarySummary): String =
    "First 30 min  •  ${formatDistance(summary.firstThirtyMinutesDistanceMeters)}  •  " +
        "+${summary.firstThirtyMinutesAscentMeters.roundToInt()} m climb"

private fun formatDistance(distanceMeters: Double): String =
    if (distanceMeters < 1_000.0) {
        "${distanceMeters.roundToInt()} m"
    } else {
        "%.1f km".format(distanceMeters / 1_000.0)
    }

private fun formatDuration(durationSeconds: Double): String {
    val roundedMinutes = (durationSeconds / 60.0).roundToInt().coerceAtLeast(1)
    val hours = roundedMinutes / 60
    val minutes = roundedMinutes % 60
    return if (hours == 0) "$minutes min" else "$hours h ${minutes.toString().padStart(2, '0')} min"
}
