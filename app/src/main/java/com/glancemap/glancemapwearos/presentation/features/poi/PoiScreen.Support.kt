@file:Suppress("FunctionName", "FunctionNaming")

package com.glancemap.glancemapwearos.presentation.features.poi

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Attractions
import androidx.compose.material.icons.filled.Castle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Church
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mosque
import androidx.compose.material.icons.filled.Museum
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Wc
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.glancemap.glancemapwearos.R
import com.glancemap.glancemapwearos.data.repository.PoiType
import com.glancemap.glancemapwearos.data.repository.USER_POI_SOURCE_PATH
import com.glancemap.glancemapwearos.presentation.ui.CompactIconHitTargetButton
import java.util.Locale

@Composable
internal fun PoiFileRow(
    file: PoiFileUiState,
    showDelete: Boolean,
    rowSpacing: Dp,
    actionButtonSize: Dp,
    actionIconSize: Dp,
    compactMode: Boolean,
    onToggle: (Boolean) -> Unit,
    onToggleExpanded: () -> Unit,
    onDelete: () -> Unit,
) {
    val cardShape = RoundedCornerShape(if (compactMode) 28.dp else 30.dp)
    val cardColor =
        when {
            file.isEnabled && isUserPoiFile(file.path) -> Color(0xFF6650A6)
            file.isEnabled -> Color(0xFF574A86)
            isUserPoiFile(file.path) -> Color(0xFF392F58)
            else -> Color(0xFF312B45)
        }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement =
            if (showDelete) {
                Arrangement.spacedBy(rowSpacing)
            } else {
                Arrangement.Start
            },
    ) {
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .clip(cardShape)
                    .background(cardColor)
                    .clickable { onToggle(!file.isEnabled) }
                    .padding(
                        horizontal = if (compactMode) 12.dp else 14.dp,
                        vertical = if (compactMode) 9.dp else 10.dp,
                    ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compactMode) 8.dp else 10.dp),
        ) {
            Icon(
                imageVector =
                    if (isUserPoiFile(file.path)) {
                        Icons.Default.Star
                    } else {
                        Icons.Filled.Folder
                    },
                contentDescription = null,
                modifier = Modifier.size(if (compactMode) 14.dp else 16.dp),
                tint =
                    if (isUserPoiFile(file.path)) {
                        Color(0xFFFFD54F)
                    } else {
                        Color(0xFFFFB74D)
                    },
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = file.name,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .basicMarquee(),
                    maxLines = 1,
                    overflow = TextOverflow.Visible,
                    color = Color(0xFFF5F2FF),
                )
                Text(
                    text = stringResource(R.string.poi_poi, file.enabledPoiCount, file.totalPoiCount),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = Color(0xFFD0D3F2),
                    maxLines = 1,
                )
            }
            TogglePillIndicator(
                checked = file.isEnabled,
                compactMode = compactMode,
                large = true,
            )
        }

        if (showDelete) {
            CompactIconHitTargetButton(
                onClick = onDelete,
                visualSize = actionButtonSize,
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription =
                        if (isUserPoiFile(file.path)) {
                            stringResource(R.string.poi_delete_created_poi)
                        } else {
                            stringResource(R.string.poi_delete_poi_file)
                        },
                    modifier = Modifier.size(actionIconSize),
                )
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                CompactIconHitTargetButton(
                    onClick = onToggleExpanded,
                    visualSize = actionButtonSize,
                    containerColor = Color.Black.copy(alpha = 0.72f),
                    contentColor = Color.White,
                ) {
                    Icon(
                        imageVector = if (file.isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription =
                            if (isUserPoiFile(file.path)) {
                                if (file.isExpanded) {
                                    stringResource(
                                        R.string.poi_collapse_saved_places,
                                    )
                                } else {
                                    stringResource(R.string.poi_expand_saved_places)
                                }
                            } else {
                                if (file.isExpanded) {
                                    stringResource(
                                        R.string.poi_collapse_categories,
                                    )
                                } else {
                                    stringResource(R.string.poi_expand_categories)
                                }
                            },
                        modifier = Modifier.size(actionIconSize),
                    )
                }
            }
        }
    }
}

