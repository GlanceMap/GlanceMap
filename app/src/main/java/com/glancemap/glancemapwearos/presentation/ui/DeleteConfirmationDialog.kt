package com.glancemap.glancemapwearos.presentation.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapwearos.R

@Composable
// Keep the existing UI API; resource-backed defaults and labels do not change its inputs.
@Suppress("FunctionNaming", "LongParameterList")
fun DeleteConfirmationDialog(
    visible: Boolean,
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = stringResource(R.string.common_delete),
    dismissText: String = stringResource(R.string.common_cancel),
    messageTopPadding: Dp = 0.dp,
    messageBottomPadding: Dp = 0.dp,
) {
    if (!visible) return

    WearActionDialog(
        visible = visible,
        title = title,
        message = message,
        confirmText = confirmText,
        onConfirm = onConfirm,
        onDismissRequest = onDismiss,
        dismissText = dismissText,
        confirmRole = WearActionButtonRole.Destructive,
        messageTopPadding = messageTopPadding,
        messageBottomPadding = messageBottomPadding,
    )
}
