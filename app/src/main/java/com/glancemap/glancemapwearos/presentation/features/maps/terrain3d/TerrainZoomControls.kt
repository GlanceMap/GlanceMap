package com.glancemap.glancemapwearos.presentation.features.maps.terrain3d

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.wear.compose.foundation.AnchorType
import androidx.wear.compose.foundation.ArcPaddingValues
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.CurvedModifier
import androidx.wear.compose.foundation.curvedComposable
import androidx.wear.compose.foundation.padding
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import com.glancemap.glancemapwearos.R

// Compose UI components use PascalCase names.
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun TerrainZoomControls(
    buttonSize: Dp,
    iconSize: Dp,
    edgePadding: Dp,
    showPlus: Boolean,
    showMinus: Boolean,
    onZoom: (Int) -> Unit,
) {
    for (step in listOf(1, -1)) {
        if (if (step > 0) !showPlus else !showMinus) continue
        CurvedLayout(
            modifier = Modifier.fillMaxSize(),
            anchor = if (step > 0) 308f else 330f,
            anchorType = AnchorType.Center,
        ) {
            curvedComposable(modifier = CurvedModifier.padding(ArcPaddingValues(outer = edgePadding))) {
                IconButton(
                    onClick = { onZoom(step) },
                    modifier = Modifier.size(buttonSize),
                    colors =
                        IconButtonDefaults.iconButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.7f),
                            contentColor = Color.White,
                        ),
                ) {
                    Icon(
                        imageVector = if (step > 0) Icons.Default.Add else Icons.Default.Remove,
                        contentDescription =
                            stringResource(if (step > 0) R.string.navigation_zoom_in else R.string.navigation_zoom_out),
                        modifier = Modifier.size(iconSize).rotate(if (step > 0) 52f else 120f),
                    )
                }
            }
        }
    }
}