@Composable
internal fun PoiCategoryRow(
    category: PoiCategoryUiState,
    categoryCount: PoiCategoryCountUiState?,
    isExpanded: Boolean,
    categoryIndentStep: Dp,
    actionButtonSize: Dp,
    actionIconSize: Dp,
    compactMode: Boolean,
    onToggle: (Boolean) -> Unit,
    onToggleExpanded: () -> Unit,
) {
    val cappedDepth = category.depth.coerceIn(0, 4)
    val indent = (cappedDepth * categoryIndentStep.value).dp
    val isTopLevel = cappedDepth == 0
    val isNested = cappedDepth > 0
    val isSyntheticGroup = category.id < 0
    val inferredType = inferPoiTypeFromCategoryName(category.name)
    val syntheticType =
        if (isSyntheticGroup) {
            inferSyntheticGroupPoiType(category.name)
        } else {
            null
        }
    val iconType = syntheticType ?: inferredType
    val namedCategoryIcon = poiCategoryNameIcon(category.name)
    val categoryIcon =
        when {
            namedCategoryIcon != null -> namedCategoryIcon
            syntheticType != null -> poiTypeCategoryIcon(iconType)
            category.hasChildren -> if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder
            iconType == PoiType.GENERIC -> null
            else -> poiTypeCategoryIcon(iconType)
        }
    val categoryTint =
        when {
            namedCategoryIcon != null -> poiCategoryNameTint(category.name)
            syntheticType != null -> poiTypeCategoryTint(iconType)
            category.hasChildren -> Color(0xFF8FB2FF)
            else -> poiTypeCategoryTint(iconType)
        }
    val countLabel =
        when {
            categoryCount == null || categoryCount.isLoading -> "..."
            categoryCount.errorMessage != null -> stringResource(R.string.poi_count_unavailable)
            else -> stringResource(R.string.poi_poi, categoryCount.enabledPoiCount, categoryCount.totalPoiCount)
        }
    val cardShape =
        when {
            isTopLevel -> RoundedCornerShape(if (compactMode) 22.dp else 24.dp)
            category.hasChildren -> RoundedCornerShape(if (compactMode) 16.dp else 18.dp)
            else -> RoundedCornerShape(if (compactMode) 14.dp else 16.dp)
        }
    val cardColor =
        when {
            isTopLevel && isExpanded && category.enabled -> Color(0xFF46527A)
            isTopLevel && isExpanded -> Color(0xFF343D5C)
            isTopLevel && category.enabled -> Color(0xFF3D4567)
            isTopLevel -> Color(0xFF252B3A)
            category.hasChildren && isExpanded && category.enabled -> Color(0xFF313A58)
            category.hasChildren && isExpanded -> Color(0xFF242B3A)
            category.hasChildren && category.enabled -> Color(0xFF2E3447)
            category.hasChildren -> Color(0xFF1D2330)
            category.enabled -> Color(0xFF2A3040)
            else -> Color(0xFF1C2028)
        }
    val rowHorizontalPadding =
        when {
            isTopLevel -> if (compactMode) 12.dp else 14.dp
            else -> if (compactMode) 9.dp else 10.dp
        }
    val rowVerticalPadding =
        when {
            isTopLevel -> if (compactMode) 9.dp else 11.dp
            category.hasChildren -> if (compactMode) 7.dp else 8.dp
            else -> if (compactMode) 6.dp else 7.dp
        }
    val titleSize =
        when {
            isTopLevel -> if (compactMode) 15.sp else 16.sp
            category.hasChildren -> if (compactMode) 12.sp else 13.sp
            else -> if (compactMode) 11.sp else 12.sp
        }
    val accentColor =
        when {
            category.enabled -> categoryTint.copy(alpha = 0.95f)
            isExpanded -> Color(0xFF8FB2FF).copy(alpha = 0.75f)
            else -> Color(0xFF6E7890).copy(alpha = 0.55f)
        }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compactMode) 4.dp else 6.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = indent),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compactMode) 5.dp else 6.dp),
            ) {
                if (isNested) {
                    Box(
                        modifier =
                            Modifier
                                .width(if (compactMode) 2.dp else 3.dp)
                                .height(if (category.hasChildren) 38.dp else 32.dp)
                                .clip(RoundedCornerShape(100))
                                .background(accentColor),
                    )
                }
                Row(
                    modifier =
                        Modifier
                            .weight(1f)
                            .clip(cardShape)
                            .background(cardColor)
                            .clickable { onToggle(!category.enabled) }
                            .padding(
                                horizontal = rowHorizontalPadding,
                                vertical = rowVerticalPadding,
                            ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (compactMode) 6.dp else 8.dp),
                ) {
                    if (categoryIcon != null) {
                        Icon(
                            imageVector = categoryIcon,
                            contentDescription = null,
                            modifier =
                                Modifier.size(
                                    when {
                                        isTopLevel -> 15.dp
                                        compactMode -> 12.dp
                                        else -> 14.dp
                                    },
                                ),
                            tint = categoryTint,
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        Text(
                            text = poiCategoryNameRes(category.name)?.let { stringResource(it) } ?: category.name,
                            modifier = Modifier.basicMarquee(),
                            maxLines = 1,
                            overflow = TextOverflow.Visible,
                            color = if (isTopLevel) Color.White else Color(0xFFE1E7F8),
                            fontSize = titleSize,
                            fontWeight = if (isTopLevel) FontWeight.SemiBold else FontWeight.Medium,
                        )
                        Text(
                            text = countLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            color = if (category.hasChildren) Color(0xFFAFC2F5) else Color(0xFF9FB0CD),
                            maxLines = 1,
                        )
                    }

                    TogglePillIndicator(
                        checked = category.enabled,
                        compactMode = compactMode,
                        large = isTopLevel && !compactMode,
                    )
                }
            }
        }

        CompactIconHitTargetButton(
            onClick = onToggleExpanded,
            visualSize = actionButtonSize,
            containerColor = Color.Black.copy(alpha = 0.72f),
            contentColor = Color.White,
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription =
                    if (isExpanded) {
                        if (category.hasChildren) {
                            stringResource(
                                R.string.poi_collapse_folder,
                            )
                        } else {
                            stringResource(R.string.poi_hide_poi_list)
                        }
                    } else {
                        if (category.hasChildren) {
                            stringResource(
                                R.string.poi_expand_folder,
                            )
                        } else {
                            stringResource(R.string.poi_show_poi_list)
                        }
                    },
                modifier = Modifier.size(actionIconSize),
            )
        }
    }
}

