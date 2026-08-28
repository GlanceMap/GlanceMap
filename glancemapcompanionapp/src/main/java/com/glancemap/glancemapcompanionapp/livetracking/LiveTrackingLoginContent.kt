@file:Suppress(
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
)

package com.glancemap.glancemapcompanionapp.livetracking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R

@Composable
internal fun ColumnScope.LoginJoinContent(
    onBack: () -> Unit,
    group: String,
    onGroupChange: (String) -> Unit,
    participantPassword: String,
    onParticipantPasswordChange: (String) -> Unit,
    isLoginJoinLoading: Boolean,
    loginJoinStatusMessage: String?,
    onLoginJoin: () -> Unit,
    showCreateGroupDialog: Boolean,
    createGroupPasswordConfirmation: String,
    onCreateGroupPasswordConfirmationChange: (String) -> Unit,
    onDismissCreateGroupDialog: () -> Unit,
    onConfirmCreateGroup: () -> Unit,
    scrollState: androidx.compose.foundation.ScrollState,
    contentSpacing: androidx.compose.ui.unit.Dp,
) {
    var isPasswordVisible by remember { mutableStateOf(false) }
    var isPasswordConfirmationVisible by remember { mutableStateOf(false) }

    HeaderRow(onBack = onBack) {
        Text(
            text = stringResource(R.string.live_tracking_setup_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f),
        )
    }

    ScrollableScreenContent(
        scrollState = scrollState,
        contentSpacing = contentSpacing,
    ) {
        TrackingPanel(title = stringResource(R.string.live_tracking_private_group_title)) {
            Text(
                text = stringResource(R.string.live_tracking_setup_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = group,
                onValueChange = onGroupChange,
                label = { Text(stringResource(R.string.live_tracking_group_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            PasswordField(
                value = participantPassword,
                onValueChange = onParticipantPasswordChange,
                label = { Text(stringResource(R.string.live_tracking_password_label)) },
                isVisible = isPasswordVisible,
                onVisibilityChange = { isPasswordVisible = it },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onLoginJoin,
                enabled = !isLoginJoinLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        isLoginJoinLoading -> stringResource(R.string.live_tracking_action_checking)
                        else -> stringResource(R.string.live_tracking_action_connect)
                    },
                )
            }
            loginJoinStatusMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (message.startsWith("Error", ignoreCase = true)) {
                            MaterialTheme.colorScheme.error
                        } else if (message.startsWith("Unable", ignoreCase = true)) {
                            MaterialTheme.colorScheme.error
                        } else if (message.contains("failed", ignoreCase = true)) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        }
    }

    if (showCreateGroupDialog) {
        AlertDialog(
            onDismissRequest = onDismissCreateGroupDialog,
            title = { Text(stringResource(R.string.live_tracking_create_group_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.live_tracking_create_group_message))
                    PasswordField(
                        value = createGroupPasswordConfirmation,
                        onValueChange = onCreateGroupPasswordConfirmationChange,
                        label = {
                            Text(stringResource(R.string.live_tracking_password_confirmation_label))
                        },
                        isVisible = isPasswordConfirmationVisible,
                        onVisibilityChange = { isPasswordConfirmationVisible = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = onConfirmCreateGroup,
                    enabled = createGroupPasswordConfirmation.isNotBlank(),
                ) {
                    Text(stringResource(R.string.live_tracking_action_create_group))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = onDismissCreateGroupDialog) {
                    Text(stringResource(R.string.common_action_cancel))
                }
            },
        )
    }
}
