@file:Suppress(
    "CyclomaticComplexMethod",
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
    "ReturnCount",
)

package com.glancemap.glancemapcompanionapp.livetracking

import android.util.Patterns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContactMail
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glancemap.glancemapcompanionapp.R

@Composable
internal fun ColumnScope.SettingsContent(
    onBack: () -> Unit,
    group: String,
    onChangeGroup: () -> Unit,
    userName: String,
    onUserNameChange: (String) -> Unit,
    notificationEmailInput: String,
    onNotificationEmailInputChange: (String) -> Unit,
    notificationEmailAddresses: List<String>,
    onNotificationEmailAdd: (String) -> Unit,
    onNotificationEmailRemove: (String) -> Unit,
    onPickNotificationEmailFromContacts: () -> Unit,
    alertRecipientInput: String,
    onAlertRecipientInputChange: (String) -> Unit,
    alertRecipients: List<String>,
    onAlertRecipientAdd: (AlertRecipient) -> Unit,
    onAlertRecipientRemove: (String) -> Unit,
    onPickAlertEmailFromContacts: () -> Unit,
    onPickAlertPhoneFromContacts: () -> Unit,
    isValidatingAlertRecipient: Boolean,
    alertRecipientStatusMessage: String?,
    stuckAlarmMinutes: String,
    onStuckAlarmMinutesChange: (String) -> Unit,
    updateIntervalSeconds: Int,
    onUpdateIntervalSecondsChange: (Int) -> Unit,
    isSavingSettings: Boolean,
    saveSettingsStatusMessage: String?,
    onSaveSettings: () -> Unit,
    scrollState: androidx.compose.foundation.ScrollState,
    contentSpacing: androidx.compose.ui.unit.Dp,
) {
    HeaderRow(onBack = onBack) {
        Text(
            text = stringResource(R.string.live_tracking_setup_title),
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }

    ScrollableScreenContent(
        scrollState = scrollState,
        contentSpacing = contentSpacing,
    ) {
        TrackingPanel(title = stringResource(R.string.live_tracking_private_group_title)) {
            Text(
                text =
                    stringResource(
                        R.string.live_tracking_connected_to_group,
                        if (group.isBlank()) {
                            stringResource(R.string.live_tracking_private_group)
                        } else {
                            group.trim()
                        },
                    ),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.live_tracking_setup_group_options_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onChangeGroup,
                enabled = !isSavingSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.live_tracking_action_change_group))
            }
        }

        TrackingPanel(title = stringResource(R.string.live_tracking_participant_panel_title)) {
            OutlinedTextField(
                value = userName,
                onValueChange = onUserNameChange,
                label = { Text(stringResource(R.string.live_tracking_participant_name_label)) },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        TrackingPanel(title = stringResource(R.string.live_tracking_notifications_title)) {
            Text(
                text =
                    stringResource(
                        R.string.live_tracking_gps_update_frequency,
                        formatUpdateInterval(updateIntervalSeconds),
                    ),
                style = MaterialTheme.typography.labelMedium,
            )
            FrequencyPresetGrid(
                selectedSeconds = updateIntervalSeconds,
                onSelected = onUpdateIntervalSecondsChange,
            )
            EmailAddressInput(
                label = stringResource(R.string.live_tracking_notification_recipients_label),
                input = notificationEmailInput,
                onInputChange = onNotificationEmailInputChange,
                addresses = notificationEmailAddresses,
                onAdd = onNotificationEmailAdd,
                onRemove = onNotificationEmailRemove,
                onPickFromContacts = onPickNotificationEmailFromContacts,
            )
            AlertRecipientInput(
                label = stringResource(R.string.live_tracking_alert_recipients_label),
                input = alertRecipientInput,
                onInputChange = onAlertRecipientInputChange,
                recipients = alertRecipients,
                onAdd = onAlertRecipientAdd,
                onRemove = onAlertRecipientRemove,
                onPickEmailFromContacts = onPickAlertEmailFromContacts,
                onPickPhoneFromContacts = onPickAlertPhoneFromContacts,
                isValidating = isValidatingAlertRecipient,
                statusMessage = alertRecipientStatusMessage,
            )
            NoMovementAlertInput(
                minutes = stuckAlarmMinutes,
                onMinutesChange = onStuckAlarmMinutesChange,
            )
        }

        Button(
            onClick = onSaveSettings,
            enabled = !isSavingSettings,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (isSavingSettings) {
                        R.string.live_tracking_action_saving
                    } else {
                        R.string.live_tracking_action_save_return
                    },
                ),
            )
        }
        saveSettingsStatusMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (
                        message.startsWith("Save failed", ignoreCase = true) ||
                        message.contains("required", ignoreCase = true)
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
    }
}