@Composable
internal fun PoiCategoryPoiRow(
    point: PoiCategoryPreviewPointUiState,
    filePath: String,
    depth: Int,
    categoryIndentStep: Dp,
    compactMode: Boolean,
    tapToCenterEnabled: Boolean,
    showDelete: Boolean,
    showRename: Boolean,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onClick: () -> Unit,
) {
    val cappedDepth = depth.coerceIn(0, 5)
    val indent = (cappedDepth * categoryIndentStep.value).dp
    val icon = poiTypeCategoryIcon(point.type) ?: Icons.Default.LocationOn
    val iconSize = if (compactMode) 10.dp else 12.dp
    val textSize = if (compactMode) 10.sp else 11.sp
    val rowShape = RoundedCornerShape(if (compactMode) 14.dp else 16.dp)
    val rowTint =
        if (isUserPoiFile(filePath)) {
            Color(0xFFFFD54F).copy(alpha = 0.07f)
        } else {
            Color.White.copy(alpha = 0.035f)
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = indent)
                .clip(rowShape)
                .background(rowTint)
                .clickable(
                    enabled = tapToCenterEnabled && !showDelete && !showRename,
                    onClick = onClick,
                ).padding(
                    horizontal = if (compactMode) 8.dp else 10.dp,
                    vertical = if (compactMode) 5.dp else 6.dp,
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compactMode) 4.dp else 6.dp),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compactMode) 4.dp else 6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                tint = poiTypeCategoryTint(point.type),
            )
            Text(
                text = point.name,
                modifier =
                    Modifier
                        .weight(1f)
                        .basicMarquee(),
                maxLines = 1,
                overflow = TextOverflow.Visible,
                fontSize = textSize,
                color = Color(0xFFD3DCFA),
            )
        }
        if (showRename) {
            CompactIconHitTargetButton(
                onClick = onRename,
                visualSize = if (compactMode) 28.dp else 32.dp,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.poi_rename_saved_place))
            }
        } else if (showDelete) {
            CompactIconHitTargetButton(
                onClick = onDelete,
                visualSize = if (compactMode) 28.dp else 32.dp,
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.poi_delete_saved_place))
            }
        }
    }
}

