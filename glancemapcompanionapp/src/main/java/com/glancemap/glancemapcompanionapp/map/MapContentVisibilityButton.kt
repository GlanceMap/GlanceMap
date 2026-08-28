package com.glancemap.glancemapcompanionapp.map

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

@Composable
internal fun mapContentVisibilityButton(
    isVisible: Boolean,
    onClick: () -> Unit,
    @StringRes hideContentDescription: Int,
    @StringRes showContentDescription: Int,
) {
    FilledTonalIconButton(onClick = onClick) {
        Icon(
            imageVector = if (isVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
            contentDescription =
                stringResource(
                    if (isVisible) hideContentDescription else showContentDescription,
                ),
        )
    }
}
