package com.glancemap.glancemapcompanionapp.weather

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R
import com.glancemap.glancemapcompanionapp.map.PhoneMapPopupCard

@Composable
internal fun weatherMapSelectionCard(
    modifier: Modifier,
    onUseCenter: () -> Unit,
    onCancel: () -> Unit,
) {
    PhoneMapPopupCard(
        title = stringResource(R.string.weather_choose_location),
        modifier = modifier,
        onDismiss = onCancel,
    ) {
        Text(stringResource(R.string.weather_choose_location_hint))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onUseCenter) { Text(stringResource(R.string.discovery_use_center)) }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_action_cancel)) }
        }
    }
}