@Composable
internal fun TogglePillIndicator(
    checked: Boolean,
    compactMode: Boolean,
    large: Boolean,
) {
    val trackWidth =
        when {
            large && compactMode -> 34.dp
            large -> 38.dp
            compactMode -> 30.dp
            else -> 34.dp
        }
    val trackHeight =
        when {
            large && compactMode -> 20.dp
            large -> 22.dp
            compactMode -> 18.dp
            else -> 20.dp
        }
    val thumbSize =
        when {
            large && compactMode -> 16.dp
            large -> 18.dp
            compactMode -> 14.dp
            else -> 16.dp
        }

    Box(
        modifier =
            Modifier
                .width(trackWidth)
                .height(trackHeight)
                .background(Color.Black.copy(alpha = 0.26f), RoundedCornerShape(50))
                .padding(2.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                    .size(thumbSize)
                    .background(
                        color =
                            if (checked) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color(0xFF6F7381)
                            },
                        shape = CircleShape,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(if (compactMode) 9.dp else 10.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
internal fun PoiCategoryInfoRow(
    text: String,
    depth: Int,
    categoryIndentStep: Dp,
    isError: Boolean,
) {
    val cappedDepth = depth.coerceIn(0, 5)
    val indent = (cappedDepth * categoryIndentStep.value).dp
    val textColor = if (isError) MaterialTheme.colorScheme.error else Color(0xFF90A4AE)
    val textSize = 10.sp

    Text(
        text = text,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = indent),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        fontSize = textSize,
        color = textColor,
    )
}

internal fun isCategoryVisible(
    category: PoiCategoryUiState,
    categoriesById: Map<Int, PoiCategoryUiState>,
    expandedCategoryIds: Set<Int>,
): Boolean {
    var currentParent = category.parentId
    val guard = mutableSetOf<Int>()
    while (currentParent != null && guard.add(currentParent)) {
        val parent = categoriesById[currentParent] ?: return true
        if (parent.id !in expandedCategoryIds) return false
        currentParent = parent.parentId
    }
    return true
}

internal fun inferSyntheticGroupPoiType(categoryName: String): PoiType? {
    val normalized = categoryName.lowercase(Locale.ROOT).trim()
    if (normalized.isBlank()) return null
    return when {
        "water" in normalized -> PoiType.WATER
        "shelter" in normalized -> PoiType.HUT
        "transport" in normalized -> PoiType.TRANSPORT
        "service" in normalized -> PoiType.SHOP
        "landmark" in normalized -> PoiType.VIEWPOINT
        "sport" in normalized || "leisure" in normalized -> PoiType.BIKE
        else -> null
    }
}

internal fun inferPoiTypeFromCategoryName(categoryName: String): PoiType {
    val normalized =
        categoryName
            .lowercase(Locale.ROOT)
            .replace('’', '\'')
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()

    if (normalized.isBlank()) return PoiType.GENERIC

    return when {
        "peak" in normalized || "summit" in normalized || "mountain" in normalized -> PoiType.PEAK
        "drink" in normalized ||
            "water" in normalized ||
            "spring" in normalized ||
            "well" in normalized ||
            "fountain" in normalized ||
            "point d'eau" in normalized ||
            "source" in normalized ||
            "eau" in normalized -> PoiType.WATER
        "hut" in normalized ||
            "shelter" in normalized ||
            "alpine" in normalized ||
            "hotel" in normalized ||
            "guest" in normalized ||
            "apartment" in normalized ||
            "hostel" in normalized ||
            "refuge" in normalized ||
            "cabane" in normalized ||
            "abri" in normalized ||
            "gite" in normalized ||
            "gîte" in normalized -> PoiType.HUT
        "camp" in normalized -> PoiType.CAMP
        "restaurant" in normalized ||
            "cafe" in normalized ||
            "food" in normalized ||
            "picnic" in normalized ||
            "bar" in normalized ||
            "pub" in normalized ||
            "grill" in normalized ||
            "bbq" in normalized -> PoiType.FOOD
        "toilet" in normalized || "wc" in normalized -> PoiType.TOILET
        "bus" in normalized ||
            "transport" in normalized ||
            "station" in normalized ||
            "railway" in normalized ||
            "airport" in normalized ||
            "taxi" in normalized -> PoiType.TRANSPORT
        "bicycle" in normalized || "cycle" in normalized -> PoiType.BIKE
        "parking" in normalized -> PoiType.PARKING
        "shop" in normalized ||
            "store" in normalized ||
            "supermarket" in normalized ||
            "pharmacy" in normalized ||
            "bakery" in normalized ||
            "hospital" in normalized ||
            "clinic" in normalized ||
            "doctor" in normalized ||
            "school" in normalized ||
            "fuel" in normalized ||
            "bank" in normalized ||
            "post office" in normalized ||
            "post box" in normalized ||
            "defibrillator" in normalized ||
            "car repair" in normalized ||
            "community center" in normalized -> PoiType.SHOP
        "viewpoint" in normalized ||
            "view" in normalized ||
            "scenic" in normalized ||
            "lookout" in normalized ||
            "attraction" in normalized ||
            "grotte" in normalized ||
            "cave" in normalized ||
            "passage" in normalized ||
            "church" in normalized ||
            "mosque" in normalized ||
            "temple" in normalized ||
            "basilika" in normalized ||
            "castle" in normalized ||
            "ruin" in normalized ||
            "memorial" in normalized ||
            "museum" in normalized ||
            "tower" in normalized ||
            ("park" in normalized && "parking" !in normalized) ||
            "garden" in normalized ||
            "hamlet" in normalized ||
            "village" in normalized ||
            "townhall" in normalized ||
            "artwork" in normalized -> PoiType.VIEWPOINT
        else -> PoiType.GENERIC
    }
}

internal fun poiTypeCategoryIcon(type: PoiType): ImageVector? =
    when (type) {
        PoiType.PEAK -> Icons.Default.Terrain
        PoiType.WATER -> Icons.Default.WaterDrop
        PoiType.HUT -> Icons.Default.Home
        PoiType.CAMP -> Icons.Default.Terrain
        PoiType.FOOD -> Icons.Default.Restaurant
        PoiType.TOILET -> Icons.Default.Wc
        PoiType.TRANSPORT -> Icons.Default.DirectionsBus
        PoiType.BIKE -> Icons.AutoMirrored.Filled.DirectionsBike
        PoiType.VIEWPOINT -> Icons.Default.Visibility
        PoiType.PARKING -> Icons.Default.LocalParking
        PoiType.SHOP -> Icons.Default.Storefront
        PoiType.GENERIC -> Icons.Default.LocationOn
        PoiType.CUSTOM -> Icons.Default.Star
    }

internal fun poiTypeCategoryTint(type: PoiType): Color =
    when (type) {
        PoiType.PEAK -> Color(0xFFB38B6D)
        PoiType.WATER -> Color(0xFF64B5F6)
        PoiType.HUT -> Color(0xFF8D6E63)
        PoiType.CAMP -> Color(0xFF81C784)
        PoiType.FOOD -> Color(0xFFFFB74D)
        PoiType.TOILET -> Color(0xFFBA68C8)
        PoiType.TRANSPORT -> Color(0xFF64B5F6)
        PoiType.BIKE -> Color(0xFF4DD0E1)
        PoiType.VIEWPOINT -> Color(0xFFAFC2F5)
        PoiType.PARKING -> Color(0xFF7986CB)
        PoiType.SHOP -> Color(0xFF90A4AE)
        PoiType.GENERIC -> Color(0xFFBDBDBD)
        PoiType.CUSTOM -> Color(0xFFFFD54F)
    }

internal fun poiCategoryNameIcon(categoryName: String): ImageVector? {
    val normalized = normalizedPoiCategoryName(categoryName)
    return when {
        normalized.containsAny("village", "hamlet", "city", "town", "suburb") -> Icons.Default.LocationCity
        normalized.containsAny("park", "garden", "picnic") && "parking" !in normalized -> Icons.Default.Park
        normalized.containsAny("attraction", "theme park") -> Icons.Default.Attractions
        normalized.containsAny("ruin", "castle", "chateau", "fort") -> Icons.Default.Castle
        normalized.containsAny("museum", "memorial", "monument") -> Icons.Default.Museum
        normalized.containsAny("church", "cathedral", "basilika") -> Icons.Default.Church
        normalized.containsAny("mosque") -> Icons.Default.Mosque
        normalized.containsAny("temple", "townhall", "town hall") -> Icons.Default.AccountBalance
        normalized.containsAny("artwork", "art ", "gallery") -> Icons.Default.Palette
        normalized.containsAny("viewpoint", "view", "lookout") -> Icons.Default.Visibility
        normalized.containsAny("scenic", "panorama", "belved") -> Icons.Default.Landscape
        else -> null
    }
}

internal fun poiCategoryNameTint(categoryName: String): Color {
    val normalized = normalizedPoiCategoryName(categoryName)
    return when {
        normalized.containsAny("village", "hamlet", "city", "town", "suburb") -> Color(0xFF8FB2FF)
        normalized.containsAny("park", "garden", "picnic") && "parking" !in normalized -> Color(0xFF81C784)
        normalized.containsAny("attraction", "theme park") -> Color(0xFFFFB74D)
        normalized.containsAny("ruin", "castle", "chateau", "fort") -> Color(0xFFC7A17A)
        normalized.containsAny("museum", "memorial", "monument") -> Color(0xFFB0BEC5)
        normalized.containsAny("church", "cathedral", "basilika", "mosque", "temple", "townhall", "town hall") -> {
            Color(0xFFB0BEC5)
        }
        normalized.containsAny("artwork", "art ", "gallery") -> Color(0xFFF48FB1)
        normalized.containsAny("viewpoint", "view", "lookout", "scenic", "panorama", "belved") -> Color(0xFFAFC2F5)
        else -> Color(0xFFBDBDBD)
    }
}

private fun normalizedPoiCategoryName(categoryName: String): String =
    categoryName
        .lowercase(Locale.ROOT)
        .replace('’', '\'')
        .replace('_', ' ')
        .replace('-', ' ')
        .trim()

private fun String.containsAny(vararg needles: String): Boolean = needles.any { it in this }

internal fun isUserPoiFile(path: String): Boolean = path == USER_POI_SOURCE_PATH
