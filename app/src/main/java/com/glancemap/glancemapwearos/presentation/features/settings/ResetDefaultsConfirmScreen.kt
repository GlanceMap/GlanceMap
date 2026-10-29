@file:Suppress("FunctionNaming")

package com.glancemap.glancemapwearos.presentation.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.presentation.ui.WearActionButtonRole
import com.glancemap.glancemapwearos.presentation.ui.WearActionDialogButton
import com.glancemap.glancemapwearos.presentation.ui.WearActionScreen

@Composable
fun ResetDefaultsConfirmScreen(
    onCancel: () -> Unit,
    onConfirmReset: () -> Unit,
) {
    WearActionScreen(
        title = stringResource(R.string.settings_reset_to_defaults),
        buttons =
            listOf(
                WearActionDialogButton(
                    text = stringResource(R.string.settings_reset_now),
                    onClick = onConfirmReset,
                    role = WearActionButtonRole.Destructive,
                ),
                WearActionDialogButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = onCancel,
                    role = WearActionButtonRole.Secondary,
                ),
            ),
    ) {
        Text(
            text = stringResource(R.string.settings_this_resets_settings_and_theme_preferences),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}
