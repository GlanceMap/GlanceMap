package com.glancemap.glancemapcompanionapp.map

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LocationDisabled
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R

@Composable
internal fun PhoneMapModeButton(
    mode: PhoneMapMode,
    onClick: () -> Unit,
    onToggleOffline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .size(40.dp)
                .clip(CircleShape)
                .combinedClickable(
                    role = Role.Button,
                    onClick = onClick,
                    onLongClick = onToggleOffline,
                    onLongClickLabel =
                        stringResource(
                            if (mode.isOffline) R.string.map_mode_offline_exit else R.string.map_mode_offline_enter,
                        ),
                ),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter =
                    when {
                        mode.isOffline -> rememberVectorPainter(Icons.Filled.LocationDisabled)
                        mode.isDetachedFromLocation -> rememberVectorPainter(Icons.Filled.MyLocation)
                        mode.orientation == PhoneMapOrientation.HEADING_UP -> rememberVectorPainter(Icons.Filled.Explore)
                        else -> painterResource(R.drawable.map_mode_north_up)
                    },
                contentDescription = stringResource(R.string.map_mode_content_description, stringResource(mode.labelResource())),
                tint = if (mode.isOffline) Color(0xFFE53935) else LocalContentColor.current,
            )
        }
    }
}
