@file:Suppress(
    "CyclomaticComplexMethod",
    "FunctionName",
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
)

package com.glancemap.glancemapwearos.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

@Composable
fun RenameValueDialog(
    visible: Boolean,
    title: String,
    initialValue: String,
    isSaving: Boolean,
    error: String?,
    autoFocusInput: Boolean = true,
    fullScreen: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var draftValue by remember(initialValue) { mutableStateOf(initialValue) }

    if (!visible) return

    val adaptive = rememberWearAdaptiveSpec()
    val dialogWidthFraction = 0.84f
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val scrollState = rememberScrollState()
    val textFieldVerticalPadding = 10.dp
    val maxDialogHeight =
        (
            adaptive.heightDp.dp -
                adaptive.dialogVerticalPadding * 2 -
                18.dp
        ).coerceAtLeast(132.dp)

    LaunchedEffect(visible, isSaving) {
        if (visible && !isSaving && autoFocusInput) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    if (fullScreen) {
        WearFormDialog(
            visible = visible,
            title = title,
            onDismiss = onDismiss,
        ) { formTokens ->
            RenameValueFormContent(
                draftValue = draftValue,
                onDraftValueChange = { draftValue = it },
                isSaving = isSaving,
                error = error,
                focusRequester = focusRequester,
                textFieldVerticalPadding = formTokens.textFieldVerticalPadding,
                controlModifier = formTokens.controlModifier,
                buttonMinHeight = formTokens.buttonMinHeight,
                onDismiss = onDismiss,
                onConfirm = onConfirm,
            )
        }
        return
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier =
                Modifier
                    .wearDialogWidth(
                        roundFraction = dialogWidthFraction,
                        squareFraction = dialogWidthFraction,
                    ).background(
                        Color.Black,
                        RoundedCornerShape(adaptive.dialogCornerRadius),
                    ).padding(
                        horizontal = adaptive.dialogHorizontalPadding,
                        vertical = adaptive.dialogVerticalPadding,
                    ).heightIn(max = maxDialogHeight)
                    .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
            )

            BasicTextField(
                value = draftValue,
                onValueChange = { draftValue = it.take(64) },
                singleLine = true,
                textStyle =
                    TextStyle(
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .background(
                            Color(0xFF1F1F1F),
                            RoundedCornerShape(12.dp),
                        ).padding(horizontal = 12.dp, vertical = textFieldVerticalPadding),
                decorationBox = { innerTextField ->
                    if (draftValue.isBlank()) {
                        Text(
                            text = "Enter name",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.45f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    innerTextField()
                },
            )

            error?.takeIf { it.isNotBlank() }?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }

            Button(
                onClick = { onConfirm(draftValue) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                enabled = !isSaving,
            ) {
                Text(if (isSaving) "Saving..." else "Save")
            }

            Button(
                onClick = onDismiss,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                enabled = !isSaving,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.12f),
                        contentColor = Color.White,
                    ),
            ) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun RenameValueFormContent(
    draftValue: String,
    onDraftValueChange: (String) -> Unit,
    isSaving: Boolean,
    error: String?,
    focusRequester: FocusRequester,
    textFieldVerticalPadding: Dp,
    controlModifier: Modifier,
    buttonMinHeight: Dp,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    BasicTextField(
        value = draftValue,
        onValueChange = { onDraftValueChange(it.take(64)) },
        singleLine = true,
        textStyle =
            TextStyle(
                color = Color.White,
                textAlign = TextAlign.Center,
            ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier =
            controlModifier
                .focusRequester(focusRequester)
                .background(
                    Color(0xFF1F1F1F),
                    RoundedCornerShape(12.dp),
                ).padding(horizontal = 12.dp, vertical = textFieldVerticalPadding),
        decorationBox = { innerTextField ->
            if (draftValue.isBlank()) {
                Text(
                    text = "Enter name",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.45f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            innerTextField()
        },
    )

    error?.takeIf { it.isNotBlank() }?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Button(
        onClick = { onConfirm(draftValue) },
        modifier =
            controlModifier
                .heightIn(min = buttonMinHeight),
        enabled = !isSaving,
    ) {
        Text(if (isSaving) "Saving..." else "Save")
    }

    Button(
        onClick = onDismiss,
        modifier =
            controlModifier
                .heightIn(min = buttonMinHeight),
        enabled = !isSaving,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = Color.White.copy(alpha = 0.12f),
                contentColor = Color.White,
            ),
    ) {
        Text("Cancel")
    }
}
