@file:Suppress("FunctionName", "FunctionNaming", "LongParameterList")

package com.glancemap.glancemapwearos.presentation.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LocationDisabled
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.tooling.preview.devices.WearDevices
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.presentation.features.settings.SettingsViewModel
import com.glancemap.glancemapwearos.presentation.navigation.WatchRoutes
import com.glancemap.glancemapwearos.presentation.ui.WearScreenEdgeScrollIndicator
import com.glancemap.glancemapwearos.presentation.ui.WearScreenSize
import com.glancemap.glancemapwearos.presentation.ui.cappedFontScale
import com.glancemap.glancemapwearos.presentation.ui.rememberWearAdaptiveSpec
import com.glancemap.glancemapwearos.presentation.ui.rememberWearScreenSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf

@Composable
fun MainScreen(
    navController: NavHostController,
    settingsViewModel: SettingsViewModel? = null,
    onNavigateClick: () -> Unit = { navController.navigate(WatchRoutes.NAVIGATE) },
) {
    val resources = LocalResources.current
    val offlineModeFlow = settingsViewModel?.offlineMode ?: flowOf(false)
    val offlineMode by offlineModeFlow.collectAsState(initial = false)
    val fontScale = LocalDensity.current.fontScale
    var gpsStatusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(gpsStatusMessage) {
        if (gpsStatusMessage == null) return@LaunchedEffect
        delay(1_500L)
        gpsStatusMessage = null
    }

    val screenSize = rememberWearScreenSize()
    val adaptive = rememberWearAdaptiveSpec()
    val horizontalPadding =
        when (screenSize) {
            WearScreenSize.LARGE -> 24.dp
            WearScreenSize.MEDIUM -> 20.dp
            WearScreenSize.SMALL -> 16.dp
        }
    val baseButtonWidth =
        when (screenSize) {
            WearScreenSize.LARGE -> 108.dp
            WearScreenSize.MEDIUM -> 100.dp
            WearScreenSize.SMALL -> 92.dp
        }
    val verticalSpacing =
        when (screenSize) {
            WearScreenSize.LARGE -> 6.dp
            WearScreenSize.MEDIUM -> 5.dp
            WearScreenSize.SMALL -> 4.dp
        }
    val settingsButtonBottomPadding =
        when (screenSize) {
            WearScreenSize.LARGE -> 5.dp
            WearScreenSize.MEDIUM -> 4.dp
            WearScreenSize.SMALL -> 3.dp
        }
    val settingsButtonSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 28.dp
            WearScreenSize.MEDIUM -> 26.dp
            WearScreenSize.SMALL -> 24.dp
        }
    val settingsButtonTouchTargetSize = 48.dp
    val settingsIconYOffset =
        when (screenSize) {
            WearScreenSize.LARGE -> 5.dp
            WearScreenSize.MEDIUM -> 4.dp
            WearScreenSize.SMALL -> 3.dp
        }
    val navigateIconButtonSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 44.dp
            WearScreenSize.MEDIUM -> 44.dp
            WearScreenSize.SMALL -> 44.dp
        }
    val navigateIconButtonHeight =
        when (screenSize) {
            WearScreenSize.LARGE -> 80.dp
            WearScreenSize.MEDIUM -> 76.dp
            WearScreenSize.SMALL -> 72.dp
        }
    val navigateIconSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 26.dp
            WearScreenSize.MEDIUM -> 24.dp
            WearScreenSize.SMALL -> 22.dp
        }
    val leftRailIconSize =
        when (screenSize) {
            WearScreenSize.LARGE -> 20.dp
            WearScreenSize.MEDIUM -> 19.dp
            WearScreenSize.SMALL -> 18.dp
        }
    val navigateIconEdgePadding =
        when (screenSize) {
            WearScreenSize.LARGE -> 10.dp
            WearScreenSize.MEDIUM -> 8.dp
            WearScreenSize.SMALL -> 8.dp
        }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compactScreen = adaptive.isRound && minOf(maxWidth, maxHeight) < 200.dp
        val centerButtonHeight =
            when (screenSize) {
                WearScreenSize.LARGE -> 46.dp
                WearScreenSize.MEDIUM -> 44.dp
                WearScreenSize.SMALL -> if (compactScreen) 40.dp else 42.dp
            }
        val centerButtonIconSize =
            when (screenSize) {
                WearScreenSize.LARGE -> 19.dp
                WearScreenSize.MEDIUM -> 18.dp
                WearScreenSize.SMALL -> if (compactScreen) 16.dp else 17.dp
            }
        val centerVerticalSpacing = if (compactScreen) 5.dp else verticalSpacing
        val contentHorizontalPadding = if (compactScreen) 0.dp else horizontalPadding
        val centerSideGap = if (compactScreen) 6.dp else 8.dp
        val leftReservedWidth = navigateIconButtonSize + navigateIconEdgePadding + centerSideGap
        val rightReservedWidth = navigateIconButtonSize + navigateIconEdgePadding + centerSideGap
        val centerAvailableWidth = (maxWidth - leftReservedWidth - rightReservedWidth).coerceAtLeast(84.dp)
        val centerButtonWidth =
            if (adaptive.isRound) {
                if (compactScreen) {
                    centerAvailableWidth.coerceIn(88.dp, baseButtonWidth)
                } else {
                    baseButtonWidth
                }
            } else {
                // Square screens have no circular edge clipping; use a slightly wider centered lane.
                (maxWidth - (horizontalPadding * 2)).coerceIn(baseButtonWidth, 148.dp)
            }
        val centerColumnOffset =
            if (adaptive.isRound && compactScreen) {
                0.dp
            } else {
                0.dp
            }
        val centerRowYOffset = 0.dp
        val centerScrollState = rememberScrollState()
        val centerScrollBottomInset =
            if (fontScale > 1f) {
                settingsButtonTouchTargetSize +
                    settingsButtonBottomPadding +
                    if (compactScreen) 6.dp else 8.dp
            } else {
                settingsButtonSize / 2 +
                    settingsButtonBottomPadding +
                    if (compactScreen) 3.dp else 5.dp
            }
        val centerScrollTopInset =
            settingsButtonSize / 2 +
                if (compactScreen) 4.dp else 6.dp

        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .offset(x = centerColumnOffset)
                        .padding(
                            top = centerScrollTopInset,
                            bottom = centerScrollBottomInset,
                        ).verticalScroll(centerScrollState)
                        .padding(horizontal = contentHorizontalPadding),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(centerVerticalSpacing, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    HomeActionButton(
                        label = stringResource(R.string.common_poi),
                        icon = Icons.Filled.Place,
                        width = centerButtonWidth,
                        height = centerButtonHeight,
                        iconSize = centerButtonIconSize,
                        compact = compactScreen,
                        onClick = { navController.navigate(WatchRoutes.POI) },
                    )
                    HomeActionButton(
                        label = stringResource(R.string.common_gpx),
                        icon = Icons.Filled.Timeline,
                        width = centerButtonWidth,
                        height = centerButtonHeight,
                        iconSize = centerButtonIconSize,
                        compact = compactScreen,
                        onClick = { navController.navigate(WatchRoutes.GPX) },
                    )
                    HomeActionButton(
                        label = stringResource(R.string.download_maps),
                        icon = Icons.Filled.Map,
                        width = centerButtonWidth,
                        height = centerButtonHeight,
                        iconSize = centerButtonIconSize,
                        compact = compactScreen,
                        onClick = { navController.navigate(WatchRoutes.MAPS) },
                    )
                }
            }
            WearScreenEdgeScrollIndicator(
                scrollState = centerScrollState,
                modifier = Modifier.padding(end = navigateIconEdgePadding),
            )

            LeftHomeRail(
                offlineMode = offlineMode,
                buttonWidth = navigateIconButtonSize,
                buttonHeight = navigateIconButtonHeight,
                iconSize = leftRailIconSize,
                modifier =
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset(y = centerRowYOffset)
                        .padding(start = navigateIconEdgePadding),
                onGpsClick = {
                    val nextOfflineMode = !offlineMode
                    settingsViewModel?.setOfflineMode(nextOfflineMode)
                    gpsStatusMessage =
                        if (nextOfflineMode) {
                            resources.getString(
                                R.string.navigation_gps_deactivated,
                            )
                        } else {
                            resources.getString(R.string.navigation_gps_activated)
                        }
                },
                onDownloadClick = { navController.navigate(WatchRoutes.DOWNLOAD) },
            )

            IconButton(
                onClick = onNavigateClick,
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .offset(y = centerRowYOffset)
                        .padding(end = navigateIconEdgePadding)
                        .width(navigateIconButtonSize)
                        .height(navigateIconButtonHeight),
                colors =
                    IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
            ) {
                Icon(
                    imageVector = Icons.Default.Explore,
                    contentDescription = stringResource(R.string.navigation_navigate),
                    modifier = Modifier.size(navigateIconSize),
                )
            }

            IconButton(
                onClick = { navController.navigate(WatchRoutes.SETTINGS) },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = settingsButtonBottomPadding)
                        .size(settingsButtonTouchTargetSize),
                colors =
                    IconButtonDefaults.iconButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = Color.White,
                    ),
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = stringResource(R.string.common_settings),
                    modifier =
                        Modifier
                            .offset(y = settingsIconYOffset)
                            .size(settingsButtonSize),
                )
            }

            gpsStatusMessage?.let { message ->
                GpsStatusOverlay(
                    message = message,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun GpsStatusOverlay(
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .background(
                    color = Color.Black.copy(alpha = 0.88f),
                    shape = RoundedCornerShape(12.dp),
                ).padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        cappedFontScale(maxFontScale = 1.1f) {
            Text(
                text = message,
                color = Color.White,
                maxLines = 1,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

@Composable
// Resource-call wrapping increases line count; keep the existing flow together.
@Suppress("LongMethod")
private fun LeftHomeRail(
    offlineMode: Boolean,
    buttonWidth: Dp,
    buttonHeight: Dp,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    onGpsClick: () -> Unit,
    onDownloadClick: () -> Unit,
) {
    Column(
        modifier =
            modifier
                .width(buttonWidth)
                .height(buttonHeight)
                .background(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(percent = 50),
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(
            onClick = onGpsClick,
            modifier =
                Modifier
                    .width(buttonWidth)
                    .height(buttonHeight / 2),
            colors =
                IconButtonDefaults.iconButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = if (offlineMode) Color(0xFFE53935) else MaterialTheme.colorScheme.onPrimary,
                ),
        ) {
            Icon(
                imageVector = if (offlineMode) Icons.Filled.LocationDisabled else Icons.Filled.MyLocation,
                contentDescription =
                    if (offlineMode) {
                        stringResource(
                            R.string.navigation_offline_mode,
                        )
                    } else {
                        stringResource(R.string.navigation_online_mode)
                    },
                modifier = Modifier.size(iconSize),
            )
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(0.52f)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f)),
        )
        IconButton(
            onClick = onDownloadClick,
            modifier =
                Modifier
                    .width(buttonWidth)
                    .height((buttonHeight / 2) - 1.dp),
            colors =
                IconButtonDefaults.iconButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
        ) {
            Icon(
                imageVector = Icons.Filled.Download,
                contentDescription = stringResource(R.string.common_download),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
private fun HomeActionButton(
    label: String,
    icon: ImageVector,
    width: Dp,
    height: Dp,
    iconSize: Dp,
    compact: Boolean,
    showLabel: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier =
            Modifier
                .width(width)
                .heightIn(min = height.coerceAtLeast(48.dp)),
        contentPadding =
            when {
                !showLabel -> PaddingValues(0.dp)
                compact -> PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                else -> ButtonDefaults.ContentPadding
            },
    ) {
        if (showLabel) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(iconSize),
            )
            Spacer(Modifier.size(if (compact) 4.dp else ButtonDefaults.IconSpacing))
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun MainScreenPreview() {
    val navController = rememberSwipeDismissableNavController()
    MainScreen(navController)
}
