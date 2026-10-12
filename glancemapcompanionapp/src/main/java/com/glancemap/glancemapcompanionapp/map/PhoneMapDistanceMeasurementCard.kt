package com.glancemap.glancemapcompanionapp.map

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
@Suppress("LongParameterList") // The card keeps its six explicit UI and data inputs.
internal fun BoxScope.phoneMapDistanceMeasurementCard(
    measurement: PhoneMapDistanceMeasurement,
    elevationRepository: PhoneElevationRepository,
    terrainDataVersion: Long,
    isMetric: Boolean,
    onClear: () -> Unit,
    onlineElevationRepository: PhoneOnlineElevationRepository? = null,
) {
    val elevations =
        phoneMapMeasurementElevations(measurement, terrainDataVersion, elevationRepository, onlineElevationRepository)
    Card(
        modifier =
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .widthIn(max = 280.dp),
    ) {
        Row(
            modifier = Modifier.width(IntrinsicSize.Max).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text =
                        stringResource(
                            R.string.map_measurement_distance_value,
                            formatPhoneMapDistanceMeters(measurement.distanceMeters),
                        ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text =
                        stringResource(
                            R.string.map_measurement_elevation_values,
                            formatPhoneMapElevation(elevations?.first ?: Double.NaN, isMetric),
                            formatPhoneMapElevation(elevations?.second ?: Double.NaN, isMetric),
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClear, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.common_action_close),
                )
            }
        }
    }
}

@Composable
private fun phoneMapMeasurementElevations(
    measurement: PhoneMapDistanceMeasurement,
    terrainDataVersion: Long,
    local: PhoneElevationRepository,
    online: PhoneOnlineElevationRepository?,
): Pair<Double?, Double?>? {
    // New coordinates or DEM data must never display the previous endpoints' elevations.
    var elevations by remember(measurement, terrainDataVersion, local, online) {
        mutableStateOf<Pair<Double?, Double?>?>(null)
    }
    LaunchedEffect(measurement, terrainDataVersion, local, online) {
        elevations =
            withContext(Dispatchers.IO) {
                val first = local.elevationAt(measurement.first.latitude, measurement.first.longitude)
                ensureActive()
                val second = local.elevationAt(measurement.second.latitude, measurement.second.longitude)
                first to second
            }
        val localElevations = elevations
        if (localElevations?.first == null && online != null) {
            val first = phoneMapElevationAt(measurement.first, local, online)
            elevations = first to localElevations?.second
        }
        ensureActive()
        if (localElevations?.second == null && online != null) {
            val second = phoneMapElevationAt(measurement.second, local, online)
            elevations = elevations?.first to second
        }
    }
    return elevations
}