@Composable
internal fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable () -> Unit,
    isVisible: Boolean,
    onVisibilityChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        singleLine = true,
        visualTransformation =
            if (isVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Password,
            ),
        trailingIcon = {
            IconButton(
                onClick = { onVisibilityChange(!isVisible) },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector =
                        if (isVisible) {
                            Icons.Filled.Visibility
                        } else {
                            Icons.Filled.VisibilityOff
                        },
                    contentDescription =
                        stringResource(
                            if (isVisible) {
                                R.string.live_tracking_hide_password_content_description
                            } else {
                                R.string.live_tracking_show_password_content_description
                            },
                        ),
                    modifier = Modifier.size(18.dp),
                )
            }
        },
        modifier = modifier,
    )
}

@Composable
@Suppress("FunctionNaming", "LongMethod")
private fun NoMovementAlertInput(
    minutes: String,
    onMinutesChange: (String) -> Unit,
) {
    val isDisabled = minutes == "-1"
    val validationMessage = validateNoMovementAlertMinutes(minutes)
    var lastEnabledMinutes by remember {
        mutableStateOf(
            minutes.takeUnless { it == "-1" } ?: DEFAULT_NO_MOVEMENT_ALERT_MINUTES,
        )
    }

    LaunchedEffect(minutes) {
        if (minutes != "-1" && validateNoMovementAlertMinutes(minutes) == null) {
            lastEnabledMinutes = minutes
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = !isDisabled,
                onCheckedChange = { enabled ->
                    onMinutesChange(
                        if (enabled) {
                            lastEnabledMinutes
                        } else {
                            "-1"
                        },
                    )
                },
            )
            Text(
                text = stringResource(R.string.live_tracking_enable_no_movement_alerts),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text = stringResource(R.string.live_tracking_no_movement_alert_time),
            style = MaterialTheme.typography.labelMedium,
            color =
                if (isDisabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = if (isDisabled) "" else minutes,
                onValueChange = { value ->
                    if (validateNoMovementAlertMinutes(value) == null && value != "-1") {
                        lastEnabledMinutes = value
                    }
                    onMinutesChange(value)
                },
                enabled = !isDisabled,
                placeholder = { Text(DEFAULT_NO_MOVEMENT_ALERT_MINUTES) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = !isDisabled && validationMessage != null,
                supportingText =
                    if (!isDisabled) {
                        {
                            Text(
                                validationMessage
                                    ?: stringResource(R.string.live_tracking_no_movement_minimum),
                            )
                        }
                    } else {
                        null
                    },
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.live_tracking_minutes_label),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun EmailAddressInput(
    label: String,
    input: String,
    onInputChange: (String) -> Unit,
    addresses: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onPickFromContacts: () -> Unit,
) {
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val invalidEmailError = stringResource(R.string.live_tracking_invalid_email_error)
    val duplicateEmailError = stringResource(R.string.live_tracking_email_already_added_error)
    val email = input.trim().trimEnd(',', ';').lowercase()
    val isEmailValid = email.isNotBlank() && Patterns.EMAIL_ADDRESS.matcher(email).matches()
    val isDuplicate = addresses.any { it.equals(email, ignoreCase = true) }
    val canAddEmail = isEmailValid && !isDuplicate

    fun submitEmail(): Boolean {
        if (email.isBlank()) return false
        if (!isEmailValid) {
            errorMessage = invalidEmailError
            return true
        }
        if (isDuplicate) {
            errorMessage = duplicateEmailError
            return true
        }
        onAdd(email)
        onInputChange("")
        errorMessage = null
        return true
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = {
                    onInputChange(it)
                    errorMessage = null
                },
                placeholder = { Text(stringResource(R.string.live_tracking_email_placeholder)) },
                trailingIcon = {
                    IconButton(onClick = onPickFromContacts) {
                        Icon(
                            imageVector = Icons.Filled.ContactMail,
                            contentDescription =
                                stringResource(
                                    R.string.live_tracking_pick_email_content_description,
                                ),
                        )
                    }
                },
                supportingText = errorMessage?.let { message -> { Text(message) } },
                isError = errorMessage != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submitEmail() }),
                modifier =
                    Modifier
                        .weight(1f)
                        .onPreviewKeyEvent { event ->
                            when {
                                event.key != Key.Enter -> false
                                event.type == KeyEventType.KeyDown -> submitEmail()
                                else -> true
                            }
                        },
            )
            Button(onClick = { submitEmail() }, enabled = canAddEmail) {
                Text(stringResource(R.string.live_tracking_action_add))
            }
        }
        if (addresses.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                addresses.forEach { email ->
                    InputChip(
                        selected = false,
                        onClick = { onRemove(email) },
                        label = {
                            Text(
                                text = email,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription =
                                    stringResource(
                                        R.string.live_tracking_remove_email_content_description,
                                        email,
                                    ),
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AlertRecipientInput(
    label: String,
    input: String,
    onInputChange: (String) -> Unit,
    recipients: List<String>,
    onAdd: (AlertRecipient) -> Unit,
    onRemove: (String) -> Unit,
    onPickEmailFromContacts: () -> Unit,
    onPickPhoneFromContacts: () -> Unit,
    isValidating: Boolean,
    statusMessage: String?,
) {
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val invalidRecipientError = stringResource(R.string.live_tracking_invalid_recipient_error)
    val duplicateRecipientError = stringResource(R.string.live_tracking_recipient_already_added_error)
    val recipient = normalizedAlertRecipient(input)
    val isDuplicate =
        recipient != null && recipients.any { it.equals(recipient.value, ignoreCase = true) }
    val canAddRecipient = recipient != null && !isDuplicate && !isValidating

    fun submitRecipient(): Boolean {
        if (input.isBlank()) return false
        if (recipient == null) {
            errorMessage = invalidRecipientError
            return true
        }
        if (isDuplicate) {
            errorMessage = duplicateRecipientError
            return true
        }

        onAdd(recipient)
        errorMessage = null
        return true
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = {
                    onInputChange(it)
                    errorMessage = null
                },
                placeholder = {
                    Text(stringResource(R.string.live_tracking_email_or_phone_placeholder))
                },
                trailingIcon = {
                    Row {
                        IconButton(
                            onClick = onPickEmailFromContacts,
                            enabled = !isValidating,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContactMail,
                                contentDescription =
                                    stringResource(
                                        R.string.live_tracking_pick_email_content_description,
                                    ),
                            )
                        }
                        IconButton(
                            onClick = onPickPhoneFromContacts,
                            enabled = !isValidating,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Phone,
                                contentDescription =
                                    stringResource(
                                        R.string.live_tracking_pick_phone_content_description,
                                    ),
                            )
                        }
                    }
                },
                supportingText =
                    (errorMessage ?: statusMessage)?.let { message ->
                        { Text(message) }
                    },
                isError = errorMessage != null || statusMessage != null,
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions = KeyboardActions(onDone = { submitRecipient() }),
                modifier =
                    Modifier
                        .weight(1f)
                        .onPreviewKeyEvent { event ->
                            when {
                                event.key != Key.Enter -> false
                                event.type == KeyEventType.KeyDown -> submitRecipient()
                                else -> true
                            }
                        },
            )
            Button(
                onClick = { submitRecipient() },
                enabled = canAddRecipient,
            ) {
                Text(
                    stringResource(
                        if (isValidating) {
                            R.string.live_tracking_action_checking
                        } else {
                            R.string.live_tracking_action_add
                        },
                    ),
                )
            }
        }
        if (recipients.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                recipients.forEach { recipientValue ->
                    InputChip(
                        selected = false,
                        onClick = { onRemove(recipientValue) },
                        label = {
                            Text(
                                text = recipientValue,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription =
                                    stringResource(
                                        R.string.live_tracking_remove_email_content_description,
                                        recipientValue,
                                    ),
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FrequencyPresetGrid(
    selectedSeconds: Int,
    onSelected: (Int) -> Unit,
) {
    val presets =
        listOf(
            15 to formatUpdateIntervalCompact(15),
            30 to formatUpdateIntervalCompact(30),
            60 to formatUpdateIntervalCompact(60),
            120 to formatUpdateIntervalCompact(120),
            300 to formatUpdateIntervalCompact(300),
            600 to formatUpdateIntervalCompact(600),
        )
    presets.chunked(3).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            row.forEach { (seconds, label) ->
                val selected = selectedSeconds == seconds
                if (selected) {
                    Button(
                        onClick = { onSelected(seconds) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(label)
                    }
                } else {
                    OutlinedButton(
                        onClick = { onSelected(seconds) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(label)
                    }
                }
            }
            repeat(3 - row.size) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun formatUpdateInterval(seconds: Int): String {
    if (seconds < 60) {
        return pluralStringResource(
            R.plurals.live_tracking_duration_seconds,
            seconds,
            seconds,
        )
    }
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return if (remainingSeconds == 0) {
        stringResource(R.string.live_tracking_duration_minutes, minutes)
    } else {
        stringResource(R.string.live_tracking_duration_minutes_seconds, minutes, remainingSeconds)
    }
}

@Composable
private fun formatUpdateIntervalCompact(seconds: Int): String =
    if (seconds < 60) {
        stringResource(R.string.live_tracking_duration_seconds_compact, seconds)
    } else {
        stringResource(R.string.live_tracking_duration_minutes_compact, seconds / 60)
    }
