@file:Suppress("FunctionName", "LongParameterList", "MatchingDeclarationName")

package com.glancemap.glancemapwearos.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.rotary.onPreRotaryScrollEvent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text

enum class WearActionButtonRole {
    Primary,
    Secondary,
    Destructive,
}

data class WearActionDialogButton(
    val text: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val role: WearActionButtonRole = WearActionButtonRole.Primary,
    val icon: ImageVector? = null,
    val iconTint: Color? = null,
)

@Composable
fun WearActionDialog(
    visible: Boolean,
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    dismissText: String? = null,
    onDismiss: () -> Unit = onDismissRequest,
    confirmEnabled: Boolean = true,
    dismissEnabled: Boolean = true,
    confirmRole: WearActionButtonRole = WearActionButtonRole.Primary,
    messageTopPadding: Dp = 0.dp,
    messageBottomPadding: Dp = 0.dp,
    pinPrimaryButton: Boolean = false,
) {
    WearActionDialog(
        visible = visible,
        title = title,
        onDismissRequest = onDismissRequest,
        pinPrimaryButton = pinPrimaryButton,
        buttons =
            buildList {
                add(
                    WearActionDialogButton(
                        text = confirmText,
                        onClick = onConfirm,
                        enabled = confirmEnabled,
                        role = confirmRole,
                    ),
                )
                if (dismissText != null) {
                    add(
                        WearActionDialogButton(
                            text = dismissText,
                            onClick = onDismiss,
                            enabled = dismissEnabled,
                            role = WearActionButtonRole.Secondary,
                        ),
                    )
                }
            },
    ) {
        Text(
            text = message,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = messageTopPadding, bottom = messageBottomPadding),
        )
    }
}

@Composable
fun WearActionDialog(
    visible: Boolean,
    title: String,
    onDismissRequest: () -> Unit,
    buttons: List<WearActionDialogButton>,
    backgroundColor: Color = Color.Black,
    pinPrimaryButton: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!visible) return

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        WearActionSurface(
            title = title,
            buttons = buttons,
            backgroundColor = backgroundColor,
            pinPrimaryButton = pinPrimaryButton,
            content = content,
        )
    }
}

@Composable
fun WearActionScreen(
    title: String,
    buttons: List<WearActionDialogButton>,
    backgroundColor: Color = Color.Black,
    content: @Composable ColumnScope.() -> Unit,
) {
    WearActionSurface(
        title = title,
        buttons = buttons,
        backgroundColor = backgroundColor,
        content = content,
    )
}

@Composable
private fun WearActionSurface(
    title: String,
    buttons: List<WearActionDialogButton>,
    backgroundColor: Color,
    pinPrimaryButton: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    val focusRequester = androidx.compose.runtime.remember { FocusRequester() }
    val metrics = rememberWearActionLayoutMetrics()
    val pinnedButton = buttons.firstOrNull().takeIf { pinPrimaryButton }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .onPreRotaryScrollEvent { event ->
                    scrollState.dispatchRawDelta(event.verticalScrollPixels) != 0f
                }.focusRequester(focusRequester)
                .focusable()
                .background(backgroundColor),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(horizontal = metrics.horizontalPadding)
                            .padding(
                                top = metrics.topPadding,
                                bottom = if (pinnedButton != null) 8.dp else metrics.bottomPadding,
                            ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Top),
                ) {
                    WearActionContent(
                        title = title,
                        buttons = if (pinnedButton != null) buttons.drop(1) else buttons,
                        metrics = metrics,
                        content = content,
                    )
                }
                if (pinnedButton == null) {
                    ScrollIndicator(state = scrollState, modifier = Modifier.align(Alignment.CenterEnd))
                }
            }
            if (pinnedButton != null) {
                WearPinnedActionButton(pinnedButton, metrics)
            }
        }
        if (pinnedButton != null) {
            WearScreenEdgeScrollIndicator(scrollState = scrollState)
        }
    }
}

@Composable
private fun WearPinnedActionButton(
    button: WearActionDialogButton,
    metrics: WearActionLayoutMetrics,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = metrics.horizontalPadding)
                .padding(bottom = metrics.bottomPadding / 2),
        contentAlignment = Alignment.Center,
    ) {
        cappedFontScale(maxFontScale = metrics.actionFontScaleCap) {
            // Keep the fixed button inside the curved bottom edge on round watches.
            WearActionButton(button = button, widthFraction = metrics.controlWidthFraction.coerceAtMost(0.72f))
        }
    }
}

private data class WearActionLayoutMetrics(
    val horizontalPadding: Dp,
    val topPadding: Dp,
    val bottomPadding: Dp,
    val controlWidthFraction: Float,
    val textWidthFraction: Float,
    val actionFontScaleCap: Float,
)

@Composable
private fun rememberWearActionLayoutMetrics(): WearActionLayoutMetrics {
    val adaptive = rememberWearAdaptiveSpec()
    val highFontTopInset = if (adaptive.isRound && adaptive.fontScale >= 1.25f) 8.dp else 0.dp
    val textWidthFraction =
        when {
            !adaptive.isRound -> 1f
            adaptive.fontScale >= 1.45f -> 0.72f
            adaptive.fontScale >= 1.25f -> 0.78f
            else -> 0.86f
        }
    return WearActionLayoutMetrics(
        horizontalPadding = adaptive.dialogHorizontalPadding,
        topPadding =
            adaptive.dialogVerticalPadding +
                adaptive.headerTopSafeInset +
                (if (adaptive.isRound) 18.dp else 0.dp) +
                highFontTopInset,
        bottomPadding = adaptive.dialogVerticalPadding + if (adaptive.isRound) 42.dp else 12.dp,
        controlWidthFraction = if (adaptive.isRound) 0.86f else 1f,
        textWidthFraction = textWidthFraction,
        actionFontScaleCap = if (adaptive.isRound && adaptive.fontScale >= 1.25f) 1.0f else 1.12f,
    )
}

@Composable
private fun ColumnScope.WearActionContent(
    title: String,
    buttons: List<WearActionDialogButton>,
    metrics: WearActionLayoutMetrics,
    content: @Composable ColumnScope.() -> Unit,
) {
    cappedFontScale(maxFontScale = metrics.actionFontScaleCap) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(metrics.textWidthFraction),
        )
        Column(
            modifier = Modifier.fillMaxWidth(metrics.textWidthFraction),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
        buttons.forEach { button ->
            WearActionButton(button = button, widthFraction = metrics.controlWidthFraction)
        }
    }
}

@Composable
private fun WearActionButton(
    button: WearActionDialogButton,
    widthFraction: Float,
) {
    Button(
        onClick = button.onClick,
        enabled = button.enabled,
        colors = actionButtonColors(button.role),
        modifier =
            Modifier
                .fillMaxWidth(widthFraction)
                .heightIn(min = 44.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            button.icon?.let { icon ->
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = button.iconTint ?: Color.Unspecified,
                    modifier = Modifier.size(18.dp),
                )
                Box(modifier = Modifier.width(6.dp))
            }
            Text(
                text = button.text,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun actionButtonColors(role: WearActionButtonRole) =
    when (role) {
        WearActionButtonRole.Primary -> ButtonDefaults.buttonColors()
        WearActionButtonRole.Secondary ->
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        WearActionButtonRole.Destructive ->
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
    }
